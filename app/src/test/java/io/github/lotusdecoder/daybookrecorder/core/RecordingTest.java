package io.github.lotusdecoder.daybookrecorder.core;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class RecordingTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void gapsDuplicatesAndOutOfOrderAreHandledWithoutFalseCompletion() throws Exception {
        File f=temp.newFile();byte[] a=new byte[160],b=new byte[160];Arrays.fill(b,(byte)9);
        try(RecordingAssembler r=new RecordingAssembler(f)) {
            r.add(1,b);assertFalse(r.complete(160));assertFalse(r.complete(320));
            assertFalse(r.add(1,b));assertEquals(160,r.bytes());r.add(0,a);assertTrue(r.complete(320));assertFalse(r.complete(332));assertFalse(r.complete(319));
            try{r.add(1,a);fail("conflicting duplicate accepted");}catch(IOException expected){ }
        }
        byte[] actual=Files.readAllBytes(f.toPath());assertArrayEquals(a,Arrays.copyOfRange(actual,0,160));assertArrayEquals(b,Arrays.copyOfRange(actual,160,320));
    }
    @Test public void transportSizeHeuristicCannotHideMissingTailFrames() throws Exception {
        try(RecordingAssembler r=new RecordingAssembler(temp.newFile())) {
            for(int i=0;i<80;i++)r.add(i,new byte[160]);
            assertFalse(r.complete(83*160L)); // 83*160 == 80*166: three missing frames.
        }
    }
    @Test public void verifiedFirmwareUsesTransportBytesToCheckForTheLastFrame() throws Exception {
        RecordingSizeUnit unit = RecordingSizeUnit.forFirmware("04.00");
        assertEquals(RecordingSizeUnit.TRANSPORT_BYTES, unit);
        byte[] frame = new byte[160];
        for (int frameCount : new int[]{7, 80, 1024}) {
            long reportedBytes = frameCount * 166L;
            try (RecordingAssembler recording = new RecordingAssembler(temp.newFile())) {
                for (int seq = 0; seq < frameCount - 1; seq++) recording.add(seq, frame);
                assertFalse("Missing last frame", recording.complete(reportedBytes, unit));
                recording.add(frameCount - 1, frame);
                assertEquals(frameCount * 160L, recording.bytes());
                assertTrue(recording.complete(reportedBytes, unit));
                assertFalse(recording.complete(reportedBytes));
            }
        }
    }
    @Test public void fixedUnitsDoNotGuessAtCommonMultiplesOrUnknownFirmware() throws Exception {
        for(String firmware:new String[]{"","03.00","05.00","04.00-unverified",null})
            assertEquals(RecordingSizeUnit.AUDIO_BYTES,RecordingSizeUnit.forFirmware(firmware));
        try(RecordingAssembler r=new RecordingAssembler(temp.newFile())) {
            for(int i=0;i<80;i++)r.add(i,new byte[160]);
            assertTrue(r.complete(80*166L,RecordingSizeUnit.TRANSPORT_BYTES));
            assertFalse(r.complete(83*166L,RecordingSizeUnit.TRANSPORT_BYTES));
            assertFalse(r.complete(83*160L,RecordingSizeUnit.AUDIO_BYTES));
            assertFalse(r.complete(80*160L,RecordingSizeUnit.TRANSPORT_BYTES));
        }
    }
    @Test public void transportByteCountNeverHidesMissingStartOrMiddle() throws Exception {
        int frameCount = 96;
        byte[] frame = new byte[160];
        for (int missing : new int[]{0, frameCount / 2}) {
            try (RecordingAssembler recording = new RecordingAssembler(temp.newFile())) {
                for (int seq = 0; seq <= frameCount; seq++) {
                    if (seq != missing) recording.add(seq, frame);
                }
                assertEquals(frameCount * 160L, recording.bytes());
                assertFalse(recording.complete(frameCount * 166L, RecordingSizeUnit.TRANSPORT_BYTES));
            }
        }
    }
    @Test public void diagnosticsDistinguishSizeMismatchFromSequenceGaps() throws Exception {
        try(RecordingAssembler r=new RecordingAssembler(temp.newFile())) {
            assertEquals(-1,r.firstSequence());assertEquals(-1,r.lastSequence());assertEquals(0,r.gapsBeforeLast());
            r.add(1,new byte[160]);r.add(3,new byte[160]);
            assertEquals(1,r.firstSequence());assertEquals(3,r.lastSequence());assertEquals(0,r.firstMissingSequence());assertEquals(2,r.gapsBeforeLast());
            assertFalse(r.complete(320)); // Byte count matches but sequence 0 and 2 are absent.
            r.add(0,new byte[160]);r.add(2,new byte[160]);
            assertEquals(4,r.firstMissingSequence());assertEquals(0,r.gapsBeforeLast());assertTrue(r.complete(640));
            assertFalse(r.complete(664)); // Contiguous data with a different size unit is still unverified.
        }
    }
    @Test public void endWaitsForLateSlicesAndStillRequiresAllFrames() throws Exception {
        TransferEnd end=new TransferEnd();
        try(RecordingAssembler r=new RecordingAssembler(temp.newFile())) {
            r.add(0,new byte[160]);end.data(0);
            assertFalse(end.settled(10000)); // Silence alone cannot finalize a download.
            end.received(10000);
            assertFalse(end.settled(10000)); // The old service checked here and discarded the transfer.
            r.add(2,new byte[160]);end.data(11500);
            assertFalse(end.settled(12000)); // Late data extends the quiet window.
            assertTrue(end.settled(13500));assertFalse(r.complete(480));
            r.add(1,new byte[160]);end.data(13500);
            assertFalse(end.settled(15499));assertTrue(end.settled(15500));assertTrue(r.complete(480));
        }
    }
    @Test public void repeatedEndAndDuplicateDataCannotBypassQuietWindow() throws Exception {
        TransferEnd end=new TransferEnd();
        try(RecordingAssembler r=new RecordingAssembler(temp.newFile())) {
            r.add(0,new byte[160]);end.received(10);end.received(1900);
            assertFalse(end.settled(2010));assertTrue(end.settled(3900));
            assertFalse(r.add(0,new byte[160]));end.data(3900);
            assertFalse(end.settled(4000));assertTrue(end.settled(5900));assertTrue(r.complete(160));
        }
    }
    @Test public void processRestartNeverPromotesUnverifiedPartialBytes() throws Exception {
        File f=temp.newFile();try(RecordingAssembler a=new RecordingAssembler(f)){a.add(0,new byte[160]);}
        try(RecordingAssembler recovered=new RecordingAssembler(f)){assertEquals(0,recovered.bytes());assertFalse(recovered.complete(160));}
    }
    @Test public void oggHasCorrectChecksumsDurationAndEndFlag() throws Exception {
        File raw=temp.newFile(),ogg=temp.newFile();byte[] frame=new byte[160];frame[0]=(byte)0xf8;frame[1]=(byte)0xff;frame[2]=(byte)0xfe;
        try(OutputStream out=new FileOutputStream(raw)){for(int i=0;i<100;i++)out.write(frame);}
        assertEquals(2000,OggOpus.mux(raw,ogg,123));byte[] bytes=Files.readAllBytes(ogg.toPath());int o=0,pages=0;
        while(o<bytes.length){assertEquals('O',bytes[o]);int count=bytes[o+26]&255,size=27+count;for(int i=0;i<count;i++)size+=bytes[o+27+i]&255;
            byte[] page=Arrays.copyOfRange(bytes,o,o+size);int expected=ByteBuffer.wrap(page,22,4).order(ByteOrder.LITTLE_ENDIAN).getInt();Arrays.fill(page,22,26,(byte)0);
            assertEquals(expected,crc(page));if(o+size==bytes.length){assertEquals(4,bytes[o+5]);assertEquals(96000,ByteBuffer.wrap(bytes,o+6,8).order(ByteOrder.LITTLE_ENDIAN).getLong());}
            pages++;o+=size;}
        assertEquals(4,pages);
    }
    private int crc(byte[] input){int crc=0;for(byte b:input)for(int bit=7;bit>=0;bit--){int top=(crc>>>31)^((b>>>bit)&1);crc<<=1;if(top!=0)crc^=0x04c11db7;}return crc;}
    @Test(expected=IOException.class) public void rejectsPartialOpusFrame() throws Exception {File raw=temp.newFile();Files.write(raw.toPath(),new byte[159]);OggOpus.mux(raw,temp.newFile(),1);}
    @Test(expected=IOException.class) public void rejectsInvalidPacketDuration() throws Exception {OggOpus.samples(new byte[]{(byte)0xfb,0});}
    @Test public void stereoTocUsesTheSameDurationRules() throws Exception {
        assertEquals(960,OggOpus.samples(new byte[]{(byte)0xbc,1}));
        assertEquals(960,OggOpus.samples(new byte[]{(byte)0xb8,1}));
        assertEquals(1920,OggOpus.samples(new byte[]{(byte)0xbd,1,1}));
        try{OggOpus.samples(new byte[]{(byte)0xbf,7});fail("140 ms packet accepted");}catch(IOException expected){ }
    }
    @Test public void mixedPacketsDeclareStereoAndPreserveTrailingCodedZeros() throws Exception {
        File raw=temp.newFile(),ogg=temp.newFile();byte[] mono=new byte[160],stereo=new byte[160];
        Arrays.fill(mono,(byte)1);Arrays.fill(stereo,(byte)2);
        mono[0]=(byte)0xb8;stereo[0]=(byte)0xbc;stereo[159]=0;
        try(OutputStream out=new FileOutputStream(raw)){out.write(mono);out.write(stereo);}
        assertEquals(40,OggOpus.mux(raw,ogg,9));
        byte[] file=Files.readAllBytes(ogg.toPath());List<byte[]> packets=new ArrayList<>();
        for(int offset=0;offset<file.length;){
            int segments=file[offset+26]&255,pos=offset+27+segments;
            for(int i=0;i<segments;i++){int n=file[offset+27+i]&255;packets.add(Arrays.copyOfRange(file,pos,pos+n));pos+=n;}
            offset=pos;
        }
        assertEquals(2,packets.get(0)[9]);assertArrayEquals(mono,packets.get(2));assertArrayEquals(stereo,packets.get(3));
    }
    @Test public void breakpointResumeAlignsBothUnitsRequiresOverlapAndSurvivesMultiHopInterrupts() throws Exception {
        File f=temp.newFile(),expectedRaw=temp.newFile(),ogg=temp.newFile();
        byte[][] packets=new byte[220][160];
        try(OutputStream out=new FileOutputStream(expectedRaw)) {
            for(int i=0;i<packets.length;i++) {
                Arrays.fill(packets[i],(byte)(i+1));
                packets[i][0]=(byte)0xbc;
                packets[i][159]=(byte)(i^0x5a);
                out.write(packets[i]);
            }
        }
        long headBytes=220L*RecordingSizeUnit.TRANSPORT_BYTES.bytesPerFrame;
        // Hop 1: receive 0..119 plus out-of-order 125 (leaving gap 120..124), then disconnect
        try(RecordingAssembler hop1=new RecordingAssembler(f,true)) {
            assertEquals(0,hop1.resumedFrames());
            assertEquals(0L,hop1.resumeRequestOffset());
            for(int i=0;i<120;i++)hop1.add(i,packets[i]);
            hop1.add(125,packets[125]);
            hop1.checkpoint(headBytes);
        }
        assertTrue(RecordingAssembler.resumeFile(f).isFile());
        // Hop 2: reopen with resume=true -> sparse tail truncated, offset aligned to 13280 (83*160 == 80*166)
        try(RecordingAssembler hop2=new RecordingAssembler(f,true)) {
            assertEquals(120,hop2.resumedFrames());
            assertEquals(120*160L,f.length());
            assertEquals(headBytes,hop2.savedHeadSize());
            assertFalse(hop2.overlapVerified());
            long off=hop2.resumeRequestOffset();
            assertEquals(13280L,off);
            assertEquals(0L,off%160L);
            assertEquals(0L,off%166L);
            assertTrue(off/160L<=119 && off/166L<=119);
            // Adding tail 120..219 WITHOUT overlap frame 119 must NOT mark complete
            for(int i=120;i<180;i++)hop2.add(i,packets[i]);
            assertFalse(hop2.overlapVerified());
            assertFalse(hop2.complete(180L*166L,RecordingSizeUnit.TRANSPORT_BYTES));
            // Feed overlapping frames from transport offset (seq 80..119) -> verifies anchor 119
            for(int i=(int)(off/166L);i<120;i++)assertFalse(hop2.add(i,packets[i]));
            assertTrue(hop2.overlapVerified());
            hop2.checkpoint(headBytes);
        }
        // Hop 3: reopen at 180 frames -> offset 26560 (166*160 == 160*166), detect corrupt overlap, then finish 166..219
        try(RecordingAssembler hop3=new RecordingAssembler(f,true)) {
            assertEquals(180,hop3.resumedFrames());
            assertEquals(26560L,hop3.resumeRequestOffset());
            byte[] bad=packets[179].clone();bad[50]^=1;
            try{hop3.add(179,bad);fail("corrupt overlap accepted");}catch(IOException expected){ }
            assertFalse(hop3.overlapVerified());
            for(int i=(int)(hop3.resumeRequestOffset()/160L);i<220;i++)hop3.add(i,packets[i]);
            assertTrue(hop3.overlapVerified());
            assertTrue(hop3.complete(headBytes,RecordingSizeUnit.TRANSPORT_BYTES));
        }
        assertArrayEquals(Files.readAllBytes(expectedRaw.toPath()),Files.readAllBytes(f.toPath()));
        assertEquals(4400L,OggOpus.mux(f,ogg,42));
        RecordingAssembler.clearResume(f);
        assertFalse(RecordingAssembler.resumeFile(f).exists());
    }
    @Test public void legacyPartialFileWithoutSidecarSalvagesValidPrefixAndRejectsZeroHoles() throws Exception {
        File f=temp.newFile();
        byte[] valid=new byte[160];Arrays.fill(valid,(byte)3);valid[0]=(byte)0xf8;
        try(RandomAccessFile raw=new RandomAccessFile(f,"rw")) {
            for(int i=0;i<95;i++){valid[159]=(byte)i;raw.write(valid);}
            raw.seek(96L*160L); // Leave a 160-byte zero hole at frame 95
            raw.write(valid);
        }
        try(RecordingAssembler resumed=new RecordingAssembler(f,true)) {
            assertEquals(95,resumed.resumedFrames());
            assertEquals(95*160L,f.length());
            assertEquals(13280L,resumed.resumeRequestOffset());
            assertFalse(resumed.overlapVerified());
            resumed.reset();
            assertEquals(0,resumed.frames());
            assertTrue(resumed.overlapVerified());
            assertEquals(0L,f.length());
        }
    }
    @Test public void resumeAttemptCounterPersistsAndClearsOnReset() throws Exception {
        File f=temp.newFile();
        assertEquals(1,RecordingAssembler.incrementResumeAttempt(f));
        assertEquals(2,RecordingAssembler.incrementResumeAttempt(f));
        assertEquals(3,RecordingAssembler.incrementResumeAttempt(f));
        assertTrue(RecordingAssembler.attemptsFile(f).isFile());
        RecordingAssembler.resetResumeAttempts(f);
        assertFalse(RecordingAssembler.attemptsFile(f).exists());
        assertEquals(1,RecordingAssembler.incrementResumeAttempt(f));
        RecordingAssembler.clearResume(f);
        assertFalse(RecordingAssembler.attemptsFile(f).exists());
    }
    @Test public void liveFastSettleAndPauseResetPreserveOfflineQuietGuarantees() {
        TransferEnd end=new TransferEnd();
        assertEquals(2000L,end.remainingMillis(100,TransferEnd.QUIET_MILLIS));
        end.received(1000);
        assertFalse(end.settledWithin(1299,TransferEnd.LIVE_EXACT_QUIET_MILLIS));
        assertEquals(1L,end.remainingMillis(1299,TransferEnd.LIVE_EXACT_QUIET_MILLIS));
        assertTrue(end.settledWithin(1300,TransferEnd.LIVE_EXACT_QUIET_MILLIS));
        assertEquals(0L,end.remainingMillis(1400,TransferEnd.LIVE_EXACT_QUIET_MILLIS));
        assertFalse(end.settled(2999));
        assertTrue(end.settled(3000));
        // Pause -> resume clears prior end marker so resumed live audio never premature-settles
        end.reset();
        assertFalse(end.received());
        assertFalse(end.settledWithin(5000,TransferEnd.LIVE_EXACT_QUIET_MILLIS));
    }
    @Test public void liveZeroHeadCheckpointTransfersSeamlesslyToOfflineTailResume() throws Exception {
        File f=temp.newFile();
        byte[][] packets=new byte[120][160];
        for(int i=0;i<120;i++) {
            Arrays.fill(packets[i],(byte)7);
            packets[i][0]=(byte)0xbc;
            packets[i][159]=(byte)i;
        }
        try(RecordingAssembler live=new RecordingAssembler(f,false)) {
            for(int i=0;i<118;i++)live.add(i,packets[i]);
            live.checkpoint(0L);
        }
        try(RecordingAssembler offline=new RecordingAssembler(f,true)) {
            assertEquals(118,offline.resumedFrames());
            assertEquals(0L,offline.savedHeadSize());
            assertEquals(13280L,offline.resumeRequestOffset());
            assertFalse(offline.overlapVerified());
            for(int i=80;i<120;i++)offline.add(i,packets[i]);
            assertTrue(offline.overlapVerified());
            assertTrue(offline.complete(120L*166L,RecordingSizeUnit.TRANSPORT_BYTES));
        }
    }
}
