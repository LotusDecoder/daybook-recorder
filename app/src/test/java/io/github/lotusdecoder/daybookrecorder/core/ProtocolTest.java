package io.github.lotusdecoder.daybookrecorder.core;

import org.junit.Test;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProtocolTest {
    static byte[] rx(int type,int id,byte[] payload) {
        byte[] b=Protocol.command(type,id,payload);b[0]=9;b[1]=(byte)255;b[4]=1;b[b.length-1]=Protocol.checksum(b,0,b.length-1);return b;
    }
    @Test public void commandMatchesKnownWireExample() {
        assertArrayEquals(new byte[]{8,(byte)0xee,0,0,0,1,1,10,0,2},Protocol.command(1,1));
        byte[] request=Protocol.requestFile(0x12345678,320,true);
        assertArrayEquals(new byte[]{0x40,1,0,0,0x78,0x56,0x34,0x12,1},Arrays.copyOfRange(request,9,18));
    }
    @Test public void reassemblesAtEveryPossibleBoundaryAndAfterGarbage() {
        byte[] frame=rx(0x1a,6,new byte[]{1,2,3,4});
        for(int split=0;split<=frame.length;split++) {
            Protocol.Decoder decoder=new Protocol.Decoder();decoder.feed(new byte[]{7,6,5});
            List<Protocol.Packet> result=new ArrayList<>(decoder.feed(Arrays.copyOfRange(frame,0,split)));
            result.addAll(decoder.feed(Arrays.copyOfRange(frame,split,frame.length)));
            assertEquals("split "+split,1,result.size());assertEquals(0x1a,result.get(0).type);
        }
    }
    @Test public void checksumFailureCannotBecomeAudio() {
        byte[] bad=rx(0x1a,8,new byte[165]);bad[20]^=1;
        byte[] good=rx(0x18,0x82,new byte[]{0});
        byte[] both=new byte[bad.length+good.length];System.arraycopy(bad,0,both,0,bad.length);System.arraycopy(good,0,both,bad.length,good.length);
        Protocol.Decoder d=new Protocol.Decoder();List<Protocol.Packet> result=d.feed(both);
        assertEquals(1,result.size());assertEquals(0x82,result.get(0).id);assertEquals(1,d.rejected);
    }
    @Test public void fileListRetainsLiveFileAndEndTime() {
        byte[] list=ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).putInt(100).putInt(120).putInt(320).putInt(200).putInt(3).array();
        Protocol.FileList parsed=new Protocol.FileList(list,true);
        assertEquals(1,parsed.count);assertEquals(120,parsed.entries.get(0).endSeconds);assertEquals(200,parsed.activeId);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedInventory(){new Protocol.FileList(new byte[]{10,0,1,2},true);}
}
