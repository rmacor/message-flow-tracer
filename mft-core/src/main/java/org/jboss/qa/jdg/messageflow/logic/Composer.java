/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.qa.jdg.messageflow.logic;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import org.jboss.qa.jdg.messageflow.objects.Event;
import org.jboss.qa.jdg.messageflow.objects.Span;
import org.jboss.qa.jdg.messageflow.objects.Trace;
import org.jboss.qa.jdg.messageflow.processors.Processor;

/**
 * Merges multiple spans from several nodes into trace
 *
 * @author Radim Vansa &lt;rvansa@redhat.com&gt;
 */
public class Composer extends Logic {

   // this map is populated in first pass and should contain the number of references to each message
   // in second pass it is only read and when the counter reaches zero for all messages in the trace
   // the trace is ready to be written to file
   private ConcurrentMap<String, AtomicInteger> messageReferences = new ConcurrentHashMap<String, AtomicInteger>();
   private AtomicInteger messagesRead = new AtomicInteger(0);

   private ConcurrentMap<String, Trace> traces = new ConcurrentHashMap<String, Trace>();

   private LinkedBlockingQueue<Trace> finishedTraces = new LinkedBlockingQueue<Trace>(1000);
   private AtomicLongArray highestUnixTimestamps;

   private List<Processor> processors = new ArrayList<Processor>();
   private boolean reportMemoryUsage = false;
   private int totalMessages;
   private boolean sortCausally = true;
   private long maxAdvanceMillis = 10000;
   public static boolean binarySpans = false;
   public Composer() {
   }

   @Override
   public void run() {
      System.err.println("Starting first pass");
      Thread[] threads = new Thread[inputFiles.size()];
      for (int i = 0; i < inputFiles.size(); ++i) {
         Thread t = new FirstPassThread(inputFiles.get(i));
         t.setName("First pass: " + inputFiles.get(i));
         threads[i] = t;
         t.start();
      }
      if (!joinAll(threads)) return;
      totalMessages = messageReferences.size();
      System.err.printf("Found %d messages\n", totalMessages);
      if (reportMemoryUsage) {
         reportMemoryUsage();
      }
      System.err.println("Starting second pass");
      highestUnixTimestamps = new AtomicLongArray(inputFiles.size());
      for (int i = 0; i < inputFiles.size(); ++i) {
         Thread t = new SecondPassThread(inputFiles.get(i), i);
         t.setName("Second pass: " + inputFiles.get(i));
         threads[i] = t;
         t.start();
      }
      ProcessorThread processorThread = new ProcessorThread(processors);
      processorThread.start();
      joinAll(threads);
      processorThread.finish();
      while (!finishedTraces.isEmpty()) {
         Thread.yield();
      }
      try {
         processorThread.join();
      } catch (InterruptedException e) {
      }
      System.err.printf("Memory:\n\tmessage references: %d\n\ttraces: %d\n",
                        messageReferences.size(), traces.size());
   }

   private static void reportMemoryUsage() {
      //System.gc();
      long used = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
      long max = Runtime.getRuntime().maxMemory() / (1024 * 1024);
      System.err.printf("Using %d/%d MB of memory\n", used, max);
   }

   public void setReportMemoryUsage(boolean reportMemoryUsage) {
      this.reportMemoryUsage = reportMemoryUsage;
   }

   public void addProcessor(Processor processor) {
      processors.add(processor);
   }

   public List<Processor> getProcessors() {
      return processors;
   }

   public void addInputFile(String file) {
      inputFiles.add(file);
   }

   public boolean isSortCausally() {
      return sortCausally;
   }

   public void setSortCausally(boolean sortCausally) {
      this.sortCausally = sortCausally;
   }

   public void setMaxAdvanceMillis(long maxAdvanceMillis) {
      this.maxAdvanceMillis = maxAdvanceMillis;
   }

   private class FirstPassThread extends Thread {
      private String file;

      private FirstPassThread(String file) {
         this.file = file;
      }

      @Override
      public void run() {
         try {
            if (binarySpans){
               readBinary();
            }else {
               read();
            }
            System.err.println("Finished reading (first pass) " + file);
         } catch (IOException e) {
            System.err.println("Error reading " + file + " due to " + e);
            e.printStackTrace();
            System.exit(1);
         }
      }

