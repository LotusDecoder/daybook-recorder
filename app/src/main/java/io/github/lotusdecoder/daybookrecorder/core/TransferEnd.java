package io.github.lotusdecoder.daybookrecorder.core;

/** A transfer end notification is not permission to discard slices still in flight. */
public final class TransferEnd {
    public static final long QUIET_MILLIS=2000;
    public static final long LIVE_EXACT_QUIET_MILLIS=300;
    private boolean received;
    private long lastActivity;
    public void received(long now) { received=true; lastActivity=now; }
    public void data(long now) { lastActivity=now; }
    public void reset() { received=false; }
    public boolean received() { return received; }
    public boolean settled(long now) { return settledWithin(now, QUIET_MILLIS); }
    public boolean settledWithin(long now, long quietMillis) { return received && now-lastActivity>=quietMillis; }
    public long remainingMillis(long now, long quietMillis) {
        if (!received) return quietMillis;
        return Math.max(0L, quietMillis - Math.max(0L, now - lastActivity));
    }
}
