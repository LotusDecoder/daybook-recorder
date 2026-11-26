package io.github.lotusdecoder.daybookrecorder.core;

import java.util.Locale;

/** Pure formatting and state-classification model for the graphic connection & transfer HUD. */
public final class ConnectionHud {
    public enum Mode { CONNECTING, STABLE, DISCONNECTED, RECONNECTING }

    private ConnectionHud() {}

    public static Mode classify(boolean serviceRunning, boolean bluetoothEnabled, boolean hasDevice,
                                boolean bleReady, int reconnectCount, long lastStableDurationMs) {
        if (!serviceRunning || !bluetoothEnabled || !hasDevice) return Mode.DISCONNECTED;
        if (bleReady) return Mode.STABLE;
        if (reconnectCount > 0 || lastStableDurationMs > 0L) return Mode.RECONNECTING;
        return Mode.CONNECTING;
    }

    public static int percent(long currentBytes, long totalBytes) {
        if (totalBytes <= 0L || currentBytes <= 0L) return 0;
        if (currentBytes >= totalBytes) return 100;
        return Math.max(1, Math.min(99, (int) Math.round(currentBytes * 100.0 / totalBytes)));
    }

    public static String formatMb(long bytes) {
        if (bytes <= 0L) return "0.00 MB";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb < 0.01) mb = 0.01;
        return String.format(Locale.ROOT, "%.2f MB", mb);
    }

    public static String formatMbPair(long currentBytes, long totalBytes) {
        if (totalBytes <= 0L) return formatMb(currentBytes);
        double cur = Math.max(0L, currentBytes) / (1024.0 * 1024.0);
        double tot = Math.max(1L, totalBytes) / (1024.0 * 1024.0);
        if (tot < 0.01) tot = 0.01;
        if (currentBytes > 0L && cur < 0.01) cur = 0.01;
        return String.format(Locale.ROOT, "%.2f / %.2f MB", Math.min(cur, tot), tot);
    }

    public static String formatSpeedMb(double bytesPerSec) {
        if (bytesPerSec <= 1024.0) return "";
        double mbps = bytesPerSec / (1024.0 * 1024.0);
        if (mbps < 0.01) mbps = 0.01;
        return String.format(Locale.ROOT, "%.2f MB/s", mbps);
    }

    public static String formatDurationClock(long millis) {
        if (millis <= 0L) return "--:--";
        long totalSec = Math.max(1L, Math.round(millis / 1000.0));
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, mins, secs);
        }
        return String.format(Locale.ROOT, "%02d:%02d", mins, secs);
    }

    public static String formatStableSpan(long millis) {
        if (millis <= 0L) return "00m 01s";
        long totalSec = Math.max(1L, millis / 1000L);
        long hours = totalSec / 3600L;
        long mins = (totalSec % 3600L) / 60L;
        long secs = totalSec % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%02dh %02dm", hours, mins);
        }
        return String.format(Locale.ROOT, "%02dm %02ds", mins, secs);
    }

    public static String signalQuality(int rssiDbm) {
        if (rssiDbm == Integer.MIN_VALUE || rssiDbm >= 0) return "在线";
        if (rssiDbm >= -62) return "信号极佳";
        if (rssiDbm >= -75) return "信号良好";
        if (rssiDbm >= -86) return "信号一般";
        return "信号较弱";
    }

    public static int signalBars(int rssiDbm) {
        if (rssiDbm == Integer.MIN_VALUE || rssiDbm >= 0) return 3;
        if (rssiDbm >= -62) return 4;
        if (rssiDbm >= -74) return 3;
        if (rssiDbm >= -85) return 2;
        return 1;
    }

    public static float[] waveformProfile(long seed, int count) {
        float[] out = new float[Math.max(4, count)];
        long x = seed ^ 0x9E3779B97F4A7C15L;
        for (int i = 0; i < out.length; i++) {
            x ^= (x << 13);
            x ^= (x >>> 7);
            x ^= (x << 17);
            double wave = 0.30 + ((x & 0xFF) / 255.0) * 0.62;
            // Taper slightly at start/end so the audio capsule looks like natural speech.
            if (i == 0 || i == out.length - 1) wave = Math.min(wave, 0.42);
            out[i] = (float) wave;
        }
        return out;
    }
}