       /**
        * Counts messages and establishes sequence of messages.
        * @throws IOException
        */
      private void read() throws IOException {
         BufferedReader reader = new BufferedReader(new FileReader(file));

         String line = reader.readLine(); // timestamp info - ignored
         while ((line = reader.readLine()) != null) {
            if (line.startsWith(Trace.SPAN) || line.startsWith(Trace.NON_CAUSAL)) {
               String[] parts = line.split(";");
               for (int i = line.startsWith(Trace.NON_CAUSAL) ? 2 : 1; i < parts.length; ++i) {
                  if (parts[i].equals("null")) continue;
                  AtomicInteger prev = messageReferences.putIfAbsent(parts[i], new AtomicInteger(1));
                  if (prev != null) {
                     int refCount = prev.incrementAndGet();
                     // System.out.println(file + ":" + lineNumber + " inc " + parts[i] + " -> " + refCount);
                  } else {
                     // System.out.println(file + ":" + lineNumber + " add " + parts[i]);
                  }
                  int read = messagesRead.incrementAndGet();
                  if (read % 1000000 == 0) {
                     System.err.printf("Read %d message references (~%d messages)\n", read, messageReferences.size());
                  }
               }
            }
         }
         System.err.printf("Read %d message references (in first pass)\n", messageReferences.size());
      }
      /**
       * Counts messages and establishes sequence of messages.
       * Reads from binary file
       * @throws IOException
       */
      private void readBinary() throws IOException {

         FileInputStream fileIS = new FileInputStream(file);
         DataInputStream stream = new DataInputStream(new BufferedInputStream(fileIS));

         stream.readLong();
         stream.readLong();

         while (fileIS.available() > 0){
            Span span = readSpan(stream);

            for (String message : span.getMessages()){
               AtomicInteger prev =  messageReferences.putIfAbsent(message, new AtomicInteger(1));
               if (prev != null){
                  int refCount = prev.incrementAndGet();
               }
               int read = messagesRead.incrementAndGet();
               if (read % 1000000 == 0) {
                  System.err.printf("Read %d message references (~%d messages)\n", read, messageReferences.size());
               }
            }
         }
         stream.close();
      }
   }


   private class SecondPassThread extends Thread {
      private String file;
      private int selfIndex;
      private long highestUnixTimestamp = 0;

      public SecondPassThread(String file, int selfIndex) {
         this.file = file;
         this.selfIndex = selfIndex;
      }

      @Override
      public void run() {
         try {
             if (binarySpans){
                readBinary();
             }else {
                 read();
             }
            System.err.println("Finished reading (second pass) " + file);
         } catch (IOException e) {
            System.err.println("Error reading " + file + " due to " + e);
            e.printStackTrace();
            System.exit(1);
         }
      }

      public void readBinary() throws IOException{
         String source = new File(file).getName();
         source = source.substring(0, source.lastIndexOf('.') < 0 ? source.length() : source.lastIndexOf('.'));

         FileInputStream fileIS = new FileInputStream(file);
         DataInputStream stream = new DataInputStream(new BufferedInputStream(fileIS));

         long nanoTime = stream.readLong();
         long unixTime = stream.readLong();

         Trace trace = null;
         long localHighestUnixTimestamp = 0;
         int spanCounter = 0;

         while (fileIS.available() > 0){
            //System.err.println("reading span");
            Span span = readSpan(stream);
         //   System.err.println(span);
            if (!span.isNonCausal()){
               tryRetire(trace);
               trace = null;
               checkAdvance(localHighestUnixTimestamp);
               spanCounter++;

               for (String message : span.getMessages()){
                  if (trace == null) {
                     trace = retrieveTraceFor(message);
                  } else {
                     Trace traceForThisMessage = traces.get(message);
                     if (trace == traceForThisMessage) {
                        //System.err.println("Message should not be twice in one trace on one machine! (" + message + ", " + source + ":" + lineNumber + ")");
                     } else if (traceForThisMessage == null) {
                        trace.addMessage(message);
                        traceForThisMessage = traces.putIfAbsent(message, trace);
                        if (traceForThisMessage != null) {
                           trace = mergeTraces(trace, message, traceForThisMessage);
                        }
                     } else {
                        trace = mergeTraces(trace, message, traceForThisMessage);
                     }
                  }
                  decrementMessageRefCount(message);
               }
               if (trace == null) {
                  // no message associated, but tracked?
                  trace = new Trace();
                  trace.lock.lock();
               }
            }else {
               tryRetire(trace);
               trace = null;
               checkAdvance(localHighestUnixTimestamp);
            }

            for (Span.LocalEvent event : span.getEvents()){

               long eventTime = event.timestamp;
               String threadName = event.threadName;
               String type = event.type.toString();
               String text = event.text;

               if (trace != null) {
                  Event e = new Event(nanoTime, unixTime, eventTime, source, spanCounter, threadName, type, text);
                  trace.addEvent(e);
                  localHighestUnixTimestamp = Math.max(localHighestUnixTimestamp, e.timestamp.getTime());
               } else {
                  // NON-CAUSAL
                  if (type.equals(Event.Type.OUTCOMING_DATA_STARTED.toString())) {
                     Trace traceForThisMessage = retrieveTraceFor(text.trim());
                     Event e = new Event(nanoTime, unixTime, eventTime, source, spanCounter,
                             threadName, Event.Type.RETRANSMISSION.toString(), text);
                     traceForThisMessage.addEvent(e);

                     decrementMessageRefCount(text);
                     tryRetire(traceForThisMessage);
                     checkAdvance(e.timestamp.getTime());
                  } else if (type.equals(Event.Type.TRACE_TAG.toString())) {
                     System.err.println(String.format("Warning: Span with trace tag (%s) marked as non-causal (%s line)", text, source));
                  }
               }
            }
         }
         tryRetire(trace);
         // as we have finished reading, nobody should be blocked by our old timestamp
         highestUnixTimestamps.set(selfIndex, Long.MAX_VALUE);
         stream.close();
      }

