package io.github.lotusdecoder.daybookrecorder;

import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import java.io.*;
import java.security.GeneralSecurityException;
import java.util.*;
import io.github.lotusdecoder.daybookrecorder.core.*;

/** Owns the BLE link and all protocol state; never owned by an Activity. */
@SuppressLint("MissingPermission")
public final class RecorderService extends Service {
    static final String STOP="io.github.lotusdecoder.daybookrecorder.STOP", BIND="io.github.lotusdecoder.daybookrecorder.BIND",
            REFRESH="io.github.lotusdecoder.daybookrecorder.REFRESH", RETRY="io.github.lotusdecoder.daybookrecorder.RETRY",
            CLEANUP="io.github.lotusdecoder.daybookrecorder.CLEANUP", CLEANUP_CHANGED="io.github.lotusdecoder.daybookrecorder.CLEANUP_CHANGED",
            VIEW_FILES="io.github.lotusdecoder.daybookrecorder.VIEW_FILES", DOWNLOAD="io.github.lotusdecoder.daybookrecorder.DOWNLOAD",
            SYNC_CHANGED="io.github.lotusdecoder.daybookrecorder.SYNC_CHANGED",
            SWITCH_DEVICE="io.github.lotusdecoder.daybookrecorder.SWITCH_DEVICE";
    private static final String CHANNEL="recording_connection";
    private static final int NOTIFICATION=3201;
    private static final long LIVE_FALLBACK_QUIET_MILLIS=650L;
    private Handler worker;
    private RecordingStore store;
    private DeviceFilesStore deviceFiles;
    private UploadQueue uploads;
    private ConnectionSync connectionSync;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar,notifyChar;
    private DeviceCrypto crypto;
    private Protocol.Decoder decoder=new Protocol.Decoder();
    private final ArrayDeque<byte[]> tx=new ArrayDeque<>();
    private final TreeMap<Long,Protocol.Entry> inventory=new TreeMap<>();
    private final InventoryPages inventoryPages=new InventoryPages();
    private final Map<Long,Long> cooldown=new HashMap<>();
    private final Set<Long> forceZeroOffset=new HashSet<>();
    private final Set<Long> liveDegradedMidStream=new HashSet<>();
    private PowerManager.WakeLock wake;
    private ConnectivityManager connectivity;
    private long generation,writeSerial,nextReconnect,lastHeartbeat,lastInfo,lastProgress,lastRx,wakeRenewed;
    private int reconnects,mtu=23,listPage,listType=0x1b;
    private boolean ready,writing,listing,recording,paused,started,closed,deviceReportedIdle;
    private long currentId,listDeadline,requestedDownload;
    private boolean requestedRestore;
    private String device="",status="正在启动",stage="",firmware="";
    private String connectedAddress="",fallbackSelectedAddress="";
    private boolean unverifiedFallbackAddress,passiveStandby,everListed,idlePingSent;
    private BluetoothLeScanner bgScanner;
    private boolean bgScanning;
    private long lastBgScanAt,passiveOpenedAt;
    private String fallbackCandidateAddr="",fallbackCandidateAdvHash="",fallbackCandidateName="";
    private int fallbackCandidateCount;
    private Active active;
    /** Live frames kept in memory across a link loss so a reconnect only fetches what is missing. */
    private Active parked;
    /** After an early seal the bean keeps streaming the rest of that file; no new 1A/07 until it ends. */
    private long drainFileId,drainLastData;
    private boolean localDeletionStop;
    private Cleanup cleanup;
    private long cleanupQuarantinedGeneration=-1,listActiveId;
    private static final class Cleanup {
        final RecordingStore.Row row;final DeviceCleanup guard;final long generation;final boolean manual;
        DeviceCleanup.Phase persisted;
        Active content;
        boolean contentRead;
        Cleanup(RecordingStore.Row row,long generation,boolean manual) {
            this.row=row;this.generation=generation;this.manual=manual;
            long reportedMillis=0;
            if(row.readbackVerified) {
                int frameBytes=RecordingSizeUnit.AUDIO_BYTES.bytesPerFrame;
                if(!manual || row.expected!=OfflineReadback.FRAME_MILLIS || row.bytes<=frameBytes || row.bytes%frameBytes!=0
                        || row.duration!=row.bytes/frameBytes*OfflineReadback.FRAME_MILLIS)
                    throw new IllegalArgumentException("invalid readback evidence");
                reportedMillis=row.expected;
            }
            guard=new DeviceCleanup(row.device,row.fileId,row.sha,row.duration,row.state.equals("SENT")&&row.uploadedAt>0,
                    row.cleanupAcknowledged,reportedMillis,SystemClock.elapsedRealtime());
        }
    }
    private final Runnable ticker=new Runnable() {
        @Override public void run() {
            if(closed || !started)return;
            try {tick();}catch(Exception e) {problem("后台检查异常："+e.getClass().getSimpleName());}
            worker.postDelayed(this,5000);
        }
    };
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {worker.post(()->{
            String act=i.getAction();
            if(Intent.ACTION_SCREEN_ON.equals(act) || Intent.ACTION_USER_PRESENT.equals(act)) {
                if(closed || !started || ready || bgScanning)return;
                long now=SystemClock.elapsedRealtime();
                if(now-lastBgScanAt<12000L)return;
                if(gatt!=null && passiveStandby)closeGatt();
                if(gatt==null) {nextReconnect=0;connect();}
                return;
            }
            int state=i.getIntExtra(BluetoothAdapter.EXTRA_STATE,BluetoothAdapter.ERROR);
            if(state==BluetoothAdapter.STATE_OFF || state==BluetoothAdapter.STATE_TURNING_OFF) {stopBgScan();disconnect("蓝牙已关闭，等待恢复",false);}
            else if(state==BluetoothAdapter.STATE_ON) {nextReconnect=0;lastBgScanAt=0;connect();}
        });}
    };
    private final ConnectivityManager.NetworkCallback network=new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) {if(worker!=null)worker.post(()->{if(!closed){store.retryUploads();uploads.kick();if(ready)advancePipeline();}});}
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"录音豆后台连接",NotificationManager.IMPORTANCE_LOW));
        worker=new Handler(LocalRecordingDeletion.looper());
        store=new RecordingStore(this);
        uploads=new UploadQueue(this,store,()->worker.post(()->{if(!closed && started && ready)advancePipeline();}));
        deviceFiles=new DeviceFilesStore(this);
        connectionSync=new ConnectionSync(store);
        Settings.prefs(this).edit().putBoolean("ble_ready",false).apply();
        RuntimeTelemetry.lastStableDurationMs=Settings.prefs(this).getLong("last_stable_ms",0L);
        BluetoothManager manager=getSystemService(BluetoothManager.class);adapter=manager==null?null:manager.getAdapter();
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,getPackageName()+":ble-session");wake.setReferenceCounted(false);
        IntentFilter filter=new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(bluetoothState,filter,Context.RECEIVER_EXPORTED);else registerReceiver(bluetoothState,filter);
        connectivity=getSystemService(ConnectivityManager.class);connectivity.registerDefaultNetworkCallback(network);
        worker.post(()->{clearCleanupCache();store.recover();LocalRecordingDeletion.attach(this);uploads.reconcileLocal();});
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null?null:intent.getAction();
        if(STOP.equals(action)) {Settings.prefs(this).edit().putBoolean("enabled",false).commit();stopSelf();return START_NOT_STICKY;}
        if(!Settings.prefs(this).getBoolean("enabled",false) || !Settings.permitted(this)) {stopSelf();return START_NOT_STICKY;}
        try {
            if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTIFICATION,notification());
        }catch(RuntimeException e) {
            Settings.prefs(this).edit().putString("status","后台启动被系统拒绝，请打开 App 重新开启").apply();stopSelf();return START_NOT_STICKY;
        }
        worker.post(()->{
            if(closed)return;
            if(!started) {started=true;EventLog.add(this,"后台服务启动");renewWake();connect();worker.post(ticker);}
            if(BIND.equals(action) && ready) {cancelCleanup("设备正在重新绑定，清理结果待检查");send(Protocol.command(0x0b,0x87,(byte)1));setStatus("已申请设备绑定；如录音豆提示，请按实体键确认");}
            if(SWITCH_DEVICE.equals(action)) {
                stopBgScan();cancelCleanup("切换录音豆，暂停清理核对");closeGatt();abandon("切换设备，稍后补收");
                reconnects=0;lastBgScanAt=0;fallbackSelectedAddress="";unverifiedFallbackAddress=false;nextReconnect=0;forceZeroOffset.clear();liveDegradedMidStream.clear();
                worker.postDelayed(this::connect,450);
            }
            if(REFRESH.equals(action)) {
                if(ready){send(Protocol.command(1,1));requestInventory();}
                else {
                    stopBgScan();
                    if(gatt!=null && passiveStandby)closeGatt();
                    if(gatt==null) {
                        if(reconnects==0)reconnects=2; // User tapped manual reconnect: allow immediate scan-assisted discovery
                        lastBgScanAt=0;nextReconnect=0;connect();
                    }
                }
            }
            if(RETRY.equals(action)) {
                store.retryUploads();store.retryDownloads();store.resetRetryableCleanups(device);
                cooldown.clear();forceZeroOffset.clear();liveDegradedMidStream.clear();uploads.kick();
                if(ready){requestInventory();advancePipeline();}
            }
            if(CLEANUP_CHANGED.equals(action) || SYNC_CHANGED.equals(action)) {
                if(cleanup!=null && !cleanup.manual && !automaticCleanupAllowed(cleanup.row))cancelCleanup("自动同步或清理已关闭");
                if(SYNC_CHANGED.equals(action) && syncEnabled()){connectionSync.connected();if(ready)requestInventory();}
                if(ready && cleanup==null)advancePipeline();
            }
            if(CLEANUP.equals(action))beginCleanup(store.get(intent.getStringExtra("recording_id")),true);
            if(VIEW_FILES.equals(action)) {
                if(!ready || crypto==null || !crypto.ready())fileAction("尚未连接就绪，连接成功后会自动读取列表");
                else if(cleanup!=null)fileAction("正在核对清理，完成后刷新豆内列表");
                else {fileAction("正在刷新豆内文件列表");send(Protocol.command(1,1));requestInventory();}
            }
            if(DOWNLOAD.equals(action))requestDownload(intent.getStringExtra("device"),intent.getLongExtra("file_id",0),intent.getBooleanExtra("restore_deleted",false));
        });
        return START_STICKY;
    }
    @Override public IBinder onBind(Intent i) {return null;}
    @Override public void onTaskRemoved(Intent rootIntent) {EventLog.add(this,"界面任务移除；后台服务继续运行");super.onTaskRemoved(rootIntent);}
    @Override public void onDestroy() {
        try {unregisterReceiver(bluetoothState);}catch(RuntimeException ignored) { }
        try {connectivity.unregisterNetworkCallback(network);}catch(RuntimeException ignored) { }
        uploads.close();
        worker.post(()->{
            closed=true;started=false;stopBgScan();worker.removeCallbacksAndMessages(null);cancelCleanup("后台服务已停止");closeGatt();abandon("接收暂停，重新开启后补收");dropParked("后台服务停止，暂存的实时分片改为稍后完整补收");
            if(wake.isHeld())wake.release();
            long wallNow=System.currentTimeMillis();
            android.content.SharedPreferences.Editor stopEdit=Settings.prefs(this).edit().putLong("heartbeat",0).putString("status","后台服务已停止");
            if(RuntimeTelemetry.connectedSinceWallMs>0L)stopEdit.putLong("last_online_at",wallNow);
            stopEdit.apply();
            RuntimeTelemetry.resetServiceStopped();
            EventLog.add(this,"后台服务停止");LocalRecordingDeletion.detach(this);store.close();
        });
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
    private void renewWake() {wake.acquire(10*60*1000L);wakeRenewed=SystemClock.elapsedRealtime();}
    private void tick() {
        long now=SystemClock.elapsedRealtime();
        if(now-wakeRenewed>5*60*1000L || !wake.isHeld())renewWake();
        if(now-lastHeartbeat>15000) {
            lastHeartbeat=now;
            android.content.SharedPreferences.Editor hb=Settings.prefs(this).edit().putLong("heartbeat",System.currentTimeMillis()).putString("status",status);
            if(ready && crypto!=null && crypto.ready()) {hb.putLong("last_online_at",System.currentTimeMillis());safeReadRssi();}
            hb.apply();uploads.kick();
        }
        if(!Settings.permitted(this)) {stopBgScan();setStatus("蓝牙权限已撤销，请重新授权");stopSelf();return;}
        if(adapter==null || !adapter.isEnabled()) {stopBgScan();if(gatt!=null)disconnect("蓝牙已关闭，等待恢复",false);return;}
        if(bgScanning)return;
        if(gatt!=null && passiveStandby && !ready) {
            if(now-passiveOpenedAt>=45000L) {
                closeGatt();nextReconnect=now+700L;
                setStatus("休眠守候周期结束，准备重新扫描录音豆");
            }
            return;
        }
        if(gatt==null && now>=nextReconnect) {connect();return;}
        if(!ready)return;
        if(cleanup!=null) {
            cleanup.guard.timeout(now);driveCleanup(DeviceCleanup.Action.NONE);
            if(cleanup!=null){if(now-lastInfo>20000){lastInfo=now;send(Protocol.command(1,1));}return;}
        }
        if(cleanupQuarantinedGeneration==generation && active==null && !recording) {
            disconnect("清理结果待检查，重建连接后继续接收",true);return;
        }
        if(listing && now>listDeadline) {
            abortInventory("豆内列表读取超时；保留上次成功读取的列表");
            if(listType==0x1b) {listType=0x1a;requestInventory();}
            else problem("录音列表无响应；固件可能不兼容，可尝试设备绑定");
        }
        if(recording && crypto!=null && crypto.ready())safeReadRssi();
        if(recording && !paused && active==null && currentId>0 && RuntimeTelemetry.liveDeferredWeakSignal
                && !liveDegradedMidStream.contains(currentId)
                && ConnectionHud.canRecoverDeferredLive(RuntimeTelemetry.rssiDbm)) {
            EventLog.add(this,"蓝牙信号已恢复稳定（"+RuntimeTelemetry.rssiDbm+" dBm），切入实时接收 "+currentId);
            startOrDeferLiveFile(currentId);
        }
        if(active!=null && active.live && recording && !paused && now-active.lastData>15000) {
            degradeLiveToBean(active,"实时流响应变慢，已暂存实时分片，结束后只补缺失部分");
            return;
        }
        if(active!=null && !paused && now-active.lastData>45000) {disconnect("录音接收超时，重新连接后补收",true);return;}
        if(active==null && now-lastRx>215000) {disconnect("设备长时间无响应，正在自动重连",true);return;}
        if(active==null && now-lastRx>180000 && !idlePingSent) {idlePingSent=true;lastInfo=now;send(Protocol.command(1,1));}
        if(now-lastInfo>90000) {lastInfo=now;send(Protocol.command(1,1));if(recording || !everListed)requestInventory();}
        if(active==null && !recording && !listing) {
            advancePipeline();
        }
    }
    private void connect() {
        if(closed || !started || gatt!=null || bgScanning || !Settings.permitted(this))return;
        if(adapter==null || !adapter.isEnabled()){setStatus("等待打开手机蓝牙");return;}
        String address=Settings.prefs(this).getString("address","");
        if(!BluetoothAdapter.checkBluetoothAddress(address)) {setStatus("请先选择录音豆");return;}
        device=Settings.activeDeviceId(this);
        long now=SystemClock.elapsedRealtime();
        if(reconnects>=2 && Settings.scanPermitted(this) && now-lastBgScanAt>=24000L) {
            if(startBgScan(address))return;
        }
        openGatt(address,false);
    }
    private void openGatt(String address,boolean passive) {
        if(closed || !started || gatt!=null || !Settings.permitted(this))return;
        if(adapter==null || !adapter.isEnabled())return;
        device=Settings.activeDeviceId(this);
        connectedAddress=address;passiveStandby=passive;passiveOpenedAt=SystemClock.elapsedRealtime();
        long gen=++generation;stage="CONNECT";mtu=23;idlePingSent=false;everListed=false;
        RuntimeTelemetry.handshakeStep=1;RuntimeTelemetry.reconnectCount=reconnects;
        if(!passive)RuntimeTelemetry.nextReconnectWallMs=0L;
        inventory.clear();listActiveId=0;listType=0x1b;firmware="";deviceReportedIdle=false;
        try {
            crypto=new DeviceCrypto();decoder=new Protocol.Decoder();
            if(passive) {
                long passiveWindow=45000L;
                RuntimeTelemetry.reconnectDelayMs=passiveWindow;
                RuntimeTelemetry.nextReconnectWallMs=System.currentTimeMillis()+passiveWindow;
                setStatus("录音豆休眠或暂离，后台守候唤醒中");
            } else {
                setStatus(reconnects==0?"正在连接录音豆":"正在重连录音豆（"+reconnects+"）");
            }
            gatt=adapter.getRemoteDevice(address).connectGatt(this,passive,callback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null)throw new IllegalStateException("no GATT");
            if(!passive) {
                long timeoutMs=reconnects==0?25000L:16000L;
                worker.postDelayed(()->{if(generation==gen && !ready)disconnect("连接未响应，切换扫描重连",true);},timeoutMs);
            }
        }catch(Exception e) {disconnect("无法建立蓝牙连接："+e.getClass().getSimpleName(),true);}
    }
    private boolean startBgScan(String currentAddress) {
        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        if(scanner==null)return false;
        stopBgScan();
        fallbackCandidateAddr="";fallbackCandidateAdvHash="";fallbackCandidateName="";fallbackCandidateCount=0;
        List<ScanFilter> filters=new ArrayList<>();
        filters.add(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(Protocol.SERVICE)).build());
        String savedName=Settings.prefs(this).getString("name","");
        if(!savedName.isEmpty())filters.add(new ScanFilter.Builder().setDeviceName(savedName).build());
        if(BluetoothAdapter.checkBluetoothAddress(currentAddress)) {
            try{filters.add(new ScanFilter.Builder().setDeviceAddress(currentAddress).build());}catch(IllegalArgumentException ignored){}
        }
        PowerManager pm=getSystemService(PowerManager.class);
        boolean interactive=pm!=null && pm.isInteractive();
        int scanMode=(reconnects<=4 || interactive)?ScanSettings.SCAN_MODE_BALANCED:ScanSettings.SCAN_MODE_LOW_POWER;
        try {
            bgScanner=scanner;bgScanning=true;lastBgScanAt=SystemClock.elapsedRealtime();
            RuntimeTelemetry.backgroundScanning=true;RuntimeTelemetry.reconnectCount=reconnects;
            RuntimeTelemetry.reconnectDelayMs=9000L;RuntimeTelemetry.nextReconnectWallMs=System.currentTimeMillis()+9000L;
            setStatus("正在自动扫描录音豆广播…");
            long scanGen=++generation;
            scanner.startScan(filters,new ScanSettings.Builder().setScanMode(scanMode).build(),bgScanCallback);
            worker.postDelayed(()->{
                if(closed || !started || !bgScanning || generation!=scanGen)return;
                stopBgScan();
                if(fallbackCandidateCount==1 && BluetoothAdapter.checkBluetoothAddress(fallbackCandidateAddr)
                        && !Settings.prefs(this).getString("serial_hash","").isEmpty()) {
                    fallbackSelectedAddress=currentAddress;unverifiedFallbackAddress=true;
                    // Keep the selected address on disk until the candidate proves its identity.
                    EventLog.add(this,"发现候选录音豆广播，正在核验序列号重连");
                    worker.postDelayed(()->{
                        if(generation==scanGen && currentAddress.equals(Settings.prefs(this).getString("address","")))
                            openGatt(fallbackCandidateAddr,false);
                    },350);
                } else {
                    // Bean is likely idle/asleep; arm passive autoConnect standby until next scan cycle.
                    worker.postDelayed(()->openGatt(currentAddress,true),400);
                }
            },9000L);
            return true;
        }catch(RuntimeException e) {
            stopBgScan();
            return false;
        }
    }
    private void stopBgScan() {
        RuntimeTelemetry.backgroundScanning=false;
        if(bgScanner!=null && bgScanning) {
            try{bgScanner.stopScan(bgScanCallback);}catch(RuntimeException ignored){}
        }
        bgScanning=false;bgScanner=null;
    }
    private final ScanCallback bgScanCallback=new ScanCallback() {
        @Override public void onScanResult(int callbackType,ScanResult result) {
            worker.post(()->{
                if(closed || !started || !bgScanning || gatt!=null)return;
                BluetoothDevice d=result.getDevice();if(d==null)return;
                String candAddr=d.getAddress();if(!BluetoothAdapter.checkBluetoothAddress(candAddr))return;
                ScanRecord rec=result.getScanRecord();
                String candAdvHash=DeviceIdentity.extractAdvIdentityHash(rec==null?null:rec.getBytes());
                String candName=rec==null?null:rec.getDeviceName();
                if(candName==null)candName=d.getName();
                boolean hasUuid=rec!=null && rec.getServiceUuids()!=null
                        && rec.getServiceUuids().contains(new ParcelUuid(Protocol.SERVICE));
                SharedPreferences p=Settings.prefs(RecorderService.this);
                String savedAddr=p.getString("address","");
                String savedAdvHash=p.getString("adv_identity_hash","");
                String savedName=p.getString("name","");
                DeviceIdentity.Match match=DeviceIdentity.evaluateCandidate(
                        candAddr,candAdvHash,candName,hasUuid,savedAddr,savedAdvHash,savedName);
                if(match==DeviceIdentity.Match.EXACT_IDENTITY || match==DeviceIdentity.Match.SAME_ADDRESS) {
                    stopBgScan();
                    String stable=Settings.activeDeviceId(RecorderService.this);
                    SharedPreferences.Editor ed=p.edit().putString("storage_device",stable).putString("storage_bound_address",candAddr);
                    if(!candAdvHash.isEmpty())ed.putString("adv_identity_hash",candAdvHash);
                    if(!candAddr.equalsIgnoreCase(savedAddr)) {
                        ed.putString("address",candAddr);
                        EventLog.add(RecorderService.this,"自动扫描跟进录音豆新蓝牙地址，正在重连");
                    }
                    unverifiedFallbackAddress=false;
                    ed.apply();
                    worker.postDelayed(()->openGatt(candAddr,false),350);
                } else if(match==DeviceIdentity.Match.CANDIDATE_FALLBACK) {
                    if(!candAddr.equalsIgnoreCase(fallbackCandidateAddr)) {
                        fallbackCandidateCount++;
                        fallbackCandidateAddr=candAddr;
                        fallbackCandidateAdvHash=candAdvHash;
                        fallbackCandidateName=candName==null?"":candName;
                    }
                }
            });
        }
        @Override public void onScanFailed(int errorCode) {
            worker.post(()->{
                if(!bgScanning)return;
                stopBgScan();
                String addr=Settings.prefs(RecorderService.this).getString("address","");
                if(BluetoothAdapter.checkBluetoothAddress(addr))worker.postDelayed(()->openGatt(addr,false),400);
            });
        }
    };
    private void disconnect(String message,boolean backoff) {
        stopBgScan();
        if(RuntimeTelemetry.connectedSinceWallMs>0L) {
            long nowWall=System.currentTimeMillis();
            long span=Math.max(1000L,nowWall-RuntimeTelemetry.connectedSinceWallMs);
            RuntimeTelemetry.lastStableDurationMs=span;
            Settings.prefs(this).edit().putLong("last_stable_ms",span).putLong("last_online_at",nowWall).apply();
        }
        fallbackSelectedAddress="";unverifiedFallbackAddress=false;
        RuntimeTelemetry.connectedSinceWallMs=0L;RuntimeTelemetry.rssiDbm=Integer.MIN_VALUE;
        RuntimeTelemetry.bleTransfer=null;RuntimeTelemetry.beanRecording=false;RuntimeTelemetry.beanPaused=false;RuntimeTelemetry.liveDeferredWeakSignal=false;
        RuntimeTelemetry.cleaningRecordingId="";RuntimeTelemetry.cleaningFileId=0L;
        liveDegradedMidStream.clear();
        cancelCleanup("蓝牙连接中断，清理结果待检查");
        if(!localDeletionStop)parkTransfer("连接中断");closeGatt();abandon("连接中断，等待补收");recording=false;paused=false;currentId=0;listing=false;
        if(backoff)reconnects++;
        long delay=reconnects<=1?2000L:reconnects==2?3500L:Math.min(35000L,4000L*(1L<<Math.min(4,reconnects-2)));
        nextReconnect=SystemClock.elapsedRealtime()+delay;
        RuntimeTelemetry.reconnectCount=reconnects;RuntimeTelemetry.reconnectDelayMs=delay;
        RuntimeTelemetry.nextReconnectWallMs=System.currentTimeMillis()+delay;
        setStatus(message);EventLog.add(this,message);
    }
    /** Runs on the same Looper as receive/cleanup callbacks and local deletion. */
    void stopForLocalDeletion(String id) {
        if(requestedDownload>0 && RecordingStore.id(device,requestedDownload).equals(id)) {
            requestedDownload=0;requestedRestore=false;
        }
        if(parked!=null && parked.id.equals(id)) {Active p=parked;parked=null;try{p.assembler.close();}catch(IOException ignored){ }}
        if((active!=null && active.id.equals(id)) || (cleanup!=null && cleanup.row.id.equals(id))) {
            // Clear queued protocol writes and close the file before unlinking. The bean's
            // physical recording is left alone; a later connection respects the tombstone.
            localDeletionStop=true;
            try{disconnect("已停止此录音的手机接收和核验，正在删除手机副本",false);}finally{localDeletionStop=false;}
        }
    }
    private void closeGatt() {
        deviceFiles.unavailable(device,"蓝牙未连接；显示上次成功读取的列表");
        Settings.prefs(this).edit().putBoolean("ble_ready",false).apply();
        RuntimeTelemetry.lastStableDurationMs=Settings.prefs(this).getLong("last_stable_ms",0L);requestedDownload=0;
        generation++;ready=false;passiveStandby=false;writing=false;writeSerial++;tx.clear();liveDegradedMidStream.clear();drainFileId=0;
        RuntimeTelemetry.liveDeferredWeakSignal=false;stage="";writeChar=null;notifyChar=null;crypto=null;
        BluetoothGatt previous=gatt;gatt=null;
        if(previous!=null) {try {previous.disconnect();}catch(RuntimeException ignored) { }try {previous.close();}catch(RuntimeException ignored) { }}
    }
    private void post(BluetoothGatt source,Runnable task) {worker.post(()->{if(!closed && source==gatt) {try {task.run();}catch(Exception e) {disconnect("蓝牙处理失败："+e.getClass().getSimpleName(),true);}}});}
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state) {post(g,()->{
            if(status!=BluetoothGatt.GATT_SUCCESS || state==BluetoothProfile.STATE_DISCONNECTED) {disconnect("蓝牙断开（"+status+"），等待重连",true);return;}
            if(state==BluetoothProfile.STATE_CONNECTED) {
                passiveStandby=false;RuntimeTelemetry.handshakeStep=1;RuntimeTelemetry.nextReconnectWallMs=0L;
                stage="MTU";long gen=generation;
                worker.postDelayed(()->{if(gen==generation && (crypto==null || !crypto.ready()))disconnect("加密初始化超时，重新扫描重连",true);},20000L);
                if(!g.requestMtu(247))discover();
                worker.postDelayed(()->{if(gen==generation && stage.equals("MTU"))discover();},2500);
            }
        });}
        @Override public void onMtuChanged(BluetoothGatt g,int value,int status) {post(g,()->{if(status==BluetoothGatt.GATT_SUCCESS)mtu=value;if(stage.equals("MTU"))discover();});}
        @Override public void onServicesDiscovered(BluetoothGatt g,int status) {post(g,()->{
            if(status!=BluetoothGatt.GATT_SUCCESS) {disconnect("无法发现设备服务",true);return;}
            for(BluetoothGattService s:g.getServices()) {
                if(writeChar==null)writeChar=s.getCharacteristic(Protocol.WRITE);
                if(notifyChar==null)notifyChar=s.getCharacteristic(Protocol.NOTIFY);
            }
            if(writeChar==null || notifyChar==null || (writeChar.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))==0)
                {disconnect("设备没有 D3200 通信特征，请核对设备型号",true);return;}
            BluetoothGattDescriptor cccd=notifyChar.getDescriptor(Protocol.CCCD);
            if(cccd==null || !g.setCharacteristicNotification(notifyChar,true)) {disconnect("无法订阅录音豆通知",true);return;}
            stage="CCCD";
            byte[] value=(notifyChar.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
            cccd.setValue(value);if(!g.writeDescriptor(cccd))disconnect("通知订阅失败",true);
        });}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status) {post(g,()->{
            if(!Protocol.CCCD.equals(d.getUuid()) || !stage.equals("CCCD"))return;
            if(status!=BluetoothGatt.GATT_SUCCESS){disconnect("通知订阅被设备拒绝",true);return;}
            ready=true;stage="READY";lastRx=SystemClock.elapsedRealtime();lastInfo=lastRx;
            setStatus("蓝牙已连接，正在协商加密会话");
            send(Protocol.command(1,1));
            send(Protocol.syncTime(System.currentTimeMillis()/1000,TimeZone.getDefault().getOffset(System.currentTimeMillis())/3600000));
            beginHandshake();
        });}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int result) {post(g,()->{
            if(!writing || writeChar==null || (writeChar.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0)return;
            if(result!=BluetoothGatt.GATT_SUCCESS){disconnect("蓝牙命令发送失败（"+result+"）",true);return;}
            writing=false;writeSerial++;pump();
        });}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value) {receive(g,c.getUuid(),value.clone());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c) {
            // Android 13+ delivers the overload above; legacy devices use this one.
            if(Build.VERSION.SDK_INT<33) {byte[] v=c.getValue();if(v!=null)receive(g,c.getUuid(),v.clone());}
        }
        @Override public void onReadRemoteRssi(BluetoothGatt g,int rssi,int status) {
            if(status==BluetoothGatt.GATT_SUCCESS)RuntimeTelemetry.rssiDbm=rssi;
        }
    };
    private void safeReadRssi() {if(gatt!=null)try{gatt.readRemoteRssi();}catch(RuntimeException ignored){ }}
    private void discover() {if(gatt==null)return;stage="DISCOVER";if(!gatt.discoverServices())disconnect("服务发现启动失败",true);}
    private void send(byte[] frame) {
        if(!ready || gatt==null)return;
        int chunk=Math.max(20,mtu-3);for(int i=0;i<frame.length;i+=chunk)tx.add(Arrays.copyOfRange(frame,i,Math.min(frame.length,i+chunk)));
        if(tx.size()>100){disconnect("命令队列异常，重新连接",true);return;}pump();
    }
    private void pump() {
        if(writing || !ready || gatt==null || tx.isEmpty())return;
        byte[] value=tx.remove();boolean response=(writeChar.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0;
        writing=true;long serial=++writeSerial,gen=generation;
        writeChar.setWriteType(response?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        writeChar.setValue(value);
        if(!gatt.writeCharacteristic(writeChar)){disconnect("蓝牙写入未启动",true);return;}
        worker.postDelayed(()->{
            if(generation!=gen || serial!=writeSerial || !writing)return;
            if(response)disconnect("蓝牙命令超时",true);else {writing=false;writeSerial++;pump();}
        },response?10000:40);
    }
    private void beginHandshake() {
        if(crypto==null)return;RuntimeTelemetry.handshakeStep=2;send(Protocol.command(0x2e,1,crypto.publicKey()));long gen=generation;
        worker.postDelayed(()->{if(gen==generation && crypto!=null && !crypto.ready())problem("加密协商未成功，可尝试设备绑定；当前固件可能不兼容");},12000);
    }
    private void receive(BluetoothGatt source,UUID characteristic,byte[] value) {
        post(source,()->{
            if(!Protocol.NOTIFY.equals(characteristic))return;
            lastRx=SystemClock.elapsedRealtime();idlePingSent=false;
            int rejectedBefore=decoder.rejected;List<Protocol.Packet> packets=decoder.feed(value);
            if(decoder.rejected!=rejectedBefore){if(listing)abortInventory("列表数据校验失败，请重新刷新");cancelCleanup("蓝牙数据校验失败，清理结果待检查");}
            for(Protocol.Packet packet:packets) {
                try {packet(packet);}catch(Exception e){
                    EventLog.add(this,String.format(Locale.ROOT,"录音处理异常 command=%02X/%02X payload=%d exception=%s",packet.type,packet.id,packet.payload.length,e.getClass().getSimpleName()));
                    if(listing)abortInventory("列表数据不完整，请重新刷新");
                    if(cleanup!=null && cleanup.content!=null){cancelCleanup("豆内复读数据校验失败，已保留豆内原件");continue;}
                    cancelCleanup("设备返回的数据不完整，清理结果待检查");
                    if(active!=null && !active.live && active.readback==null && active.initialBytes>0 && !forceZeroOffset.contains(active.fileId)) {
                        fallbackActiveToZero("续传分片校验不一致，自动从头补收");
                        continue;
                    }
                    failActive("音频校验失败，等待重新补收");
                }
            }
        });
    }
    private void packet(Protocol.Packet p) throws Exception {
        if (unverifiedFallbackAddress && !(p.id == 1 && (p.type == 1 || p.type == 0x2e))) return;
        byte[] b=p.payload;
        if(p.type==0x1a && p.id==0x10) {
            if(cleanup!=null)driveCleanup(cleanup.guard.acknowledged(p.ok(),SystemClock.elapsedRealtime()));
            return;
        }
        if(!p.ok()) {
            if(cleanup!=null){if(listing)abortInventory("设备拒绝读取列表");cancelCleanup("设备拒绝清理核对指令");return;}
            if((p.type==0x1a||p.type==0x1b) && p.id==0x0e) {
                abortInventory("设备拒绝读取列表；保留上次成功读取的列表");
                if(p.type==0x1b) {listType=0x1a;requestInventory();return;}
            }
            if((p.type==0x1a||p.type==0x1b) && p.id==7 && active!=null && active.backfill && active.backfillPhase==LiveBackfill.Phase.TAIL) {
                requestBackfillHead(active);return;
            }
            if((p.type==0x1a||p.type==0x1b) && p.id==7 && active!=null && !active.live && active.readback==null
                    && active.resumedOffset>0 && !forceZeroOffset.contains(active.fileId)) {
                fallbackActiveToZero("设备不支持偏移续传，自动从头补收");
                return;
            }
            problem(String.format(Locale.ROOT,"设备拒绝指令 %02X/%02X（%02X），请检查绑定及固件",p.type,p.id,p.status));
            if((p.type==0x1a||p.type==0x1b) && p.id==7)failActive("设备拒绝读取录音");return;
        }
        if(p.type==0x2e && p.id==1) {
            crypto.handshake(b);
            if(unverifiedFallbackAddress) {
                send(Protocol.command(1,1));
                return;
            }
            completeSessionReady();return;
        }
        if(p.type==0x0b && (p.id==0x87||p.id==0x88)) {setStatus(p.id==0x88?"设备绑定已确认":"绑定请求已接受，等待设备确认");beginHandshake();return;}
        if(p.type==1 && p.id==1) {
            String obsSerial=DeviceIdentity.extractSerialHash(b);
            SharedPreferences sp=Settings.prefs(this);
            if (unverifiedFallbackAddress && (obsSerial.isEmpty()
                    || !obsSerial.equals(sp.getString("serial_hash", ""))
                    || !fallbackSelectedAddress.equals(sp.getString("address", "")))) {
                disconnect("无法确认是已选择的录音豆，请在手机上重新选择",true);
                return;
            }
            if(!obsSerial.isEmpty()) {
                String savedSerial=sp.getString("serial_hash","");
                if(!savedSerial.isEmpty() && !savedSerial.equals(obsSerial)) {
                    EventLog.add(this,"跳过序列号不匹配的周边录音豆");
                    disconnect("周边存在另一台录音豆，继续寻找原绑定设备",true);
                    return;
                }
                String serialOwnerKey="serial_device_"+obsSerial;
                String anchoredDevice=sp.getString(serialOwnerKey,"");
                if(!anchoredDevice.isEmpty() && !anchoredDevice.equals(device)) {
                    device=anchoredDevice;
                    EventLog.add(this,"硬件序列号匹配既有录音豆，复用唯一设备标识 "+device);
                }
                SharedPreferences.Editor idEd=sp.edit().putString("serial_hash",obsSerial);
                if(!device.isEmpty()) {
                    idEd.putString("storage_device",device).putString(serialOwnerKey,device);
                    if(ready)idEd.putString("ble_device",device);
                }
                if(!connectedAddress.isEmpty())idEd.putString("storage_bound_address",connectedAddress);
                if(unverifiedFallbackAddress) {
                    idEd.putString("address", connectedAddress);
                    if(!fallbackCandidateAdvHash.isEmpty())idEd.putString("adv_identity_hash",fallbackCandidateAdvHash);
                }
                idEd.apply();
                fallbackSelectedAddress="";
                if(unverifiedFallbackAddress) {
                    unverifiedFallbackAddress=false;
                    if(crypto!=null && crypto.ready())completeSessionReady();
                }
            }
            if(b.length>=8)firmware=new String(b,3,5,java.nio.charset.StandardCharsets.US_ASCII).replace("\0","").trim();
            if(b.length>=2) {
                int battery=b[1]&127;if(battery<=9)battery=(battery+1)*10;
                Settings.prefs(this).edit().putString("firmware",firmware).putInt("battery",Math.min(100,battery)).apply();
            }
            if(b.length>=51)applyRecordState(b[50]&255);
            if(cleanup!=null)driveCleanup(cleanup.guard.deviceInfo(device,b.length>=51?b[50]&255:-1,SystemClock.elapsedRealtime()));
            return;
        }
        if(p.type==1 && p.id==3 && b.length>0) {int battery=b[0]&127;Settings.prefs(this).edit().putInt("battery",battery<=9?(battery+1)*10:Math.min(100,battery)).apply();return;}
        if(p.type==0x18 && p.id==0x82 && b.length>0) {applyRecordState(b[0]&255);return;}
        if(p.type!=0x1a && p.type!=0x1b)return;
        if(p.id==6 && b.length>=4) {
            cancelCleanup("新录音优先，暂缓豆内清理");
            long id=Protocol.u32(b,0);if(id==0)return;currentId=id;recording=true;paused=false;deviceReportedIdle=false;
            RuntimeTelemetry.beanRecording=true;RuntimeTelemetry.beanPaused=false;
            safeReadRssi();
            if(active!=null && active.fileId!=id)abandon("新录音优先，旧录音稍后补收");
            if(crypto!=null && crypto.ready())startOrDeferLiveFile(id);
            return;
        }
        if(p.id==0x0e) {if(p.type==listType)fileList(new Protocol.FileList(b,p.type==0x1b));return;}
        if(active==null && drainFileId!=0 && (p.id==7 || p.id==8 || p.id==0x12 || p.id==0x0a)) {
            drainLastData=SystemClock.elapsedRealtime();
            if(p.id==0x0a && (b.length<9 || LiveBackfill.endFileId(b)==drainFileId))endDrain("豆内剩余分片已传完");
            return;
        }
        Cleanup attempt=cleanup;
        boolean reading=attempt!=null && attempt.content!=null;
        if(attempt!=null && attempt.contentRead && !attempt.guard.sent() && (p.id==7 || p.id==8 || p.id==0x12)) {
            cancelCleanup("复读结束后又收到音频数据，请重新核对");return;
        }
        Active a=reading?attempt.content:active;if(a==null)return;
        if(p.id==7) {
            if(reading && (b.length<8 || Protocol.u32(b,0)!=a.fileId || a.key!=null)) {
                cancelCleanup("豆内复读文件头与所选录音不一致，已保留豆内原件");return;
            }
            if(b.length<8 || Protocol.u32(b,0)!=a.fileId)return;
            DeviceCrypto.FileKey headerKey;
            try{headerKey=crypto.fileKey(b,a.fileId);}
            catch(GeneralSecurityException e) {
                // A 1A/07 repeated mid-transfer is acknowledged with a header that does not verify while the
                // bean keeps streaming under the previous key.
                if(!reading && a.key!=null){EventLog.add(this,"忽略传输中重复请求返回的文件头 id="+a.fileId);return;}
                if(!reading && a.backfill){retryBackfillRequest(a);return;}
                throw e;
            }
            a.key=headerKey;long size=Protocol.u32(b,4);
            if(reading && size!=a.sizeUnit.bytesPerFrame && size!=attempt.row.bytes/RecordingSizeUnit.AUDIO_BYTES.bytesPerFrame*a.sizeUnit.bytesPerFrame) {
                cancelCleanup("本次豆内文件头与已备份录音不符，已保留豆内原件");return;
            }
            if(!reading && !a.live && a.readback==null && a.assembler.savedHeadSize()>0 && a.assembler.savedHeadSize()!=size) {
                if(a.resumedOffset>0) {
                    fallbackActiveToZero("豆内文件头已变化，自动从头补收");
                    return;
                }
                a.assembler.reset();
                a.initialBytes=0L;
            }
            a.headSize=size;
            if(!a.live && size>0)a.expected=size;
            if(reading){attempt.guard.contentActivity(SystemClock.elapsedRealtime());return;}
            EventLog.add(this,"文件头 id="+a.fileId+" mode="+(a.live?"live":"offline")+" headSize="+size+" listSize="+a.listSize+" sizeUnit="+a.sizeUnit+" offset="+a.resumedOffset);
            a.lastData=SystemClock.elapsedRealtime();
            if(a.backfill && size>0 && decoder.rejected==a.rejectedAtStart && a.assembler.complete(size,a.sizeUnit)){sealBackfillEarly(a);return;}
            setStatus(a.live?"录音中，正在接收原音":a.backfill?"正在从豆内补齐实时流缺失的片段":a.initialBytes>0?"正在断点续收完整录音":"正在补收完整录音");return;
        }
        if(p.id==8||p.id==0x12) {
            if(reading && (a.key==null || b.length<165)) {cancelCleanup("豆内复读分片缺少有效文件头或音频，已保留豆内原件");return;}
            if(a.key==null)return; // No ciphertext fallback, and ignore stale data before the matching header.
            if(b.length%166!=0 && b.length%166!=165)throw new IOException("invalid slice payload");
            for(int o=0;o+165<=b.length;o+=166) {
                long seq=Protocol.u32(b,o);
                if(reading && seq>=attempt.row.bytes/RecordingSizeUnit.AUDIO_BYTES.bytesPerFrame) {
                    cancelCleanup("豆内原音超过已备份长度，已保留豆内原件");return;
                }
                byte[] decrypted=a.key.decrypt(seq,Arrays.copyOfRange(b,o+5,o+165));
                if(!a.assembler.add(seq,decrypted))a.duplicates++;
                a.sliceFlags.add(b[o+4]&255);
                if((b[o+4]&255)==LiveBackfill.FLAG_HIGHLIGHT)a.highlights.add(seq);
            }
            a.lastData=SystemClock.elapsedRealtime();
            a.lastSliceAt=a.lastData;
            a.end.data(a.lastData);
            if(reading)attempt.guard.contentActivity(a.lastData);
            else {
                long expBytes=a.headSize>a.sizeUnit.bytesPerFrame?(a.headSize/a.sizeUnit.bytesPerFrame)*160L
                        :(a.listSize>OfflineReadback.FRAME_MILLIS?(a.listSize/OfflineReadback.FRAME_MILLIS)*160L:0L);
                // Live seq numbers are absolute, so elapsed audio is (last+1) frames although the head is never streamed.
                long durMs=a.live?LiveBackfill.receivedDurationMs(a.assembler):a.listSize>OfflineReadback.FRAME_MILLIS?a.listSize:a.assembler.frames()*20L;
                double elapsed=Math.max(0.05,(a.lastData-a.startedAt)/1000.0);
                long deltaBytes=Math.max(0L,a.assembler.bytes()-a.initialBytes);
                RuntimeTelemetry.bleTransfer=new RuntimeTelemetry.Transfer(a.id,a.fileId,false,a.live,false,
                        a.assembler.bytes(),expBytes,durMs,deltaBytes/elapsed,a.initialBytes>0);
                if(a.lastData-lastProgress>1000) {
                    if(getFilesDir().getUsableSpace()<16L*1024*1024) {failActive("手机存储空间不足，已暂停接收");return;}
                    lastProgress=a.lastData;store.progress(a.id,a.assembler.bytes());
                    if(a.readback==null && decoder.rejected==a.rejectedAtStart && (a.live || a.headSize>0)) {
                        try{a.assembler.checkpoint(a.live?0L:a.headSize);}catch(IOException ignored){ }
                    }
                    setStatus((a.live?"录音中，已接收 ":a.initialBytes>0?"断点续收中，已接收 ":"补收中，已接收 ")+ConnectionHud.formatMb(a.assembler.bytes()));
                }
                if(a.live && recording && !paused && !ConnectionHud.isLiveSignalStable(RuntimeTelemetry.rssiDbm)
                        && a.assembler.internalGaps()>25) {
                    degradeLiveToBean(a,"蓝牙信号转弱且出现丢片，已暂存实时分片，结束后只补缺失部分");
                    return;
                }
                if(a.backfill && a.key!=null && a.headSize>0 && !a.end.received() && decoder.rejected==a.rejectedAtStart
                        && a.assembler.complete(a.headSize,a.sizeUnit)) {sealBackfillEarly(a);return;}
            }
            if(a.stopConfirmed || a.end.received())scheduleSettle(a);return;
        }
        if(p.id==0x0a) {
            // A previous request can leave an end notification behind. A matching,
            // decrypted header must exist before it can end the current transfer.
            if(a.key==null) {if(reading)cancelCleanup("豆内复读缺少文件头，已保留豆内原件");
                else EventLog.add(this,"忽略文件头之前的结束通知 id="+a.fileId);return;}
            a.lastData=SystemClock.elapsedRealtime();a.end.received(a.lastData);
            if(reading)attempt.guard.contentActivity(a.lastData);
            EventLog.add(this,"收到传输结束 id="+a.fileId+" payload="+b.length+" frames="+a.assembler.frames());
            if(a.live) {
                long endMs=LiveBackfill.endDurationMs(b,a.fileId);
                if(endMs>0) {
                    // Use the duration reported by the live end notification for backfill.
                    a.liveEndMs=endMs;a.closedListSize=endMs;a.finalSize=true;a.stopConfirmed=true;
                    if(a.stoppedAt==0L)a.stoppedAt=a.lastData;
                }
                if(!recording) {a.stopConfirmed=true;if(a.stoppedAt==0L)a.stoppedAt=a.lastData;}
                if(!listing && !a.finalSize)requestInventory();
            }
            scheduleSettle(a);
        }
    }
    private void completeSessionReady() {
        reconnects=0;fallbackSelectedAddress="";unverifiedFallbackAddress=false;
        long wallNow=System.currentTimeMillis();
        RuntimeTelemetry.handshakeStep=3;RuntimeTelemetry.connectedSinceWallMs=wallNow;
        RuntimeTelemetry.reconnectCount=0;RuntimeTelemetry.reconnectDelayMs=0L;RuntimeTelemetry.nextReconnectWallMs=0L;
        SharedPreferences.Editor ed=Settings.prefs(this).edit()
                .putBoolean("ble_ready",true).putString("ble_device",device).putLong("last_online_at",wallNow);
        if(!device.isEmpty() && !connectedAddress.isEmpty()) {
            ed.putString("storage_device",device).putString("storage_bound_address",connectedAddress);
        }
        ed.apply();
        setStatus("连接就绪，等待录音豆按钮");EventLog.add(this,"加密协商成功；固件 "+firmware);
        safeReadRssi();
        store.retryUploads();
        store.retryDownloads();
        store.resetRetryableCleanups(device);
        cooldown.clear();
        uploads.kick();
        connectionSync.connected();
        requestInventory();if(currentId>0 && recording)startOrDeferLiveFile(currentId);
    }
    private void beginCleanup(RecordingStore.Row row,boolean manual) {
        if(row==null)return;
        if(!manual && !automaticCleanupAllowed(row))return;
        if(cleanup!=null){if(manual){setStatus("正在核对另一条录音，请稍候");fileAction("正在核对另一条录音，请稍候");}return;}
        String reason="";
        if(!row.state.equals("SENT") || row.uploadedAt<=0)reason="尚无中转保存回执，不能清理豆内副本";
        else if(!ready || crypto==null || !crypto.ready() || !row.device.equals(device))reason="请先连接保存这条录音的录音豆";
        else if(!RecordingSizeUnit.usesVerifiedTransportProtocol(firmware))reason="当前仅对已验证列表规则的 04.x 固件启用豆内清理校验";
        else if(active!=null || recording || listing)reason="录音或接收优先，稍后再清理";
        else if(cleanupQuarantinedGeneration==generation)reason="前次清理结果待检查，请等待连接重建";
        else if(Settings.prefs(this).getString("endpoint","").isEmpty())reason="请先配置中转服务";
        if(!reason.isEmpty()) {
            if(!manual)store.cleanup(row.id,"RETRY",reason);
            else {setStatus(reason);fileAction(reason);}
            return;
        }
        try {cleanup=new Cleanup(row,generation,manual);}
        catch(IllegalArgumentException e){store.cleanup(row.id,"FAILED","录音元数据不完整，不能清理");return;}
        if(!store.claimCleanup(row.id)){cleanup=null;fileAction("录音状态已变化，请刷新后重试");return;}
        RuntimeTelemetry.cleaningRecordingId=row.id;
        RuntimeTelemetry.cleaningFileId=row.fileId;
        driveCleanup(DeviceCleanup.Action.VERIFY_REMOTE);
    }
    private void verifyCleanupBackup(Cleanup attempt) {
        RecordingStore.Row row=attempt.row;
        uploads.verifyForCleanup(row,(endpoint,error)->worker.post(()->{
            if(closed || cleanup!=attempt)return;
            if(generation!=attempt.generation || !ready || (!attempt.manual && !automaticCleanupAllowed(attempt.row))) {
                cancelCleanup("连接或清理开关已变化");return;
            }
            if(!error.isEmpty()){cancelCleanup("中转重新核对未通过："+error);return;}
            boolean same=false;
            try {same=matchesSavedRecording(row);}catch(IOException ignored){ }
            driveCleanup(attempt.guard.remoteVerified(endpoint,Settings.prefs(this).getString("endpoint",""),same,SystemClock.elapsedRealtime()));
        }));
    }
    private void cancelCleanup(String reason) {
        if(cleanup==null)return;
        cleanup.guard.interrupt(reason,SystemClock.elapsedRealtime());driveCleanup(DeviceCleanup.Action.NONE);
    }
    private boolean syncEnabled(){return Settings.prefs(this).getBoolean("sync_on_connect",true);}
    private boolean matchesSavedRecording(RecordingStore.Row row) throws IOException {
        RecordingStore.Row current=store.get(row.id);
        return current!=null && current.state.equals("SENT") && current.uploadedAt>0 && current.duration==row.duration
                && current.bytes==row.bytes && current.readbackVerified==row.readbackVerified && row.sha.equals(current.sha)
                && row.sha.equals(RecordingStore.hash(store.file(row.id,".ogg")));
    }
    private boolean automaticCleanupAllowed(RecordingStore.Row row) {
        return ConnectionSync.mayClean(row,syncEnabled(),Settings.prefs(this).getBoolean("auto_cleanup",false));
    }
    private boolean sameCleanupTarget(Cleanup attempt) {
        String address=Settings.prefs(this).getString("address","");
        return ready && generation==attempt.generation && RecordingSizeUnit.usesVerifiedTransportProtocol(firmware) && attempt.row.device.equals(device)
                && !address.isEmpty() && device.equals(Settings.activeDeviceId(this))
                && deviceReportedIdle && !recording && !paused && currentId==0 && listActiveId==0 && active==null;
    }
    private void startCleanupReadback(Cleanup attempt) {
        if(!sameCleanupTarget(attempt)){cancelCleanup("录音豆或录音状态已变化，已保留豆内原件");return;}
        RecordingStore.Row row=attempt.row;
        try {
            requireTransferSpace(getCacheDir(),row.bytes);
            attempt.content=new Active(row.fileId,row.id,row.expected,false,new File(getCacheDir(),"cleanup-readback.part"),
                    null,decoder.rejected,RecordingSizeUnit.TRANSPORT_BYTES);
            send(Protocol.requestFile(row.fileId,0,false));
        }catch(IOException e){cancelCleanup("无法写入复读临时文件，请检查手机空间后重试");}
    }
    private void finishCleanupReadback(Cleanup attempt) {
        Active a=attempt.content;
        if(!sameCleanupTarget(attempt) || a.key==null || a.headSize<=0 || !a.end.settled(SystemClock.elapsedRealtime())
                || decoder.rejected!=a.rejectedAtStart || !a.assembler.complete(attempt.row.bytes)) {
            cancelCleanup("豆内复读未取得与备份等长的连续原音，已保留豆内原件");return;
        }
        try {
            a.assembler.close();
            File ogg=new File(getCacheDir(),"cleanup-readback.ogg");
            long millis=OggOpus.mux(a.source,ogg,(int)a.fileId);
            String sha=RecordingStore.hash(ogg);
            if(!matchesSavedRecording(attempt.row)){cancelCleanup("手机备份已变化，已保留豆内原件");return;}
            // The archived Ogg uses the same muxer and file ID. Only the scratch copy is ever written here.
            attempt.content=null;clearCleanupCache();
            attempt.contentRead=true;
            driveCleanup(attempt.guard.contentRead(sha,millis,SystemClock.elapsedRealtime()));
        }catch(IOException e){cancelCleanup("豆内复读音频无法完整核对，已保留豆内原件");}
    }
    private void clearCleanupCache() {
        new File(getCacheDir(),"cleanup-readback.part").delete();
        new File(getCacheDir(),"cleanup-readback.ogg").delete();
    }
    private static void requireTransferSpace(File directory,long bytes) throws IOException {
        if(directory.getUsableSpace()<Math.max(32L*1024*1024,bytes*3))throw new IOException("storage full");
    }
    private void driveCleanup(DeviceCleanup.Action action) {
        Cleanup attempt=cleanup;if(attempt==null)return;
        if(action==DeviceCleanup.Action.DELETE && (!sameCleanupTarget(attempt)
                || (!attempt.manual && !automaticCleanupAllowed(attempt.row)))) {
            // The state is already durable-before-send; conservatively mark unknown.
            attempt.guard.interrupt("连接或清理开关已变化",SystemClock.elapsedRealtime());action=DeviceCleanup.Action.NONE;
        }
        if(attempt.persisted!=attempt.guard.phase()) {
            store.cleanup(attempt.row.id,attempt.guard.phase().name(),attempt.guard.detail());
            attempt.persisted=attempt.guard.phase();
            EventLog.add(this,"豆内清理 id="+attempt.row.fileId+" device="+attempt.row.device+" phase="+attempt.guard.phase()+" "+attempt.guard.detail());
            fileAction("录音 "+attempt.row.fileId+"："+attempt.guard.detail());
            setStatus(attempt.guard.detail());
        }
        if(attempt.guard.finished()) {
            if(attempt.guard.phase()==DeviceCleanup.Phase.UNKNOWN || attempt.content!=null)cleanupQuarantinedGeneration=generation;
            if(attempt.content!=null) {
                try{attempt.content.assembler.close();}catch(IOException ignored){ }
                attempt.content=null;clearCleanupCache();
            }
            RuntimeTelemetry.cleaningRecordingId="";
            RuntimeTelemetry.cleaningFileId=0L;
            cleanup=null;
            if(!closed && ready && cleanupQuarantinedGeneration!=generation)worker.post(this::advancePipeline);
            return;
        }
        if(action==DeviceCleanup.Action.VERIFY_REMOTE)verifyCleanupBackup(attempt);
        else if(action==DeviceCleanup.Action.READ_CONTENT)startCleanupReadback(attempt);
        else if(action==DeviceCleanup.Action.READ_INFO)send(Protocol.command(1,1));
        else if(action==DeviceCleanup.Action.READ_LIST) {
            if(attempt.guard.phase()==DeviceCleanup.Phase.AFTER)worker.postDelayed(()->{
                if(cleanup==attempt && generation==attempt.generation)requestInventory();
            },300);
            else requestInventory();
        }else if(action==DeviceCleanup.Action.DELETE)send(Protocol.deleteFile(attempt.row.fileId));
    }
    private void applyRecordState(int state) {
        deviceReportedIdle=state==0;
        if(state<0 || state>2)return;
        if(state!=0)cancelCleanup("录音豆正在录音或暂停，暂缓清理");
        if(state==2) {
            // Pause may resume the SAME file ID. Never seal and upload its prefix.
            paused=true;recording=true;RuntimeTelemetry.beanPaused=true;RuntimeTelemetry.beanRecording=true;
            setStatus("录音已暂停，等待结束后完整保存");requestInventory();return;
        }
        paused=false;RuntimeTelemetry.beanPaused=false;applyRecording(state==1);
    }
    private void applyRecording(boolean value) {
        boolean was=recording;recording=value;RuntimeTelemetry.beanRecording=value;
        if(value) {
            safeReadRssi();
            if(active!=null && active.live) {
                boolean resumeStream=active.stopConfirmed || active.end.received();
                active.stopConfirmed=false;active.stoppedAt=0L;active.finalSize=false;active.closedListSize=0L;active.end.reset();active.settle++;
                if(resumeStream && ConnectionHud.isLiveSignalStable(RuntimeTelemetry.rssiDbm)) {
                    send(Protocol.requestFile(active.fileId,active.assembler.resumeRequestOffset(),true));
                    setStatus("继续录音，恢复实时接收原音");
                }
            }
            if(active!=null && !active.live)abandon("录音优先，旧录音稍后补收");
            if(currentId>0 && crypto!=null && crypto.ready()) {
                if(active==null || active.fileId!=currentId)startOrDeferLiveFile(currentId);
            } else if(ready)requestInventory();
        }else if(was || (active!=null && active.live)) {
            currentId=0;
            RuntimeTelemetry.liveDeferredWeakSignal=false;
            liveDegradedMidStream.clear();
            if(active!=null && active.live) {
                long now=SystemClock.elapsedRealtime();
                active.stopConfirmed=true;if(active.stoppedAt==0L)active.stoppedAt=now;
                active.lastData=now;store.stopped(active.id);scheduleSettle(active);
            }
            long delayMs=(active!=null && active.live)?220L:450L;
            long gen=generation;worker.postDelayed(()->{if(gen==generation && ready && (active==null || !active.finalSize))requestInventory();},delayMs);
        }
    }
    private void requestInventory() {
        if(!ready || crypto==null || !crypto.ready() || listing)return;
        if(cleanup!=null && cleanup.guard.phase()!=DeviceCleanup.Phase.BEFORE && cleanup.guard.phase()!=DeviceCleanup.Phase.AFTER)return;
        listing=true;listPage=0;listActiveId=0;inventory.clear();inventoryPages.reset();deviceFiles.reading(device);listDeadline=SystemClock.elapsedRealtime()+10000;send(Protocol.list(listType,0));
    }
    private void abortInventory(String reason) {
        listing=false;inventory.clear();requestedDownload=0;deviceFiles.unavailable(device,reason);
    }
    private void fileAction(String message){deviceFiles.action(device,message);}
    private void requestDownload(String expectedDevice,long fileId,boolean restore) {
        if(!device.equals(expectedDevice) || fileId<=0 || fileId>0xffff_ffffL){fileAction("所选录音豆已变化，请刷新后重新选择");return;}
        if(!ready || crypto==null || !crypto.ready()){fileAction("请先连接录音豆，再下载此录音");return;}
        if(active!=null || recording || cleanup!=null || listing || cleanupQuarantinedGeneration==generation){fileAction("录音、接收或核对正在进行，请稍后再下载");return;}
        requestedDownload=fileId;requestedRestore=restore;fileAction("正在核对录音 "+fileId+" 是否仍在豆内");requestInventory();
    }
    private void fileList(Protocol.FileList list) {
        if(!listing)return; // A refresh must start before pages can complete a cleanup check.
        final boolean lastPage;
        try {lastPage=inventoryPages.accept(list);}
        catch(IllegalArgumentException e){abortInventory("设备列表不完整、重复或过长，请重新刷新");cancelCleanup("设备列表未完成全部核对，暂不清理");return;}
        if(list.activeId>0)listActiveId=list.activeId;
        // Ignore old response families after fallback, but still reconcile complete entries.
        for(Protocol.Entry e:list.entries)inventory.put(e.id,e);
        if(recording && list.activeId>0) {
            currentId=list.activeId;if(active==null || active.fileId!=currentId)startOrDeferLiveFile(currentId);
        }
        for(Protocol.Entry e:list.entries) {
            if(active!=null && active.fileId==e.id && active.live) {
                active.listSize=e.size;
                if((recording || paused) && e.endSeconds>0 && list.activeId!=e.id) {
                    paused=false;recording=false;currentId=0;
                    RuntimeTelemetry.beanPaused=false;RuntimeTelemetry.beanRecording=false;RuntimeTelemetry.liveDeferredWeakSignal=false;
                    active.stopConfirmed=true;if(active.stoppedAt==0L)active.stoppedAt=SystemClock.elapsedRealtime();
                    store.stopped(active.id);
                }
                boolean closedOnBean=e.size>0 && list.activeId!=e.id
                        && (listType==0x1b ? e.endSeconds>0 : deviceReportedIdle || !recording);
                if(active.stopConfirmed && closedOnBean) {
                    store.ensure(device,e.id,e.size);
                    active.closedListSize=e.size;
                    active.finalSize=true;
                    scheduleSettle(active);
                }
            }
        }
        if(!lastPage) {
            listPage++;listDeadline=SystemClock.elapsedRealtime()+10000;send(Protocol.list(listType,listPage));return;
        }
        listing=false;everListed=true;
        if(active!=null && active.live && active.stopConfirmed) {
            if(active.finalSize)scheduleSettle(active);
            else {
                long gen=generation;
                worker.postDelayed(()->{if(gen==generation && ready && active!=null && active.live && active.stopConfirmed && !active.finalSize)requestInventory();},450L);
            }
        }
        deviceFiles.complete(device,inventory,listActiveId,listType==0x1b,firmware);
        connectionSync.capture(device,inventory,listActiveId,recording || active!=null,listType==0x1b,syncEnabled());
        if(requestedDownload>0) {
            long id=requestedDownload;requestedDownload=0;Protocol.Entry entry=inventory.get(id);
            if(entry==null)fileAction("录音 "+id+" 已不在当前豆内列表，未下载");
            else if(recording || active!=null || listActiveId>0)fileAction("录音或接收优先，请稍后再下载此录音");
            else if(entry.size<=0)fileAction("此录音尚无完整文件信息，请稍后刷新");
            else {
                RecordingStore.Row row=store.ensure(device,id,entry.size);
                if(row.saved() && store.file(row.id,".ogg").isFile())fileAction("录音 "+id+" 已有完整手机副本，可直接播放");
                else {
                    if(row.localDeleted()) {
                        if(!requestedRestore){fileAction("手机副本已删除，请重新选择并确认下载");return;}
                        try{store.restoreLocal(row.id);}catch(IOException e){fileAction("手机删除尚未完成，请回到收件箱重试删除");return;}
                    }
                    if(row.saved())store.waiting(row.id,"手机副本缺失，按用户请求重新补收");
                    if(row.reconciled())store.redownloadReconciled(row.id);
                    cooldown.remove(id);startFile(id,entry.size,false);fileAction("已请求下载录音 "+id+"，完整保存后可播放");
                }
            }
        }
        if(cleanup!=null) {
            if(cleanup.guard.phase()==DeviceCleanup.Phase.BEFORE && !sameCleanupTarget(cleanup))
                cancelCleanup("当前设备或空闲状态已变化，暂缓豆内清理");
            else driveCleanup(cleanup.guard.files(inventory,listActiveId,recording||active!=null,listType==0x1b,device,
                    Settings.prefs(this).getString("endpoint",""),SystemClock.elapsedRealtime()));
        }
        if(RecordingSizeUnit.usesVerifiedTransportProtocol(firmware)) {
            int completed=store.reconcileAcknowledged(device,inventory,listActiveId,recording||active!=null||cleanup!=null);
            if(completed>0)EventLog.add(this,"完整列表确认前次已回执清理 "+completed+" 条；未重发删除命令");
        }
        if(active==null && !recording)advancePipeline();
    }
    private boolean eligible(long id,RecordingStore.Row row) {
        if(!ConnectionSync.mayDownload(row,syncEnabled()))return false;
        return row!=null || Settings.prefs(this).getBoolean("history",false)
                || id>=Settings.prefs(this).getLong("since",System.currentTimeMillis()/1000)-2 || id==currentId;
    }
    /**
     * Oldest-first single-item closed loop:
     * 1. Drain any older phone READY uploads to cloud first.
     * 2. Inspect bean inventory from oldest fileId to newest:
     *    - If not yet saved on phone: pull (or resume from breakpoint) this single oldest item and wait.
     *    - If saved as READY and cloud endpoint is configured: upload this item to cloud and wait.
     *    - If uploaded (SENT) and eligible for auto-cleanup: verify & delete this item from bean, then refresh list.
     *    Only when the oldest item completes its closed loop does the pipeline advance to the next oldest item.
     */
    private void advancePipeline() {
        if(!ready || active!=null || recording || listActiveId>0 || listing || cleanup!=null || drainFileId!=0
                || cleanupQuarantinedGeneration==generation || crypto==null || !crypto.ready())return;
        String endpoint=Settings.prefs(this).getString("endpoint","");
        if(!endpoint.isEmpty() && (uploads.isBusy() || store.nextUpload()!=null)) {
            uploads.kick();
            return;
        }
        long nowElapsed=SystemClock.elapsedRealtime();
        long nowWall=System.currentTimeMillis();
        for(Protocol.Entry e:inventory.values()) {
            if(e.id==currentId || e.size<=0 || (listType==0x1b && e.endSeconds<=0))continue;
            RecordingStore.Row row=store.get(RecordingStore.id(device,e.id));
            if(row!=null && (row.localDeleted() || row.reconciled() || row.state.equals("SERVER_DELETED")))continue;
            if(row==null || !row.saved()) {
                if(!eligible(e.id,row))continue;
                RecordingStore.Row existingComplete=store.findCompleteByFileId(e.id,e.size);
                if(existingComplete!=null) {
                    if(existingComplete.state.equals("READY") && !endpoint.isEmpty()) {
                        uploads.kick();
                        return;
                    }
                    if(existingComplete.state.equals("SENT")) {
                        RecordingStore.Row target=row!=null?row:store.ensure(device,e.id,e.size);
                        store.markReconciledTo(target.id,existingComplete.id);
                        EventLog.add(this,"跳过重复接收（本机已有已上传完整副本）："+e.id);
                        continue;
                    }
                    if(existingComplete.state.equals("SERVER_DELETED") || endpoint.isEmpty())continue;
                }
                if(row!=null && row.nextAttempt-nowWall>120_000L)continue; // Skip 30-min anomaly quarantine
                if(cooldown.getOrDefault(e.id,0L)>nowElapsed || (row!=null && row.nextAttempt>nowWall))return;
                startFile(e.id,e.size,false);
                return;
            }
            if(row.state.equals("READY")) {
                if(endpoint.isEmpty())continue;
                uploads.kick();
                return;
            }
            if(row.state.equals("SENT")) {
                boolean canAutoClean=automaticCleanupAllowed(row) && RecordingSizeUnit.usesVerifiedTransportProtocol(firmware)
                        && !endpoint.isEmpty() && !row.cleanupAcknowledged && !row.readbackVerified;
                if(!canAutoClean)continue;
                if(row.cleanupState.equals("") || (row.cleanupState.equals("RETRY") && row.cleanupNextAttempt<=nowWall)) {
                    beginCleanup(row,false);
                    return;
                }
                if(row.cleanupState.equals("RETRY") && row.cleanupAttempts<3)return;
            }
        }
        if(cleanup==null) {
            beginCleanup(store.nextCleanup(device,Settings.prefs(this).getBoolean("auto_cleanup",false),syncEnabled()),false);
        }
    }
    private void startOrDeferLiveFile(long fileId) {
        if(!ready || crypto==null || !crypto.ready() || fileId<=0)return;
        if(active!=null && active.fileId==fileId)return;
        if(drainFileId!=0){setStatus("录音中；上一条录音的补齐传输结束后接入实时流");return;}
        int rssi=RuntimeTelemetry.rssiDbm;
        if(liveDegradedMidStream.contains(fileId) || !ConnectionHud.isLiveSignalStable(rssi)) {
            RecordingStore.Row row=store.ensure(device,fileId,0);
            RuntimeTelemetry.liveDeferredWeakSignal=true;
            if(!liveDegradedMidStream.contains(fileId)) {
                String msg="蓝牙信号较弱（"+rssi+" dBm），录音豆独立存储中，结束后自动补收";
                if(row!=null && !row.saved() && !row.localDeleted() && !row.reconciled())store.waiting(row.id,msg);
                setStatus(msg);
                EventLog.add(this,"信号较弱暂缓实时拉流 id="+fileId+" rssi="+rssi);
            }
            return;
        }
        RuntimeTelemetry.liveDeferredWeakSignal=false;
        startFile(fileId,0,true);
    }
    private void degradeLiveToBean(Active a,String reason) {
        if(a==null || active!=a)return;
        liveDegradedMidStream.add(a.fileId);
        RuntimeTelemetry.liveDeferredWeakSignal=true;
        parkTransfer(reason);
        if(gatt!=null)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);}catch(RuntimeException ignored){ }
        setStatus(reason);
    }
    /** Keep received live frames (head missing by design) instead of discarding them on a stall or link loss. */
    private void parkTransfer(String reason) {
        Active a=active;
        if(a==null || a.readback!=null || (!a.live && !a.backfill) || a.assembler.frames()==0 || (cleanup!=null && cleanup.content==a))return;
        if(parked!=null)dropParked("新的中断录音替换了暂存的实时分片");
        active=null;RuntimeTelemetry.bleTransfer=null;parked=a;a.settle++;
        traceTransfer(a,reason+"，暂存实时分片");
        store.progress(a.id,a.assembler.bytes());
        store.waiting(a.id,reason+"；手机已保留 "+a.assembler.frames()+" 帧，重连后只补缺失部分");
        EventLog.add(this,"暂存实时分片 "+a.fileId+"：seq "+a.assembler.firstSequence()+"…"+a.assembler.lastSequence()+"，共 "+a.assembler.frames()+" 帧");
    }
    private void dropParked(String reason) {
        Active p=parked;parked=null;if(p==null)return;
        Active current=active;active=p;abandon(reason);active=current;
    }
    private void resumeParked(RecordingStore.Row row,long size,boolean live) {
        Active a=parked;parked=null;
        if(active!=null)abandon("录音切换，稍后补收");
        if(!store.receiving(row.id)){parked=a;return;}
        long now=SystemClock.elapsedRealtime();
        active=a;a.settle++;a.end.reset();a.key=null;a.headSize=-1;a.lastData=now;a.lastSliceAt=now;a.rejectedAtStart=decoder.rejected;
        store.progress(a.id,a.assembler.bytes());
        if(gatt!=null)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);}catch(RuntimeException ignored){ }
        if(live) {
            a.live=true;a.backfill=false;a.stopConfirmed=false;a.stoppedAt=0L;a.finalSize=false;a.closedListSize=0L;a.liveEndMs=0L;
            RuntimeTelemetry.bleTransfer=new RuntimeTelemetry.Transfer(a.id,a.fileId,false,true,false,a.assembler.bytes(),0L,
                    LiveBackfill.receivedDurationMs(a.assembler),0.0,true);
            send(Protocol.requestFile(a.fileId,0,true));
            setStatus("重新连接后仍在录音，恢复实时接收");
            EventLog.add(this,"恢复实时接收 "+a.fileId+"，已暂存 "+a.assembler.frames()+" 帧");
            return;
        }
        if(size>0){a.listSize=size;if(a.closedListSize<=0)a.closedListSize=size;}
        a.stopConfirmed=true;
        beginBackfill(a);
    }
    /** Fetch only what the live stream did not deliver; see {@link LiveBackfill} for the measured protocol. */
    private void beginBackfill(Active a) {
        if(active!=a)return;
        long expected=a.liveEndMs>0?a.liveEndMs/LiveBackfill.FRAME_MILLIS
                :(a.closedListSize>0 && a.closedListSize%LiveBackfill.FRAME_MILLIS==0?a.closedListSize/LiveBackfill.FRAME_MILLIS:-1L);
        LiveBackfill.Plan plan=LiveBackfill.plan(a.assembler,expected,a.sizeUnit);
        a.live=false;a.backfill=true;a.backfillPhase=plan.phase;a.key=null;a.headSize=-1;a.end.reset();a.settle++;
        a.resumedOffset=plan.offset;a.lastData=SystemClock.elapsedRealtime();
        if(a.listSize<=OfflineReadback.FRAME_MILLIS && a.closedListSize>0)a.listSize=a.closedListSize;
        int first=a.assembler.firstSequence();
        EventLog.add(this,"实时流补齐 id="+a.fileId+"：已收 "+a.assembler.frames()+" 帧（seq "+first+"…"+a.assembler.lastSequence()
                +"），应有 "+expected+" 帧；"+(plan.phase==LiveBackfill.Phase.TAIL?"先从第 "+plan.fromFrame+" 帧补收断点之后":"补收开头 "+Math.max(0,first)+" 帧"));
        setStatus(plan.phase==LiveBackfill.Phase.TAIL?"连接曾中断，正在补收断点之后的录音":"录音已结束，正在补齐开头 "+Math.max(0,first)*LiveBackfill.FRAME_MILLIS+" ms");
        if(gatt!=null)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);}catch(RuntimeException ignored){ }
        send(Protocol.requestFile(a.fileId,plan.offset,false));
    }
    /** The request landed while an earlier stream was still running; ask again once the bean is quiet. */
    private void retryBackfillRequest(Active a) {
        long stamp=++a.settle;
        EventLog.add(this,"补齐请求遇到仍在进行的传输，稍后重发 id="+a.fileId);
        worker.postDelayed(()->{
            if(active!=a || a.settle!=stamp || a.key!=null || !ready)return;
            a.lastData=SystemClock.elapsedRealtime();send(Protocol.requestFile(a.fileId,a.resumedOffset,false));
        },2000L);
    }
    private void requestBackfillHead(Active a) {
        if(active!=a)return;
        a.backfillPhase=LiveBackfill.Phase.HEAD;a.key=null;a.headSize=-1;a.end.reset();a.settle++;a.resumedOffset=0L;
        a.lastData=SystemClock.elapsedRealtime();
        EventLog.add(this,"断点之后已补收，再补开头 "+Math.max(0,a.assembler.firstSequence())+" 帧 id="+a.fileId);
        setStatus("正在补齐录音开头");
        send(Protocol.requestFile(a.fileId,0,false));
    }
    /** Seal as soon as every frame is present; the rest of this stream only repeats frames already verified live. */
    private void sealBackfillEarly(Active a) {
        if(active!=a)return;
        long fileId=a.fileId;
        drainFileId=fileId;drainLastData=SystemClock.elapsedRealtime();scheduleDrainCheck();
        try {
            int frames=a.assembler.frames();long bytes=a.assembler.bytes();
            a.assembler.close();
            saveHighlights(a);
            long duration=store.saveReceived(a.id,fileId,a.source,frames,false,a.liveEndMs);
            forceZeroOffset.remove(fileId);
            active=null;RuntimeTelemetry.bleTransfer=null;
            traceTransfer(a,"实时流补齐后完整");
            if(gatt!=null)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);}catch(RuntimeException ignored){ }
            EventLog.add(this,"实时录音补齐后保存 "+fileId+"，"+bytes+" B，"+duration+" ms；豆内剩余分片传完前不发新请求");
            setStatus("录音已完整保存；"+(Settings.prefs(this).getString("endpoint","").isEmpty()?"未配置中转，仅保存在手机":"正在上传至中转"));
            uploads.kick();
        }catch(Exception e) {
            String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage().replace('\n',' ');
            failActive("实时录音补齐后封装失败："+(detail.length()>160?detail.substring(0,160):detail));
        }
    }
    private void scheduleDrainCheck() {
        long gen=generation;
        worker.postDelayed(()->{
            if(gen!=generation || drainFileId==0)return;
            if(SystemClock.elapsedRealtime()-drainLastData>=3000L)endDrain("豆内剩余分片已静默 3 秒");else scheduleDrainCheck();
        },1000L);
    }
    private void endDrain(String reason) {
        long id=drainFileId;drainFileId=0;if(id==0)return;
        EventLog.add(this,reason+"，结束 "+id+" 的补齐传输");
        if(!ready || active!=null)return;
        if(recording && !paused && currentId>0)startOrDeferLiveFile(currentId);
        else if(!recording && !listing)advancePipeline();
    }
    private void saveHighlights(Active a) {
        if(a.highlights.isEmpty())return;
        List<Long> ms=LiveBackfill.highlightMillis(a.highlights);
        try{store.saveHighlights(a.id,ms);EventLog.add(this,"录音 "+a.fileId+" "+LiveBackfill.describeHighlights(ms));}
        catch(IOException e){EventLog.add(this,"保存重点标记失败 "+a.fileId);}
    }
    private boolean canSealLiveDirectly(Active a) {
        if(a==null || !a.live || !a.stopConfirmed || !a.finalSize || recording || paused)return false;
        // The stop status can arrive without an end packet. Check the contiguous frame count and mux duration.
        if(!RecordingSizeUnit.usesVerifiedTransportProtocol(firmware) || a.key==null)return false;
        if(listType!=0x1b && !deviceReportedIdle && a.liveEndMs<=0)return false;
        if(decoder.rejected!=a.rejectedAtStart)return false;
        if(a.closedListSize<=OfflineReadback.FRAME_MILLIS || a.closedListSize%OfflineReadback.FRAME_MILLIS!=0)return false;
        long expectedFrames=a.closedListSize/OfflineReadback.FRAME_MILLIS;
        return expectedFrames>1 && a.assembler.contiguous() && a.assembler.frames()==expectedFrames;
    }
    private static long liveQuietElapsed(Active a,long now) {
        long anchor=Math.max(a.stoppedAt,a.lastSliceAt);
        if(a.end.received())anchor=Math.max(anchor,a.lastData);
        return Math.max(0L,now-anchor);
    }
    private void finishLiveDirectly(Active a) {
        if(active!=a || !canSealLiveDirectly(a))return;
        long fileId=a.fileId,listed=a.closedListSize;
        try {
            int frames=a.assembler.frames();
            long bytes=a.assembler.bytes();
            a.assembler.close();
            saveHighlights(a);
            long duration=store.saveReceived(a.id,fileId,a.source,frames,false,listed);
            forceZeroOffset.remove(fileId);
            active=null;RuntimeTelemetry.bleTransfer=null;
            traceTransfer(a,"实时零重传直通校验通过");
            if(gatt!=null)try{gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);}catch(RuntimeException ignored){ }
            EventLog.add(this,"实时录音直通保存 "+fileId+"，"+bytes+" B，"+duration+" ms");
            setStatus("实时录音已保存；"+(Settings.prefs(this).getString("endpoint","").isEmpty()?"未配置中转，仅保存在手机":"正在立即上传至中转"));
            uploads.kick();
            long gen=generation;worker.postDelayed(()->{if(gen==generation)advancePipeline();},300);
        }catch(Exception e) {
            forceZeroOffset.add(fileId);
            active=null;RuntimeTelemetry.bleTransfer=null;
            traceTransfer(a,"实时直封校验回退离线重收");
            try{a.assembler.close();}catch(IOException ignored){ }
            RecordingAssembler.clearResume(a.source);
            a.source.delete();
            store.file(a.id,".ogg.tmp").delete();
            store.progress(a.id,0L);
            store.waiting(a.id,"实时封装需重新核对，正在从豆内完整重收");
            EventLog.add(this,"实时直封回退离线重收 "+fileId+"："+e.getClass().getSimpleName());
            if(ready && !recording && !listing)startFile(fileId,listed,false);
        }
    }
    private void startFile(long fileId,long size,boolean live) {
        if(!ready || crypto==null || !crypto.ready() || (active!=null && active.fileId==fileId) || drainFileId!=0)return;
        if(cooldown.getOrDefault(fileId,0L)>SystemClock.elapsedRealtime())return;
        RecordingStore.Row row=store.ensure(device,fileId,size);if(row.saved() || row.localDeleted() || row.reconciled())return;
        if(parked!=null && parked.id.equals(row.id)){resumeParked(row,size,live);return;}
        if(!live) {
            RecordingStore.Row existingComplete=store.findCompleteByFileId(fileId,size);
            if(existingComplete!=null && !existingComplete.id.equals(row.id)) {
                if(existingComplete.state.equals("SENT")) {
                    store.markReconciledTo(row.id,existingComplete.id);
                    EventLog.add(this,"跳过重复接收（本机已有已上传完整副本）："+fileId);
                } else if(existingComplete.state.equals("READY")) {
                    uploads.kick();
                }
                return;
            }
        }
        if(active!=null)abandon("录音切换，稍后补收");
        if(!store.receiving(row.id))return;
        try {
            requireTransferSpace(getFilesDir(),size);
            OfflineReadback readback=OfflineReadback.forRequest(store,row.id,firmware,live,size);
            File source=readback==null?store.file(row.id,".part"):readback.attempt;
            boolean allowResume=!live && readback==null && !forceZeroOffset.contains(fileId);
            Active a=new Active(fileId,row.id,size,live,source,readback,decoder.rejected,
                    RecordingSizeUnit.forFirmware(firmware),allowResume);
            if(allowResume && a.initialBytes>0L) {
                a.resumeAttempt=RecordingAssembler.incrementResumeAttempt(source);
                if(a.resumeAttempt>2) {
                    a.assembler.reset();
                    a.initialBytes=0L;
                    a.resumeAttempt=0;
                    forceZeroOffset.add(fileId);
                    store.progress(row.id,0L);
                    EventLog.add(this,"断点续传多次未完成，已删除手机旧分片并从零重新接收 "+fileId);
                }
            }
            active=a;
            long reqOffset=(allowResume && a.initialBytes>0L)?a.assembler.resumeRequestOffset():0L;
            a.resumedOffset=reqOffset;
            if(a.initialBytes>0L)store.progress(row.id,a.initialBytes);
            long expAudio=(!live && size>OfflineReadback.FRAME_MILLIS)?(size/OfflineReadback.FRAME_MILLIS)*160L:0L;
            RuntimeTelemetry.bleTransfer=new RuntimeTelemetry.Transfer(row.id,fileId,false,live,false,
                    a.initialBytes,expAudio,!live&&size>OfflineReadback.FRAME_MILLIS?size:0L,0.0,a.initialBytes>0L);
            if(gatt!=null)gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            send(Protocol.requestFile(fileId,reqOffset,live));
            setStatus(live?"正在接收按钮录音":a.initialBytes>0L?"正在断点续收录音 "+fileId:"正在补收录音 "+fileId);
            EventLog.add(this,(live?"实时接收 ":a.initialBytes>0L?"断点续收 ":"完整补收 ")+fileId
                    +(a.initialBytes>0L?" (已缓存 "+a.initialBytes+" B，请求偏移 "+reqOffset+")":""));
        }catch(IOException e) {store.waiting(row.id,"无法写入，请检查手机剩余空间");cooldown.put(fileId,SystemClock.elapsedRealtime()+60000);problem("录音保存失败，请检查手机剩余空间");}
    }
    private void scheduleSettle(Active a) {
        long stamp=++a.settle;
        long nowAtSchedule=SystemClock.elapsedRealtime();
        long waitMs;
        if(a.live) {
            if(!a.stopConfirmed)waitMs=TransferEnd.QUIET_MILLIS+25L;
            else if(canSealLiveDirectly(a)) {
                long rem=Math.max(0L,TransferEnd.LIVE_EXACT_QUIET_MILLIS-liveQuietElapsed(a,nowAtSchedule));
                waitMs=rem+25L;
            } else if(a.finalSize) {
                long rem=Math.max(0L,LIVE_FALLBACK_QUIET_MILLIS-liveQuietElapsed(a,nowAtSchedule));
                waitMs=rem+25L;
            } else {
                waitMs=450L;
            }
        } else if(cleanup==null && a.readback==null && a.key!=null && a.headSize>0
                && decoder.rejected==a.rejectedAtStart && a.assembler.complete(a.headSize,a.sizeUnit) && a.end.received()) {
            waitMs=a.end.remainingMillis(nowAtSchedule,TransferEnd.LIVE_EXACT_QUIET_MILLIS)+25L;
        } else {
            waitMs=a.end.received()?a.end.remainingMillis(nowAtSchedule,TransferEnd.QUIET_MILLIS)+25L:TransferEnd.QUIET_MILLIS+25L;
        }
        worker.postDelayed(()->{
            if(a.settle!=stamp)return;
            long now=SystemClock.elapsedRealtime();
            if(cleanup!=null && cleanup.content==a) {
                if(a.end.settled(now))finishCleanupReadback(cleanup);
                else if(a.end.received())scheduleSettle(a);
                return;
            }
            if(active!=a)return;
            if(!a.live) {
                boolean exactOffline=a.readback==null && a.key!=null && a.headSize>0
                        && decoder.rejected==a.rejectedAtStart && a.assembler.complete(a.headSize,a.sizeUnit);
                long reqQuiet=exactOffline?TransferEnd.LIVE_EXACT_QUIET_MILLIS:TransferEnd.QUIET_MILLIS;
                if(a.end.settledWithin(now,reqQuiet))finishIfComplete(a);
                else if(a.end.received())scheduleSettle(a);
                return;
            }
            if(!a.stopConfirmed)return;
            if(canSealLiveDirectly(a)) {
                if(liveQuietElapsed(a,now)>=TransferEnd.LIVE_EXACT_QUIET_MILLIS)finishLiveDirectly(a);
                else scheduleSettle(a);
                return;
            }
            if(a.finalSize) {
                if(liveQuietElapsed(a,now)<LIVE_FALLBACK_QUIET_MILLIS) {
                    scheduleSettle(a);
                    return;
                }
                if(listing){scheduleSettle(a);return;}
                beginBackfill(a);
            }else if(!listing)requestInventory();
        },waitMs);
    }
    private void finishIfComplete(Active a) {
        if(active!=a || a.live)return;
        boolean exactOffline=a.readback==null && a.key!=null && a.headSize>0
                && decoder.rejected==a.rejectedAtStart && a.assembler.complete(a.headSize,a.sizeUnit);
        if(!a.end.settledWithin(SystemClock.elapsedRealtime(),exactOffline?TransferEnd.LIVE_EXACT_QUIET_MILLIS:TransferEnd.QUIET_MILLIS))return;
        if(a.key==null || a.headSize<=0){failActive("设备尚未报告最终文件长度，等待补收");return;}
        boolean matched=false;
        try {
            OfflineReadback.Result result;
            if(a.readback!=null) {
                Protocol.Entry entry=inventory.get(a.fileId);
                boolean closedIdle=!listing && !recording && currentId==0 && listActiveId==0
                        && a.listSize==OfflineReadback.FRAME_MILLIS && OfflineReadback.closedEntry(entry,listType,deviceReportedIdle);
                result=a.readback.verify(a.assembler,a.headSize,closedIdle,decoder.rejected==a.rejectedAtStart);
            }else {
                result=a.assembler.complete(a.headSize,a.sizeUnit)?OfflineReadback.Result.EXACT:OfflineReadback.Result.INCOMPLETE;
            }
            if(result==OfflineReadback.Result.READ_AGAIN) {
                abandon("豆内仅标注一片，已保留首次补收原音，正在从头复读");
                startFile(a.fileId,a.listSize,false);return;
            }
            if(result==OfflineReadback.Result.DIFFERENT || result==OfflineReadback.Result.SHORTER) {
                deferReadback(result==OfflineReadback.Result.SHORTER?"本次音频短于已保留分片":"两次补收的音频内容不同");return;
            }
            if(result==OfflineReadback.Result.INCOMPLETE) {
                if(a.backfill && a.backfillPhase==LiveBackfill.Phase.TAIL){requestBackfillHead(a);return;}
                if(!a.backfill && a.readback==null && (a.resumedOffset>0L || a.initialBytes>0L) && !forceZeroOffset.contains(a.fileId)) {
                    fallbackActiveToZero("断点续传核对未通过，已删除手机旧分片并从零重收");
                    return;
                }
                String reason=a.assembler.frames()==0?"尚未收到音频分片":!a.assembler.contiguous()?"录音分片序号不连续":"录音长度尚未核对一致";
                failActive(reason+"（音频 "+a.assembler.bytes()+" B；文件头 "+a.headSize+" B，"+a.sizeUnit.bytesPerFrame+" B/片），稍后补收");return;
            }
            matched=result==OfflineReadback.Result.MATCHED;
            long bytes=a.assembler.bytes();a.assembler.close();
            saveHighlights(a);
            long duration=store.saveReceived(a.id,a.fileId,a.source,a.assembler.frames(),matched,a.backfill?a.liveEndMs:0L);
            forceZeroOffset.remove(a.fileId);
            active=null;RuntimeTelemetry.bleTransfer=null;
            traceTransfer(a,matched?"豆内长度异常，两次独立补收 SHA256 一致":"校验通过");
            if(gatt!=null)gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED);
            EventLog.add(this,"录音完整保存 "+a.fileId+"，"+bytes+" B，"+duration+" ms");
            setStatus("录音已完整保存；"+(Settings.prefs(this).getString("endpoint","").isEmpty()?"未配置中转，仅保存在手机":"正在上传至中转"));
            uploads.kick();
            long gen=generation;worker.postDelayed(()->{if(gen==generation)advancePipeline();},300);
        }catch(Exception e) {
            if(!matched && !a.backfill && a.readback==null && (a.resumedOffset>0L || a.initialBytes>0L) && !forceZeroOffset.contains(a.fileId)) {
                fallbackActiveToZero("续传音频封装核对失败，已删除手机旧分片并从零重收");
                return;
            }
            String detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage().replace('\n',' ');
            if(detail.length()>160)detail=detail.substring(0,160);
            if(matched)deferReadback("复读音频保存失败，"+detail);else failActive("录音封装失败："+detail);
        }
    }
    private void fallbackActiveToZero(String reason) {
        Active a=active;if(a==null)return;
        long fileId=a.fileId,listed=a.listSize;
        forceZeroOffset.add(fileId);
        active=null;RuntimeTelemetry.bleTransfer=null;
        traceTransfer(a,reason);
        try{a.assembler.close();}catch(IOException ignored){ }
        RecordingAssembler.clearResume(a.source);
        a.source.delete();
        store.file(a.id,".ogg.tmp").delete();
        store.progress(a.id,0L);
        store.waiting(a.id,reason);
        EventLog.add(this,reason+"，文件 "+fileId);
        if(ready && !recording)startFile(fileId,listed,false);
    }
    private void deferReadback(String reason) {
        String detail=reason+"，已保留原音，30 分钟后再核对，也可手动重试";
        abandon(detail,System.currentTimeMillis()+30*60_000L);problem(detail);
    }
    private void failActive(String reason) {if(active!=null)cooldown.put(active.fileId,SystemClock.elapsedRealtime()+60000);abandon(reason);problem(reason);}
    private void abandon(String reason) {abandon(reason,0);}
    private void abandon(String reason,long retryAt) {
        Active a=active;active=null;RuntimeTelemetry.bleTransfer=null;if(a==null)return;
        traceTransfer(a,reason);
        if(a.live && a.readback==null) {
            long contiguousBytes=0L;
            if(decoder.rejected==a.rejectedAtStart && a.listSize!=OfflineReadback.FRAME_MILLIS
                    && a.assembler.firstMissingSequence()>=2) {
                contiguousBytes=a.assembler.firstMissingSequence()*160L;
                RecordingAssembler.resetResumeAttempts(a.source);
                try{a.assembler.checkpoint(0L);}catch(IOException ignored){ }
            } else {
                RecordingAssembler.clearResume(a.source);
            }
            try{a.assembler.close();}catch(IOException ignored){ }
            if(contiguousBytes==0L && a.listSize!=OfflineReadback.FRAME_MILLIS)a.source.delete();
            store.progress(a.id,contiguousBytes);
            store.waiting(a.id,reason,retryAt);
            EventLog.add(this,reason+"，文件 "+a.fileId);
            return;
        }
        if(!a.live && a.readback==null) {
            long contiguousBytes=a.assembler.firstMissingSequence()*160L;
            boolean progressed=a.assembler.overlapVerified()
                    && contiguousBytes>=a.initialBytes+RecordingAssembler.RESUME_ALIGN_BYTES;
            if(progressed)RecordingAssembler.resetResumeAttempts(a.source);
            if(!progressed && a.resumeAttempt>=2) {
                try{a.assembler.close();}catch(IOException ignored){ }
                RecordingAssembler.clearResume(a.source);
                a.source.delete();
                forceZeroOffset.add(a.fileId);
                store.progress(a.id,0L);
                String purgedMsg="断点续传连续失败，已删除手机旧分片等待从零重收";
                store.waiting(a.id,purgedMsg,retryAt);
                EventLog.add(this,purgedMsg+"，文件 "+a.fileId);
                return;
            }
            if(a.headSize>0)try{a.assembler.checkpoint(a.headSize);}catch(IOException ignored){ }
            store.progress(a.id,contiguousBytes);
        } else {
            store.progress(a.id,a.assembler.bytes());
        }
        try {a.assembler.close();}catch(IOException ignored) { }
        store.waiting(a.id,reason,retryAt);EventLog.add(this,reason+"，文件 "+a.fileId);
    }
    private void traceTransfer(Active a,String reason) {
        EventLog.add(this,"接收摘要 id="+a.fileId+" mode="+(a.live?"live":"offline")
                +" bytes="+a.assembler.bytes()+" expected="+a.expected+" sizeUnit="+a.sizeUnit+" headSize="+a.headSize+" listSize="+a.listSize
                +" frames="+a.assembler.frames()+" first="+a.assembler.firstSequence()+" last="+a.assembler.lastSequence()
                +" firstMissing="+a.assembler.firstMissingSequence()+" gaps="+a.assembler.gapsBeforeLast()
                +" duplicates="+a.duplicates+" flags="+a.sliceFlags+" key="+(a.key!=null)+" end="+a.end.received()
                +" rejected="+Math.max(0,decoder.rejected-a.rejectedAtStart)+" result="+reason);
    }
    private void problem(String message) {setStatus(message);EventLog.add(this,message);}
    private void setStatus(String text) {
        status=text;Settings.prefs(this).edit().putString("status",text).putLong("heartbeat",System.currentTimeMillis()).apply();
        if(started && !closed)getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());
    }
    private Notification notification() {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RecorderService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle(getString(R.string.app_name)+" · 后台连接")
                .setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE).addAction(new Notification.Action.Builder(null,"停止连接",stop).build()).build();
    }
    static final class Active {
        final long fileId;final String id;boolean live;final RecordingAssembler assembler;
        final File source;final OfflineReadback readback;
        final TransferEnd end=new TransferEnd();final Set<Integer> sliceFlags=new TreeSet<>();int rejectedAtStart;final RecordingSizeUnit sizeUnit;
        final Set<Long> highlights=new TreeSet<>();
        boolean backfill;LiveBackfill.Phase backfillPhase;long liveEndMs;
        final long startedAt=SystemClock.elapsedRealtime();
        long expected,headSize=-1,listSize,closedListSize,lastData=startedAt,lastSliceAt=startedAt,stoppedAt,settle,duplicates,initialBytes,resumedOffset;
        int resumeAttempt;
        boolean stopConfirmed,finalSize;DeviceCrypto.FileKey key;
        Active(long fileId,String id,long size,boolean live,File source,OfflineReadback readback,int rejected,RecordingSizeUnit sizeUnit) throws IOException {
            this(fileId,id,size,live,source,readback,rejected,sizeUnit,false);
        }
        Active(long fileId,String id,long size,boolean live,File source,OfflineReadback readback,int rejected,RecordingSizeUnit sizeUnit,boolean resume) throws IOException {
            this.fileId=fileId;this.id=id;this.expected=size;this.listSize=size;this.live=live;this.source=source;this.readback=readback;
            this.assembler=new RecordingAssembler(source,resume);this.initialBytes=this.assembler.bytes();
            this.rejectedAtStart=rejected;this.sizeUnit=sizeUnit;
        }
    }
}
