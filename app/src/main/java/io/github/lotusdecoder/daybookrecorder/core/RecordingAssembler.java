package io.github.lotusdecoder.daybookrecorder.core;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;

/** Random access prevents duplicate/out-of-order BLE packets from corrupting recordings. */
public final class RecordingAssembler implements Closeable {
    /** Exact common multiple of 160 B audio payload and 166 B transport record (83*160 == 80*166). */
    public static final long RESUME_ALIGN_BYTES=13280L;
    private static final int RESUME_MIN_FRAMES=84;
    private static final int RESUME_MAGIC=0x44425231; // "DBR1"
    private static final int MAX_FRAMES=24*60*60*50;
    private final File target,metaFile,metaTmpFile;
    private final RandomAccessFile file;
    private final BitSet received=new BitSet();
    private int resumedContiguousFrames;
    private long savedHeadSize=-1L;
    private int requiredOverlapSeq=-1;
    private boolean overlapVerified=true;
    private boolean closed;

    public RecordingAssembler(File target) throws IOException {
        this(target,false);
    }

    public RecordingAssembler(File target,boolean resume) throws IOException {
        this.target=target;
        this.metaFile=resumeFile(target);
        this.metaTmpFile=new File(target.getParentFile(),target.getName()+".resume.tmp");
        this.file=new RandomAccessFile(target,"rw");
        if (!resume) {
            clearResume(target);
            file.setLength(0);
            return;
        }
        if (!restoreFromSidecar() && !restoreLegacyOpusPrefix()) {
            clearResume(target);
            file.setLength(0);
        }
    }