      public void read() throws IOException {
         String source = new File(file).getName();
         source = source.substring(0, source.lastIndexOf('.') < 0 ? source.length() : source.lastIndexOf('.'));

         BufferedReader reader = new BufferedReader(new FileReader(file));

         String timeSync = reader.readLine();
         if (timeSync == null) {
            System.err.println("Empty file!");
            return;
         }
         String[] nanoAndUnix = timeSync.split("=");
         long nanoTime = Long.parseLong(nanoAndUnix[0]);
         long unixTime = Long.parseLong(nanoAndUnix[1]);

         Trace trace = null;
         long localHighestUnixTimestamp = 0;
         int lineNumber = 1;
         int spanCounter = 0;
         String line;
         while ((line = reader.readLine()) != null) {
            try {
               lineNumber++;
               String[] parts = line.split(";");
               if (line.startsWith(Trace.SPAN)) {
                  tryRetire(trace);
                  trace = null;
                  checkAdvance(localHighestUnixTimestamp);
                  spanCounter++;
                  for (int i = 1; i < parts.length; ++i) {
                     String message = parts[i];
                     if (message.equals("null")) continue;
                     if (trace == null) {
                        trace = retrieveTraceFor(message);
                     } else {
                        Trace traceForThisMessage = traces.get(message);
                        if (trace == traceForThisMessage) {
                           //System.err.println("Message should not be twice in one trace on one machine! (" + message + ", " + source + ":" + lineNumber + ")");
                        } else if (traceForThisMessage == null) {
                           trace.addMessage(message);
                           traceForThisMessage = traces.putIfAbsent(message, trace);
                           if (traceForThisMessage != null) {
                              trace = mergeTraces(trace, message, traceForThisMessage);
                           }
                        } else {
                           trace = mergeTraces(trace, message, traceForThisMessage);
                        }
                     }
                     decrementMessageRefCount(message);
                  }
                  if (trace == null) {
                     // no message associated, but tracked?
                     trace = new Trace();
                     trace.lock.lock();
                  }
               } else if (line.startsWith(Trace.NON_CAUSAL)) {
                  tryRetire(trace);
                  trace = null;
                  checkAdvance(localHighestUnixTimestamp);
               } else if (line.startsWith(Trace.EVENT)) {
                  if (parts.length < 4 || parts.length > 5) {
                     System.err.println("Invalid line " + lineNumber + "\n" + line);
                     continue;
                  }

                   //In case of number format exception, the trace is thrown away
                   long eventTime;
                   try{
                       eventTime = Long.valueOf(parts[1]);
                   }catch (NumberFormatException e){
                       System.err.println("Invalid number " + parts[1] + " this trace was thrown away");
                       return;
                   }

                  String threadName = parts[2];
                  String type = parts[3];
                  String text = parts.length > 4 ? parts[4].trim() : null;

                  if (trace != null) {
                     Event e = new Event(nanoTime, unixTime, eventTime, source, spanCounter, threadName, type, text);
                     trace.addEvent(e);
                     localHighestUnixTimestamp = Math.max(localHighestUnixTimestamp, e.timestamp.getTime());
                  } else {
                     // NON-CAUSAL
                     if (type.equals(Event.Type.OUTCOMING_DATA_STARTED.toString())) {
                        Trace traceForThisMessage = retrieveTraceFor(parts[4].trim());
                        Event e = new Event(nanoTime, unixTime, eventTime, source, spanCounter,
                                            threadName, Event.Type.RETRANSMISSION.toString(), text);
                        traceForThisMessage.addEvent(e);

                        decrementMessageRefCount(text);
                        tryRetire(traceForThisMessage);
                        checkAdvance(e.timestamp.getTime());
                     } else if (type.equals(Event.Type.TRACE_TAG.toString())) {
                        System.err.println(String.format("Warning: Span with trace tag (%s) marked as non-causal (%s line %d)", text, source, lineNumber));
                     }
                  }
               } else {
                  System.err.println("Invalid line: " + line);
               }
            } catch (RuntimeException e) {
               System.err.println("Error reading line " + lineNumber + ":\n" + line + "\n");
               throw e;
            }
         }
         tryRetire(trace);
         // as we have finished reading, nobody should be blocked by our old timestamp
         highestUnixTimestamps.set(selfIndex, Long.MAX_VALUE);
         System.err.println("Span counter second pass: " + spanCounter);
      }

