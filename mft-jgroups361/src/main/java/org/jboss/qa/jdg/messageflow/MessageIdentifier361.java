package org.jboss.qa.jdg.messageflow;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.Util;

import java.io.DataInput;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Created by rmacor on 3/10/15.
 */
public class MessageIdentifier361 extends MessageIdentifier35{

    //Replacing ExposedInputStream with ByteArrayStream
    //TODO: need to be backported to MessageIdentifier35
    public static List<String> getDataIdentifiers(Runnable r) {
        try {
            Class<?> clazz = r.getClass();
            if (clazz.getSimpleName().equals("MyHandler")) {
                return identifyMyHandler(r, clazz);
            } else if (clazz.getSimpleName().equals("BatchHandler")) {
                return identifyBatchHandler(r, clazz);
            } else if (clazz.getSimpleName().equals("IncomingPacket")) {
                return identifyIncomingPacket(r, clazz);
            }

        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static List<String> identifyIncomingPacket(Runnable r, Class<?> clazz) throws NoSuchFieldException, IllegalAccessException {
        Field bufField = clazz.getDeclaredField("buf");
        bufField.setAccessible(true);
        byte[] buf = (byte[]) bufField.get(r);
        Field offsetField = clazz.getDeclaredField("offset");
        offsetField.setAccessible(true);
        int offset = (Integer) offsetField.get(r);
        Field lengthField = clazz.getDeclaredField("length");
        lengthField.setAccessible(true);
        int length = (Integer) lengthField.get(r);
        return unmarshall(buf, offset, length);
    }
    //TODO: should ByteArrayDataInputStream be closed?
    private static List<String> unmarshall(byte[] buf, int offset, int length) {
        short                        version;
        byte                         flags;
        //ExposedByteArrayInputStream in_stream;
        DataInput in_stream;// = null;
        //DataInputStream dis=null;
        try {
            in_stream=new ByteArrayDataInputStream(buf, offset, length);
            version=in_stream.readShort();
            flags=in_stream.readByte();
            boolean isMessageList=(flags & LIST) == LIST;
            boolean multicast=(flags & MULTICAST) == MULTICAST;
            if(isMessageList) { // used if message bundling is enabled
                return readMessageList(in_stream);
            }
            else {
                return readMessage(in_stream);
            }
        }
        catch(Throwable t) {
            System.err.println(t);
            t.printStackTrace(System.err);
            return null;
        }
    }
    protected static List<String> readMessage(DataInput instream) throws Exception {
        Message msg = new Message(false); // don't create headers, readFrom() will do this
        msg.readFrom(instream);
        return Collections.singletonList(getMessageIdentifier(msg));
    }
    protected static List<String> readMessageList(DataInput in) throws Exception {
        List<String> list=new ArrayList<String>();
        Address dest = Util.readAddress(in);
        Address src = Util.readAddress(in);
        while(in.readBoolean()) {
            Message msg = new Message(false);
            msg.readFrom(in);
            msg.setDest(dest);
            if(msg.getSrc() == null)
                msg.setSrc(src);
            list.add(getMessageIdentifier(msg));
        }
        return list;
    }
}
