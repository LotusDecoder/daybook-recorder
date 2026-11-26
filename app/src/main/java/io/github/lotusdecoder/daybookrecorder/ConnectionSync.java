package io.github.lotusdecoder.daybookrecorder;

import java.util.Map;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;

/** Captures one complete, idle inventory per connection; SQLite owns the retry queue. */
final class ConnectionSync {
    private final RecordingStore store;
    private boolean pending;
    ConnectionSync(RecordingStore store){this.store=store;}
    void connected(){pending=true;}
    void capture(String device,Map<Long,Protocol.Entry> files,long activeId,boolean busy,boolean withEnd,boolean enabled) {
        if(!pending || !enabled || busy || activeId>0)return;
        store.enqueueConnectionSync(device,files,withEnd);
        pending=false;
    }
    static boolean mayDownload(RecordingStore.Row row,boolean enabled) {
        return row==null || (!row.localDeleted() && !row.reconciled() && !row.state.equals("SERVER_DELETED") && (!row.connectionSync || enabled));
    }
    static boolean mayClean(RecordingStore.Row row,boolean enabled,boolean allRecordings) {
        return row!=null && !row.readbackVerified && (allRecordings || (enabled && row.connectionSync));
    }
}
