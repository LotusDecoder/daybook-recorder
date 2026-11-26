package io.github.lotusdecoder.daybookrecorder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.*;
import android.database.sqlite.SQLiteDatabase;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CheckBox;
import android.widget.ListView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import static org.junit.Assert.*;
import io.github.lotusdecoder.daybookrecorder.core.OggOpus;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;
import io.github.lotusdecoder.daybookrecorder.core.RecordingAssembler;
import io.github.lotusdecoder.daybookrecorder.core.RecordingSizeUnit;
import io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup;
import io.github.lotusdecoder.daybookrecorder.core.DeviceCrypto;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RecorderInstrumentedTest {
    private Context context;
    private Instrumentation instrumentation;
    private RecordingStore store;
    private Activity activity;
    @Before public void before() throws Exception {
        assertTrue("Destructive test setup is restricted to emulators",Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"));
        instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();
        context.stopService(new Intent(context,RecorderService.class));SystemClock.sleep(500);
        RuntimeTelemetry.resetServiceStopped();RuntimeTelemetry.lastStableDurationMs=0L;
        Settings.prefs(context).edit().clear().commit();context.getSharedPreferences("device_files",Context.MODE_PRIVATE).edit().clear().commit();context.deleteDatabase("recordings.db");store=new RecordingStore(context);
        for(String p:new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.POST_NOTIFICATIONS})shell("pm grant "+context.getPackageName()+" "+p);
    }
    @After public void after(){if(activity!=null)instrumentation.runOnMainSync(()->activity.finish());store.close();}
    @Test public void aKeystoreAndEndpointValidation() throws Exception {
        String first = "https://first.example/v1/recordings";
        String second = "https://second.example/v1/recordings";
        Settings.configureUpload(context, first, "synthetic-token-for-test", false);
        Settings.UploadTarget inFlight = Settings.uploadTarget(context);
        assertEquals("synthetic-token-for-test", inFlight.token);
        assertFalse(Settings.prefs(context).getString("token","").contains("synthetic-token"));
        Settings.configureUpload(context, first, "", false);
        assertEquals(inFlight.token, Settings.uploadTarget(context).token);
        Settings.configureUpload(context, second, "", false);
        assertEquals(second, Settings.uploadTarget(context).endpoint);
        assertEquals("", Settings.uploadTarget(context).token);
        assertEquals(first, inFlight.endpoint);
        Settings.configureUpload(context, second, "different-test-token", false);
        try {
            Settings.configureUpload(context, first, "invalid\nvalue", false);
            fail("invalid token accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals(second, Settings.uploadTarget(context).endpoint);
        assertEquals("different-test-token", Settings.uploadTarget(context).token);
        Settings.configureUpload(context, second, "", true);
        assertEquals("", Settings.uploadTarget(context).token);
        for(String bad:new String[]{"http://example.com/upload","https://user:pass@example.com/upload","https://example.com/?token=value"}) {
            try{Settings.validateEndpoint(bad);fail("unsafe endpoint accepted");}catch(IllegalArgumentException expected){ }
        }
        assertEquals("https://example.com/v1/recordings",Settings.validateEndpoint("https://example.com/v1/recordings"));
    }
    @Test public void anotherReceiverGetsAudioWithoutThePreviousReceiversToken() throws Exception {
        SSLSocketFactory original = useFixtureTls();
        UploadQueue queue = new UploadQueue(context, store);
        try {
            endpoint("/v1/recordings");
            RecordingStore.Row first = audio(1700001001L);
            queue.kick();
            waitFor(() -> store.get(first.id).state.equals("SENT"));
            Settings.configureUpload(context, "https://10.0.2.2:18770/v1/recordings", "", false);
            RecordingStore.Row second = audio(1700001002L);
            assertNotEquals(first.sha, second.sha);
            queue.kick();
            waitFor(() -> store.get(second.id).state.equals("SENT"));
        } finally {
            queue.close();
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
    }
    @Test public void bQueueSurvivesDatabaseReopenAndRejectsPartialRecording() throws Exception {
        RecordingStore.Row row=store.ensure("1111111111111111",1700000001L,320);store.receiving(row.id);store.progress(row.id,160);store.close();
        store=new RecordingStore(context);store.recover();assertEquals("WAITING",store.get(row.id).state);assertNull(store.nextUpload());
        store.ready(row.id,320,40,"a".repeat(64));assertNotNull(store.nextUpload());
        store.failedUpload(store.get(row.id),"HTTP 503");assertNull(store.nextUpload());store.close();
        store=new RecordingStore(context);assertEquals(1,store.get(row.id).attempts);assertEquals("READY",store.get(row.id).state);
        store.retryUploads();assertEquals(row.id,store.nextUpload().id);
    }
    @Test public void cTlsUploadRequiresMatchingReceiptAndRetriesFailures() throws Exception {
        SSLSocketFactory original=useFixtureTls();
        endpoint("/v1/recordings");UploadQueue queue=new UploadQueue(context,store);
        try {
            RecordingStore.Row first=audio(1700000101L);endpoint("/v1/recordings");queue.kick();
            waitFor(()->store.get(first.id).state.equals("SENT"));
            RecordingStore.Row second=audio(1700000102L);endpoint("/wrong-ack");queue.kick();
            waitFor(()->store.get(second.id).attempts>0);assertEquals("READY",store.get(second.id).state);
            endpoint("/temporary-error");store.retryUploads();queue.kick();waitFor(()->store.get(second.id).attempts>0);
            assertEquals("READY",store.get(second.id).state);assertEquals("HTTP 503",store.get(second.id).error);
            endpoint("/v1/recordings");store.retryUploads();queue.kick();waitFor(()->store.get(second.id).state.equals("SENT"));
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    @Test public void dExplicitStopPersistsAcrossServiceRestart() throws Exception {
        openSession();waitFor(this::running);context.startService(new Intent(context,RecorderService.class).setAction(RecorderService.STOP));
        waitFor(()->!running());assertFalse(Settings.prefs(context).getBoolean("enabled",true));
    }
    @Test public void eCleanupNeedsFreshReceiptAndKeepsEveryLocalByte() throws Exception {
        SSLSocketFactory original=useFixtureTls();endpoint("/v1/recordings");
        UploadQueue queue=new UploadQueue(context,store);
        try {
            RecordingStore.Row row=audio(1700000401L);endpoint("/v1/recordings");queue.kick();waitFor(()->store.get(row.id).state.equals("SENT"));
            byte[] raw=java.nio.file.Files.readAllBytes(store.file(row.id,".opus.bin").toPath());String hash=RecordingStore.hash(store.file(row.id,".ogg"));
            for(String route:new String[]{"/queued-ack","/wrong-ack","/wrong-id","/temporary-error"}) {
                endpoint(route);String[] result=verify(queue,store.get(row.id));assertTrue(result[0].isEmpty());assertFalse(result[1].isEmpty());assertEquals("SENT",store.get(row.id).state);
            }
            endpoint("/v1/recordings");String[] result=verify(queue,store.get(row.id));assertEquals("",result[1]);assertTrue(result[0].endsWith("/v1/recordings"));
            store.cleanup(row.id,"DELETED","synthetic cleanup result");store.close();store=new RecordingStore(context);
            assertEquals("SENT",store.get(row.id).state);assertEquals("DELETED",store.get(row.id).cleanupState);
            assertEquals(hash,RecordingStore.hash(store.file(row.id,".ogg")));assertArrayEquals(raw,java.nio.file.Files.readAllBytes(store.file(row.id,".opus.bin").toPath()));
            assertNull(store.nextCleanup(row.device));
            try(OutputStream out=new FileOutputStream(store.file(row.id,".ogg"),true)){out.write(0);}
            result=verify(queue,store.get(row.id));assertEquals("",result[0]);assertTrue(result[1].startsWith("本地"));
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    @Test public void fVersionOneDatabaseUpgradePreservesRecordsFilesAndPreferences() throws Exception {
        store.close();context.deleteDatabase("recordings.db");
        String device="1111111111111111",id=RecordingStore.id(device,1700000402L);byte[] retained={8,7,6,5,4};
        File file=store.file(id,".opus.bin");try(OutputStream out=new FileOutputStream(file)){out.write(retained);}
        Settings.prefs(context).edit().putString("upgrade_fixture","retain").commit();
        try(SQLiteDatabase legacy=context.openOrCreateDatabase("recordings.db",Context.MODE_PRIVATE,null)) {
            // Schema v1; migration must preserve every pre-existing column.
            legacy.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, device TEXT NOT NULL, file_id INTEGER NOT NULL, recorded_at INTEGER NOT NULL, stopped_at INTEGER NOT NULL DEFAULT 0, expected INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, sha TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, uploaded_at INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '')");
            legacy.execSQL("CREATE INDEX ready_queue ON recordings(state,next_attempt)");
            for(int i=0;i<3;i++)legacy.execSQL("INSERT INTO recordings VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",new Object[]{RecordingStore.id(device,1700000402L+i),device,1700000402L+i,1700000402000L+i,1700000403000L,1660,new String[]{"SENT","READY","WAITING"}[i],1600,200,"b".repeat(64),2,1234,i==0?5678:0,"retained note"});
            legacy.setVersion(1);
        }
        store=new RecordingStore(context);assertEquals(5,store.getReadableDatabase().getVersion());assertEquals(3,store.latest().size());
        RecordingStore.Row row=store.get(id);assertEquals("SENT",row.state);assertEquals(1600,row.bytes);assertEquals(1660,row.expected);assertEquals(200,row.duration);assertEquals(5678,row.uploadedAt);
        assertEquals(1700000402000L,row.recordedAt);assertEquals(1700000403000L,row.stoppedAt);assertEquals("b".repeat(64),row.sha);assertEquals(2,row.attempts);assertEquals("retained note",row.error);
        assertEquals("",row.cleanupState);assertEquals("",row.cleanupDetail);assertEquals(0,row.cleanupAt);
        assertFalse(row.connectionSync);assertFalse(row.readbackVerified);
        assertFalse(row.cleanupAcknowledged);assertEquals("",row.duplicateOf);
        assertArrayEquals(retained,java.nio.file.Files.readAllBytes(file.toPath()));assertEquals("retain",Settings.prefs(context).getString("upgrade_fixture",""));assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",false));
    }
    @Test public void gInterruptedCleanupNeverRetriesAnAlreadySentDelete() throws Exception {
        RecordingStore.Row before=audio(1700000403L),after=audio(1700000404L);store.sent(before.id);store.sent(after.id);
        store.cleanup(before.id,"VERIFYING","synthetic in progress");store.cleanup(after.id,"ACK","synthetic delete awaiting ACK");store.close();
        store=new RecordingStore(context);store.recover();assertEquals("RETRY",store.get(before.id).cleanupState);assertEquals("UNKNOWN",store.get(after.id).cleanupState);assertNull(store.nextCleanup(before.device));
        store.retryUploads();assertEquals("UNKNOWN",store.get(after.id).cleanupState);assertEquals("SENT",store.get(after.id).state);
        assertTrue(store.file(before.id,".ogg").isFile());assertTrue(store.file(after.id,".opus.bin").isFile());
    }
    @Test public void hAutoCleanupRequiresOptInAndCanBeStopped() throws Exception {
        activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(2));
        AtomicReference<CheckBox> box=new AtomicReference<>();instrumentation.runOnMainSync(()->box.set(findCleanup(activity.findViewById(android.R.id.content))));
        assertNotNull(box.get());assertFalse(box.get().isChecked());assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",false));
        instrumentation.runOnMainSync(()->box.get().performClick());waitFor(()->clickDialog("取消"));assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",false));
        instrumentation.runOnMainSync(()->box.get().performClick());waitFor(()->clickDialog("开启"));waitFor(()->Settings.prefs(context).getBoolean("auto_cleanup",false));
        instrumentation.runOnMainSync(()->box.get().performClick());assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",true));
    }
    @Test public void iFailedRefreshKeepsCompleteSnapshotAndNeverLeaksOtherDevice() {
        DeviceFilesStore files=new DeviceFilesStore(context);String first="1111111111111111",second="2222222222222222";
        assertEquals(0,files.read(first).fetchedAt);
        files.complete(first,Map.of(1700000501L,new Protocol.Entry(1700000501L,1000,1700000502L)),0,true,"04.00");
        long time=files.read(first).fetchedAt;assertTrue(time>0);
        files.reading(first);assertEquals("READING",files.read(first).state);assertEquals(1,files.read(first).entries.size());
        files.unavailable(first,"synthetic timeout");files=new DeviceFilesStore(context);
        assertEquals(time,files.read(first).fetchedAt);assertEquals(1,files.read(first).entries.size());assertEquals("UNAVAILABLE",files.read(first).state);
        assertEquals(0,files.read(second).entries.size());assertEquals(0,files.read(second).fetchedAt);
        files.reading(second);assertEquals(0,files.read(second).entries.size());assertEquals(0,files.read(second).fetchedAt);
        files.complete(second,Map.of(),0,true,"04.00");assertEquals(0,files.read(second).entries.size());assertTrue(files.read(second).fetchedAt>0);assertEquals("READY",files.read(second).state);
        assertEquals(0,files.read(first).fetchedAt);
    }
    @Test public void jDeviceFileBrowserShowsBackupsAndRequiresPerFileConfirmation() throws Exception {
        String address="00:11:22:33:44:55",device=RecordingStore.deviceId(address);
        Settings.prefs(context).edit().putString("address",address).commit();
        RecordingStore.Row sent=audio(device,1700000501L);store.sent(sent.id);audio(device,1700000503L);
        String hash=RecordingStore.hash(store.file(sent.id,".ogg"));Map<Long,Protocol.Entry> bean=new HashMap<>();
        for(long id=1700000501L;id<=1700000503L;id++)bean.put(id,new Protocol.Entry(id,1000,id+1));
        new DeviceFilesStore(context).complete(device,bean,0,true,"04.00");
        activity=instrumentation.startActivitySync(new Intent(context,DeviceFilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<ListView> list=new AtomicReference<>();instrumentation.runOnMainSync(()->list.set(findList(activity.findViewById(android.R.id.content))));
        assertNotNull(list.get());assertEquals(3,list.get().getCount());
        assertTrue(windowContains("当前未确认连接"));assertTrue(windowContains("曾获中转回执"));assertTrue(windowContains("手机尚无完整副本"));
        instrumentation.runOnMainSync(()->list.get().performItemClick(list.get().getChildAt(2),2,1700000501L));
        waitFor(()->clickDialog("核验后删除豆内副本"));assertTrue(windowContains("删除这条豆内副本？"));
        assertEquals("",store.get(sent.id).cleanupState);waitFor(()->clickDialog("取消"));
        assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",false));assertFalse(running());assertEquals(hash,RecordingStore.hash(store.file(sent.id,".ogg")));
        instrumentation.runOnMainSync(()->list.get().performItemClick(list.get().getChildAt(1),1,1700000502L));
        waitFor(()->windowContains("先完整下载到手机"));
        for(AccessibilityNodeInfo node:instrumentation.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("核验后删除豆内副本"))assertFalse(node.isEnabled());
        waitFor(()->clickDialog("关闭"));
        Settings.prefs(context).edit().putString("address","00:11:22:33:44:66").commit();waitFor(()->list.get().getCount()==0);
        assertEquals(hash,RecordingStore.hash(store.file(sent.id,".ogg")));assertEquals("",store.get(sent.id).cleanupState);
    }
    @Test public void zServiceSurvivesActivityRemovalAndLeavesSessionForProcessRecoveryCheck() throws Exception {
        openSession();waitFor(this::running);SystemClock.sleep(1000);
        instrumentation.runOnMainSync(()->activity.finishAndRemoveTask());activity=null;
        SystemClock.sleep(2500);assertTrue(running());assertTrue(Settings.prefs(context).getBoolean("enabled",false));
        shell("input keyevent KEYCODE_SLEEP");shell("dumpsys battery unplug");shell("cmd deviceidle force-idle");
        SystemClock.sleep(5000);assertTrue(running());
        shell("cmd deviceidle unforce");shell("dumpsys battery reset");shell("input keyevent KEYCODE_WAKEUP");
        // Intentionally retain this synthetic session for the host's kill/restart audit.
    }
    @Test public void kLocalDeleteSurvivesRetryReopenAndRequiresExplicitRestore() throws Exception {
        RecordingStore.Row row=audio(1700000601L),other=audio(1700000602L);
        String otherHash=RecordingStore.hash(store.file(other.id,".ogg"));
        for(String suffix:new String[]{".part",".ogg.tmp"})try(FileOutputStream out=new FileOutputStream(store.file(row.id,suffix))){out.write(7);}
        store.deleteLocal(row.id);
        for(String suffix:new String[]{".ogg",".opus.bin",".part",".ogg.tmp"})assertFalse(store.file(row.id,suffix).exists());
        store.ensure(row.device,row.fileId,row.expected);assertFalse(store.receiving(row.id));
        store.waiting(row.id,"late transfer");store.ready(row.id,10,20,row.sha);store.sent(row.id);store.failedUpload(row,"late callback");
        store.cleanup(row.id,"RETRY","late cleanup");store.retryUploads();
        store.close();store=new RecordingStore(context);store.recover();
        assertEquals("LOCAL_DELETED",store.get(row.id).state);assertEquals(1,store.latest().size());
        assertEquals(other.id,store.nextUpload().id);assertFalse(store.claimCleanup(row.id));
        assertEquals(otherHash,RecordingStore.hash(store.file(other.id,".ogg")));
        store.restoreLocal(row.id);assertEquals("WAITING",store.get(row.id).state);assertTrue(store.receiving(row.id));
    }
    @Test public void lLocalDeleteArbitratesWithTransferAndCleanupAcrossHelpers() throws Exception {
        RecordingStore.Row row=audio(1700000603L);store.sent(row.id);
        try(RecordingStore second=new RecordingStore(context)) {
            assertTrue(second.claimCleanup(row.id));
            try{store.deleteLocal(row.id);fail("deleted during cleanup");}catch(IOException expected){}
            assertTrue(store.file(row.id,".ogg").exists());store.cleanup(row.id,"UNKNOWN","synthetic uncertain prior result");
            store.deleteLocal(row.id);assertFalse(second.claimCleanup(row.id));
            assertEquals("UNKNOWN",second.get(row.id).cleanupState);
            RecordingStore.Row partial=store.ensure(row.device,1700000604L,160);assertTrue(second.receiving(partial.id));
            try(FileOutputStream out=new FileOutputStream(store.file(partial.id,".part"))){out.write(8);}
            try{store.deleteLocal(partial.id);fail("deleted while receiving");}catch(IOException expected){}
            assertEquals(1,store.file(partial.id,".part").length());second.waiting(partial.id,"synthetic failure");
            store.deleteLocal(partial.id);assertFalse(second.receiving(partial.id));assertFalse(store.file(partial.id,".part").exists());
        }
    }
    @Test public void mInterruptedLocalDeleteResumesAndStaleUploadCannotReviveIt() throws Exception {
        RecordingStore.Row row=audio(1700000605L);File blocked=store.file(row.id,".ogg.tmp");assertTrue(blocked.mkdir());
        File child=new File(blocked,"synthetic");try(FileOutputStream out=new FileOutputStream(child)){out.write(9);}
        try{store.deleteLocal(row.id);fail("expected unlink error");}catch(IOException expected){}
        assertEquals("LOCAL_DELETING",store.get(row.id).state);assertNull(store.nextUpload());
        assertTrue(child.delete());store.close();store=new RecordingStore(context);store.recover();
        assertEquals("LOCAL_DELETED",store.get(row.id).state);assertFalse(blocked.exists());
        SSLSocketFactory original=useFixtureTls();endpoint("/v1/recordings");
        UploadQueue queue=new UploadQueue(context,store);
        try {
            RecordingStore.Row slow=audio(1700000606L);endpoint("/slow-ack");queue.kick();SystemClock.sleep(800);
            store.deleteLocal(slow.id);SystemClock.sleep(2500);assertEquals("LOCAL_DELETED",store.get(slow.id).state);assertNull(store.nextUpload());
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    @Test public void nServerDeletionStopsRetriesAndBeanCleanupButKeepsPhoneAudio() throws Exception {
        SSLSocketFactory original=useFixtureTls();endpoint("/v1/recordings");
        UploadQueue queue=new UploadQueue(context,store);
        try {
            RecordingStore.Row row=audio(1700000607L);endpoint("/deleted");queue.kick();waitFor(()->store.get(row.id).state.equals("SERVER_DELETED"));
            store.retryUploads();assertNull(store.nextUpload());assertNull(store.nextCleanup(row.device));
            assertEquals(row.sha,RecordingStore.hash(store.file(row.id,".ogg")));assertTrue(store.get(row.id).saved());
            String[] result=verify(queue,store.get(row.id));assertFalse(result[1].isEmpty());
            RecordingStore.Row sent=audio(1700000608L);store.sent(sent.id);result=verify(queue,store.get(sent.id));
            assertFalse(result[1].isEmpty());assertEquals("SERVER_DELETED",store.get(sent.id).state);assertFalse(store.claimCleanup(sent.id));
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    @Test public void oPhoneDeleteDialogCancelsThenDeletesOnlySelectedLocalCopy() throws Exception {
        RecordingStore.Row row=audio(1700000609L),keep=audio(1700000610L);
        String keepHash=RecordingStore.hash(store.file(keep.id,".ogg"));
        activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        pressDirectDelete(row.id);assertTrue(windowContains("删除这条手机副本？"));
        waitFor(()->clickDialog("取消"));assertTrue(store.file(row.id,".ogg").isFile());
        pressDirectDelete(row.id);waitFor(()->clickDialog("删除手机副本"));
        waitFor(()->store.get(row.id).state.equals("LOCAL_DELETED"));
        assertFalse(store.file(row.id,".ogg").exists());
        assertEquals(keepHash,RecordingStore.hash(store.file(keep.id,".ogg")));assertFalse(running());
    }
    @Test public void pConnectionSnapshotIsIdleOncePerConnectionAndSurvivesRestart() throws Exception {
        String device="1111111111111111",other="2222222222222222";
        ConnectionSync sync=new ConnectionSync(store);sync.connected();
        Map<Long,Protocol.Entry> files=new HashMap<>();
        for(long id=1700000701L;id<=1700000713L;id++)files.put(id,new Protocol.Entry(id,1000,id+1));
        sync.capture(device,files,0,false,true,false);assertEquals(0,store.forDevice(device).size());
        sync.capture(device,files,1700000701L,false,true,true);assertEquals(0,store.forDevice(device).size());
        sync.capture(device,files,0,true,true,true);assertEquals(0,store.forDevice(device).size());
        files.put(1700000714L,new Protocol.Entry(1700000714L,1000,0));
        files.put(1700000715L,new Protocol.Entry(1700000715L,0,1700000716L));
        sync.capture(device,files,0,false,true,true);assertEquals(13,store.forDevice(device).size());
        for(RecordingStore.Row row:store.forDevice(device))assertTrue(row.connectionSync);
        files.put(1700000716L,new Protocol.Entry(1700000716L,1000,1700000717L));
        sync.capture(device,files,0,false,true,true);assertNull(store.get(RecordingStore.id(device,1700000716L)));
        store.receiving(RecordingStore.id(device,1700000701L));store.close();store=new RecordingStore(context);store.recover();
        assertEquals("WAITING",store.get(RecordingStore.id(device,1700000701L)).state);
        assertTrue(store.get(RecordingStore.id(device,1700000701L)).connectionSync);
        sync=new ConnectionSync(store);sync.connected();sync.capture(device,files,0,false,true,true);
        assertEquals(14,store.forDevice(device).size());assertEquals(0,store.forDevice(other).size());
        sync.connected();sync.capture(other,files,0,false,true,true);assertEquals(14,store.forDevice(other).size());
        assertTrue(store.connectionSyncSummary(device).contains("待补收 14"));
    }
    @Test public void oaEveryPhoneStateHasDirectDeleteEvenWithNoConnectionOrService() throws Exception {
        List<RecordingStore.Row> rows=new ArrayList<>();
        RecordingStore.Row full=audio(1700000900L);store.sent(full.id);
        rows.add(partialCopy(full,"2222222222222222"));assertEquals(1,store.reconcileDuplicates());
        RecordingStore.Row interrupted=store.ensure(full.device,1700000901L,32000);
        try(FileOutputStream out=new FileOutputStream(store.file(interrupted.id,".part"))){out.write(new byte[160]);}
        store.progress(interrupted.id,160);store.waiting(interrupted.id,"连接中断，等待补收");rows.add(interrupted);
        rows.add(store.ensure(full.device,1700000902L,0)); // A metadata-only failed receive is deletable too.
        RecordingStore.Row receiving=store.ensure(full.device,1700000903L,160);store.receiving(receiving.id);rows.add(receiving);
        RecordingStore.Row ready=audio(1700000904L);store.failedUpload(ready,"HTTP 503");rows.add(ready);
        RecordingStore.Row remote=audio(1700000905L);store.remoteDeleted(remote.id);rows.add(remote);
        long fileId=1700000910L;
        for(String phase:new String[]{"","VERIFYING","INFO","BEFORE","ACK","AFTER","DELETED","ABSENT","UNKNOWN","RETRY","FAILED"}) {
            RecordingStore.Row row=audio(fileId++);store.sent(row.id);store.cleanup(row.id,phase,"synthetic "+phase);rows.add(row);
        }
        RecordingStore.Row retry=audio(1700000930L);File blocked=store.file(retry.id,".ogg.tmp");assertTrue(blocked.mkdir());
        File child=new File(blocked,"synthetic");try(FileOutputStream out=new FileOutputStream(child)){out.write(1);}
        try{store.deleteLocal(retry.id);fail();}catch(IOException expected){}rows.add(retry);
        activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{
            ((MainActivity)activity).selectTab(1);
            for(RecordingStore.Row row:rows) {
                View button=activity.findViewById(android.R.id.content).findViewWithTag("delete:"+row.id);
                assertNotNull("missing direct delete for "+store.get(row.id).state+" / "+store.get(row.id).cleanupState,button);
                assertTrue(button.isEnabled());assertEquals(View.VISIBLE,button.getVisibility());
            }
        });
        pressDirectDelete(interrupted.id);waitFor(()->clickDialog("取消"));
        assertEquals("WAITING",store.get(interrupted.id).state);assertEquals(160,store.file(interrupted.id,".part").length());
        assertTrue(child.delete());
        for(RecordingStore.Row row:rows) {
            pressDirectDelete(row.id);waitFor(()->clickDialog("删除手机副本"));
            waitFor(()->store.get(row.id).state.equals("LOCAL_DELETED"));
            for(String suffix:new String[]{".ogg",".opus.bin",".part",".ogg.tmp"})assertFalse(store.file(row.id,suffix).exists());
            assertEquals(full.sha,RecordingStore.hash(store.file(full.id,".ogg")));
        }
        store.recover();store.retryUploads();assertEquals(1,store.latest().size());assertFalse(running());
    }
    @Test public void obActiveReceiveClosesBeforeDeleteAndCannotRecreateFiles() throws Exception {
        openSession();waitFor(this::running);
        RecordingStore.Row other=audio(1700000940L),row=store.ensure(other.device,1700000941L,320);
        AtomicReference<RecordingAssembler> writer=new AtomicReference<>();AtomicReference<Object> transfer=new AtomicReference<>();
        onRecordingThread(()->{
            RecorderService service=recordingOwner();assertNotNull(service);assertTrue(store.receiving(row.id));
            RecorderService.Active active=new RecorderService.Active(row.fileId,row.id,320L,true,
                    store.file(row.id,".part"),null,0,RecordingSizeUnit.TRANSPORT_BYTES);
            active.assembler.add(0,new byte[160]);writer.set(active.assembler);transfer.set(active);
            setField(service,"active",active);setField(service,"device",row.device);
            setField(service,"requestedDownload",row.fileId);setField(service,"requestedRestore",true);
            ((ArrayDeque<byte[]>)field(service,"tx")).add(new byte[]{1,2,3});return null;
        });
        pressDirectDelete(row.id);waitFor(()->clickDialog("删除手机副本"));waitFor(()->store.get(row.id).state.equals("LOCAL_DELETED"));
        onRecordingThread(()->{
            RecorderService service=recordingOwner();assertNull(field(service,"active"));assertEquals(0L,field(service,"requestedDownload"));
            assertTrue(((ArrayDeque<?>)field(service,"tx")).isEmpty());
            try{writer.get().add(1,new byte[160]);fail("writer still open");}catch(IOException expected){}
            java.lang.reflect.Method finish=RecorderService.class.getDeclaredMethod("finishIfComplete",transfer.get().getClass());finish.setAccessible(true);finish.invoke(service,transfer.get());return null;
        });
        store.ensure(row.device,row.fileId,320);assertFalse(store.receiving(row.id));store.ready(row.id,320,40,"b".repeat(64));
        assertEquals("LOCAL_DELETED",store.get(row.id).state);assertFalse(store.file(row.id,".part").exists());assertFalse(store.file(row.id,".ogg").exists());
        assertEquals(other.sha,RecordingStore.hash(store.file(other.id,".ogg")));assertTrue(Settings.prefs(context).getBoolean("enabled",false));
    }
    @Test public void ocActiveCleanupCancelsWithoutNewDeleteAndKeepsAcknowledgement() throws Exception {
        openSession();waitFor(this::running);
        for(int phase=0;phase<3;phase++) {
            final int stage=phase;RecordingStore.Row row=audio(1700000950L+phase);store.sent(row.id);
            onRecordingThread(()->{
                RecorderService service=recordingOwner();Class<?> type=Class.forName(RecorderService.class.getName()+"$Cleanup");
                java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);
                Object cleanup=constructor.newInstance(store.get(row.id),(long)field(service,"generation"),true);
                DeviceCleanup guard=(DeviceCleanup)field(cleanup,"guard");long now=SystemClock.elapsedRealtime();String endpoint="https://example.com/v1/recordings";
                guard.remoteVerified(endpoint,endpoint,true,now);guard.deviceInfo(row.device,0,now);
                if(stage>0)guard.files(Map.of(row.fileId,new Protocol.Entry(row.fileId,row.duration,row.fileId+1)),0,false,true,row.device,endpoint,now);
                if(stage==2)guard.acknowledged(true,now);
                store.cleanup(row.id,guard.phase().name(),guard.detail());setField(cleanup,"persisted",guard.phase());setField(service,"cleanup",cleanup);
                ((ArrayDeque<byte[]>)field(service,"tx")).add(Protocol.deleteFile(row.fileId));return null;
            });
            String message=deleteThroughCoordinator(row.id);assertTrue(message.contains("已删除"));
            assertEquals("LOCAL_DELETED",store.get(row.id).state);assertEquals(stage==0?"RETRY":"UNKNOWN",store.get(row.id).cleanupState);
            assertEquals(stage==2,store.get(row.id).cleanupAcknowledged);
            onRecordingThread(()->{assertNull(field(recordingOwner(),"cleanup"));assertTrue(((ArrayDeque<?>)field(recordingOwner(),"tx")).isEmpty());return null;});
        }
    }
    @Test public void odDeleteSurvivesServiceShutdownAndLateUploadReceipt() throws Exception {
        openSession();waitFor(this::running);RecordingStore.Row row=audio(1700000960L);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),deleted=new CountDownLatch(1);
        new Handler(LocalRecordingDeletion.looper()).post(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(entered.await(5,TimeUnit.SECONDS));
        try {
            instrumentation.runOnMainSync(()->context.stopService(new Intent(context,RecorderService.class)));instrumentation.waitForIdleSync();
            LocalRecordingDeletion.delete(context,row.id,result->deleted.countDown());
        }finally{release.countDown();}
        assertTrue(deleted.await(10,TimeUnit.SECONDS));assertEquals("LOCAL_DELETED",store.get(row.id).state);
        assertNull(onRecordingThread(this::recordingOwner));
        SSLSocketFactory original=useFixtureTls();endpoint("/v1/recordings");UploadQueue queue=new UploadQueue(context,store);
        try {
            RecordingStore.Row slow=audio(1700000961L);endpoint("/slow-ack");queue.kick();SystemClock.sleep(800);
            pressDirectDelete(slow.id);waitFor(()->clickDialog("删除手机副本"));waitFor(()->store.get(slow.id).state.equals("LOCAL_DELETED"));
            SystemClock.sleep(2500);store.recover();assertEquals("LOCAL_DELETED",store.get(slow.id).state);assertNull(store.nextUpload());
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    private void pressDirectDelete(String id) throws Exception {
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(1));
        waitFor(()->{AtomicReference<Boolean> found=new AtomicReference<>(false);instrumentation.runOnMainSync(()->found.set(activity.findViewById(android.R.id.content).findViewWithTag("delete:"+id)!=null));return found.get();});
        instrumentation.runOnMainSync(()->activity.findViewById(android.R.id.content).findViewWithTag("delete:"+id).performClick());
        waitFor(()->windowContains("删除这条手机副本？"));
    }
    private String deleteThroughCoordinator(String id) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> result=new AtomicReference<>();
        LocalRecordingDeletion.delete(context,id,message->{result.set(message);done.countDown();});assertTrue(done.await(15,TimeUnit.SECONDS));return result.get();
    }
    private <T> T onRecordingThread(Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action);new Handler(LocalRecordingDeletion.looper()).post(task);return task.get(15,TimeUnit.SECONDS);
    }
    private RecorderService recordingOwner() throws Exception {
        java.lang.reflect.Field owner=LocalRecordingDeletion.class.getDeclaredField("owner");owner.setAccessible(true);return (RecorderService)owner.get(null);
    }
    private Object field(Object target,String name) throws Exception {java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private void setField(Object target,String name,Object value) throws Exception {java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    @Test public void qConnectionSyncUsesExistingUploadVerificationAndNeverCleansNewRecordings() throws Exception {
        SSLSocketFactory original=useFixtureTls();endpoint("/v1/recordings");
        UploadQueue queue=new UploadQueue(context,store);
        try {
            RecordingStore.Row first=audio(1700000721L),second=audio(1700000722L),fresh=audio(1700000723L);
            ConnectionSync sync=new ConnectionSync(store);sync.connected();
            sync.capture(first.device,Map.of(first.fileId,new Protocol.Entry(first.fileId,first.duration,first.fileId+1),
                    second.fileId,new Protocol.Entry(second.fileId,second.duration,second.fileId+1)),0,false,true,true);
            endpoint("/temporary-error");queue.kick();waitFor(()->store.get(first.id).attempts>0);
            assertNull(store.nextCleanup(first.device,false,true));
            endpoint("/v1/recordings");store.retryUploads();queue.kick();waitFor(()->store.get(first.id).state.equals("SENT") && store.get(second.id).state.equals("SENT"));
            for(int i=0;i<2;i++) {
                RecordingStore.Row row=store.nextCleanup(first.device,false,true);assertNotNull(row);assertTrue(row.connectionSync);
                assertTrue(ConnectionSync.mayClean(row,true,false));assertFalse(ConnectionSync.mayClean(row,false,false));
                assertFalse(ConnectionSync.mayDownload(row,false));assertNull(store.nextCleanup(first.device,false,false));
                io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup guard=new io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup(row.device,row.fileId,row.sha,row.duration,true,0);
                endpoint("/wrong-ack");assertFalse(verify(queue,row)[1].isEmpty());assertFalse(guard.sent());
                endpoint("/queued-ack");assertFalse(verify(queue,row)[1].isEmpty());assertFalse(guard.sent());
                endpoint("/v1/recordings");String[] receipt=verify(queue,row);assertEquals("",receipt[1]);
                assertEquals(io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup.Action.READ_INFO,guard.remoteVerified(receipt[0],receipt[0],true,1));
                guard.deviceInfo(row.device,0,2);
                assertEquals(io.github.lotusdecoder.daybookrecorder.core.DeviceCleanup.Action.DELETE,guard.files(Map.of(row.fileId,new Protocol.Entry(row.fileId,row.duration,row.fileId+1)),0,false,true,row.device,receipt[0],3));
                guard.acknowledged(true,4);guard.files(Map.of(),0,false,true,row.device,receipt[0],5);
                store.cleanup(row.id,guard.phase().name(),guard.detail());
                assertEquals(row.sha,RecordingStore.hash(store.file(row.id,".ogg")));
            }
            assertNull(store.nextCleanup(first.device,false,true));assertFalse(store.get(fresh.id).connectionSync);
            assertFalse(ConnectionSync.mayClean(store.get(fresh.id),true,false));assertNotNull(store.nextCleanup(first.device,true,false));
            assertTrue(store.connectionSyncSummary(first.device).contains("已清理 2"));
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    @Test public void rConnectionSyncPreservesDeletionMarkersAndUnknownCleanup() throws Exception {
        RecordingStore.Row local=audio(1700000731L),remote=audio(1700000732L),unknown=audio(1700000733L);
        store.deleteLocal(local.id);store.remoteDeleted(remote.id);store.sent(unknown.id);store.cleanup(unknown.id,"ACK","synthetic interrupted delete");
        store.close();store=new RecordingStore(context);store.recover();
        ConnectionSync sync=new ConnectionSync(store);sync.connected();
        Map<Long,Protocol.Entry> files=new HashMap<>();
        for(RecordingStore.Row row:new RecordingStore.Row[]{local,remote,unknown})files.put(row.fileId,new Protocol.Entry(row.fileId,row.duration,row.fileId+1));
        sync.capture(local.device,files,0,false,true,true);
        assertEquals("LOCAL_DELETED",store.get(local.id).state);assertEquals("SERVER_DELETED",store.get(remote.id).state);
        assertFalse(store.get(local.id).connectionSync);assertFalse(store.get(remote.id).connectionSync);
        assertFalse(ConnectionSync.mayDownload(store.get(local.id),true));assertFalse(ConnectionSync.mayDownload(store.get(remote.id),true));
        assertEquals("UNKNOWN",store.get(unknown.id).cleanupState);assertNull(store.nextCleanup(local.device,false,true));
        store.retryUploads();sync.connected();sync.capture(local.device,files,0,false,true,true);
        assertNull(store.nextCleanup(local.device,false,true));assertTrue(store.connectionSyncSummary(local.device).contains("待检查 1"));
        assertEquals(remote.sha,RecordingStore.hash(store.file(remote.id,".ogg")));
    }
    @Test public void sConnectionSyncDefaultsOnButCanBeDisabledWithoutChangingContinuousCleanup() {
        activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(2));
        AtomicReference<CheckBox> box=new AtomicReference<>();
        instrumentation.runOnMainSync(()->box.set(findCheckBox(activity.findViewById(android.R.id.content),"连接后自动同步遗留录音")));
        assertNotNull(box.get());assertTrue(box.get().isChecked());assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",false));
        instrumentation.runOnMainSync(()->box.get().performClick());assertFalse(Settings.prefs(context).getBoolean("sync_on_connect",true));
        instrumentation.runOnMainSync(()->box.get().performClick());assertTrue(Settings.prefs(context).getBoolean("sync_on_connect",false));
        assertFalse(Settings.prefs(context).getBoolean("auto_cleanup",false));assertFalse(running());
    }
    @Test public void tExactDuplicateIsResolvedWithoutChangingIdentityOrAnyAudio() throws Exception {
        RecordingStore.Row full=audio(1700000801L);store.sent(full.id);store.cleanup(full.id,"DELETED","synthetic done");
        RecordingStore.Row pending=partialCopy(full,"2222222222222222");
        String before=RecordingStore.hash(store.file(pending.id,".part"));
        assertEquals(1,store.reconcileDuplicates());assertEquals(0,store.reconcileDuplicates());
        RecordingStore.Row resolved=store.get(pending.id);assertTrue(resolved.reconciled());assertEquals(full.id,resolved.duplicateOf);
        assertFalse(resolved.saved());assertEquals(0,resolved.uploadedAt);assertEquals("",resolved.cleanupState);assertFalse(resolved.cleanupAcknowledged);
        assertEquals(before,RecordingStore.hash(store.file(pending.id,".part")));assertEquals(full.sha,RecordingStore.hash(store.file(full.id,".ogg")));
        assertTrue(store.summary().contains("待补收 0"));assertTrue(store.summary().contains("已核对重复记录 1"));
        assertFalse(ConnectionSync.mayDownload(resolved,true));assertNull(store.nextCleanup(pending.device));assertFalse(store.receiving(pending.id));
        store.waiting(pending.id,"late interruption");store.ready(pending.id,8000,1000,full.sha);
        store.close();store=new RecordingStore(context);store.recover();assertTrue(store.get(pending.id).reconciled());
        assertEquals(before,RecordingStore.hash(store.file(pending.id,".part")));
        store.deleteLocal(full.id);assertEquals("WAITING",store.get(pending.id).state);assertEquals("",store.get(pending.id).duplicateOf);
        assertEquals(before,RecordingStore.hash(store.file(pending.id,".part")));assertEquals(0,store.reconcileDuplicates());
    }
    @Test public void uSameIdAndLengthNeverReplaceContentVerification() throws Exception {
        RecordingStore.Row full=audio(1700000802L);store.sent(full.id);
        RecordingStore.Row pending=partialCopy(full,"2222222222222222");
        File part=store.file(pending.id,".part");
        try(RandomAccessFile f=new RandomAccessFile(part,"rw")){f.seek(40);f.write(123);}
        assertEquals(full.bytes,part.length());assertEquals(0,store.reconcileDuplicates());assertEquals("WAITING",store.get(pending.id).state);
        java.nio.file.Files.copy(store.file(full.id,".opus.bin").toPath(),part.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try(RandomAccessFile f=new RandomAccessFile(part,"rw")){f.setLength(full.bytes-160);}
        assertEquals(0,store.reconcileDuplicates());
        java.nio.file.Files.copy(store.file(full.id,".opus.bin").toPath(),part.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try(FileOutputStream f=new FileOutputStream(store.file(full.id,".ogg"),true)){f.write(0);}
        assertEquals(0,store.reconcileDuplicates());assertEquals("WAITING",store.get(pending.id).state);
    }
    @Test public void vReconciliationPreservesActiveTransfersAndDeletionMarkers() throws Exception {
        RecordingStore.Row full=audio(1700000803L);store.sent(full.id);
        RecordingStore.Row pending=partialCopy(full,"2222222222222222");store.receiving(pending.id);
        assertEquals(0,store.reconcileDuplicates());assertEquals("RECEIVING",store.get(pending.id).state);
        store.waiting(pending.id,"synthetic stop");store.progress(pending.id,full.bytes);store.remoteDeleted(full.id);
        assertEquals(0,store.reconcileDuplicates());assertEquals("WAITING",store.get(pending.id).state);
        store.deleteLocal(pending.id);assertEquals(0,store.reconcileDuplicates());assertTrue(store.get(pending.id).localDeleted());
    }
    @Test public void wAcknowledgedDeleteFinishesAfterRestartWithoutAnotherCommand() throws Exception {
        RecordingStore.Row full=audio(1700000804L);store.sent(full.id);
        store.cleanup(full.id,"AFTER","synthetic device success");store.cleanup(full.id,"UNKNOWN","synthetic new recording");
        store.close();store=new RecordingStore(context);store.recover();assertTrue(store.get(full.id).cleanupAcknowledged);
        assertNull(store.nextCleanup(full.device));
        Map<Long,Protocol.Entry> present=Map.of(full.fileId,new Protocol.Entry(full.fileId,full.duration,full.fileId+1));
        assertEquals(0,store.reconcileAcknowledged(full.device,present,0,false));
        assertEquals(0,store.reconcileAcknowledged("2222222222222222",Map.of(),0,false));
        assertEquals(0,store.reconcileAcknowledged(full.device,Map.of(),full.fileId,false));
        assertEquals(0,store.reconcileAcknowledged(full.device,Map.of(),0,true));
        assertEquals("UNKNOWN",store.get(full.id).cleanupState);
        assertEquals(1,store.reconcileAcknowledged(full.device,Map.of(),0,false));
        assertEquals("DELETED",store.get(full.id).cleanupState);assertEquals(0,store.reconcileAcknowledged(full.device,Map.of(),0,false));
        assertEquals(full.sha,RecordingStore.hash(store.file(full.id,".ogg")));
    }
    @Test public void xUnacknowledgedAndLegacyAbsentStatesNeverInventSuccessfulDeletion() throws Exception {
        RecordingStore.Row unknown=audio(1700000805L),absent=audio(1700000806L);store.sent(unknown.id);store.sent(absent.id);
        store.cleanup(unknown.id,"ACK","synthetic no receipt");store.cleanup(absent.id,"ABSENT","legacy detail");
        store.enqueueConnectionSync(absent.device,Map.of(absent.fileId,new Protocol.Entry(absent.fileId,absent.duration,absent.fileId+1)),true);
        store.close();store=new RecordingStore(context);store.recover();
        assertEquals(0,store.reconcileAcknowledged(unknown.device,Map.of(),0,false));
        assertEquals("UNKNOWN",store.get(unknown.id).cleanupState);assertEquals("ABSENT",store.get(absent.id).cleanupState);
        assertFalse(store.get(unknown.id).cleanupAcknowledged);assertFalse(store.get(absent.id).cleanupAcknowledged);
        assertTrue(store.get(absent.id).cleanupDisplay().contains("豆内已无"));
        assertTrue(store.connectionSyncSummary(absent.device).contains("豆内已无 1"));assertFalse(store.connectionSyncSummary(absent.device).contains("待检查"));
    }
    @Test public void yTwoIndependentReadsRecoverAudioAndOnlyAReceiptReconcilesTheOtherDevice() throws Exception {
        RecordingStore.Row row=store.ensure("1111111111111111",1700000901L,20);
        RecordingStore.Row alias=store.ensure("2222222222222222",row.fileId,20);
        File legacy=store.file(row.id,".part");
        try(RecordingAssembler audio=new RecordingAssembler(legacy)) {
            writeReadbackFrames(audio,100,-1,0);
            assertEquals(16000,audio.bytes());
            assertFalse(audio.complete(166,RecordingSizeUnit.TRANSPORT_BYTES));
        }
        java.nio.file.Files.copy(legacy.toPath(),store.file(alias.id,".part").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.progress(alias.id,16000);
        String original=RecordingStore.hash(legacy);
        OfflineReadback readback=new OfflineReadback(store,row.id);
        assertTrue(store.receiving(row.id));
        assertEquals(OfflineReadback.Result.READ_AGAIN,readback(readback,100,-1,0,166,true,true));
        assertNull(store.nextUpload());assertEquals(0,store.reconcileDuplicates());
        assertEquals(original,RecordingStore.hash(legacy));

        store.close();store=new RecordingStore(context);store.recover();
        assertTrue(store.receiving(row.id));readback=new OfflineReadback(store,row.id);
        assertEquals(OfflineReadback.Result.MATCHED,readback(readback,100,-1,0,166,true,true));
        assertEquals(2000,store.saveReceived(row.id,row.fileId,readback.attempt,100,true));
        assertEquals(original,RecordingStore.hash(store.file(row.id,".opus.bin")));
        assertEquals("READY",store.get(row.id).state);assertTrue(store.get(row.id).readbackVerified);
        assertEquals(0,store.reconcileDuplicates());
        SSLSocketFactory originalTls=useFixtureTls();UploadQueue queue=new UploadQueue(context,store);
        try {
            endpoint("/v1/recordings");queue.kick();
            waitFor(()->store.get(row.id).state.equals("SENT") && store.get(alias.id).reconciled());
            assertEquals(row.id,store.get(alias.id).duplicateOf);
            assertEquals(original,RecordingStore.hash(store.file(alias.id,".part")));
            assertNull(store.nextCleanup(row.device,true,true));
            assertFalse(ConnectionSync.mayClean(store.get(row.id),true,true));
            DeviceCleanup ordinary=new DeviceCleanup(row.device,row.fileId,store.get(row.id).sha,2000,true,0);
            ordinary.remoteVerified("endpoint","endpoint",true,1);ordinary.deviceInfo(row.device,0,2);
            assertEquals(DeviceCleanup.Action.NONE,ordinary.files(Map.of(row.fileId,new Protocol.Entry(row.fileId,20,row.fileId+2)),
                    0,false,true,row.device,"endpoint",3));
            assertEquals(DeviceCleanup.Phase.FAILED,ordinary.phase());assertFalse(ordinary.sent());
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(originalTls);}
    }
    @Test public void yaReadbackRejectsGapsShorterStreamsAndDifferentContent() throws Exception {
        RecordingStore.Row row=store.ensure("1111111111111111",1700000902L,20);
        assertNotNull(OfflineReadback.forRequest(store,row.id,"04.00",false,20));
        assertNull(OfflineReadback.forRequest(store,row.id,"04.00",true,20));
        assertNull(OfflineReadback.forRequest(store,row.id,"03.00",false,20));
        assertNull(OfflineReadback.forRequest(store,row.id,"04.00",false,40));
        Protocol.Entry noEnd=new Protocol.Entry(10,20,0);
        assertFalse(OfflineReadback.closedEntry(noEnd,0x1b,true));
        assertFalse(OfflineReadback.closedEntry(noEnd,0x1a,false));
        assertTrue(OfflineReadback.closedEntry(noEnd,0x1a,true));
        assertTrue(OfflineReadback.closedEntry(new Protocol.Entry(10,20,12),0x1b,false));
        OfflineReadback check=new OfflineReadback(store,row.id);
        assertEquals(OfflineReadback.Result.EXACT,readback(check,1,-1,0,166,true,true));
        assertEquals(OfflineReadback.Result.EXACT,readback(check,4,-1,0,664,true,true));
        assertEquals(OfflineReadback.Result.INCOMPLETE,readback(check,3,-1,0,664,true,true)); // Missing tail.
        assertEquals(OfflineReadback.Result.INCOMPLETE,readback(check,4,0,0,166,true,true));
        assertEquals(OfflineReadback.Result.INCOMPLETE,readback(check,4,2,0,166,true,true));
        assertEquals(OfflineReadback.Result.INCOMPLETE,readback(check,4,-1,0,166,false,true));
        assertEquals(OfflineReadback.Result.INCOMPLETE,readback(check,4,-1,0,166,true,false));
        assertEquals(OfflineReadback.Result.READ_AGAIN,readback(check,4,-1,0,166,true,true));
        String firstHash=RecordingStore.hash(store.file(row.id,".readback-first"));
        assertEquals(OfflineReadback.Result.SHORTER,readback(check,1,-1,0,166,true,true));
        assertEquals(OfflineReadback.Result.SHORTER,readback(check,3,-1,0,166,true,true));
        assertEquals(OfflineReadback.Result.DIFFERENT,readback(check,4,-1,1,166,true,true));
        assertEquals(firstHash,RecordingStore.hash(store.file(row.id,".readback-original")));
        assertNull(store.nextUpload());
        RecordingStore.Row other=store.ensure("2222222222222222",row.fileId,20);
        assertEquals(OfflineReadback.Result.READ_AGAIN,readback(new OfflineReadback(store,other.id),4,-1,1,166,true,true));
        assertEquals(OfflineReadback.Result.MATCHED,readback(check,4,-1,1,166,true,true));
        long retryAt=System.currentTimeMillis()+1800000;
        store.waiting(row.id,"两次内容不同",retryAt);store.close();store=new RecordingStore(context);store.recover();
        assertEquals(retryAt,store.get(row.id).nextAttempt);
        store.retryDownloads();assertEquals(0,store.get(row.id).nextAttempt);
        store.deleteLocal(row.id);assertFalse(check.attempt.exists());assertFalse(store.file(row.id,".readback-first").exists());
        assertFalse(store.file(row.id,".readback-original").exists());
        assertTrue(store.file(other.id,".readback-first").isFile());store.deleteLocal(other.id);
    }
    @Test public void ybInvalidOpusReadbackCannotEnterUploadAndDiagnosticsIncludeOlderPendingRows() throws Exception {
        RecordingStore.Row pending=store.ensure("1111111111111111",1700000903L,20);
        File attempt=store.file(pending.id,".readback");
        try(RecordingAssembler audio=new RecordingAssembler(attempt)) {
            byte[] packet=new byte[160];packet[0]=(byte)0x80; // 2.5 ms Opus.
            audio.add(0,packet);audio.add(1,packet);
        }
        try{store.saveReceived(pending.id,pending.fileId,attempt,2,true);fail("Unconfirmed duration");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("Opus 时长"));}
        assertTrue(attempt.isFile());assertNull(store.nextUpload());assertFalse(store.get(pending.id).readbackVerified);
        for(int i=1;i<=31;i++) {
            RecordingStore.Row sent=store.ensure(pending.device,pending.fileId+i,1000);
            store.ready(sent.id,8000,1000,"a".repeat(64));store.sent(sent.id);
        }
        assertEquals(30,store.latest().size());assertEquals(31,store.diagnosticRows().size());
        assertEquals(pending.id,store.diagnosticRows().get(30).id);
    }
    @Test public void ycManualCleanupRereadsFramesBeforeAckAndAbsence() throws Exception {
        runManualCleanup(false);
    }
    @Test public void ydManualCleanupPreservesAllCopiesWhenOneByteDiffers() throws Exception {
        runManualCleanup(true);
    }
    @Test public void yeInterruptedContentProofOnlyRestartsWithManualConsent() throws Exception {
        String device="1111111111111111";RecordingStore.Row row=store.ensure(device,1700000912L,20);
        File part=store.file(row.id,".part");
        try(RecordingAssembler data=new RecordingAssembler(part)){writeReadbackFrames(data,5,-1,0);}
        store.saveReceived(row.id,row.fileId,part,5,true);store.sent(row.id);
        assertTrue(store.claimCleanup(row.id));store.cleanup(row.id,"CONTENT","核对中");
        assertFalse(store.claimCleanup(row.id));store.recover();
        assertEquals("RETRY",store.get(row.id).cleanupState);assertEquals("SENT",store.get(row.id).state);
        assertFalse(store.get(row.id).cleanupAcknowledged);assertNull(store.nextCleanup(device,true,true));
        assertTrue(store.claimCleanup(row.id));
    }
    @Test public void yfThreeTabGraphicHudShowsConnectionStatesAndMegabyteProgress() throws Exception {
        String address="00:11:22:33:44:55",dev=RecordingStore.deviceId(address);
        RecordingStore.Row row=audio(dev,1700000980L);
        activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // 1. Disconnected state
        waitFor(()->windowContains("连接已断开"));
        // 2. First launch / establishing connection state
        Settings.prefs(context).edit().putString("address",address).putString("name","Soundcore Work")
                .putLong("since",System.currentTimeMillis()/1000).putBoolean("enabled",true).commit();
        RuntimeTelemetry.handshakeStep=2;
        instrumentation.runOnMainSync(()->context.startForegroundService(new Intent(context,RecorderService.class)));
        waitFor(this::running);
        instrumentation.runOnMainSync(()->{RuntimeTelemetry.handshakeStep=2;((MainActivity)activity).selectTab(0);});
        waitFor(()->windowContains("正在建立连接") && windowContains("密钥握手"));
        // 3. Stably connected state + active MB/%/duration upload progress
        long now=System.currentTimeMillis();
        Settings.prefs(context).edit().putBoolean("ble_ready",true).putString("ble_device",dev)
                .putString("firmware","04.00").putInt("battery",80).putLong("heartbeat",now).commit();
        RuntimeTelemetry.connectedSinceWallMs=now-3*3600_000L-42*60_000L;
        RuntimeTelemetry.rssiDbm=-54;
        RuntimeTelemetry.httpUpload=new RuntimeTelemetry.Transfer(row.id,row.fileId,true,false,false,
                Math.round(1.42*1024*1024),Math.round(2.08*1024*1024),204_000L,0.38*1024*1024);
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(0));
        waitFor(()->windowContains("已稳定连接") && windowContains("68%") && windowContains("1.42 / 2.08 MB") && windowContains("03:24"));
        // 4. Reconnecting after stable session
        Settings.prefs(context).edit().putBoolean("ble_ready",false).putLong("last_stable_ms",2*3600_000L+18*60_000L).commit();
        RuntimeTelemetry.httpUpload=null;
        RuntimeTelemetry.reconnectCount=2;RuntimeTelemetry.reconnectDelayMs=8000L;RuntimeTelemetry.nextReconnectWallMs=System.currentTimeMillis()+5000L;
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(0));
        waitFor(()->windowContains("信号暂离") && windowContains("02h 18m"));
        // 5. Switch to Copies tab and Settings/Logs tab
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(1));
        waitFor(()->windowContains("手机副本") && windowContains("MB"));
        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(2));
        waitFor(()->windowContains("设置与维护") && windowContains("设备运行日志"));
    }
    @Test public void ygRotatedBleAddressKeepsStableDeviceIdAndRejectsForeignSerial() throws Exception {
        String origAddr="00:11:22:33:44:55",rotatedAddr="66:77:88:99:AA:BB",foreignAddr="AA:BB:CC:DD:EE:FF";
        String stableId=RecordingStore.deviceId(origAddr);
        Settings.prefs(context).edit().putString("address",origAddr)
                .putString("storage_device",stableId).putString("storage_bound_address",origAddr).commit();
        assertEquals(stableId,Settings.activeDeviceId(context));
        // When RPA rotates and bound address updates together, logical device ID stays identical
        Settings.prefs(context).edit().putString("address",rotatedAddr).putString("storage_bound_address",rotatedAddr).commit();
        assertEquals(stableId,Settings.activeDeviceId(context));
        // When user switches to an unbound address directly, logical device ID switches cleanly
        Settings.prefs(context).edit().putString("address",foreignAddr).commit();
        assertEquals(RecordingStore.deviceId(foreignAddr),Settings.activeDeviceId(context));

        // Verify RecorderService serial_hash gate rolls back foreign candidate and accepts matching serial
        openSession();waitFor(this::running);
        byte[] myInfo=new byte[51];
        System.arraycopy("04.00".getBytes(StandardCharsets.US_ASCII),0,myInfo,3,5);
        System.arraycopy("MYSERIAL00001234".getBytes(StandardCharsets.US_ASCII),0,myInfo,8,16);
        String mySerialHash=io.github.lotusdecoder.daybookrecorder.core.DeviceIdentity.extractSerialHash(myInfo);

        byte[] foreignInfo=new byte[51];
        System.arraycopy("04.00".getBytes(StandardCharsets.US_ASCII),0,foreignInfo,3,5);
        System.arraycopy("OTHERSER99999999".getBytes(StandardCharsets.US_ASCII),0,foreignInfo,8,16);

        RecorderService service=onRecordingThread(()->{
            RecorderService owner=recordingOwner();
            ((Handler)field(owner,"worker")).removeCallbacks((Runnable)field(owner,"ticker"));
            java.lang.reflect.Method close=RecorderService.class.getDeclaredMethod("closeGatt");close.setAccessible(true);close.invoke(owner);
            Settings.prefs(context).edit().putString("address",origAddr)
                    .putString("storage_device",stableId).putString("storage_bound_address",origAddr)
                    .putString("serial_hash",mySerialHash).commit();
            setField(owner,"device",stableId);setField(owner,"connectedAddress",rotatedAddr);
            setField(owner,"fallbackSelectedAddress",origAddr);setField(owner,"unverifiedFallbackAddress",true);
            setField(owner,"ready",false);return owner;
        });
        // Foreign bean responds to 01/01 -> rejected and address rolled back to origAddr
        notifyBean(service,1,1,foreignInfo);
        waitFor(()->onRecordingThread(()->!(boolean)field(service,"unverifiedFallbackAddress")));
        assertEquals(origAddr,Settings.prefs(context).getString("address",""));
        assertEquals(stableId,Settings.activeDeviceId(context));

        onRecordingThread(() -> {
            Settings.prefs(context).edit().remove("serial_hash").commit();
            setField(service,"connectedAddress",rotatedAddr);
            setField(service,"fallbackSelectedAddress",origAddr);
            setField(service,"unverifiedFallbackAddress",true);
            return null;
        });
        notifyBean(service,1,1,foreignInfo);
        waitFor(()->onRecordingThread(()->!(boolean)field(service,"unverifiedFallbackAddress")));
        assertFalse(Settings.prefs(context).contains("serial_hash"));
        assertEquals(origAddr,Settings.prefs(context).getString("address",""));
        Settings.prefs(context).edit().putString("serial_hash",mySerialHash).commit();

        // Now matching bean responds on rotatedAddr -> accepted and committed
        onRecordingThread(()->{
            Settings.prefs(context).edit().putString("address",origAddr)
                    .putString("storage_device",stableId).putString("storage_bound_address",origAddr).commit();
            setField(service,"device",stableId);setField(service,"connectedAddress",rotatedAddr);
            setField(service,"fallbackSelectedAddress",origAddr);setField(service,"unverifiedFallbackAddress",true);
            setField(service,"ready",false);return null;
        });
        notifyBean(service,1,1,myInfo);
        waitFor(()->onRecordingThread(()->!(boolean)field(service,"unverifiedFallbackAddress")));
        assertEquals(rotatedAddr,Settings.prefs(context).getString("address",""));
        assertEquals(stableId,Settings.activeDeviceId(context));
    }
    @Test public void yhOldestFirstClosedLoopAndBreakpointResumeAcrossDisconnect() throws Exception {
        // 1. Verify RecordingStore.nextUpload() strictly selects oldest recording first
        String address="00:11:22:33:44:55",device=RecordingStore.deviceId(address);
        RecordingStore.Row strandedOld=audio(device,1700001000L);
        RecordingStore.Row checkNewer=audio(device,1700001099L);
        assertEquals(strandedOld.id,store.nextUpload().id);
        store.deleteLocal(checkNewer.id);

        // Prepare 3 synthetic bean recordings (1001=oldest 120 frames, 1002=middle 50 frames, 1003=newest 50 frames)
        long id1=1700001001L,id2=1700001002L,id3=1700001003L;
        byte[][] raw1=new byte[120][160],raw2=new byte[50][160];
        ByteArrayOutputStream expected1=new ByteArrayOutputStream();
        for(int i=0;i<120;i++){Arrays.fill(raw1[i],(byte)5);raw1[i][0]=(byte)0xbc;raw1[i][159]=(byte)i;expected1.write(raw1[i]);}
        for(int i=0;i<50;i++){Arrays.fill(raw2[i],(byte)6);raw2[i][0]=(byte)0xbc;raw2[i][159]=(byte)i;}
        File tmpRaw1=new File(context.getCacheDir(),"exp1001.raw"),tmpOgg1=new File(context.getCacheDir(),"exp1001.ogg");
        java.nio.file.Files.write(tmpRaw1.toPath(),expected1.toByteArray());
        assertEquals(2400L,OggOpus.mux(tmpRaw1,tmpOgg1,(int)id1));
        String expectedOggSha1=RecordingStore.hash(tmpOgg1);
        tmpRaw1.delete();tmpOgg1.delete();

        DeviceCrypto phone=new DeviceCrypto(),peer=new DeviceCrypto();byte[] secret=peer.shared(phone.publicKey());
        phone.handshake(ByteBuffer.allocate(97).put(peer.publicKey()).put(secret).array());
        byte[] salt={1,2,3},key=new byte[32],nonce=new byte[16];Arrays.fill(key,(byte)9);
        byte[] wrapped=DeviceCrypto.ctr(ByteBuffer.allocate(46).put("soundcored3200".getBytes(StandardCharsets.US_ASCII)).put(key).array(),
                DeviceCrypto.hkdf(secret,salt,salt),nonce);
        byte[] head1=ByteBuffer.allocate(87).order(ByteOrder.LITTLE_ENDIAN).putInt((int)id1).putInt(120*166).put(nonce)
                .put(wrapped).put(nonce).put((byte)0).array();
        DeviceCrypto.FileKey fileKey1=phone.fileKey(head1,id1);

        SSLSocketFactory original=useFixtureTls();
        try {
            endpoint("/v1/recordings");
            endpoint("/v1/recordings");
            Settings.prefs(context).edit().putBoolean("sync_on_connect",true).putBoolean("auto_cleanup",false).commit();
            openSession();
            waitFor(()->running() && onRecordingThread(()->recordingOwner()!=null && (boolean)field(recordingOwner(),"started")));

            byte[] idleInfo=new byte[51];System.arraycopy("04.00".getBytes(StandardCharsets.US_ASCII),0,idleInfo,3,5);
            // Send 3 bean entries in shuffled order (1003, 1001, 1002)
            byte[] listAll=ByteBuffer.allocate(2+3*12).order(ByteOrder.LITTLE_ENDIAN).putShort((short)3)
                    .putInt((int)id3).putInt((int)id3+1).putInt(1000)
                    .putInt((int)id1).putInt((int)id1+3).putInt(2400)
                    .putInt((int)id2).putInt((int)id2+1).putInt(1000).array();

            java.lang.reflect.Method reqInv=RecorderService.class.getDeclaredMethod("requestInventory");
            reqInv.setAccessible(true);

            RecorderService service=onRecordingThread(()->{
                RecorderService owner=recordingOwner();
                ((Handler)field(owner,"worker")).removeCallbacks((Runnable)field(owner,"ticker"));
                java.lang.reflect.Method close=RecorderService.class.getDeclaredMethod("closeGatt");close.setAccessible(true);close.invoke(owner);
                setField(owner,"device",device);setField(owner,"firmware","04.00");setField(owner,"ready",true);
                setField(owner,"crypto",phone);setField(owner,"deviceReportedIdle",true);
                ((ConnectionSync)field(owner,"connectionSync")).connected();
                ((UploadQueue)field(owner,"uploads")).kick();
                reqInv.invoke(owner);
                return owner;
            });

            // Wait for stranded 1000 to upload to cloud, then deliver bean list {1003, 1001, 1002}
            waitFor(()->store.get(strandedOld.id).state.equals("SENT"));
            notifyBean(service,0x1b,0x0e,listAll);

            // Pipeline MUST pick oldest bean file 1001 (NOT 1003!) with initial offset 0
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==id1 && (long)field(act,"resumedOffset")==0L;
            }));
            String recId1=RecordingStore.id(device,id1),recId2=RecordingStore.id(device,id2),recId3=RecordingStore.id(device,id3);
            assertEquals("WAITING",store.get(recId2).state);
            assertEquals("WAITING",store.get(recId3).state);

            // Send header + partial frames 0..89 (75% of 1001), then simulate mid-transfer BLE disconnect!
            notifyBean(service,0x1a,7,head1);
            for(int start=0;start<90;start+=30) {
                ByteBuffer slices=ByteBuffer.allocate(30*166).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=start;i<start+30;i++)slices.putInt(i).put((byte)0).put(fileKey1.decrypt(i,raw1[i])).put((byte)0);
                notifyBean(service,0x1a,8,slices.array());
            }
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && ((RecordingAssembler)field(act,"assembler")).frames()==90;
            }));
            onRecordingThread(()->{
                java.lang.reflect.Method ab=RecorderService.class.getDeclaredMethod("abandon",String.class);
                ab.setAccessible(true);ab.invoke(service,"连接中断，等待补收");
                return null;
            });
            assertEquals("WAITING",store.get(recId1).state);
            assertEquals(90*160L,store.get(recId1).bytes);
            assertTrue(RecordingAssembler.resumeFile(store.file(recId1,".part")).isFile());

            // Reconnect and deliver bean list again -> MUST resume 1001 from aligned breakpoint offset 13280!
            onRecordingThread(()->{reqInv.invoke(service);return null;});
            notifyBean(service,0x1b,0x0e,listAll);
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==id1
                        && (long)field(act,"initialBytes")==90*160L
                        && (long)field(act,"resumedOffset")==13280L;
            }));

            // Send header + ONLY frames 80..119 (never resending frames 0..79!) + transfer end 1A/0A
            notifyBean(service,0x1a,7,head1);
            ByteBuffer tailSlices=ByteBuffer.allocate(40*166).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=80;i<120;i++)tailSlices.putInt(i).put((byte)0).put(fileKey1.decrypt(i,raw1[i])).put((byte)0);
            notifyBean(service,0x1a,8,tailSlices.array());
            notifyBean(service,0x1a,0x0a,new byte[9]);

            // Wait for 1001 to settle (2s quiet window), save, upload to cloud (SENT), and immediately start bean cleanup (INFO)
            waitFor(()->store.get(recId1).state.equals("SENT") && store.get(recId1).cleanupState.equals("INFO"));
            assertEquals(expectedOggSha1,store.get(recId1).sha);
            assertFalse(RecordingAssembler.resumeFile(store.file(recId1,".part")).exists());
            // Crucially: 1002 and 1003 MUST NOT have started downloading while 1001 was uploading / cleaning!
            assertEquals("WAITING",store.get(recId2).state);
            assertEquals(0L,store.get(recId2).bytes);
            assertEquals("WAITING",store.get(recId3).state);

            // Finish bean verification & delete for 1001 -> bean now returns {1002, 1003}
            notifyBean(service,1,1,idleInfo);
            waitFor(()->onRecordingThread(()->(boolean)field(service,"listing")));
            notifyBean(service,0x1b,0x0e,listAll);
            waitFor(()->store.get(recId1).cleanupState.equals("ACK"));
            notifyBean(service,0x1a,0x10,new byte[0]);
            waitFor(()->store.get(recId1).cleanupState.equals("AFTER"));
            waitFor(()->onRecordingThread(()->(boolean)field(service,"listing")));

            byte[] listAfter1=ByteBuffer.allocate(2+2*12).order(ByteOrder.LITTLE_ENDIAN).putShort((short)2)
                    .putInt((int)id3).putInt((int)id3+1).putInt(1000)
                    .putInt((int)id2).putInt((int)id2+1).putInt(1000).array();
            notifyBean(service,0x1b,0x0e,listAfter1);

            // 1001 is now DELETED on bean, and pipeline MUST immediately advance to 1002 (now the oldest, NOT 1003!)
            waitFor(()->store.get(recId1).cleanupState.equals("DELETED"));
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==id2;
            }));
            assertEquals("WAITING",store.get(recId3).state);
        }finally{HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    @Test public void yiPrefixDuplicateReconciliationAndEmptyBeanHidesOrphanWaitingOnHome() throws Exception {
        String activeAddr="00:11:22:33:44:55",activeDev=RecordingStore.deviceId(activeAddr);
        String oldRotatedDev="4444444444444444";
        Settings.prefs(context).edit().putString("address",activeAddr).putString("name","Soundcore Work")
                .putString("storage_device",activeDev).putString("storage_bound_address",activeAddr).commit();

        // 1. Exact byte-for-byte partial prefix of an already-SENT recording reconciles automatically
        long sampleId=1700000991L;
        RecordingStore.Row fullSent=audio(activeDev,sampleId);
        store.sent(fullSent.id);
        RecordingStore.Row partialDup=store.ensure(oldRotatedDev,sampleId,320000L);
        byte[] fullRawBytes=java.nio.file.Files.readAllBytes(store.file(fullSent.id,".opus.bin").toPath());
        byte[] prefix15Frames=Arrays.copyOfRange(fullRawBytes,0,15*160);
        File partFile=store.file(partialDup.id,".part");
        byte[] tampered=prefix15Frames.clone();tampered[40]^=1;
        java.nio.file.Files.write(partFile.toPath(),tampered);
        store.progress(partialDup.id,tampered.length);
        assertEquals(0,store.reconcileDuplicates());
        assertEquals("WAITING",store.get(partialDup.id).state);

        java.nio.file.Files.write(partFile.toPath(),prefix15Frames);
        String partShaBefore=RecordingStore.hash(partFile);
        assertEquals(1,store.reconcileDuplicates());
        assertEquals("RECONCILED",store.get(partialDup.id).state);
        assertEquals(fullSent.id,store.get(partialDup.id).duplicateOf);
        assertEquals(partShaBefore,RecordingStore.hash(partFile));

        // 2. Un-reconciled old WAITING orphan (>30 newer SENT rows, while bean inventory is EMPTY 0 items):
        //    Home Tab 1 MUST NOT show phantom "等待断点续传", while Copies Tab 2 MUST still expose it for 1-tap delete!
        long orphanId=1700000900L;
        RecordingStore.Row orphan=store.ensure(activeDev,orphanId,120000L);
        java.nio.file.Files.write(store.file(orphan.id,".part").toPath(),Arrays.copyOfRange(fullRawBytes,0,2*160));
        store.progress(orphan.id,320L);
        for(int i=1;i<=32;i++) {
            RecordingStore.Row newer=audio(activeDev,1700001200L+i);
            store.sent(newer.id);
        }
        assertEquals(30,store.latest().size());
        assertTrue(store.copiesRows().size()>=31);
        new DeviceFilesStore(context).complete(activeDev,Map.of(),0,true,"04.00");

        activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor(()->windowContains("全部已备份") && windowContains("0 待办") && windowContains("已清空 (0 条)"));
        assertFalse(windowContains("等待断点续传"));

        instrumentation.runOnMainSync(()->((MainActivity)activity).selectTab(1));
        waitFor(()->windowContains("豆内已无 · 残片"));
        AtomicReference<View> delBtn=new AtomicReference<>();
        instrumentation.runOnMainSync(()->delBtn.set(activity.findViewById(android.R.id.content).findViewWithTag("delete:"+orphan.id)));
        assertNotNull(delBtn.get());
        assertTrue(delBtn.get().isEnabled());
    }
    @Test public void yjAntiRedundancyBlocksDuplicateIdUploadAndPurgesFailedResumeToRestartFromZero() throws Exception {
        String origAddr="00:11:22:33:44:55",rotatedAddr="66:77:88:99:AA:BB";
        String devA=RecordingStore.deviceId(origAddr),devB=RecordingStore.deviceId(rotatedAddr);
        long dupFileId=1700001301L,resumeVerifyFailId=1700001302L,resumeMultiFailId=1700001303L;

        // 1. Pre-upload SHA-256 deduplication gate: identical audio under 2 device keys NEVER uploads twice
        SSLSocketFactory original=useFixtureTls();
        try {
            endpoint("/v1/recordings");
            endpoint("/v1/recordings");
            Settings.prefs(context).edit().putBoolean("sync_on_connect",true).putBoolean("auto_cleanup",false).commit();

            RecordingStore.Row firstCopy=audio(devA,dupFileId);
            RecordingStore.Row secondCopy=audio(devB,dupFileId);
            assertEquals(firstCopy.sha,secondCopy.sha);
            UploadQueue standAloneQueue=new UploadQueue(context,store);
            try {
                standAloneQueue.kick();
                waitFor(()->store.get(firstCopy.id).state.equals("SENT") && store.get(secondCopy.id).reconciled());
                assertEquals(firstCopy.id,store.get(secondCopy.id).duplicateOf);
            } finally {
                standAloneQueue.close();
            }

            // Prepare 120-frame test recording for resume verification-failure & multi-failure purge tests
            byte[][] raw120=new byte[120][160];
            ByteArrayOutputStream expRaw=new ByteArrayOutputStream();
            for(int i=0;i<120;i++){Arrays.fill(raw120[i],(byte)4);raw120[i][0]=(byte)0xbc;raw120[i][159]=(byte)i;expRaw.write(raw120[i]);}
            File tmpRaw=new File(context.getCacheDir(),"exp120.raw"),tmpOgg=new File(context.getCacheDir(),"exp120.ogg");
            java.nio.file.Files.write(tmpRaw.toPath(),expRaw.toByteArray());
            assertEquals(2400L,OggOpus.mux(tmpRaw,tmpOgg,(int)resumeVerifyFailId));
            String expectedSha=RecordingStore.hash(tmpOgg);
            tmpRaw.delete();tmpOgg.delete();

            DeviceCrypto phone=new DeviceCrypto(),peer=new DeviceCrypto();byte[] secret=peer.shared(phone.publicKey());
            phone.handshake(ByteBuffer.allocate(97).put(peer.publicKey()).put(secret).array());
            byte[] salt={1,2,3},key=new byte[32],nonce=new byte[16];Arrays.fill(key,(byte)11);
            byte[] wrapped=DeviceCrypto.ctr(ByteBuffer.allocate(46).put("soundcored3200".getBytes(StandardCharsets.US_ASCII)).put(key).array(),
                    DeviceCrypto.hkdf(secret,salt,salt),nonce);
            byte[] headVerify=ByteBuffer.allocate(87).order(ByteOrder.LITTLE_ENDIAN).putInt((int)resumeVerifyFailId).putInt(120*166)
                    .put(nonce).put(wrapped).put(nonce).put((byte)0).array();
            byte[] headMulti=ByteBuffer.allocate(87).order(ByteOrder.LITTLE_ENDIAN).putInt((int)resumeMultiFailId).putInt(120*166)
                    .put(nonce).put(wrapped).put(nonce).put((byte)0).array();
            DeviceCrypto.FileKey fkVerify=phone.fileKey(headVerify,resumeVerifyFailId);
            DeviceCrypto.FileKey fkMulti=phone.fileKey(headMulti,resumeMultiFailId);

            // Seed partial 90-frame .part files for both resumeVerifyFailId and resumeMultiFailId under devB
            String recVerify=RecordingStore.id(devB,resumeVerifyFailId);
            String recMulti=RecordingStore.id(devB,resumeMultiFailId);
            String devC="3333333333333333";
            String recDupC=RecordingStore.id(devC,dupFileId);

            openSession();
            waitFor(()->running() && onRecordingThread(()->recordingOwner()!=null && (boolean)field(recordingOwner(),"started")));
            java.lang.reflect.Method reqInv=RecorderService.class.getDeclaredMethod("requestInventory");
            reqInv.setAccessible(true);

            // 2. Verify serial_hash anchor restores devA even if manual rescan temporarily set devB,
            //    and unique-fileId gate skips re-downloading already-SENT dupFileId under devC!
            byte[] infoSerial=new byte[51];
            System.arraycopy("04.00".getBytes(StandardCharsets.US_ASCII),0,infoSerial,3,5);
            System.arraycopy("ANCHORSERIAL1234".getBytes(StandardCharsets.US_ASCII),0,infoSerial,8,16);
            String serialHash=io.github.lotusdecoder.daybookrecorder.core.DeviceIdentity.extractSerialHash(infoSerial);
            Settings.prefs(context).edit().putString("serial_device_"+serialHash,devA).commit();

            RecorderService service=onRecordingThread(()->{
                RecorderService owner=recordingOwner();
                ((Handler)field(owner,"worker")).removeCallbacks((Runnable)field(owner,"ticker"));
                java.lang.reflect.Method close=RecorderService.class.getDeclaredMethod("closeGatt");close.setAccessible(true);close.invoke(owner);
                setField(owner,"device",devB);setField(owner,"connectedAddress",rotatedAddr);
                setField(owner,"firmware","04.00");setField(owner,"ready",true);
                setField(owner,"crypto",phone);setField(owner,"deviceReportedIdle",true);
                return owner;
            });
            notifyBean(service,1,1,infoSerial);
            waitFor(()->onRecordingThread(()->devA.equals(field(service,"device"))));
            assertEquals(devA,Settings.prefs(context).getString("storage_device",""));

            // Switch service to devC (unanchored) and deliver inventory {dupFileId, resumeVerifyFailId, resumeMultiFailId}:
            // dupFileId MUST be skipped & reconciled immediately without downloading, jumping straight to resumeVerifyFailId!
            store.ensure(devC,resumeVerifyFailId,2400L);
            try(RecordingAssembler pre=new RecordingAssembler(store.file(RecordingStore.id(devC,resumeVerifyFailId),".part"),false)) {
                for(int i=0;i<90;i++)pre.add(i,raw120[i]);
                pre.checkpoint(120L*166L);
            }
            store.progress(RecordingStore.id(devC,resumeVerifyFailId),90*160L);

            store.ensure(devC,resumeMultiFailId,2400L);
            try(RecordingAssembler pre2=new RecordingAssembler(store.file(RecordingStore.id(devC,resumeMultiFailId),".part"),false)) {
                for(int i=0;i<90;i++)pre2.add(i,raw120[i]);
                pre2.checkpoint(120L*166L);
            }
            store.progress(RecordingStore.id(devC,resumeMultiFailId),90*160L);

            String recVerifyC=RecordingStore.id(devC,resumeVerifyFailId);
            String recMultiC=RecordingStore.id(devC,resumeMultiFailId);

            onRecordingThread(()->{
                setField(service,"device",devC);
                ((ConnectionSync)field(service,"connectionSync")).connected();
                reqInv.invoke(service);
                return null;
            });
            byte[] list3=ByteBuffer.allocate(2+3*12).order(ByteOrder.LITTLE_ENDIAN).putShort((short)3)
                    .putInt((int)dupFileId).putInt((int)dupFileId+1).putInt(1000)
                    .putInt((int)resumeVerifyFailId).putInt((int)resumeVerifyFailId+3).putInt(2400)
                    .putInt((int)resumeMultiFailId).putInt((int)resumeMultiFailId+3).putInt(2400).array();
            notifyBean(service,0x1b,0x0e,list3);

            waitFor(()->store.get(recDupC)!=null && store.get(recDupC).reconciled());
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==resumeVerifyFailId && (long)field(act,"resumedOffset")==13280L;
            }));

            // 3. Resume Verification Failure: overlap frame 80..104 matches, then 1A/0A arrives early (missing 105..119!)
            //    -> MUST purge old .part & immediately restart from offset 0!
            notifyBean(service,0x1a,7,headVerify);
            ByteBuffer shortTail=ByteBuffer.allocate(25*166).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=80;i<105;i++)shortTail.putInt(i).put((byte)0).put(fkVerify.decrypt(i,raw120[i])).put((byte)0);
            notifyBean(service,0x1a,8,shortTail.array());
            notifyBean(service,0x1a,0x0a,new byte[9]);

            // After 2s settle, fallbackActiveToZero deletes old .part and restarts resumeVerifyFailId from offset 0!
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==resumeVerifyFailId
                        && (long)field(act,"initialBytes")==0L
                        && (long)field(act,"resumedOffset")==0L;
            }));

            // Now feed full 0..119 from zero -> completes, uploads to SENT, and enters cleanup!
            notifyBean(service,0x1a,7,headVerify);
            for(int s=0;s<120;s+=30) {
                ByteBuffer blk=ByteBuffer.allocate(30*166).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=s;i<s+30;i++)blk.putInt(i).put((byte)0).put(fkVerify.decrypt(i,raw120[i])).put((byte)0);
                notifyBean(service,0x1a,8,blk.array());
            }
            notifyBean(service,0x1a,0x0a,new byte[9]);
            waitFor(()->store.get(recVerifyC).state.equals("SENT"));
            assertEquals(expectedSha,store.get(recVerifyC).sha);

            // 4. Resume Multiple Failures (2 consecutive resume disconnects without progress):
            //    Attempt 1 keeps .part; Attempt 2 deletes .part & resets bytes=0 so Attempt 3 starts from offset 0!
            onRecordingThread(()->{
                java.lang.reflect.Method cancel=RecorderService.class.getDeclaredMethod("cancelCleanup",String.class);
                cancel.setAccessible(true);cancel.invoke(service,"test");
                setField(service,"cleanupQuarantinedGeneration",-1L);
                reqInv.invoke(service);
                return null;
            });
            byte[] listMultiOnly=ByteBuffer.allocate(2+12).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1)
                    .putInt((int)resumeMultiFailId).putInt((int)resumeMultiFailId+3).putInt(2400).array();
            notifyBean(service,0x1b,0x0e,listMultiOnly);

            // Attempt 1 starts at resumedOffset 13280 -> disconnects without progress -> .part still retained once
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==resumeMultiFailId && (long)field(act,"resumedOffset")==13280L;
            }));
            onRecordingThread(()->{
                java.lang.reflect.Method ab=RecorderService.class.getDeclaredMethod("abandon",String.class);
                ab.setAccessible(true);ab.invoke(service,"连接中断 1");
                return null;
            });
            assertTrue(store.file(recMultiC,".part").isFile());

            // Attempt 2 starts at resumedOffset 13280 -> disconnects again without progress -> .part PURGED!
            onRecordingThread(()->{reqInv.invoke(service);return null;});
            notifyBean(service,0x1b,0x0e,listMultiOnly);
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==resumeMultiFailId && (long)field(act,"resumedOffset")==13280L;
            }));
            onRecordingThread(()->{
                java.lang.reflect.Method ab=RecorderService.class.getDeclaredMethod("abandon",String.class);
                ab.setAccessible(true);ab.invoke(service,"连接中断 2");
                return null;
            });
            assertFalse(store.file(recMultiC,".part").exists());
            assertFalse(RecordingAssembler.resumeFile(store.file(recMultiC,".part")).exists());
            assertEquals(0L,store.get(recMultiC).bytes);

            // Attempt 3 MUST start cleanly from zero (initialBytes=0, resumedOffset=0)!
            onRecordingThread(()->{reqInv.invoke(service);return null;});
            notifyBean(service,0x1b,0x0e,listMultiOnly);
            waitFor(()->onRecordingThread(()->{
                Object act=field(service,"active");
                return act!=null && (long)field(act,"fileId")==resumeMultiFailId
                        && (long)field(act,"initialBytes")==0L
                        && (long)field(act,"resumedOffset")==0L;
            }));
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original);
        }
    }
    private void runManualCleanup(boolean changed) throws Exception {
        openSession();waitFor(this::running);
        String device=RecordingStore.deviceId("00:11:22:33:44:55");
        long fileId=changed?1700000911L:1700000910L;
        RecordingStore.Row row=store.ensure(device,fileId,20);
        File part=store.file(row.id,".part");
        try(RecordingAssembler data=new RecordingAssembler(part)){writeReadbackFrames(data,100,-1,0);}
        store.saveReceived(row.id,fileId,part,100,true);
        String hash=store.get(row.id).sha,rawHash=RecordingStore.hash(store.file(row.id,".opus.bin"));
        byte[] raw=java.nio.file.Files.readAllBytes(store.file(row.id,".opus.bin").toPath());
        if(changed)raw[raw.length-1]^=1;
        DeviceCrypto phone=new DeviceCrypto(),peer=new DeviceCrypto();byte[] secret=peer.shared(phone.publicKey());
        phone.handshake(ByteBuffer.allocate(97).put(peer.publicKey()).put(secret).array());
        byte[] salt={1,2,3},key=new byte[32],nonce=new byte[16];Arrays.fill(key,(byte)7);
        byte[] wrapped=DeviceCrypto.ctr(ByteBuffer.allocate(46).put("soundcored3200".getBytes(StandardCharsets.US_ASCII)).put(key).array(),
                DeviceCrypto.hkdf(secret,salt,salt),nonce);
        byte[] header=ByteBuffer.allocate(87).order(ByteOrder.LITTLE_ENDIAN).putInt((int)fileId).putInt(166).put(nonce)
                .put(wrapped).put(nonce).put((byte)0).array();
        DeviceCrypto.FileKey fileKey=phone.fileKey(header,fileId);
        RecorderService service=onRecordingThread(()->{
            RecorderService owner=recordingOwner();
            ((Handler)field(owner,"worker")).removeCallbacks((Runnable)field(owner,"ticker"));
            java.lang.reflect.Method close=RecorderService.class.getDeclaredMethod("closeGatt");close.setAccessible(true);close.invoke(owner);
            setField(owner,"device",device);setField(owner,"firmware","04.00");setField(owner,"ready",true);setField(owner,"crypto",phone);return owner;
        });
        SSLSocketFactory original=useFixtureTls();UploadQueue queue=new UploadQueue(context,store);
        try {
            endpoint("/v1/recordings");
            queue.kick();waitFor(()->store.get(row.id).state.equals("SENT"));
            store.cleanup(row.id,"FAILED","旧版本时长不一致");assertNull(store.nextCleanup(device,true,true));
            context.startService(new Intent(context,RecorderService.class).setAction(RecorderService.CLEANUP).putExtra("recording_id",row.id));
            waitFor(()->store.get(row.id).cleanupState.equals("INFO"));
            byte[] idle=new byte[51];System.arraycopy("04.00".getBytes(StandardCharsets.US_ASCII),0,idle,3,5);
            byte[] present=ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort((short)1).putInt((int)fileId)
                    .putInt((int)fileId+2).putInt(20).array();
            notifyBean(service,1,1,idle);notifyBean(service,0x1b,0x0e,present);
            waitFor(()->store.get(row.id).cleanupState.equals("CONTENT"));
            notifyBean(service,0x1a,7,header);
            for(int start=0;start<100;start+=32) {
                int count=Math.min(32,100-start);
                ByteBuffer frames=ByteBuffer.allocate(count*166).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=start;i<start+count;i++)frames.putInt(i).put((byte)0)
                        .put(fileKey.decrypt(i,Arrays.copyOfRange(raw,i*160,(i+1)*160))).put((byte)0);
                notifyBean(service,0x1a,8,frames.array());
            }
            notifyBean(service,0x1a,0x0a,new byte[9]);
            if(changed) {
                waitFor(()->store.get(row.id).cleanupState.equals("FAILED"));
                assertTrue(store.get(row.id).cleanupDetail.contains("原音与已备份音频不一致"));assertFalse(store.get(row.id).cleanupAcknowledged);
            }else {
                waitFor(()->store.get(row.id).cleanupState.equals("INFO"));
                notifyBean(service,1,1,idle);notifyBean(service,0x1b,0x0e,present);
                waitFor(()->store.get(row.id).cleanupState.equals("ACK"));assertFalse(store.get(row.id).cleanupAcknowledged);
                notifyBean(service,0x1a,0x10,new byte[0]);waitFor(()->store.get(row.id).cleanupState.equals("AFTER"));
                waitFor(()->onRecordingThread(()->(boolean)field(service,"listing")));
                notifyBean(service,0x1b,0x0e,new byte[2]);waitFor(()->store.get(row.id).cleanupState.equals("DELETED"));
                assertTrue(store.get(row.id).cleanupAcknowledged);
            }
            assertFalse(new File(context.getCacheDir(),"cleanup-readback.part").exists());
            assertFalse(new File(context.getCacheDir(),"cleanup-readback.ogg").exists());
            assertEquals(16000,store.get(row.id).bytes);assertEquals(2000,store.get(row.id).duration);assertTrue(store.get(row.id).readbackVerified);
            assertEquals("SENT",store.get(row.id).state);assertEquals(hash,RecordingStore.hash(store.file(row.id,".ogg")));
            assertEquals(rawHash,RecordingStore.hash(store.file(row.id,".opus.bin")));
            assertEquals("",verify(queue,store.get(row.id))[1]);
        }finally{queue.close();HttpsURLConnection.setDefaultSSLSocketFactory(original);}
    }
    private void notifyBean(RecorderService service,int type,int id,byte[] payload) throws Exception {
        byte[] frame=Protocol.command(type,id,payload);frame[0]=9;frame[1]=(byte)255;frame[4]=1;
        frame[frame.length-1]=Protocol.checksum(frame,0,frame.length-1);
        java.lang.reflect.Method receive=RecorderService.class.getDeclaredMethod("receive",android.bluetooth.BluetoothGatt.class,UUID.class,byte[].class);
        receive.setAccessible(true);receive.invoke(service,null,Protocol.NOTIFY,frame);
    }
    private static OfflineReadback.Result readback(OfflineReadback check,int count,int missing,int variant,
                                                   long head,boolean closed,boolean clean) throws Exception {
        try(RecordingAssembler audio=new RecordingAssembler(check.attempt)) {
            writeReadbackFrames(audio,count,missing,variant);
            return check.verify(audio,head,closed,clean);
        }
    }
    private static void writeReadbackFrames(RecordingAssembler audio,int count,int missing,int variant) throws IOException {
        byte[] packet=new byte[160];packet[0]=(byte)0xf8;packet[1]=(byte)0xff;packet[2]=(byte)0xfe;
        for(int i=count-1;i>=0;i--) {
            if(i==missing)continue;
            packet[159]=(byte)(i+variant);audio.add(i,packet);
        }
    }
    private RecordingStore.Row partialCopy(RecordingStore.Row full,String device) throws Exception {
        RecordingStore.Row pending=store.ensure(device,full.fileId,full.duration);
        java.nio.file.Files.copy(store.file(full.id,".opus.bin").toPath(),store.file(pending.id,".part").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.progress(pending.id,full.bytes);store.waiting(pending.id,"synthetic interrupted receive");return store.get(pending.id);
    }
    private CheckBox findCheckBox(View view,String text) {
        if(view instanceof CheckBox && text.contentEquals(((CheckBox)view).getText()))return (CheckBox)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){CheckBox found=findCheckBox(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;
    }
    private android.widget.Button findButton(View view,String text) {
        if(view instanceof android.widget.Button && ((android.widget.Button)view).getText().toString().contains(text))return (android.widget.Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){android.widget.Button found=findButton(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;
    }
    private void endpoint(String path) throws Exception {
        Settings.configureUpload(context, "https://10.0.2.2:18769"+path, "test-fixture-only", false);
    }
    private String[] verify(UploadQueue queue,RecordingStore.Row row) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String[]> result=new AtomicReference<>();
        queue.verifyForCleanup(row,(endpoint,error)->{result.set(new String[]{endpoint,error});done.countDown();});
        assertTrue(done.await(15,TimeUnit.SECONDS));return result.get();
    }
    private SSLSocketFactory useFixtureTls() throws Exception {
        SSLSocketFactory original=HttpsURLConnection.getDefaultSSLSocketFactory();KeyStore ks=KeyStore.getInstance(KeyStore.getDefaultType());ks.load(null);
        try(InputStream ca=instrumentation.getContext().getAssets().open("test-ca.pem")){ks.setCertificateEntry("fixture",CertificateFactory.getInstance("X.509").generateCertificate(ca));}
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(ks);
        SSLContext ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());return original;
    }
    private CheckBox findCleanup(View view) {
        if(view instanceof CheckBox && ((CheckBox)view).getText().toString().equals("核验中转后自动清理豆内副本"))return (CheckBox)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){CheckBox found=findCleanup(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;
    }
    private ListView findList(View view) {
        if(view instanceof ListView)return (ListView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){ListView found=findList(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;
    }
    private boolean windowContains(String text){AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();return root!=null && !root.findAccessibilityNodeInfosByText(text).isEmpty();}
    private boolean clickDialog(String text) {
        AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();if(root==null)return false;
        for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))if(node.getText()!=null && text.contentEquals(node.getText()) && node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }
    private RecordingStore.Row audio(long fileId) throws Exception {
        return audio("1111111111111111",fileId);
    }
    private RecordingStore.Row audio(String device,long fileId) throws Exception {
        RecordingStore.Row row=store.ensure(device,fileId,8000);File raw=store.file(row.id,".opus.bin"),ogg=store.file(row.id,".ogg");
        byte[] frame=new byte[160];frame[0]=(byte)0xf8;frame[1]=(byte)0xff;frame[2]=(byte)0xfe;
        try(OutputStream out=new FileOutputStream(raw)){for(int i=0;i<50;i++)out.write(frame);}
        long duration=OggOpus.mux(raw,ogg,(int)fileId);store.ready(row.id,8000,duration,RecordingStore.hash(ogg));return store.get(row.id);
    }
    private void openSession(){
        Intent launch=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=instrumentation.startActivitySync(launch);
        Settings.prefs(context).edit().putString("address","00:11:22:33:44:55").putString("name","模拟器生命周期测试，无真实录音豆")
                .putLong("since",System.currentTimeMillis()/1000).putBoolean("enabled",true).commit();
        instrumentation.runOnMainSync(()->context.startForegroundService(new Intent(context,RecorderService.class)));
    }
    private boolean running(){
        for(ActivityManager.RunningServiceInfo info:context.getSystemService(ActivityManager.class).getRunningServices(50))
            if(info.service.getClassName().equals(RecorderService.class.getName()) && info.foreground)return true;
        return false;
    }
    private void waitFor(Callable<Boolean> assertion) throws Exception {
        long until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){if(assertion.call())return;SystemClock.sleep(100);}
        fail("timed out");
    }
    private void shell(String command) throws IOException {
        try(ParcelFileDescriptor fd=instrumentation.getUiAutomation().executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor())) {
            byte[] buffer=new byte[4096];while(in.read(buffer)!=-1){ }
        }
    }
}
