package io.github.lotusdecoder.daybookrecorder.core;

import java.util.Map;

/** One explicitly enabled cleanup attempt. No deletion retries inside an attempt. */
public final class DeviceCleanup {
    public enum Phase { VERIFYING, INFO, BEFORE, CONTENT, ACK, AFTER, DELETED, ABSENT, RETRY, FAILED, UNKNOWN }
    public enum Action { NONE, VERIFY_REMOTE, READ_INFO, READ_LIST, READ_CONTENT, DELETE }
    public final String device,sha;
    public final long fileId,duration;
    private Phase phase=Phase.VERIFYING;
    private String detail="正在重新核对中转副本",endpoint="";
    private long changedAt,verifiedAt;
    private boolean sent;
    private final boolean previouslyAcknowledged;
    private final long readbackListMillis;
    private Protocol.Entry readEntry;
    private boolean contentMatched,readWithEnd;
    public DeviceCleanup(String device,long fileId,String sha,long duration,boolean uploaded,long now) {
        this(device,fileId,sha,duration,uploaded,false,now);
    }
    public DeviceCleanup(String device,long fileId,String sha,long duration,boolean uploaded,boolean previouslyAcknowledged,long now) {
        this(device,fileId,sha,duration,uploaded,previouslyAcknowledged,0,now);
    }
    /** A nonzero list duration is supplied only for an explicitly selected, independently read-back recording. */
    public DeviceCleanup(String device,long fileId,String sha,long duration,boolean uploaded,boolean previouslyAcknowledged,
                         long readbackListMillis,long now) {
        if(!uploaded || !device.matches("[a-f0-9]{16}") || !sha.matches("[a-f0-9]{64}")
                || fileId<=0 || fileId>0xffff_ffffL || duration<=0 || readbackListMillis<0
                || readbackListMillis>=duration)throw new IllegalArgumentException("unverified recording");
        this.device=device;this.fileId=fileId;this.sha=sha;this.duration=duration;
        this.readbackListMillis=readbackListMillis;
        this.previouslyAcknowledged=previouslyAcknowledged;changedAt=now;
    }
    public Phase phase(){return phase;}
    public String detail(){return detail;}
    public boolean sent(){return sent;}
    public boolean finished(){return phase.ordinal()>=Phase.DELETED.ordinal();}
    private Action move(Phase next,String text,long now,Action action){phase=next;detail=text;changedAt=now;return action;}
    public Action remoteVerified(String verifiedEndpoint,String currentEndpoint,boolean sameLocalHash,long now) {
        if(phase!=Phase.VERIFYING)return Action.NONE;
        if(verifiedEndpoint.isEmpty() || !verifiedEndpoint.equals(currentEndpoint) || !sameLocalHash
                || (!endpoint.isEmpty() && !endpoint.equals(verifiedEndpoint))) {
            interrupt("中转配置或本地文件已变化，稍后重新核对",now);return Action.NONE;
        }
        endpoint=verifiedEndpoint;verifiedAt=now;
        return move(Phase.INFO,"中转副本已核对，正在确认录音豆空闲",now,Action.READ_INFO);
    }
    public Action deviceInfo(String currentDevice,int recordingState,long now) {
        if(phase!=Phase.INFO)return Action.NONE;
        if(!device.equals(currentDevice) || recordingState!=0){interrupt("设备不匹配或仍在录音，暂不清理",now);return Action.NONE;}
        return move(Phase.BEFORE,"正在核对豆内对应录音",now,Action.READ_LIST);
    }
    /** Caller supplies an entire fresh paginated list, never a partial page. */
    public Action files(Map<Long,Protocol.Entry> files,long activeId,boolean busy,boolean withEnd,
                        String currentDevice,String currentEndpoint,long now) {
        if(phase!=Phase.BEFORE && phase!=Phase.AFTER)return Action.NONE;
        if(!device.equals(currentDevice)){interrupt("设备已变化，清理结果待检查",now);return Action.NONE;}
        Protocol.Entry entry=files.get(fileId);
        if(phase==Phase.AFTER) {
            return move(entry==null?Phase.DELETED:Phase.UNKNOWN,
                    entry==null?"豆内副本已清理；手机和中转副本保留":"设备回复成功，但文件仍在列表，等待检查",now,Action.NONE);
        }
        if(contentMatched && !freshReceipt(now) && !busy && activeId==0 && endpoint.equals(currentEndpoint))
            return move(Phase.VERIFYING,"豆内原音已核对，正在更新中转回执",now,Action.VERIFY_REMOTE);
        if(busy || activeId>0 || !endpoint.equals(currentEndpoint) || !freshReceipt(now)) {
            interrupt("录音豆忙或核对已过期，稍后重新核对",now);return Action.NONE;
        }
        if(previouslyAcknowledged)return move(entry==null?Phase.DELETED:Phase.UNKNOWN,
                entry==null?"前次删除已收到成功回执，本次列表确认已清理；手机和中转副本保留"
                        :"前次删除已收到成功回执，但文件仍在列表；未重发删除命令",now,Action.NONE);
        if(entry==null)return move(Phase.ABSENT,"豆内已无此录音；手机和中转副本保留（本次未发送删除命令）",now,Action.NONE);
        // This list field is verified as milliseconds on supported 04.x firmware only.
        if(entry.id!=fileId || (entry.size!=duration && (readbackListMillis==0 || entry.size!=readbackListMillis))
                || (withEnd && entry.endSeconds<=0))
            return move(Phase.FAILED,"豆内录音时长或结束状态与手机不一致，未删除",now,Action.NONE);
        if(readbackListMillis>0) {
            if(!contentMatched) {
                readEntry=entry;readWithEnd=withEnd;
                return move(Phase.CONTENT,"正在从录音豆重新读取这条原音，与备份核对",now,Action.READ_CONTENT);
            }
            if(withEnd!=readWithEnd || entry.size!=readEntry.size || entry.endSeconds!=readEntry.endSeconds)
                return move(Phase.FAILED,"复读后豆内文件信息已变化，已保留豆内原件",now,Action.NONE);
        }
        sent=true;
        return move(Phase.ACK,"已发送单条清理请求，等待设备回执",now,Action.DELETE);
    }
    private boolean freshReceipt(long now) {return now>=verifiedAt && now-verifiedAt<=120000;}
    public void contentActivity(long now) {if(phase==Phase.CONTENT)changedAt=now;}
    public Action contentRead(String rereadSha,long rereadDuration,long now) {
        if(phase!=Phase.CONTENT)return Action.NONE;
        if(!sha.equals(rereadSha) || duration!=rereadDuration)
            return move(Phase.FAILED,"本次豆内原音与已备份音频不一致，已保留豆内原件",now,Action.NONE);
        contentMatched=true;
        if(!freshReceipt(now))
            return move(Phase.VERIFYING,"豆内原音已核对，正在更新中转回执",now,Action.VERIFY_REMOTE);
        return move(Phase.INFO,"豆内原音与备份 SHA256 一致，正在再次确认设备空闲和文件列表",now,Action.READ_INFO);
    }
    public Action acknowledged(boolean success,long now) {
        if(phase!=Phase.ACK)return Action.NONE;
        if(!success)return move(Phase.FAILED,"设备拒绝清理请求；手机和中转副本保留",now,Action.NONE);
        return move(Phase.AFTER,"设备已回复成功，正在刷新列表核对",now,Action.READ_LIST);
    }
    public void interrupt(String reason,long now) {
        if(!finished())move(sent||previouslyAcknowledged?Phase.UNKNOWN:Phase.RETRY,
                reason+(sent||previouslyAcknowledged?"；不会自动重发删除命令":""),now,Action.NONE);
    }
    public void timeout(long now) {
        long limit=phase==Phase.VERIFYING?150000:phase==Phase.ACK?15000:45000;
        if(!finished() && now-changedAt>limit)interrupt("清理核对超时",now);
    }
}
