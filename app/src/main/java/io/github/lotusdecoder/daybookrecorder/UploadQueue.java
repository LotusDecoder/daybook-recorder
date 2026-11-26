package io.github.lotusdecoder.daybookrecorder;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Durable retry queue; accepting server must echo BOTH idempotency key and content digest. */
final class UploadQueue implements AutoCloseable {
    private final Context context;
    private final RecordingStore store;
    private final Runnable onSettled;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean busy=new AtomicBoolean();
    private final AtomicBoolean pendingKick=new AtomicBoolean();
    private final AtomicInteger cleanupWaiting=new AtomicInteger();
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    UploadQueue(Context c,RecordingStore s) {this(c,s,null);}
    UploadQueue(Context c,RecordingStore s,Runnable onSettled) {context=c.getApplicationContext();store=s;this.onSettled=onSettled;}
    boolean isBusy() {return busy.get();}
    void reconcileLocal() {
        if(closed)return;
        try{executor.execute(()->{if(!closed)store.reconcileDuplicates();});}catch(RejectedExecutionException ignored){ }
    }
    void kick() {
        if(closed || Settings.prefs(context).getString("endpoint","").isEmpty())return;
        if(!busy.compareAndSet(false,true)){pendingKick.set(true);return;}
        try {executor.execute(()->{
            boolean progressed=false;
            try {
                for(int i=0;i<5000 && !closed && cleanupWaiting.get()==0;i++) {
                    RecordingStore.Row row=store.nextUpload();if(row==null)break;
                    if(store.reconcileIfAlreadySent(row)) {
                        progressed=true;
                        EventLog.add(context,"跳过重复上传（本机已有相同 SHA256 云端回执）："+row.fileId);
                        if(onSettled!=null)onSettled.run();
                        continue;
                    }
                    try {
                        send(row,false);
                        if(!closed){
                            store.sent(row.id);store.reconcileDuplicates();progressed=true;
                            EventLog.add(context,"中转确认接收："+row.fileId);
                            if(onSettled!=null)onSettled.run();
                        }
                    }catch(Exception e) {
                        if(!closed){
                            long delay=store.failedUpload(row,safeError(e));
                            progressed=true;
                            try{deadlines.schedule(this::kick,delay+150L,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){ }
                        }
                    }
                }
            }finally {
                busy.set(false);
                if(!closed && progressed && onSettled!=null)onSettled.run();
                if(!closed && cleanupWaiting.get()==0 && pendingKick.getAndSet(false))kick();
            }
        });}catch(RejectedExecutionException ignored){busy.set(false);}
    }
    interface Verification {void complete(String endpoint,String error);}
    /** Repeat the idempotent upload: the receiver rehashes its on-disk copy. */
    void verifyForCleanup(RecordingStore.Row row,Verification callback) {
        if(closed){callback.complete("","上传服务已停止");return;}
        cleanupWaiting.incrementAndGet();
        try {executor.execute(()->{
            try {
                if(closed)return;
                String endpoint=send(row,true);
                if(!closed)callback.complete(endpoint,"");
            }catch(Exception e){if(!closed)callback.complete("",safeError(e));}
            finally{cleanupWaiting.decrementAndGet();}
        });}catch(RejectedExecutionException e){cleanupWaiting.decrementAndGet();callback.complete("","上传服务已停止");}
    }
    private String send(RecordingStore.Row row,boolean cleanup) throws Exception {
        requireLocal(row.id);
        Settings.UploadTarget target=Settings.uploadTarget(context);
        String endpoint=target.endpoint;
        if(endpoint.isEmpty())throw new IOException("未配置中转地址");
        File f=store.file(row.id,".ogg");
        if(!f.isFile() || !RecordingStore.hash(f).equals(row.sha))throw new IOException("本地音频校验失败");
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();active=c;
        // HttpURLConnection readTimeout does not bound a stalled upload write.
        ScheduledFuture<?> deadline=deadlines.schedule(c::disconnect,90,TimeUnit.SECONDS);
        try {
            requireLocal(row.id);
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(45000);
            c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(f.length());
            c.setRequestProperty("Content-Type","audio/ogg");c.setRequestProperty("Accept","application/json");
            c.setRequestProperty("Idempotency-Key",row.id);c.setRequestProperty("X-Audio-SHA256",row.sha);
            c.setRequestProperty("X-Recorded-At",Instant.ofEpochMilli(row.recordedAt).toString());
            c.setRequestProperty("X-Duration-Ms",String.valueOf(row.duration));
            c.setRequestProperty("X-Source-File-Id",String.valueOf(row.fileId));
            c.setRequestProperty("X-Source-Device",row.device);
            if(!target.token.isEmpty())c.setRequestProperty("Authorization","Bearer "+target.token);
            long totalLen=f.length(),sentLen=0L,startedNs=System.nanoTime();
            RuntimeTelemetry.httpUpload=new RuntimeTelemetry.Transfer(row.id,row.fileId,true,false,cleanup,0L,totalLen,row.duration,0.0);
            try(OutputStream out=c.getOutputStream();InputStream in=new FileInputStream(f)) {
                byte[] b=new byte[16384];int n;
                while((n=in.read(b))!=-1) {
                    if(closed)throw new InterruptedIOException();
                    requireLocal(row.id);
                    out.write(b,0,n);
                    sentLen+=n;
                    double elapsedSec=Math.max(0.05,(System.nanoTime()-startedNs)/1_000_000_000.0);
                    RuntimeTelemetry.httpUpload=new RuntimeTelemetry.Transfer(row.id,row.fileId,true,false,cleanup,sentLen,totalLen,row.duration,sentLen/elapsedSec);
                }
            }
            int code=c.getResponseCode();
            if(code==410) {
                JSONObject removed=readReply(c.getErrorStream());
                if("recording_deleted".equals(removed.optString("error")) && row.id.equals(removed.optString("import_key")) && row.sha.equals(removed.optString("audio_sha256"))) {
                    store.remoteDeleted(row.id);throw new IOException("中转副本已手动删除，停止重试并保留手机副本");
                }
            }
            if(code!=200 && code!=201 && code!=202)throw new IOException("HTTP "+code);
            if(cleanup && code==202)throw new IOException("中转只返回排队状态，不能清理豆内副本");
            JSONObject ack=readReply(c.getInputStream());
            if(!row.id.equals(ack.optString("import_key")) || !row.sha.equals(ack.optString("audio_sha256"))
                    || !"accepted".equals(ack.optString("status")))throw new IOException("中转回执校验失败");
            if(cleanup && !RecordingStore.hash(f).equals(row.sha))throw new IOException("本地音频在核对期间变化");
            requireLocal(row.id);
            return endpoint;
        }finally {RuntimeTelemetry.httpUpload=null;deadline.cancel(false);c.disconnect();active=null;}
    }
    private void requireLocal(String id) throws IOException {
        RecordingStore.Row row=store.get(id);
        if(row==null || row.localDeleted())throw new IOException("本地副本已删除，已停止上传");
        if(row.state.equals("SERVER_DELETED"))throw new IOException("中转副本已手动删除，已停止上传");
    }
    private static JSONObject readReply(InputStream stream) throws Exception {
        ByteArrayOutputStream response=new ByteArrayOutputStream();
        if(stream==null)throw new IOException("中转回执为空");
        try(InputStream in=stream) {byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1) {if(response.size()+n>8192)throw new IOException("中转回执过大");response.write(b,0,n);}}
        return new JSONObject(new String(response.toByteArray(),StandardCharsets.UTF_8));
    }
    private static String safeError(Exception e) {
        if(e instanceof IOException && e.getMessage()!=null && (e.getMessage().matches("HTTP [0-9]{3}") || e.getMessage().startsWith("中转") || e.getMessage().startsWith("本地")))return e.getMessage();
        return "上传未完成，稍后自动重试（"+e.getClass().getSimpleName()+"）";
    }
    @Override public void close() {closed=true;HttpURLConnection c=active;if(c!=null)c.disconnect();executor.shutdownNow();deadlines.shutdownNow();}
}
