package io.github.lotusdecoder.daybookrecorder;

import android.content.*;

/** Restores only a session the user previously enabled, with an explicit reboot opt-in. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))return;
        if(!Settings.prefs(context).getBoolean("enabled",false) || !Settings.prefs(context).getBoolean("boot",false) || !Settings.permitted(context))return;
        try {context.startForegroundService(new Intent(context,RecorderService.class));}
        catch(RuntimeException e) {
            Settings.prefs(context).edit().putString("status","系统未允许自动恢复，请打开 App 重新开启").apply();
            EventLog.add(context,"重启后恢复被系统拒绝："+e.getClass().getSimpleName());
        }
    }
}
