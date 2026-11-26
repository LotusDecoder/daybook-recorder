package io.github.lotusdecoder.daybookrecorder.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionHudTest {
    @Test public void classifiesFourConnectionLifecycleModes() {
        assertEquals(ConnectionHud.Mode.DISCONNECTED,
                ConnectionHud.classify(false, true, true, false, 0, 0L));
        assertEquals(ConnectionHud.Mode.DISCONNECTED,
                ConnectionHud.classify(true, false, true, false, 0, 0L));
        assertEquals(ConnectionHud.Mode.DISCONNECTED,
                ConnectionHud.classify(true, true, false, false, 0, 0L));
        assertEquals(ConnectionHud.Mode.CONNECTING,
                ConnectionHud.classify(true, true, true, false, 0, 0L));
        assertEquals(ConnectionHud.Mode.STABLE,
                ConnectionHud.classify(true, true, true, true, 0, 120_000L));
        assertEquals(ConnectionHud.Mode.RECONNECTING,
                ConnectionHud.classify(true, true, true, false, 2, 0L));
        assertEquals(ConnectionHud.Mode.RECONNECTING,
                ConnectionHud.classify(true, true, true, false, 0, 180_000L));
    }

    @Test public void formatsMegabytesPercentSpeedAndDurationsCleanly() {
        assertEquals("0.00 MB", ConnectionHud.formatMb(0L));
        assertEquals("0.01 MB", ConnectionHud.formatMb(8000L));
        assertEquals("1.42 MB", ConnectionHud.formatMb(Math.round(1.42 * 1024 * 1024)));
        assertEquals("1.42 / 2.08 MB", ConnectionHud.formatMbPair(
                Math.round(1.42 * 1024 * 1024), Math.round(2.08 * 1024 * 1024)));
        assertEquals(68, ConnectionHud.percent(1420, 2080));
        assertEquals(0, ConnectionHud.percent(0, 2080));
        assertEquals(100, ConnectionHud.percent(2080, 2080));
        assertEquals("0.38 MB/s", ConnectionHud.formatSpeedMb(0.38 * 1024 * 1024));
        assertEquals("", ConnectionHud.formatSpeedMb(200));
        assertEquals("--:--", ConnectionHud.formatDurationClock(0));
        assertEquals("03:24", ConnectionHud.formatDurationClock(204_000L));
        assertEquals("1:02:05", ConnectionHud.formatDurationClock(3725_000L));
        assertEquals("03h 42m", ConnectionHud.formatStableSpan((3 * 3600L + 42 * 60L) * 1000L));
        assertEquals("18m 24s", ConnectionHud.formatStableSpan((18 * 60L + 24L) * 1000L));
    }

    @Test public void signalAndWaveformProfilesStayWithinBounds() {
        assertEquals(4, ConnectionHud.signalBars(-54));
        assertEquals("信号极佳", ConnectionHud.signalQuality(-54));
        assertEquals(2, ConnectionHud.signalBars(-80));
        assertEquals(1, ConnectionHud.signalBars(-92));
        float[] bars = ConnectionHud.waveformProfile(1700000100L, 20);
        assertEquals(20, bars.length);
        for (float h : bars) {
            assertTrue(h >= 0.20f && h <= 1.0f);
        }
        assertArrayEquals(bars, ConnectionHud.waveformProfile(1700000100L, 20), 0.0001f);
    }
}
