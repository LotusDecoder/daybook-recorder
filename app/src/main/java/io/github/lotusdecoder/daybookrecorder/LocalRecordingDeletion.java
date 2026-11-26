package io.github.lotusdecoder.daybookrecorder;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.io.IOException;
import java.util.function.Consumer;

/** File deletion and BLE file writes share a Looper, even when no BLE service is running. */
final class LocalRecordingDeletion {
    private static HandlerThread thread;
    private static RecorderService owner; // Accessed only on the recording Looper.
    static synchronized Looper looper() {
        if(thread==null){thread=new HandlerThread("daybook-recordings");thread.start();}
        return thread.getLooper();
    }
    static void attach(RecorderService service){owner=service;}
    static void detach(RecorderService service){if(owner==service)owner=null;}
    static void delete(Context context,String id,Consumer<String> complete) {
        Context app=context.getApplicationContext();
        // A separate Handler keeps this request alive when Service.onDestroy clears its callbacks.
        new Handler(looper()).post(()->{
            String result;
            try(RecordingStore store=new RecordingStore(app)) {
                if(owner!=null)owner.stopForLocalDeletion(id);
                store.releaseLocalWork(id);
                store.deleteLocal(id);
                result="手机副本已删除，不会自动补收";
            }catch(IOException e){result="手机文件尚未全部删除，已暂停补收和上传；可重试删除";}
            catch(RuntimeException e){result="手机副本删除未完成，请重试";}
            complete.accept(result);
        });
    }
}
