package io.github.lotusdecoder.daybookrecorder.core;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup.Action.*;
import static io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup.Phase.*;

public class DeviceCleanupTest {
    private static final String DEVICE="1111111111111111",SHA="a".repeat(64),URL="https://example.com/v1/recordings";
    private static final long ID=1700000001L;
    private DeviceCleanup attempt(){return new DeviceCleanup(DEVICE,ID,SHA,10000,true,1000);}
    private Map<Long,Protocol.Entry> present(){return Map.of(ID,new Protocol.Entry(ID,10000,ID+10));}
    private void verified(DeviceCleanup c){assertEquals(READ_INFO,c.remoteVerified(URL,URL,true,1100));assertEquals(READ_LIST,c.deviceInfo(DEVICE,0,1200));}
    private void sent(DeviceCleanup c){verified(c);assertEquals(DELETE,c.files(present(),0,false,true,DEVICE,URL,1300));}

    @Test public void queuedOrPartialRecordingsCannotCreateCleanup() {
        try{new DeviceCleanup(DEVICE,ID,SHA,10000,false,1000);fail();}catch(IllegalArgumentException expected){ }
        try{new DeviceCleanup(DEVICE,ID,"",10000,true,1000);fail();}catch(IllegalArgumentException expected){ }
    }
    @Test public void checksMustPrecedeTheSingleTargetDelete() {
        DeviceCleanup c=attempt();assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL,1050));assertFalse(c.sent());
        assertEquals(NONE,c.acknowledged(true,1050));assertEquals(VERIFYING,c.phase());
        sent(c);assertEquals(ACK,c.phase());
        assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL,1400));
        assertEquals(READ_LIST,c.acknowledged(true,1500));assertEquals(AFTER,c.phase());
        assertEquals(NONE,c.files(Map.of(),0,false,true,DEVICE,URL,1600));assertEquals(DELETED,c.phase());
        assertEquals(NONE,c.acknowledged(true,1700));assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL,1800));
    }
    @Test public void changedEndpointOrLocalAudioCannotDelete() {
        DeviceCleanup c=attempt();assertEquals(NONE,c.remoteVerified(URL,URL+"/other",true,1100));assertEquals(RETRY,c.phase());assertFalse(c.sent());
        c=attempt();assertEquals(NONE,c.remoteVerified(URL,URL,false,1100));assertFalse(c.sent());
        c=attempt();verified(c);assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL+"/new",1300));assertFalse(c.sent());
    }
    @Test public void wrongDevicePausedOrActiveRecordingCannotDelete() {
        for(int state:new int[]{-1,1,2}){DeviceCleanup c=attempt();c.remoteVerified(URL,URL,true,1100);assertEquals(NONE,c.deviceInfo(DEVICE,state,1200));assertFalse(c.sent());}
        DeviceCleanup c=attempt();c.remoteVerified(URL,URL,true,1100);assertEquals(NONE,c.deviceInfo("2222222222222222",0,1200));assertFalse(c.sent());
        c=attempt();verified(c);assertEquals(NONE,c.files(present(),ID,true,true,DEVICE,URL,1300));assertFalse(c.sent());
    }
    @Test public void absentOrDifferentFileIsNeverDeleted() {
        DeviceCleanup c=attempt();verified(c);assertEquals(NONE,c.files(Map.of(),0,false,true,DEVICE,URL,1300));assertEquals(ABSENT,c.phase());assertFalse(c.sent());
        for(Protocol.Entry entry:new Protocol.Entry[]{new Protocol.Entry(ID,9980,ID+10),new Protocol.Entry(ID,10000,0)}) {
            c=attempt();verified(c);assertEquals(NONE,c.files(Map.of(ID,entry),0,false,true,DEVICE,URL,1300));assertEquals(FAILED,c.phase());assertFalse(c.sent());
        }
    }
    @Test public void freshServerReceiptIsRequired() {
        DeviceCleanup c=attempt();verified(c);assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL,122000));assertEquals(RETRY,c.phase());assertFalse(c.sent());
        c=attempt();c.timeout(152000);assertEquals(RETRY,c.phase());assertEquals(NONE,c.remoteVerified(URL,URL,true,152001));assertFalse(c.sent());
    }
    @Test public void lostAckOrDisconnectDoesNotRetryDeletion() {
        DeviceCleanup c=attempt();sent(c);c.timeout(17000);assertEquals(UNKNOWN,c.phase());
        assertEquals(NONE,c.acknowledged(true,18000));assertEquals(UNKNOWN,c.phase());
        c=attempt();sent(c);c.interrupt("disconnect",1400);assertEquals(UNKNOWN,c.phase());
        assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL,1500));
        c=attempt();c.interrupt("stopped before send",1050);assertEquals(RETRY,c.phase());assertFalse(c.sent());
    }
    @Test public void ackAloneDoesNotProveRemoval() {
        DeviceCleanup c=attempt();sent(c);c.acknowledged(true,1400);c.files(present(),0,false,true,DEVICE,URL,1500);assertEquals(UNKNOWN,c.phase());
        c=attempt();sent(c);c.acknowledged(false,1400);assertEquals(FAILED,c.phase());
    }
    @Test public void acknowledgedAttemptResumesWithOnlyAnInventoryCheck() {
        DeviceCleanup c=new DeviceCleanup(DEVICE,ID,SHA,10000,true,true,1000);
        verified(c);assertEquals(NONE,c.files(Map.of(),0,false,true,DEVICE,URL,1300));
        assertEquals(DELETED,c.phase());assertFalse(c.sent());
        assertTrue(c.detail().contains("前次删除"));
        c=new DeviceCleanup(DEVICE,ID,SHA,10000,true,true,1000);
        verified(c);assertEquals(NONE,c.files(present(),0,false,true,DEVICE,URL,1300));
        assertEquals(UNKNOWN,c.phase());assertFalse(c.sent());
    }
    @Test public void resumedAcknowledgementSurvivesAnotherInterruptionWithoutDeleteRetry() {
        DeviceCleanup c=new DeviceCleanup(DEVICE,ID,SHA,10000,true,true,1000);
        c.interrupt("new recording",1100);assertEquals(UNKNOWN,c.phase());assertFalse(c.sent());
        assertEquals(NONE,c.acknowledged(true,1200));assertEquals(NONE,c.files(Map.of(),0,false,true,DEVICE,URL,1300));
        c=new DeviceCleanup(DEVICE,ID,SHA,10000,true,true,1000);verified(c);
        c.files(Map.of(),0,false,true,"2222222222222222",URL,1300);
        assertEquals(UNKNOWN,c.phase());assertFalse(c.sent());
    }
    private final Map<Long,Protocol.Entry> oneFrame=Map.of(ID,new Protocol.Entry(ID,20,ID+2));
    private DeviceCleanup reading() {
        DeviceCleanup c=new DeviceCleanup(DEVICE,ID,SHA,2000,true,false,20,1000);
        verified(c);assertEquals(READ_CONTENT,c.files(oneFrame,0,false,true,DEVICE,URL,1300));
        assertEquals(CONTENT,c.phase());assertFalse(c.sent());return c;
    }
    @Test public void manualReadbackRequiresMatchingContentThenFreshIdleAndIdenticalList() {
        DeviceCleanup c=reading();assertEquals(NONE,c.files(oneFrame,0,false,true,DEVICE,URL,1350));
        assertEquals(READ_INFO,c.contentRead(SHA,2000,1400));assertFalse(c.sent());
        assertEquals(NONE,c.files(oneFrame,0,false,true,DEVICE,URL,1450));
        assertEquals(READ_LIST,c.deviceInfo(DEVICE,0,1500));
        assertEquals(DELETE,c.files(oneFrame,0,false,true,DEVICE,URL,1600));
        assertEquals(NONE,c.files(oneFrame,0,false,true,DEVICE,URL,1650));
        assertEquals(READ_LIST,c.acknowledged(true,1700));
        assertEquals(NONE,c.files(Map.of(),0,false,true,DEVICE,URL,1800));assertEquals(DELETED,c.phase());
    }
    @Test public void differentBytesOrDurationAndInterruptedReadCannotAuthorizeDeletion() {
        for(String hash:new String[]{SHA,"b".repeat(64)}) {
            DeviceCleanup c=reading();
            assertEquals(NONE,c.contentRead(hash,hash.equals(SHA)?1980:2000,1400));assertEquals(FAILED,c.phase());
            assertEquals(NONE,c.contentRead(SHA,2000,1500));assertFalse(c.sent());
        }
        DeviceCleanup c=reading();c.contentActivity(43000);c.timeout(44000);assertEquals(CONTENT,c.phase());
        c.timeout(90000);assertEquals(RETRY,c.phase());assertEquals(NONE,c.contentRead(SHA,2000,91000));assertFalse(c.sent());
    }
    @Test public void changedOrReusedFileOrDeviceAfterReadKeepsOriginal() {
        for(Protocol.Entry changed:new Protocol.Entry[]{new Protocol.Entry(ID,20,ID+3),new Protocol.Entry(ID,2000,ID+2),
                new Protocol.Entry(ID,20,0),new Protocol.Entry(ID+1,20,ID+2)}) {
            DeviceCleanup c=reading();c.contentRead(SHA,2000,1400);c.deviceInfo(DEVICE,0,1500);
            assertEquals(NONE,c.files(Map.of(ID,changed),0,false,true,DEVICE,URL,1600));assertEquals(FAILED,c.phase());assertFalse(c.sent());
        }
        DeviceCleanup c=reading();c.contentRead(SHA,2000,1400);
        assertEquals(NONE,c.deviceInfo(DEVICE,1,1500));assertFalse(c.sent());
        c=reading();c.contentRead(SHA,2000,1400);c.deviceInfo(DEVICE,0,1500);
        assertEquals(NONE,c.files(oneFrame,0,false,true,"2222222222222222",URL,1600));assertFalse(c.sent());
    }
    @Test public void longReadRefreshesReceiptAtSameEndpointBeforeAnotherDeviceCheck() {
        DeviceCleanup c=reading();assertEquals(VERIFY_REMOTE,c.contentRead(SHA,2000,125000));
        assertEquals(NONE,c.files(oneFrame,0,false,true,DEVICE,URL,125100));assertFalse(c.sent());
        assertEquals(READ_INFO,c.remoteVerified(URL,URL,true,126000));assertEquals(READ_LIST,c.deviceInfo(DEVICE,0,126100));
        assertEquals(DELETE,c.files(oneFrame,0,false,true,DEVICE,URL,126200));
        c=reading();c.contentRead(SHA,2000,125000);
        assertEquals(NONE,c.remoteVerified(URL+"/changed",URL+"/changed",true,126000));assertFalse(c.sent());
    }
    @Test public void readbackWithPriorAcknowledgementOnlyReconcilesInventory() {
        DeviceCleanup c=new DeviceCleanup(DEVICE,ID,SHA,2000,true,true,20,1000);verified(c);
        assertEquals(NONE,c.files(oneFrame,0,false,true,DEVICE,URL,1300));assertEquals(UNKNOWN,c.phase());assertFalse(c.sent());
        c=new DeviceCleanup(DEVICE,ID,SHA,2000,true,true,20,1000);verified(c);
        assertEquals(NONE,c.files(Map.of(),0,false,true,DEVICE,URL,1300));assertEquals(DELETED,c.phase());assertFalse(c.sent());
    }
    @Test public void wireCommandContainsExactlyOneUnsignedFileId() {
        assertArrayEquals(new byte[]{8,(byte)0xee,0,0,0,0x1a,0x10,14,0,1,0,0,(byte)0x80,(byte)0xaf},Protocol.deleteFile(0x80000001L));
        assertEquals(0xffff_ffffL,Protocol.u32(Protocol.deleteFile(0xffff_ffffL),9));
        for(long bad:new long[]{-1,0,0x1_0000_0000L})try{Protocol.deleteFile(bad);fail();}catch(IllegalArgumentException expected){ }
    }
}
