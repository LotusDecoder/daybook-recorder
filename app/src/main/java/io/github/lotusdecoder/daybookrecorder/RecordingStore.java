package io.github.lotusdecoder.daybookrecorder;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.*;
import java.util.*;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;
import io.github.lotusdecoder.daybookrecorder.core.OggOpus;

final class RecordingStore extends SQLiteOpenHelper {
    private static final Object DELETE_LOCK=new Object();
    private final File root,cache;
    RecordingStore(Context c) { super(c,"recordings.db",null,5); root=new File(c.getFilesDir(),"recordings"); cache=c.getCacheDir(); if(!root.isDirectory())root.mkdirs(); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE recordings (id TEXT PRIMARY KEY, device TEXT NOT NULL, file_id INTEGER NOT NULL, recorded_at INTEGER NOT NULL, stopped_at INTEGER NOT NULL DEFAULT 0, expected INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, bytes INTEGER NOT NULL DEFAULT 0, duration INTEGER NOT NULL DEFAULT 0, sha TEXT NOT NULL DEFAULT '', attempts INTEGER NOT NULL DEFAULT 0, next_attempt INTEGER NOT NULL DEFAULT 0, uploaded_at INTEGER NOT NULL DEFAULT 0, error TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX ready_queue ON recordings(state,next_attempt)");
        addCleanupColumns(db);
        addConnectionSyncColumn(db);
        addReconciliationColumns(db);
        addReadbackColumn(db);
    }
    private static void addCleanupColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_state TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_detail TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_verified_at INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_next_attempt INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_attempts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("CREATE INDEX cleanup_queue ON recordings(device,state,cleanup_state,cleanup_next_attempt)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) {
        if(oldV<1 || newV!=5)throw new IllegalStateException("unsupported database version");
        if(oldV<2)addCleanupColumns(db);
        if(oldV<3)addConnectionSyncColumn(db);
        if(oldV<4)addReconciliationColumns(db);
        if(oldV<5)addReadbackColumn(db);
    }
    private static void addReadbackColumn(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE recordings ADD COLUMN readback_verified INTEGER NOT NULL DEFAULT 0");
    }
    private static void addConnectionSyncColumn(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE recordings ADD COLUMN connection_sync INTEGER NOT NULL DEFAULT 0");
    }
    private static void addReconciliationColumns(SQLiteDatabase db) {
        db.execSQL("ALTER TABLE recordings ADD COLUMN cleanup_acknowledged INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE recordings ADD COLUMN duplicate_of TEXT NOT NULL DEFAULT ''");
        db.execSQL("ALTER TABLE recordings ADD COLUMN receive_generation INTEGER NOT NULL DEFAULT 0");
        // AFTER is the only unfinished legacy phase that proves a successful device ACK.
        db.execSQL("UPDATE recordings SET cleanup_acknowledged=1 WHERE cleanup_state='AFTER'");
    }
    /** Only called with a complete idle snapshot. Preserve tombstones and cleanup outcomes. */
    synchronized void enqueueConnectionSync(String device,Map<Long,Protocol.Entry> files,boolean withEnd) {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try {
            for(Protocol.Entry entry:files.values()) {
                if(entry.id<=0 || entry.size<=0 || (withEnd && entry.endSeconds<=0))continue;
                Row row=ensure(device,entry.id,entry.size);
                if(row.localDeleted() || row.reconciled() || row.state.equals("SERVER_DELETED"))continue;
                ContentValues v=new ContentValues();v.put("connection_sync",1);update(row.id,v);
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    static String deviceId(String address) { return hash(address.getBytes(StandardCharsets.UTF_8)).substring(0,16); }
    static String id(String device,long file) { return "soundcore-"+device+"-"+file; }
    static String hash(byte[] bytes) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String hash(File f) throws IOException {
        try {
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            try(InputStream in=new FileInputStream(f)) { byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)d.update(b,0,n); }
            return hex(d.digest());
        }catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String hex(byte[] b) { StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString(); }
    File file(String id,String suffix) {
        if(!id.matches("soundcore-[a-f0-9]{16}-[0-9]{1,10}"))throw new IllegalArgumentException("invalid recording id");
        return new File(root,id+suffix);
    }
    synchronized void recover() {
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("UPDATE recordings SET state='WAITING', error='接收中断，等待重新补收' WHERE state='RECEIVING'");
        db.execSQL("UPDATE recordings SET cleanup_state='RETRY',cleanup_detail='核对中断，稍后重新核对',cleanup_next_attempt=? WHERE cleanup_state IN ('VERIFYING','INFO','BEFORE','CONTENT')",new Object[]{System.currentTimeMillis()+60000});
        db.execSQL("UPDATE recordings SET cleanup_state='UNKNOWN',cleanup_detail='删除时连接或进程中断，结果待检查；不会自动重发' WHERE cleanup_state IN ('ACK','AFTER')");
        db.execSQL("UPDATE recordings SET cleanup_state='UNKNOWN' WHERE cleanup_state='RETRY' AND cleanup_acknowledged=1");
        finishPendingDeletes();
    }
    synchronized Row ensure(String device,long fileId,long expected) {
        String id=id(device,fileId);Row old=get(id);
        if(old==null) {
            ContentValues v=new ContentValues();v.put("id",id);v.put("device",device);v.put("file_id",fileId);
            // Timestamp-like device IDs are the source recording time, not upload time.
            v.put("recorded_at",fileId>=946684800L && fileId<=4102444800L ? fileId*1000 : System.currentTimeMillis());
            v.put("expected",expected);v.put("state","WAITING");getWritableDatabase().insertOrThrow("recordings",null,v);
        } else if(expected>0 && !old.saved() && !old.localDeleted() && !old.reconciled()) {ContentValues v=new ContentValues();v.put("expected",expected);update(id,v);}
        return get(id);
    }
    synchronized boolean receiving(String id) {
        Row row=get(id);if(row==null || !row.state.equals("WAITING"))return false;
        ContentValues v=new ContentValues();v.put("state","RECEIVING");v.put("error","");v.put("bytes",0);
        v.put("receive_generation",row.receiveGeneration+1);
        return getWritableDatabase().update("recordings",v,"id=? AND state='WAITING' AND receive_generation=?",new String[]{id,String.valueOf(row.receiveGeneration)})==1;
    }
    synchronized void stopped(String id) {ContentValues v=new ContentValues();v.put("stopped_at",System.currentTimeMillis());update(id,v);}
    synchronized void waiting(String id,String reason) {waiting(id,reason,0);}
    synchronized void waiting(String id,String reason,long retryAt) {
        ContentValues v=new ContentValues();v.put("state","WAITING");v.put("error",reason);v.put("next_attempt",retryAt);update(id,v);
    }
    synchronized void progress(String id,long bytes) {ContentValues v=new ContentValues();v.put("bytes",bytes);update(id,v);}
    long saveReceived(String id,long fileId,File source,int frames,boolean matchedReadback) throws IOException {
        File temp=file(id,".ogg.tmp");
        long duration=OggOpus.mux(source,temp,(int)fileId);
        if(matchedReadback && duration!=frames*(long)OfflineReadback.FRAME_MILLIS)
            throw new IOException("Opus 时长与分片数不符");
        String sha=hash(temp);
        Files.move(source.toPath(),file(id,".opus.bin").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        Files.move(temp.toPath(),file(id,".ogg").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        file(id,".part.resume").delete();
        file(id,".part.resume.tmp").delete();
        file(id,".part.attempts").delete();
        ready(id,frames*160L,duration,sha,matchedReadback);
        if(matchedReadback)file(id,".readback-first").delete(); // Now redundant with the committed raw audio.
        return duration;
    }
    synchronized void ready(String id,long bytes,long duration,String sha) {
        ready(id,bytes,duration,sha,false);
    }
    private synchronized void ready(String id,long bytes,long duration,String sha,boolean matchedReadback) {
        ContentValues v=new ContentValues();v.put("bytes",bytes);v.put("duration",duration);v.put("sha",sha);v.put("state","READY");v.put("error","");v.put("next_attempt",0L);
        Row old=get(id);if(old!=null && !old.sha.equals(sha))v.put("cleanup_acknowledged",0);
        v.put("readback_verified",matchedReadback);
        update(id,v);
    }
    synchronized void sent(String id) {
        ContentValues v=new ContentValues();v.put("state","SENT");v.put("uploaded_at",System.currentTimeMillis());v.put("error","");
        getWritableDatabase().update("recordings",v,"id=? AND state='READY'",new String[]{id});
    }
    synchronized void remoteDeleted(String id) {
        ContentValues v=new ContentValues();v.put("state","SERVER_DELETED");v.put("error","服务器副本已手动删除，已停止上传和自动豆内清理");
        getWritableDatabase().update("recordings",v,"id=? AND state IN ('READY','SENT')",new String[]{id});
        detachDuplicates(id);
    }
    synchronized long failedUpload(Row row,String error) {
        int attempts=row.attempts+1;long delay=Math.min(300000L,5000L*(1L<<Math.min(6,attempts-1)));
        ContentValues v=new ContentValues();v.put("attempts",attempts);v.put("error",error);v.put("next_attempt",System.currentTimeMillis()+delay);update(row.id,v);
        return delay;
    }
    synchronized void retryUploads() {getWritableDatabase().execSQL("UPDATE recordings SET next_attempt=0,attempts=0 WHERE state='READY'");}
    synchronized void retryDownloads() {getWritableDatabase().execSQL("UPDATE recordings SET next_attempt=0 WHERE state='WAITING' AND next_attempt>0");}
    synchronized void resetRetryableCleanups(String device) {
        if(device==null || device.isEmpty())return;
        getWritableDatabase().execSQL("UPDATE recordings SET cleanup_next_attempt=0 WHERE device=? AND state='SENT' AND cleanup_state='RETRY' AND cleanup_acknowledged=0",new Object[]{device});
    }
    synchronized Row nextCleanup(String device) {
        return nextCleanup(device,true,false);
    }
    synchronized Row nextCleanup(String device,boolean allRecordings,boolean connectionSync) {
        if(!allRecordings && !connectionSync)return null;
        List<Row> rows=query("device=? AND state='SENT' AND uploaded_at>0 AND cleanup_acknowledged=0 AND readback_verified=0 AND cleanup_state IN ('','RETRY') AND cleanup_next_attempt<=?"+(allRecordings?"":" AND connection_sync=1"),
                new String[]{device,String.valueOf(System.currentTimeMillis())},"recorded_at ASC, file_id ASC","1");
        return rows.isEmpty()?null:rows.get(0);
    }
    synchronized void cleanup(String id,String state,String detail) {
        ContentValues v=new ContentValues();v.put("cleanup_state",state);v.put("cleanup_detail",detail);
        if(state.equals("INFO"))v.put("cleanup_verified_at",System.currentTimeMillis());
        if(state.equals("AFTER"))v.put("cleanup_acknowledged",1);
        if(state.equals("DELETED"))v.put("cleanup_at",System.currentTimeMillis());
        if(state.equals("RETRY")) {
            Row row=get(id);int attempts=row.cleanupAttempts+1;v.put("cleanup_attempts",attempts);
            v.put("cleanup_next_attempt",System.currentTimeMillis()+Math.min(900000L,60000L*(1L<<Math.min(4,attempts-1))));
        }
        update(id,v);
    }
    /** Read-only device reconciliation: a complete idle list can finish an acknowledged deletion. */
    synchronized int reconcileAcknowledged(String device,Map<Long,Protocol.Entry> files,long activeId,boolean busy) {
        if(device.isEmpty() || activeId>0 || busy)return 0;
        int count=0;
        for(Row row:query("device=? AND state='SENT' AND cleanup_acknowledged=1 AND cleanup_state IN ('UNKNOWN','ABSENT','RETRY')",new String[]{device},null,null)) {
            if(files.containsKey(row.fileId))continue;
            cleanup(row.id,"DELETED","前次删除已收到成功回执，本次列表确认已清理；手机和中转副本保留");count++;
        }
        return count;
    }

    /** Hash full audio, never infer identity from a timestamp or byte count. No source files are removed. */
    int reconcileDuplicates() {
        int count=0;
        for(Row old:query("state='RECONCILED'",null,null,null)) {
            Row full=get(old.duplicateOf);
            try {
                if(full!=null && full.state.equals("SENT") && full.uploadedAt>0
                        && hash(file(full.id,".ogg")).equals(full.sha))continue;
            }catch(IOException ignored){ }
            detachDuplicates(old.duplicateOf);
        }
        for(Row pending:query("state='WAITING' AND bytes>0",null,null,null)) {
            List<Row> candidates=query("file_id=? AND id!=? AND state='SENT' AND uploaded_at>0 AND bytes>=?",
                    new String[]{String.valueOf(pending.fileId),pending.id,String.valueOf(pending.bytes)},null,null);
            if(candidates.isEmpty())continue;
            File source=file(pending.id,".readback-first");if(!source.isFile())source=file(pending.id,".part");
            if(!source.isFile())source=file(pending.id,".opus.bin");
            if(!source.isFile() || source.length()!=pending.bytes)continue;
            File temp=null;
            try {
                boolean matched=false;
                for(Row full:candidates) {
                    if(full.bytes==pending.bytes) {
                        if(temp==null) {
                            temp=File.createTempFile("reconcile-",".ogg",cache);
                            long duration=OggOpus.mux(source,temp,(int)pending.fileId);
                            if(duration!=full.duration){temp.delete();temp=null;continue;}
                        }
                        String sha=hash(temp);
                        if(!sha.equals(full.sha) || !sha.equals(hash(file(full.id,".ogg"))))continue;
                    } else {
                        // Partial interrupted receive under an older rotated BLE address: require exact
                        // frame-aligned decrypted Opus byte-for-byte prefix match against the verified SENT raw copy.
                        if(pending.bytes<160 || pending.bytes%160!=0)continue;
                        File fullRaw=file(full.id,".opus.bin"),fullOgg=file(full.id,".ogg");
                        if(!fullRaw.isFile() || fullRaw.length()!=full.bytes || !fullOgg.isFile()
                                || !full.sha.equals(hash(fullOgg)) || !prefixEquals(source,fullRaw,pending.bytes))continue;
                    }
                    ContentValues v=new ContentValues();v.put("state","RECONCILED");v.put("duplicate_of",full.id);
                    // A transfer or manual delete may have claimed either row while hashing.
                    count+=getWritableDatabase().update("recordings",v,
                            "id=? AND state='WAITING' AND bytes=? AND receive_generation=? AND EXISTS (SELECT 1 FROM recordings candidate WHERE candidate.id=? AND candidate.state='SENT' AND candidate.uploaded_at>0 AND candidate.sha=?)",
                            new String[]{pending.id,String.valueOf(pending.bytes),String.valueOf(pending.receiveGeneration),full.id,full.sha});
                    matched=true;
                    break;
                }
                if(matched)continue;
            }catch(IOException ignored){/* Keep the pending record and every original byte. */}
            finally{if(temp!=null)temp.delete();}
        }
        return count;
    }
    private static boolean prefixEquals(File prefixFile,File fullFile,long length) throws IOException {
        if(length<=0 || prefixFile.length()!=length || fullFile.length()<length)return false;
        try(InputStream a=new BufferedInputStream(new FileInputStream(prefixFile));
            InputStream b=new BufferedInputStream(new FileInputStream(fullFile))) {
            byte[] ba=new byte[16384],bb=new byte[16384];
            long rem=length;
            while(rem>0) {
                int step=(int)Math.min(ba.length,rem);
                int ra=readExact(a,ba,step),rb=readExact(b,bb,step);
                if(ra!=step || rb!=step)return false;
                for(int i=0;i<step;i++)if(ba[i]!=bb[i])return false;
                rem-=step;
            }
            return true;
        }
    }
    private static int readExact(InputStream in,byte[] buf,int len) throws IOException {
        int off=0;
        while(off<len){int n=in.read(buf,off,len-off);if(n<0)break;off+=n;}
        return off;
    }
    private void detachDuplicates(String fullId) {
        ContentValues v=new ContentValues();v.put("state","WAITING");v.put("duplicate_of","");
        v.put("error","已核对的完整副本已变化，保留原分片等待检查");
        getWritableDatabase().update("recordings",v,"state='RECONCILED' AND duplicate_of=?",new String[]{fullId});
    }
    synchronized void redownloadReconciled(String id) {
        ContentValues v=new ContentValues();v.put("state","WAITING");v.put("duplicate_of","");v.put("error","按用户请求重新下载");
        getWritableDatabase().update("recordings",v,"id=? AND state='RECONCILED'",new String[]{id});
    }
    /** Prevent downloading a duplicate copy when any row on this phone already saved the same recording. */
    synchronized Row findCompleteByFileId(long fileId,long beanListSize) {
        if(fileId<=0)return null;
        for(Row cand:query("file_id=? AND state IN ('SENT','READY','SERVER_DELETED')",
                new String[]{String.valueOf(fileId)},"CASE WHEN state='SENT' THEN 0 WHEN state='READY' THEN 1 ELSE 2 END, uploaded_at DESC",null)) {
            if(cand.sha.isEmpty() || cand.duration<=0)continue;
            if(beanListSize>OfflineReadback.FRAME_MILLIS && cand.duration!=beanListSize && !cand.readbackVerified)continue;
            File ogg=file(cand.id,".ogg");
            try {
                if(ogg.isFile() && cand.sha.equals(hash(ogg)))return cand;
            }catch(IOException ignored){ }
        }
        return null;
    }
    /** Prevent uploading identical audio twice under different device hashes / Idempotency-Keys. */
    synchronized boolean reconcileIfAlreadySent(Row row) {
        if(row==null || row.sha.isEmpty())return false;
        for(Row sent:query("id!=? AND state='SENT' AND uploaded_at>0 AND sha=?",
                new String[]{row.id,row.sha},"uploaded_at ASC","1")) {
            ContentValues v=new ContentValues();v.put("state","RECONCILED");v.put("duplicate_of",sent.id);v.put("error","");
            int updated=getWritableDatabase().update("recordings",v,
                    "id=? AND state='READY' AND EXISTS (SELECT 1 FROM recordings c WHERE c.id=? AND c.state='SENT' AND c.uploaded_at>0 AND c.sha=?)",
                    new String[]{row.id,sent.id,row.sha});
            if(updated==1)return true;
        }
        return false;
    }
    synchronized void markReconciledTo(String pendingId,String sentId) {
        if(pendingId==null || sentId==null || pendingId.equals(sentId))return;
        ContentValues v=new ContentValues();v.put("state","RECONCILED");v.put("duplicate_of",sentId);v.put("error","");
        getWritableDatabase().update("recordings",v,
                "id=? AND state='WAITING' AND EXISTS (SELECT 1 FROM recordings c WHERE c.id=? AND c.state='SENT' AND c.uploaded_at>0)",
                new String[]{pendingId,sentId});
    }
    synchronized boolean claimCleanup(String id) {
        ContentValues v=new ContentValues();v.put("cleanup_state","VERIFYING");
        return getWritableDatabase().update("recordings",v,"id=? AND state='SENT' AND cleanup_state NOT IN ('VERIFYING','INFO','BEFORE','CONTENT','ACK','AFTER')",new String[]{id})==1;
    }
    /** Only after the recording Looper has stopped this row's work (or no service owns it). */
    void releaseLocalWork(String id) {
        SQLiteDatabase db=getWritableDatabase();
        db.execSQL("UPDATE recordings SET state='WAITING' WHERE id=? AND state='RECEIVING'",new Object[]{id});
        db.execSQL("UPDATE recordings SET cleanup_state='RETRY',cleanup_detail='用户删除手机副本，已停止核验' WHERE id=? AND cleanup_state IN ('VERIFYING','INFO','BEFORE','CONTENT')",new Object[]{id});
        db.execSQL("UPDATE recordings SET cleanup_state='UNKNOWN',cleanup_detail='用户删除手机副本，前次豆内清理结果待检查；不会自动重发' WHERE id=? AND cleanup_state IN ('ACK','AFTER')",new Object[]{id});
    }
    /** A durable state change arbitrates against transfer/cleanup claims across SQLite helpers. */
    void deleteLocal(String id) throws IOException {
        file(id,".ogg"); // Validate before touching the database or filesystem.
        synchronized(DELETE_LOCK) {
            ContentValues v=new ContentValues();v.put("state","LOCAL_DELETING");v.put("error","");
            SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
            try {
                int count=db.update("recordings",v,"id=? AND state!='RECEIVING' AND cleanup_state NOT IN ('VERIFYING','INFO','BEFORE','CONTENT','ACK','AFTER')",new String[]{id});
                if(count!=1)throw new IOException("正在接收或核验清理，请完成或停止后台连接后再删除");
                detachDuplicates(id);db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            // Keep the ID even after unlinking so inventory scans/retry callbacks cannot resurrect it.
            purgeLocal(id);
        }
    }
    private void purgeLocal(String id) throws IOException {
        for(String suffix:new String[]{".ogg",".opus.bin",".part",".part.resume",".part.resume.tmp",".part.attempts",".ogg.tmp",".readback",".readback-first",".readback-original"})Files.deleteIfExists(file(id,suffix).toPath());
        try {
            FileDescriptor directory=android.system.Os.open(root.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);
            try{android.system.Os.fsync(directory);}finally{android.system.Os.close(directory);}
        }catch(android.system.ErrnoException e){throw new IOException("本地删除尚未落盘",e);}
        ContentValues v=new ContentValues();v.put("state","LOCAL_DELETED");v.put("error","");
        getWritableDatabase().update("recordings",v,"id=? AND state='LOCAL_DELETING'",new String[]{id});
    }
    void finishPendingDeletes() {
        synchronized(DELETE_LOCK) {
            for(Row row:query("state IN ('LOCAL_DELETING','LOCAL_DELETED')",null,"recorded_at ASC",null)) {
                try{purgeLocal(row.id);}catch(IOException e){/* Visible retry entry remains until all files are gone. */}
            }
        }
    }
    void restoreLocal(String id) throws IOException {
        synchronized(DELETE_LOCK) {
            List<Row> rows=query("id=?",new String[]{id},null,"1");if(rows.isEmpty() || !rows.get(0).localDeleted())return;
            purgeLocal(id);
            ContentValues v=new ContentValues();v.put("state","WAITING");v.put("error","按用户请求重新下载");
            v.put("attempts",0);v.put("next_attempt",0L);
            getWritableDatabase().update("recordings",v,"id=? AND state='LOCAL_DELETED'",new String[]{id});
        }
    }
    private void update(String id,ContentValues v) {getWritableDatabase().update("recordings",v,"id=? AND state NOT IN ('LOCAL_DELETING','LOCAL_DELETED','RECONCILED')",new String[]{id});}
    synchronized Row get(String id) {
        try(Cursor c=getReadableDatabase().query("recordings",null,"id=?",new String[]{id},null,null,null)) {return c.moveToFirst()?new Row(c):null;}
    }
    synchronized List<Row> latest() {return query("state!='LOCAL_DELETED'",null,"recorded_at DESC","30");}
    synchronized List<Row> copiesRows() {
        return query("state!='LOCAL_DELETED' AND (state IN ('WAITING','RECEIVING','READY','LOCAL_DELETING') OR id IN (SELECT id FROM recordings WHERE state!='LOCAL_DELETED' ORDER BY recorded_at DESC, id LIMIT 30))",
                null,"recorded_at DESC, id",null);
    }
    synchronized List<Row> diagnosticRows() {
        return query("state IN ('WAITING','RECEIVING') OR id IN (SELECT id FROM recordings WHERE state!='LOCAL_DELETED' ORDER BY recorded_at DESC, id LIMIT 30)",
                null,"recorded_at DESC, id",null);
    }
    synchronized List<Row> forDevice(String device) {return query("device=?",new String[]{device},"recorded_at DESC",null);}
    synchronized Row nextUpload() {
        List<Row> rows=query("state='READY' AND next_attempt<=?",new String[]{String.valueOf(System.currentTimeMillis())},"recorded_at ASC, file_id ASC","1");return rows.isEmpty()?null:rows.get(0);
    }
    synchronized List<Row> pendingRows(String endpoint) {
        String where=(endpoint==null || endpoint.isEmpty())?"state IN ('RECEIVING','WAITING')":"state IN ('RECEIVING','WAITING','READY')";
        return query(where,null,"recorded_at ASC, file_id ASC",null);
    }
    synchronized String summary() {
        int saved=0,waiting=0,sent=0,reconciled=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT state,COUNT(*) FROM recordings GROUP BY state",null)) {
            while(c.moveToNext()) {String s=c.getString(0);int n=c.getInt(1);if(s.equals("SENT")){sent+=n;saved+=n;}else if(s.equals("READY")||s.equals("SERVER_DELETED"))saved+=n;else if(s.equals("RECONCILED"))reconciled+=n;else if(!s.startsWith("LOCAL_DELET"))waiting+=n;}
        }
        return "本地完整 "+saved+" 条 · 待补收 "+waiting+" 条 · 中转已接收 "+sent+" 条"+(reconciled>0?" · 已核对重复记录 "+reconciled+" 条":"");
    }
    synchronized String connectionSyncSummary(String device) {
        int receive=0,upload=0,verify=0,done=0,absent=0,check=0,skipped=0;
        for(Row row:query("device=? AND connection_sync=1",new String[]{device},null,null)) {
            if(row.localDeleted() || row.reconciled() || row.state.equals("SERVER_DELETED")){skipped++;continue;}
            if(!row.saved()){receive++;continue;}
            if(row.state.equals("READY")){upload++;continue;}
            if(row.cleanupState.equals("DELETED")){done++;continue;}
            if(row.cleanupState.equals("ABSENT")){absent++;continue;}
            if(row.readbackVerified || row.cleanupState.equals("UNKNOWN") || row.cleanupState.equals("FAILED")){check++;continue;}
            verify++;
        }
        if(receive+upload+verify+done+absent+check+skipped==0)return "连接就绪后自动检查豆内遗留录音";
        return "遗留同步：待补收 "+receive+" · 待上传 "+upload+" · 待核验清理 "+verify+" · 已清理 "+done
                +(absent>0?" · 豆内已无 "+absent:"")+(check>0?" · 待检查 "+check:"")+(skipped>0?" · 已跳过 "+skipped:"");
    }
    private List<Row> query(String where,String[] args,String order,String limit) {
        List<Row> rows=new ArrayList<>();try(Cursor c=getReadableDatabase().query("recordings",null,where,args,null,null,order,limit)) {while(c.moveToNext())rows.add(new Row(c));}return rows;
    }
    static final class Row {
        final String id,device,state,sha,error,cleanupState,cleanupDetail,duplicateOf;
        final long fileId,recordedAt,stoppedAt,expected,bytes,duration,uploadedAt,cleanupAt,cleanupVerifiedAt,cleanupNextAttempt,receiveGeneration,nextAttempt;
        final int attempts,cleanupAttempts;
        final boolean connectionSync,cleanupAcknowledged,readbackVerified;
        Row(Cursor c) {
            id=s(c,"id");device=s(c,"device");state=s(c,"state");sha=s(c,"sha");error=s(c,"error");
            fileId=n(c,"file_id");recordedAt=n(c,"recorded_at");stoppedAt=n(c,"stopped_at");expected=n(c,"expected");bytes=n(c,"bytes");duration=n(c,"duration");uploadedAt=n(c,"uploaded_at");attempts=(int)n(c,"attempts");
            cleanupState=s(c,"cleanup_state");cleanupDetail=s(c,"cleanup_detail");cleanupAt=n(c,"cleanup_at");cleanupVerifiedAt=n(c,"cleanup_verified_at");cleanupNextAttempt=n(c,"cleanup_next_attempt");cleanupAttempts=(int)n(c,"cleanup_attempts");
            connectionSync=n(c,"connection_sync")!=0;
            cleanupAcknowledged=n(c,"cleanup_acknowledged")!=0;duplicateOf=s(c,"duplicate_of");
            receiveGeneration=n(c,"receive_generation");nextAttempt=n(c,"next_attempt");readbackVerified=n(c,"readback_verified")!=0;
        }
        boolean saved() {return state.equals("READY")||state.equals("SENT")||state.equals("SERVER_DELETED");}
        boolean localDeleted() {return state.equals("LOCAL_DELETED")||state.equals("LOCAL_DELETING");}
        boolean reconciled() {return state.equals("RECONCILED");}
        String manualReadbackNotice() {
            if(!readbackVerified)return "";
            return String.format(Locale.ROOT,"豆内标注 %d ms，手机保存了 %.2f 秒。确认后会从豆内重读整段原音，内容与手机和中转备份一致才删除。\n\n",
                    expected,duration/1000.0);
        }
        String cleanupDisplay() {
            String detail=cleanupState.equals("ABSENT")?"最近核对时豆内已无此录音；手机和中转副本保留":cleanupDetail;
            if(!readbackVerified || !saved())return detail;
            String proof="两次补收原音一致。豆内标注 20 ms，手机音频 "+duration+" ms，已跳过自动清理";
            return detail.isEmpty()?proof:proof+"\n"+detail;
        }
        private static String s(Cursor c,String name){return c.getString(c.getColumnIndexOrThrow(name));}
        private static long n(Cursor c,String name){return c.getLong(c.getColumnIndexOrThrow(name));}
    }
}
