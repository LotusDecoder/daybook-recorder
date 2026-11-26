package io.github.lotusdecoder.daybookrecorder.core;

/** A transfer end notification is not permission to discard slices still in flight. */
public final class TransferEnd {
    public static final long QUIET_MILLIS=2000;
    private boolean received;
    private long lastActivity;
    public void received(long now) { received=true; lastActivity=now; }
    public void data(long now) { lastActivity=now; }
    public boolean received() { return received; }
    public boolean settled(long now) { return received && now-lastActivity>=QUIET_MILLIS; }
}
