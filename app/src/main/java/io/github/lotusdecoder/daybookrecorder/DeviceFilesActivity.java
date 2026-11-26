package io.github.lotusdecoder.daybookrecorder;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;
import io.github.lotusdecoder.daybookrecorder.core.RecordingSizeUnit;

/** Browses the last complete bean inventory, separately from the phone inbox. */
@SuppressLint("SetTextI18n")
public final class DeviceFilesActivity extends Activity {
    private static final int GREEN=0xff176b5b,INK=0xff172f29,MUTED=0xff62746b,BG=0xfff3f5ef;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ArrayList<Protocol.Entry> entries=new ArrayList<>();
    private final Map<Long,RecordingStore.Row> local=new HashMap<>();
    private RecordingStore store;private DeviceFilesStore files;private DeviceFilesStore.Snapshot snapshot;
    private TextView status,notice,empty,playback;private ListView list;private BaseAdapter adapter;
    private MediaPlayer player;private String device="",renderKey="";private long playingId;
    private final Runnable refresh=new Runnable(){public void run(){render();main.postDelayed(this,2000);}};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);store=new RecordingStore(this);files=new DeviceFilesStore(this);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column();root.setBackgroundColor(BG);root.setPadding(dp(22),dp(18),dp(22),dp(20));
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(22),dp(18)+insets.getSystemWindowInsetTop(),dp(22),dp(20)+insets.getSystemWindowInsetBottom());return insets;});
        root.addView(button("返回首页",v->finish()));root.addView(label("录音豆内文件",26,INK,true));
        root.addView(label("查看录音豆返回的文件，点击单条查看备份、播放或人工清理。",14,MUTED,false));
        status=label("",13,MUTED,false);root.addView(status);
        root.addView(button("刷新豆内列表",v->refreshFiles()));
        notice=label("",13,GREEN,false);root.addView(notice);
        playback=label("",13,GREEN,false);playback.setVisibility(View.GONE);root.addView(playback);
        playback.setOnClickListener(v->releasePlayer());
        empty=label("尚未读取豆内列表，请连接后刷新。",15,MUTED,false);root.addView(empty);
        list=new ListView(this);list.setDividerHeight(dp(8));list.setBackgroundColor(BG);
        adapter=new BaseAdapter() {
            @Override public int getCount(){return entries.size();}
            @Override public Object getItem(int position){return entries.get(position);}
            @Override public long getItemId(int position){return entries.get(position).id;}
            @Override public boolean hasStableIds(){return true;}
            @Override public View getView(int position,View convert,ViewGroup parent) {
                TextView view=convert instanceof TextView?(TextView)convert:label("",15,INK,false);
                Protocol.Entry entry=entries.get(position);view.setText(rowText(entry));view.setPadding(dp(16),dp(12),dp(16),dp(12));
                GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(12));view.setBackground(bg);return view;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((parent,view,position,id)->showFile(entries.get(position)));
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);render();
        if(sessionEnabled())refreshFiles();
    }
    @Override protected void onResume(){super.onResume();main.removeCallbacks(refresh);main.post(refresh);}
    @Override protected void onPause(){main.removeCallbacks(refresh);releasePlayer();super.onPause();}
    @Override protected void onDestroy(){releasePlayer();store.close();super.onDestroy();}
    private boolean sessionEnabled(){return Settings.prefs(this).getBoolean("enabled",false) && Settings.permitted(this);}
    private boolean send(Intent intent) {
        if(!sessionEnabled()){toast("请先回到首页开启后台接收");return false;}
        startService(intent);return true;
    }
    private void refreshFiles(){if(send(new Intent(this,RecorderService.class).setAction(RecorderService.VIEW_FILES)))notice.setText("已请求刷新，等待录音豆返回完整列表");}
    private void render() {
        if(isFinishing())return;
        String address=Settings.prefs(this).getString("address","");String selected=Settings.activeDeviceId(this);
        if(!device.equals(selected)){releasePlayer();device=selected;renderKey="";}
        snapshot=files.read(device);
        boolean connected=sessionEnabled() && Settings.prefs(this).getBoolean("ble_ready",false)
                && device.equals(Settings.prefs(this).getString("ble_device",""))
                && System.currentTimeMillis()-Settings.prefs(this).getLong("heartbeat",0)<45000;
        String read=snapshot.fetchedAt>0?"上次成功读取："+date(snapshot.fetchedAt)+" · "+snapshot.entries.size()+" 条":"尚未成功读取";
        status.setText((connected?"录音豆已连接":"当前未确认连接，列表仅供回看")+"\n"+read+"\n"+snapshot.detail);
        notice.setText(snapshot.action);notice.setVisibility(snapshot.action.isEmpty()?View.GONE:View.VISIBLE);
        local.clear();for(RecordingStore.Row row:store.forDevice(device))local.put(row.fileId,row);
        StringBuilder key=new StringBuilder(device).append(snapshot.fetchedAt).append(snapshot.state).append(snapshot.activeId);
        for(Protocol.Entry entry:snapshot.entries.values())key.append(entry.id).append(':').append(entry.size).append(':').append(entry.endSeconds).append(';');
        for(RecordingStore.Row row:local.values())key.append(row.id).append(row.state).append(row.bytes).append(row.cleanupDetail).append(store.file(row.id,".ogg").isFile());
        if(!key.toString().equals(renderKey)) {
            renderKey=key.toString();entries.clear();entries.addAll(snapshot.entries.values());adapter.notifyDataSetChanged();
        }
        boolean noFiles=entries.isEmpty();empty.setVisibility(noFiles?View.VISIBLE:View.GONE);list.setVisibility(noFiles?View.GONE:View.VISIBLE);
        empty.setText(snapshot.fetchedAt==0?"尚未读取豆内列表，请连接后刷新。":"上次成功读取的列表为 0 条。手机收件箱中的文件仍保留。"+(!snapshot.state.equals("READY")?"\n当前刷新未完成，不能据此判断此刻的豆内文件。":""));
    }
    private String rowText(Protocol.Entry entry) {
        RecordingStore.Row row=local.get(entry.id);String text=title(entry)+"\nID "+entry.id+" · "+duration(entry);
        if(entry.id==snapshot.activeId)text+=" · 录音中或暂停";
        else if(snapshot.withEnd && entry.endSeconds==0)text+=" · 结束状态待确认";
        text+="\n"+backup(row);if(row!=null && !row.cleanupDisplay().isEmpty())text+="\n"+row.cleanupDisplay();return text;
    }
    private boolean saved(RecordingStore.Row row){return row!=null && row.saved() && store.file(row.id,".ogg").isFile();}
    private String backup(RecordingStore.Row row) {
        if(row!=null && row.reconciled())return "音频已与完整副本核对，可在手机收件箱播放";
        String phone=saved(row)?"手机已保存":row!=null && row.saved()?"手机文件缺失":row!=null && row.state.equals("RECEIVING")?"手机正在接收":"手机尚无完整副本";
        if(row!=null && row.localDeleted())phone="手机副本已手动删除，暂停自动补收";
        if(row!=null && row.state.equals("SERVER_DELETED"))return phone+" · 服务器副本已删除";
        return phone+" · "+(row!=null && row.state.equals("SENT")?"曾获中转回执":row!=null && row.state.equals("READY")?"等待中转接收":"中转未确认");
    }
    private void showFile(Protocol.Entry shown) {
        final String selectedDevice=device;final Protocol.Entry entry=shown;
        RecordingStore.Row row=store.get(RecordingStore.id(selectedDevice,entry.id));
        LinearLayout box=column();box.setPadding(dp(22),dp(6),dp(22),dp(8));
        box.addView(label("ID "+entry.id+" · "+duration(entry)+"\n"+backup(row)+"\n清理只影响豆内文件，已有手机和中转副本保留。",14,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title(entry)).setView(box).setNegativeButton("关闭",null).create();
        Button play=button("播放手机副本",v->{dialog.dismiss();if(sameDevice(selectedDevice))play(store.file(RecordingStore.id(selectedDevice,entry.id),".ogg"),entry.id);});
        play.setEnabled(saved(row));box.addView(play);
        Button download=button("下载此录音到手机",v->{
            dialog.dismiss();if(!sameDevice(selectedDevice))return;
            Runnable request=()->{if(sameDevice(selectedDevice))send(new Intent(this,RecorderService.class).setAction(RecorderService.DOWNLOAD).putExtra("device",selectedDevice).putExtra("file_id",entry.id).putExtra("restore_deleted",row!=null && row.localDeleted()));};
            if(row!=null && row.localDeleted())new AlertDialog.Builder(this).setTitle("重新下载已删除的手机副本？")
                    .setMessage("会取消这条录音的手机删除标记，从录音豆重新下载，并按原配置上传。服务器中已手动删除的同一条录音仍不会恢复。")
                    .setPositiveButton("重新下载",(d,w)->request.run()).setNegativeButton("取消",null).show();
            else request.run();
        });
        download.setEnabled(!saved(row));box.addView(download);
        Button remove=button("核验后删除豆内副本",v->{dialog.dismiss();confirmCleanup(selectedDevice,entry,row);});
        boolean eligible=saved(row) && row.state.equals("SENT");remove.setEnabled(eligible);box.addView(remove);
        if(!eligible)box.addView(label("先完整下载到手机并取得中转回执，才能核验后删除。下载后会按原配置自动上传。",13,MUTED,false));
        dialog.show();
    }
    private boolean sameDevice(String selected) {
        String current=Settings.activeDeviceId(this);
        if(current.isEmpty() || !selected.equals(current)){toast("所选录音豆已变化，请重新选择文件");return false;}return true;
    }
    private void confirmCleanup(String selected,Protocol.Entry entry,RecordingStore.Row row) {
        if(!sameDevice(selected))return;
        new AlertDialog.Builder(this).setTitle("删除这条豆内副本？")
                .setMessage(title(entry)+"\nID "+entry.id+"\n\n"+(row.readbackVerified?row.manualReadbackNotice():"豆内时长 "+duration(entry)+"。\n\n")
                        +"会重新核对中转文件、当前录音豆和完整列表，再永久删除豆内这一条。核验时中转按原编号去重，手机和中转文件保留。")
                .setPositiveButton("核验并删除",(dialog,which)->{if(sameDevice(selected))send(new Intent(this,RecorderService.class).setAction(RecorderService.CLEANUP).putExtra("recording_id",RecordingStore.id(selected,entry.id)));})
                .setNegativeButton("取消",null).show();
    }
    private String duration(Protocol.Entry entry){
        if(!RecordingSizeUnit.usesVerifiedTransportProtocol(snapshot.firmware))return "时长待确认";
        return entry.size<1000?entry.size+" ms":String.format(Locale.ROOT,"%.1f 秒",entry.size/1000.0);
    }
    private String title(Protocol.Entry entry){return entry.id>=946684800L && entry.id<=4102444800L?date(entry.id*1000):"录音 "+entry.id;}
    private static String date(long millis){return DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(millis));}
    private void play(File file,long id) {
        releasePlayer();try {
            player=new MediaPlayer();playingId=id;player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp->{mp.start();playback.setText("正在播放录音 "+playingId+" · 点击停止");playback.setVisibility(View.VISIBLE);});
            player.setOnErrorListener((mp,what,extra)->{toast("系统播放器无法解码此音频");releasePlayer();return true;});
            player.setOnCompletionListener(mp->releasePlayer());player.prepareAsync();
        }catch(IOException e){releasePlayer();toast("手机副本无法打开，请检查收件箱");}
    }
    private void releasePlayer(){if(player!=null){player.release();player=null;}if(playback!=null)playback.setVisibility(View.GONE);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private TextView label(String text,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);view.setLineSpacing(dp(3),1);view.setPadding(0,dp(7),0,dp(7));if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    private Button button(String text,View.OnClickListener click){Button view=new Button(this);view.setText(text);view.setAllCaps(false);view.setTextColor(GREEN);view.setMinHeight(dp(48));view.setOnClickListener(click);return view;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
