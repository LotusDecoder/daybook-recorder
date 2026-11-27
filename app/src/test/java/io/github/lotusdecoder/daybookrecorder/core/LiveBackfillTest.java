package io.github.lotusdecoder.daybookrecorder.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.*;
import java.util.*;
import static org.junit.Assert.*;

/** Generated recordings exercise gaps, duration checks and highlight ordering. */
public class LiveBackfillTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static byte[] end(long ms,long id){return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).put((byte)0).putInt((int)ms).putInt((int)id).array();}
    private RecordingAssembler frames(int from,int toExclusive) throws Exception {
        RecordingAssembler a=new RecordingAssembler(temp.newFile());
        for(int i=from;i<toExclusive;i++){byte[] f=new byte[160];f[0]=(byte)0xbc;f[1]=(byte)i;a.add(i,f);}
        return a;
    }
    @Test public void liveEndNotificationCarriesDurationOnlyForItsOwnFile() {
        assertEquals(1280L,LiveBackfill.endDurationMs(end(1280,101L),101L));
        assertEquals(-1L,LiveBackfill.endDurationMs(end(1280,101L),102L));
        assertEquals(-1L,LiveBackfill.endDurationMs(new byte[9],101L));
        assertEquals(-1L,LiveBackfill.endDurationMs(end(1281,101L),101L));
        assertEquals(-1L,LiveBackfill.endDurationMs(new byte[5],101L));
        assertEquals(101L,LiveBackfill.endFileId(end(1,101L)));
        assertEquals(0L,LiveBackfill.endFileId(new byte[4]));
    }
    @Test public void missingHeadIsFetchedFromZeroAndDurationUsesAbsoluteSeq() throws Exception {
        try(RecordingAssembler a=frames(8,64)) {
            assertEquals(1280L,LiveBackfill.receivedDurationMs(a));
            assertEquals(0,a.internalGaps());assertEquals(8,a.gapsBeforeLast());
            LiveBackfill.Plan p=LiveBackfill.plan(a,64,RecordingSizeUnit.TRANSPORT_BYTES);
            assertEquals(LiveBackfill.Phase.HEAD,p.phase);assertEquals(0L,p.offset);
            assertFalse(a.complete(64L*166,RecordingSizeUnit.TRANSPORT_BYTES));
            for(int i=0;i<8;i++){byte[] f=new byte[160];f[0]=(byte)0xbc;f[1]=(byte)i;a.add(i,f);}
            assertTrue(a.complete(64L*166,RecordingSizeUnit.TRANSPORT_BYTES));
        }
    }
    @Test public void linkLossFetchesTheTailByWholeTransportRecordsBeforeTheHead() throws Exception {
        try(RecordingAssembler a=frames(12,32)) {
            assertEquals(32,a.nextMissingFrom(12));
            LiveBackfill.Plan p=LiveBackfill.plan(a,96,RecordingSizeUnit.TRANSPORT_BYTES);
            assertEquals(LiveBackfill.Phase.TAIL,p.phase);assertEquals(32,p.fromFrame);assertEquals(32L*166,p.offset);
            assertEquals(0,p.offset%166);
            assertEquals(LiveBackfill.Phase.HEAD,LiveBackfill.plan(a,-1,RecordingSizeUnit.TRANSPORT_BYTES).phase);
            assertEquals(LiveBackfill.Phase.HEAD,LiveBackfill.plan(a,96,RecordingSizeUnit.AUDIO_BYTES).phase);
        }
        try(RecordingAssembler a=frames(0,118)) {
            LiveBackfill.Plan p=LiveBackfill.plan(a,120,RecordingSizeUnit.TRANSPORT_BYTES);
            assertEquals(LiveBackfill.Phase.TAIL,p.phase);assertEquals(118L*166,p.offset);
        }
        try(RecordingAssembler a=frames(8,100)) {
            for(int i=110;i<200;i++)a.add(i,new byte[160]);
            assertEquals(10,a.internalGaps());assertEquals(100,a.nextMissingFrom(8));
            assertEquals(100L*166,LiveBackfill.plan(a,200,RecordingSizeUnit.TRANSPORT_BYTES).offset);
        }
        try(RecordingAssembler a=new RecordingAssembler(temp.newFile())) {
            assertEquals(LiveBackfill.Phase.HEAD,LiveBackfill.plan(a,100,RecordingSizeUnit.TRANSPORT_BYTES).phase);
            assertEquals(0L,LiveBackfill.receivedDurationMs(a));assertEquals(0,a.internalGaps());
        }
    }
    @Test public void highlightsRoundTripSortedAndDescribed() {
        List<Long> ms=LiveBackfill.highlightMillis(Arrays.asList(500L,200L,750L,200L));
        assertEquals(Arrays.asList(4000L,10000L,15000L),ms);
        assertEquals("4000,10000,15000",LiveBackfill.encodeHighlights(ms));
        assertEquals(ms,LiveBackfill.decodeHighlights("4000, 10000,15000,"));
        assertEquals(Collections.emptyList(),LiveBackfill.decodeHighlights("x,-5"));
        assertEquals("重点 3 处：0:04 · 0:10 · 0:15",LiveBackfill.describeHighlights(ms));
        assertEquals("",LiveBackfill.describeHighlights(Collections.emptyList()));
        assertTrue(LiveBackfill.describeHighlights(LiveBackfill.highlightMillis(Arrays.asList(1L,2L,3L,4L,5L,6L,7L))).endsWith(" …"));
    }
}
