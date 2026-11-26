package io.github.lotusdecoder.daybookrecorder.core;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Streaming Ogg muxer. Retains the original 160-byte slices separately. */
public final class OggOpus {
    private OggOpus() { }
    public static long mux(File raw,File output,int serial) throws IOException {
        if (raw.length()==0 || raw.length()%160!=0) throw new IOException("incomplete raw audio");
        long total=raw.length()/160, granule=0; int page=0,channels=1;
        // D3200 streams 160-byte CBR Opus packets (TOC 0xbc,
        // stereo, 20 ms). Scan before writing the OpusHead: mono packets may
        // also occur in a stereo stream, but a stereo packet must not be
        // rejected or silently downmixed by declaring a mono output stream.
        try(InputStream in=new BufferedInputStream(new FileInputStream(raw))) {
            for(long n=0;n<total;n++) {
                byte[] packet=readPacket(in);
                try{samples(packet);}catch(IOException e){throw new IOException("Opus packet "+n+": "+e.getMessage(),e);}
                if((packet[0]&4)!=0)channels=2;
            }
        }
        try (InputStream in=new BufferedInputStream(new FileInputStream(raw));
             FileOutputStream fd=new FileOutputStream(output); BufferedOutputStream out=new BufferedOutputStream(fd)) {
            byte[] head=ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                    .put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte)1).put((byte)channels)
                    .putShort((short)0).putInt(48000).putShort((short)0).put((byte)0).array();
            byte[] vendor="DaybookRecorder".getBytes(StandardCharsets.US_ASCII);
            byte[] tags=ByteBuffer.allocate(16+vendor.length).order(ByteOrder.LITTLE_ENDIAN)
                    .put("OpusTags".getBytes(StandardCharsets.US_ASCII)).putInt(vendor.length).put(vendor).putInt(0).array();
            writePage(out,Collections.singletonList(head),serial,page++,0,2);
            writePage(out,Collections.singletonList(tags),serial,page++,0,0);
            for (long n=0;n<total;) {
                List<byte[]> batch=new ArrayList<>();
                while (batch.size()<50 && n<total) {
                    // No per-packet length is supplied. A trailing zero is a
                    // legal coded byte, not evidence of padding. Preserve all
                    // 160 bytes; trimming changes the Opus payload.
                    byte[] packet=readPacket(in);
                    granule+=samples(packet); batch.add(packet); n++;
                }
                writePage(out,batch,serial,page++,granule,n==total ? 4 : 0);
            }
            out.flush(); fd.getFD().sync();
        }
        return (granule*1000+24000)/48000;
    }
    private static byte[] readPacket(InputStream in)throws IOException {
        byte[] packet=new byte[160];int read=0;
        while(read<packet.length){int n=in.read(packet,read,packet.length-read);if(n<0)throw new EOFException();read+=n;}
        return packet;
    }
    public static int samples(byte[] p) throws IOException {
        if (p.length<1) throw new IOException("empty Opus packet");
        int toc=p[0]&255, size;
        if ((toc&0x80)!=0)size=(48000<<((toc>>3)&3))/400;
        else if ((toc&0x60)==0x60)size=(toc&8)!=0 ? 960 : 480;
        else { int config=(toc>>3)&3; size=config==3 ? 2880 : (48000<<config)/100; }
        int code=toc&3, frames=code==0 ? 1 : code==3 ? (p.length>1 ? p[1]&63 : 0) : 2;
        // RFC 6716 section 3.1: bit 2 is the stereo flag, not an error bit.
        if (frames==0 || size*frames>5760) throw new IOException("invalid Opus packet duration");
        return size*frames;
    }
    private static void writePage(OutputStream out,List<byte[]> packets,int serial,int sequence,long granule,int flags) throws IOException {
        int size=0; for(byte[] p:packets)size+=p.length;
        ByteBuffer b=ByteBuffer.allocate(27+packets.size()+size).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte)0).put((byte)flags).putLong(granule)
                .putInt(serial).putInt(sequence).putInt(0).put((byte)packets.size());
        for(byte[] p:packets) { if(p.length>=255)throw new IOException("packet too large"); b.put((byte)p.length); }
        for(byte[] p:packets)b.put(p);
        byte[] data=b.array(); int crc=0;
        for(byte v:data) { crc^=(v&255)<<24; for(int i=0;i<8;i++)crc=(crc<<1)^((crc<0)?0x04c11db7:0); }
        b.putInt(22,crc); out.write(data);
    }
}
