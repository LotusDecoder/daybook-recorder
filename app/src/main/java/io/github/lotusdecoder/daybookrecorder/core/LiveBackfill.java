package io.github.lotusdecoder.daybookrecorder.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Backfill rules for the live recording stream (realtimeFlag=1).
 * <ul>
 * <li>The live stream starts at its current sequence number. Any earlier frames are read from the stored file.</li>
 * <li>Slices are contiguous with absolute seq numbers; a weak link only delays them before the link drops.</li>
 * <li>Stop: 18/82(0), the last slice carries flag 4, then 1A/0A = u8 0, u32 duration ms, u32 file id.</li>
 * <li>Slice flag 1 marks a double-click highlight, 2 the first frame.</li>
 * <li>A 1A/07 offset is honoured only on an idle link and counts whole 166 B transport records;
 *     a request repeated mid-transfer is acknowledged but the device keeps streaming the old position.</li>
 * </ul>
 */
public final class LiveBackfill {
    public static final int FRAME_MILLIS=20;
    public static final int FLAG_HIGHLIGHT=1, FLAG_FIRST=2, FLAG_LAST=4;
    public enum Phase { TAIL, HEAD }
    public static final class Plan {
        public final Phase phase;
        public final long offset;
        public final int fromFrame;
        Plan(Phase phase,long offset,int fromFrame){this.phase=phase;this.offset=offset;this.fromFrame=fromFrame;}
    }
    private LiveBackfill() { }

    /** Duration from a 1A/0A end notification, or -1 when it belongs to another file or is malformed. */
    public static long endDurationMs(byte[] payload,long fileId) {
        if(payload.length<9 || Protocol.u32(payload,5)!=fileId)return -1;
        long ms=Protocol.u32(payload,1);
        return ms>0 && ms%FRAME_MILLIS==0 ? ms : -1;
    }
    /** File id carried by a 1A/0A end notification, 0 when absent. */
    public static long endFileId(byte[] payload) {
        return payload.length>=9 ? Protocol.u32(payload,5) : 0;
    }
    /** Seq numbers are absolute positions, so the elapsed audio is (last+1) frames even while the head is missing. */
    public static long receivedDurationMs(RecordingAssembler a) {
        int last=a.lastSequence();
        return last<0 ? 0 : (last+1L)*FRAME_MILLIS;
    }
    /**
     * What to request from the bean after live frames stopped. Frames after the first missing one
     * (link loss) come first with an offset, because a stream always runs to the end of the file;
     * the head is fetched last so the recording can be sealed as soon as it is filled.
     */
    public static Plan plan(RecordingAssembler a,long expectedFrames,RecordingSizeUnit unit) {
        int first=a.firstSequence();
        if(first<0)return new Plan(Phase.HEAD,0,0);
        int gap=a.nextMissingFrom(first);
        boolean tailMissing=expectedFrames>0 && gap<expectedFrames;
        if(unit==RecordingSizeUnit.TRANSPORT_BYTES && tailMissing && gap>0)
            return new Plan(Phase.TAIL,(long)gap*unit.bytesPerFrame,gap);
        return new Plan(Phase.HEAD,0,0);
    }
    /** Highlight positions in milliseconds, ascending and de-duplicated. */
    public static List<Long> highlightMillis(Collection<Long> seqs) {
        List<Long> out=new ArrayList<>();
        for(long seq:new java.util.TreeSet<>(seqs))if(seq>=0)out.add(seq*FRAME_MILLIS);
        return out;
    }
    public static String encodeHighlights(List<Long> millis) {
        StringBuilder s=new StringBuilder();
        for(long ms:millis){if(s.length()>0)s.append(',');s.append(ms);}
        return s.toString();
    }
    public static List<Long> decodeHighlights(String text) {
        List<Long> out=new ArrayList<>();
        if(text==null)return out;
        for(String part:text.trim().split(",")) {
            if(part.isEmpty())continue;
            try{long ms=Long.parseLong(part.trim());if(ms>=0)out.add(ms);}catch(NumberFormatException ignored){ }
        }
        return out;
    }
    public static String describeHighlights(List<Long> millis) {
        if(millis.isEmpty())return "";
        StringBuilder s=new StringBuilder("重点 ").append(millis.size()).append(" 处：");
        for(int i=0;i<millis.size() && i<6;i++) {
            if(i>0)s.append(" · ");
            long sec=millis.get(i)/1000;s.append(String.format(Locale.ROOT,"%d:%02d",sec/60,sec%60));
        }
        if(millis.size()>6)s.append(" …");
        return s.toString();
    }
}