    private boolean restoreFromSidecar() throws IOException {
        if (!metaFile.isFile()) return false;
        int contiguous;
        long headSize;
        byte[] anchor=new byte[160];
        try (DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(metaFile)))) {
            if (in.readInt()!=RESUME_MAGIC) return false;
            contiguous=in.readInt();
            headSize=in.readLong();
            in.readFully(anchor);
        } catch (IOException e) {
            return false;
        }
        if (contiguous<2 || contiguous>=MAX_FRAMES || file.length()<contiguous*160L) return false;
        byte[] onDisk=new byte[160];
        file.seek((contiguous-1L)*160L);
        file.readFully(onDisk);
        if (!Arrays.equals(anchor,onDisk)) return false;
        if (file.length()!=contiguous*160L) file.setLength(contiguous*160L);
        received.set(0,contiguous);
        resumedContiguousFrames=contiguous;
        savedHeadSize=headSize;
        requiredOverlapSeq=contiguous-1;
        overlapVerified=false;
        return true;
    }

    /** Salvage contiguous verified Opus frames when no resume sidecar exists. */
    private boolean restoreLegacyOpusPrefix() throws IOException {
        if (metaFile.exists() || file.length()<320L) return false;
        long max=Math.min((long)(MAX_FRAMES-1),file.length()/160L);
        int contiguous=0;
        file.seek(0L);
        byte[] frame=new byte[160];
        for (int i=0;i<max;i++) {
            file.readFully(frame);
            if (isAllZero(frame)) break;
            try { OggOpus.samples(frame); } catch (IOException bad) { break; }
            contiguous++;
        }
        if (contiguous<2) return false;
        if (file.length()!=contiguous*160L) file.setLength(contiguous*160L);
        received.set(0,contiguous);
        resumedContiguousFrames=contiguous;
        savedHeadSize=-1L;
        requiredOverlapSeq=contiguous-1;
        overlapVerified=false;
        return true;
    }

    private static boolean isAllZero(byte[] data) {
        for (byte b:data) if (b!=0) return false;
        return true;
    }

    public static File resumeFile(File target) {
        return new File(target.getParentFile(),target.getName()+".resume");
    }

    public static File attemptsFile(File target) {
        return new File(target.getParentFile(),target.getName()+".attempts");
    }

    public static int incrementResumeAttempt(File target) {
        File f=attemptsFile(target);
        int prev=0;
        if (f.isFile()) {
            try (DataInputStream in=new DataInputStream(new FileInputStream(f))) {
                prev=Math.max(0,in.readInt());
            } catch (IOException ignored) { }
        }
        int next=prev+1;
        try (FileOutputStream raw=new FileOutputStream(f);
             DataOutputStream out=new DataOutputStream(raw)) {
            out.writeInt(next);
            out.flush();
            raw.getFD().sync();
        } catch (IOException ignored) { }
        return next;
    }

    public static void resetResumeAttempts(File target) {
        attemptsFile(target).delete();
    }

    public static void clearResume(File target) {
        resumeFile(target).delete();
        new File(target.getParentFile(),target.getName()+".resume.tmp").delete();
        attemptsFile(target).delete();
    }

    public boolean add(long sequence,byte[] data) throws IOException {
        if (closed || sequence<0 || sequence>=MAX_FRAMES || data.length!=160) throw new IOException("invalid slice range");
        int seq=(int)sequence; long offset=sequence*160;
        if (received.get(seq)) {
            byte[] old=new byte[160]; file.seek(offset); file.readFully(old);
            if (!Arrays.equals(old,data)) throw new IOException("conflicting duplicate slice");
            if (seq==requiredOverlapSeq) overlapVerified=true;
            return false;
        }
        file.seek(offset); file.write(data); received.set(seq); return true;
    }

    public int resumedFrames() { return resumedContiguousFrames; }
    public long savedHeadSize() { return savedHeadSize; }
    public boolean overlapVerified() { return requiredOverlapSeq<0 || overlapVerified; }

    /**
     * Returns a byte offset aligned to BOTH 160 B and 166 B (multiples of 13,280 B) that
     * strictly precedes or lands at frame (resumedContiguousFrames - 1), guaranteeing at least
     * one overlapping frame to verify stream continuity under either firmware unit.
     */
    public long resumeRequestOffset() {
        if (resumedContiguousFrames<RESUME_MIN_FRAMES) return 0L;
        long blocks=(resumedContiguousFrames-1L)/83L;
        return blocks*RESUME_ALIGN_BYTES;
    }

    public void checkpoint(long headSize) throws IOException {
        if (closed) return;
        file.getFD().sync();
        int contiguous=firstMissingSequence();
        if (contiguous<2) return;
        if (!overlapVerified() && contiguous>resumedContiguousFrames) return;
        if (!overlapVerified() && metaFile.isFile()) return;
        byte[] anchor=new byte[160];
        file.seek((contiguous-1L)*160L);
        file.readFully(anchor);
        try (FileOutputStream raw=new FileOutputStream(metaTmpFile);
             DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw))) {
            out.writeInt(RESUME_MAGIC);
            out.writeInt(contiguous);
            out.writeLong(headSize);
            out.write(anchor);
            out.flush();
            raw.getFD().sync();
        }
        Files.move(metaTmpFile.toPath(),metaFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        savedHeadSize=headSize;
    }

    public void reset() throws IOException {
        if (closed) throw new IOException("assembler closed");
        clearResume(target);
        file.setLength(0);
        received.clear();
        resumedContiguousFrames=0;
        savedHeadSize=-1L;
        requiredOverlapSeq=-1;
        overlapVerified=true;
    }

    public int frames() { return received.cardinality(); }
    public long bytes() { return frames()*160L; }
    public int firstSequence() { return received.nextSetBit(0); }
    public int lastSequence() { return received.length()-1; }
    public int firstMissingSequence() { return received.nextClearBit(0); }
    public int gapsBeforeLast() { return received.length()-frames(); }
    /** First missing frame at or after {@code from}; equals the frame count past the last one when none. */
    public int nextMissingFrom(int from) { return received.nextClearBit(Math.max(0,from)); }
    /** Missing frames between the first and last received frame (a live stream never carries the head). */
    public int internalGaps() { int first=firstSequence(); return first<0?0:received.length()-first-frames(); }
    public boolean contiguous() {
        return !received.isEmpty() && firstMissingSequence()==received.length();
    }
    public boolean complete(long expected) {
        return complete(expected,RecordingSizeUnit.AUDIO_BYTES);
    }
    public boolean complete(long expected,RecordingSizeUnit unit) {
        // Fix the unit before examining data. Accepting whichever of 160/166
        // matches would hide missing tail frames at their common multiples.
        return overlapVerified() && contiguous() && expected==received.length()*(long)unit.bytesPerFrame;
    }
    public void sync() throws IOException { file.getFD().sync(); }
    @Override public void close() throws IOException {
        if (!closed) { closed=true; try { file.getFD().sync(); } finally { file.close(); } }
    }
}
