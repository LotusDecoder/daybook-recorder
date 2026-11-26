package io.github.lotusdecoder.daybookrecorder;

/** Lock-free live snapshots shared between background BLE/HTTP workers and the foreground UI. */
final class RuntimeTelemetry {
    static final class Transfer {
        final String recordingId;
        final long fileId;
        final boolean uploading;
        final boolean liveRecording;
        final boolean cleanupVerify;
        final boolean resumed;
        final long currentBytes;
        final long totalBytes;
        final long durationMs;
        final double bytesPerSec;

        Transfer(String recordingId, long fileId, boolean uploading, boolean liveRecording,
                 boolean cleanupVerify, long currentBytes, long totalBytes, long durationMs, double bytesPerSec) {
            this(recordingId, fileId, uploading, liveRecording, cleanupVerify,
                    currentBytes, totalBytes, durationMs, bytesPerSec, false);
        }

        Transfer(String recordingId, long fileId, boolean uploading, boolean liveRecording,
                 boolean cleanupVerify, long currentBytes, long totalBytes, long durationMs,
                 double bytesPerSec, boolean resumed) {
            this.recordingId = recordingId;
            this.fileId = fileId;
            this.uploading = uploading;
            this.liveRecording = liveRecording;
            this.cleanupVerify = cleanupVerify;
            this.resumed = resumed;
            this.currentBytes = currentBytes;
            this.totalBytes = totalBytes;
            this.durationMs = durationMs;
            this.bytesPerSec = bytesPerSec;
        }
    }

    static volatile int handshakeStep = 0; // 1=BLE link, 2=ECDH handshake, 3=Inventory sync
    static volatile long connectedSinceWallMs = 0L;
    static volatile long lastStableDurationMs = 0L;
    static volatile int reconnectCount = 0;
    static volatile long reconnectDelayMs = 0L;
    static volatile long nextReconnectWallMs = 0L;
    static volatile int rssiDbm = Integer.MIN_VALUE;
    static volatile boolean beanRecording = false;
    static volatile boolean beanPaused = false;
    static volatile boolean backgroundScanning = false;
    static volatile Transfer bleTransfer = null;
    static volatile Transfer httpUpload = null;
    static volatile String cleaningRecordingId = "";
    static volatile long cleaningFileId = 0L;

    static void resetServiceStopped() {
        handshakeStep = 0;
        connectedSinceWallMs = 0L;
        reconnectCount = 0;
        reconnectDelayMs = 0L;
        nextReconnectWallMs = 0L;
        rssiDbm = Integer.MIN_VALUE;
        beanRecording = false;
        beanPaused = false;
        backgroundScanning = false;
        bleTransfer = null;
        httpUpload = null;
        cleaningRecordingId = "";
        cleaningFileId = 0L;
    }

    private RuntimeTelemetry() {}
}
