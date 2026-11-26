package io.github.lotusdecoder.daybookrecorder.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** D3200 wire format, based on tacshi/Soundcore f01a19c. No reset or bulk erase. */
public final class Protocol {
    public static final UUID SERVICE = UUID.fromString("020cf5da-0000-1000-8000-00805f9b34fb");
    public static final UUID WRITE = UUID.fromString("00007777-0000-1000-8000-00805f9b34fb");
    public static final UUID NOTIFY = UUID.fromString("00008888-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private Protocol() { }

    public static byte[] command(int type, int id, byte... payload) {
        int len = 10 + payload.length;
        if (len > 65535) throw new IllegalArgumentException("frame too large");
        byte[] b = new byte[len];
        b[0] = 8; b[1] = (byte) 0xee; b[5] = (byte) type; b[6] = (byte) id;
        b[7] = (byte) len; b[8] = (byte) (len >>> 8);
        System.arraycopy(payload, 0, b, 9, payload.length);
        b[len - 1] = checksum(b, 0, len - 1);
        return b;
    }
    public static byte[] list(int type, int page) {
        return command(type, 0x0e, (byte) page, (byte) (page >>> 8));
    }
    public static byte[] requestFile(long id, long offset, boolean live) {
        return command(0x1a, 7, ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) offset).putInt((int) id).put((byte) (live ? 1 : 0)).array());
    }
    public static byte[] deleteFile(long id) {
        if(id<=0 || id>0xffff_ffffL)throw new IllegalArgumentException("invalid file id");
        return command(0x1a,0x10,ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)id).array());
    }
    public static byte[] syncTime(long seconds, int timezoneHours) {
        return command(1, 0xa6, ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .putInt((int) seconds).put((byte) timezoneHours).array());
    }
    public static int u16(byte[] b, int o) { return (b[o] & 255) | ((b[o+1] & 255) << 8); }
    public static long u32(byte[] b, int o) { return Integer.toUnsignedLong(ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()); }
    public static byte checksum(byte[] b, int start, int end) {
        int sum = 0; for (int i = start; i < end; i++) sum += b[i] & 255;
        return (byte) sum;
    }
    public static final class Packet {
        public final byte[] raw, payload;
        public final int type, id, status;
        public Packet(byte[] raw) {
            this.raw = raw; status = raw[4] & 255; type = raw[5] & 255; id = raw[6] & 255;
            payload = Arrays.copyOfRange(raw, 9, raw.length - 1);
        }
        public boolean ok() { return (status & 15) == 1; }
    }
    /** Bounded incremental framing: retains split headers; rejects corrupt frames. */
    public static final class Decoder {
        private byte[] pending = new byte[0];
        public int rejected;
        public List<Packet> feed(byte[] input) {
            if (input.length > 65535 || pending.length + input.length > 131070) {
                pending = new byte[0]; rejected++; return new ArrayList<>();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(pending, 0, pending.length); out.write(input, 0, input.length);
            byte[] all = out.toByteArray(); int i = 0;
            List<Packet> packets = new ArrayList<>();
            while (i + 4 <= all.length) {
                if (all[i] != 9 || all[i+1] != (byte) 255 || all[i+2] != 0 || all[i+3] != 0) { i++; continue; }
                if (all.length - i < 9) break;
                int n = u16(all, i+7);
                if (n < 10 || n > 32768) { rejected++; i++; continue; }
                if (all.length - i < n) break;
                if (checksum(all, i, i+n-1) != all[i+n-1]) { rejected++; i++; continue; }
                packets.add(new Packet(Arrays.copyOfRange(all, i, i+n))); i += n;
            }
            pending = Arrays.copyOfRange(all, i, all.length);
            return packets;
        }
    }
    public static final class Entry {
        public final long id, size, endSeconds;
        public Entry(long id, long size, long endSeconds) { this.id=id; this.size=size; this.endSeconds=endSeconds; }
    }
    public static final class FileList {
        public final List<Entry> entries = new ArrayList<>();
        public final int count;
        public final long activeId;
        public FileList(byte[] p, boolean withEnd) {
            if (p.length < 2) throw new IllegalArgumentException("short file list");
            count = u16(p, 0); int stride = withEnd ? 12 : 8;
            if (count > 1000 || p.length < 2 + count*stride) throw new IllegalArgumentException("incomplete file list");
            int o=2;
            for (int i=0;i<count;i++,o+=stride) {
                long id=u32(p,o), end=withEnd ? u32(p,o+4) : 0, size=u32(p,o+stride-4);
                if (id>0) entries.add(new Entry(id,size,end));
            }
            activeId = p.length >= o+8 ? u32(p,o) : 0;
        }
    }
}