      private void checkAdvance(long eventUnixTimestamp) {
         if (eventUnixTimestamp > highestUnixTimestamp) {
            highestUnixTimestamp = eventUnixTimestamp;
            highestUnixTimestamps.set(selfIndex, highestUnixTimestamp);
            for (;;) {
               long min = highestUnixTimestamp;
               for (int i = 0; i < highestUnixTimestamps.length(); ++i) {
                  min = Math.min(highestUnixTimestamps.get(i), min);
               }
               if (highestUnixTimestamp > min + maxAdvanceMillis) {
                  try {
                     Thread.sleep(1000);
                  } catch (InterruptedException e1) {
                  }
               } else {
                  break;
               }
            }
         }
      }

      private Trace mergeTraces(Trace trace, String message, Trace traceForThisMessage) {
         // hopefully even if we merge the traces twice the result is still correct
         for (;;) {
            if (traceForThisMessage.lock.tryLock()) {
               // check if we have really the right trace
               Trace possiblyOtherTrace = traces.get(message);
               if (possiblyOtherTrace != traceForThisMessage) {
                  traceForThisMessage.lock.unlock();
                  if (possiblyOtherTrace == trace || possiblyOtherTrace == null) {
                     // nobody can manipulate with current trace and the trace cannot be retired either
                     trace.lock.unlock();
                     throw new IllegalStateException();
                  }
                  traceForThisMessage = possiblyOtherTrace;
               } else {
                  for (String msg : traceForThisMessage.messages) {
                     traces.put(msg, trace);
                     trace.addMessage(msg);
                  }
                  for (Event e : traceForThisMessage.events) {
                     trace.addEvent(e);
                  }

                  if (traceForThisMessage.mergedInto != null) {
                     throw new IllegalStateException();
                  }
                  traceForThisMessage.mergedInto = trace;
                  trace.mergeCounter += traceForThisMessage.mergeCounter;
                  traceForThisMessage.lock.unlock();
                  break;
               }
            } else {
               trace.mergeCounter++;
               trace.lock.unlock();
               Thread.yield();
               trace.lock.lock();
               trace.mergeCounter--;
               while (trace.mergedInto != null) {
                  Trace old = trace;
                  trace = trace.mergedInto;
                  old.lock.unlock();
                  trace.lock.lock();
                  trace.mergeCounter--;
               }
            }
         }
         return trace;
      }

