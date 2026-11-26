package io.github.lotusdecoder.daybookrecorder;

import android.content.*;
import java.util.*;
import org.json.*;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;

/** Display-only, last complete device inventory. Never used as deletion proof. */
final class DeviceFilesStore {
    private final SharedPreferences prefs;
    DeviceFilesStore(Context context){prefs=context.getSharedPreferences("device_files",Context.MODE_PRIVATE);}
    void reading(String device) {
        SharedPreferences.Editor edit=prefs.edit();
        if(!device.equals(prefs.getString("device","")))edit.clear();
        edit.putString("device",device).putString("state","READING").putString("detail","正在读取豆内完整列表").apply();
    }
    void complete(String device,Map<Long,Protocol.Entry> files,long activeId,boolean withEnd,String firmware) {
        if(files.size()>5000)throw new IllegalArgumentException("too many device files");
        JSONArray array=new JSONArray();
        TreeMap<Long,Protocol.Entry> sorted=new TreeMap<>(Collections.reverseOrder());sorted.putAll(files);
        for(Protocol.Entry entry:sorted.values()) {
            if(entry.id<=0 || entry.id>0xffff_ffffL)throw new IllegalArgumentException("invalid device file");
            array.put(new JSONArray().put(entry.id).put(entry.size).put(entry.endSeconds));
        }
        prefs.edit().putString("device",device).putString("state","READY").putString("detail","已读取完整列表")
                .putString("entries",array.toString()).putLong("fetched_at",System.currentTimeMillis())
                .putLong("active_id",activeId).putBoolean("with_end",withEnd).putString("firmware",firmware).apply();
    }
    void unavailable(String device,String reason) {
        if(device.equals(prefs.getString("device","")))prefs.edit().putString("state","UNAVAILABLE").putString("detail",reason).apply();
    }
    void action(String device,String message) {
        prefs.edit().putString("action_device",device).putString("action",message).putLong("action_at",System.currentTimeMillis()).apply();
    }
    Snapshot read(String device) {
        if(device.isEmpty() || !device.equals(prefs.getString("device","")))return new Snapshot(device);
        Snapshot result=new Snapshot(device);
        result.state=prefs.getString("state","");result.detail=prefs.getString("detail","");
        result.fetchedAt=prefs.getLong("fetched_at",0);result.activeId=prefs.getLong("active_id",0);
        result.withEnd=prefs.getBoolean("with_end",false);result.firmware=prefs.getString("firmware","");
        if(device.equals(prefs.getString("action_device","")))result.action=prefs.getString("action","");
        try {
            JSONArray array=new JSONArray(prefs.getString("entries","[]"));
            if(array.length()>5000)throw new JSONException("too many files");
            for(int i=0;i<array.length();i++) {
                JSONArray entry=array.getJSONArray(i);long id=entry.getLong(0);
                if(id<=0 || id>0xffff_ffffL || result.entries.containsKey(id))throw new JSONException("invalid file");
                result.entries.put(id,new Protocol.Entry(id,entry.getLong(1),entry.getLong(2)));
            }
        }catch(JSONException e){result.entries.clear();result.fetchedAt=0;result.state="UNAVAILABLE";result.detail="缓存无法读取，请重新刷新豆内列表";}
        return result;
    }
    static final class Snapshot {
        final String device;
        final TreeMap<Long,Protocol.Entry> entries=new TreeMap<>(Collections.reverseOrder());
        String state="",detail="尚未读取豆内文件列表",firmware="",action="";
        long fetchedAt,activeId;boolean withEnd;
        Snapshot(String device){this.device=device;}
    }
}