      private Trace retrieveTraceFor(String message) {
         Trace trace = traces.get(message);
         if (trace == null) {
            trace = new Trace();
            trace.addMessage(message);
            Trace prev = traces.putIfAbsent(message, trace);
            if (prev != null) {
               trace = prev;
            }
         }
         for (;;) {
            trace.lock.lock();
            Trace traceFromMap = traces.get(message);
            if (traceFromMap == null) {
               // removal from the map happens only when the trace is retired, therefore, no more
               // references for the message are alive
               trace.lock.unlock();
               throw new IllegalStateException();
            }
            if (traceFromMap != trace) {
               trace.lock.unlock();
               trace = traceFromMap;
            } else {
               break;
            }
         }
         return trace;
      }

       /**
        * retire trace and remove all the trace references from the traces map
        * if merge counter is not possitive and if messages from this trace are not in messageReferences
        * @param trace
        */
      private void tryRetire(Trace trace) {
         if (trace == null) return;
         try {
            if (trace.mergeCounter > 0) return;
            for (String message : trace.messages) {
               if (messageReferences.get(message) != null) {
                  return;
               }
            }
            for (String message : trace.messages) {
               traces.remove(message);
            }
            trace.retired = true;
            retireTrace(trace);
         } catch (InterruptedException e) {
            System.err.println("Interrupted when adding to queue!");
         } finally {
            trace.lock.unlock();
         }
      }
   }

    /**
     * Sort trace and put into finishedTraces
     * @param trace
     * @throws InterruptedException
     */
   private void retireTrace(Trace trace) throws InterruptedException {
      if (sortCausally) {
         try {
            trace.sortCausally();
         } catch (Exception e) {
            System.err.println("Failed to sort trace causally: " + e);
            trace.sortByTimestamps();
         }
      } else {
         trace.sortByTimestamps();
      }
      finishedTraces.put(trace);
   }

   private void decrementMessageRefCount(String message) {
      AtomicInteger counter = messageReferences.get(message);
      if (counter == null) {
         throw new IllegalStateException("No message counter for " + message);
      }
      int refCount = counter.decrementAndGet();
      if (refCount == 0) {
         messageReferences.remove(message);
      }
      //System.out.println(source + " dec " + message + " -> " + refCount);
   }

   private class ProcessorThread extends Thread {
      private volatile boolean finished = false;
      private final List<Processor> processors;

      private ProcessorThread(List<Processor> processors) {
         setName("TraceProcessor");
         this.processors = processors;
      }

      @Override
      public void run() {
         long traceCounter = 0;
         while (!finished || !finishedTraces.isEmpty()) {
            Trace trace = null;
            try {
               while (!finished && finishedTraces.isEmpty()) {
                  Thread.yield();
               }
               if (finishedTraces.isEmpty()) break;
               trace = finishedTraces.take();
            } catch (InterruptedException e) {
               System.err.println("Printer interrupted!");
               break;
            }
            for (Processor processor : processors) {
               try {
                  processor.process(trace, traceCounter);
               } catch (Throwable exception) {
                  System.err.printf("Failed to process trace %d in %s: %s\n", traceCounter, processor.getClass().getSimpleName(), exception);
                  exception.printStackTrace(System.err);
               }
            }
            if (++traceCounter % 10000 == 0) {
               System.err.printf("Processed %d traces, %d/%d messages\n",
                                 traceCounter, totalMessages - messageReferences.size(), totalMessages);
               if (reportMemoryUsage) {
                  Composer.reportMemoryUsage();
               }
            }
         }
         for (Processor processor : processors) {
            processor.finish();
         }
         System.err.println("Processing finished");
      }

      public void finish() {
         this.finished = true;
      }
   }

   /**
    * Reads span from binary file
    * @param stream input stream to read data
    * @return span from the stream
    * @throws IOException
    */
   public Span readSpan(DataInputStream stream) throws IOException {
      Span span = new Span();

      if (stream.readBoolean()){
         span.setNonCausal();
      }
      String incoming = stream.readUTF();
      if (!incoming.equals("")){
         span.setIncoming(incoming);
      }

      short outcommingCount = stream.readShort();
      for (int i = 0; i < outcommingCount; i++){
         span.addOutcoming(stream.readUTF());
      }
      short eventCount = stream.readShort();
      for (int i = 0; i < eventCount; i++){
         Span.LocalEvent event = new Span.LocalEvent();
         event.timestamp = stream.readLong();
         event.threadName = stream.readUTF();
         //Is there a better way to do this?
         event.type = Event.Type.values()[stream.readShort()];
         event.text = stream.readUTF();
         span.addEvent(event);
      }
      return span;
   }
}
