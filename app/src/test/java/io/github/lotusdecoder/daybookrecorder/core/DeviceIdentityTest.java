package io.github.lotusdecoder.daybookrecorder.core;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceIdentityTest {
    private static byte[] buildAdvWithManufacturer(byte[] prefix6, String marker) {
        byte[] mfg = new byte[15];
        System.arraycopy(prefix6, 0, mfg, 0, 6);
        mfg[6] = 0x01;
        mfg[7] = 0x02;
        byte[] ascii = marker.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, mfg, 8, Math.min(6, ascii.length));
        // Wrap in AD structure: [flags][0xFF mfg]
        byte[] raw = new byte[3 + 2 + mfg.length];
        raw[0] = 2; raw[1] = 0x01; raw[2] = 0x06;
        raw[3] = (byte) (1 + mfg.length);
        raw[4] = (byte) 0xFF;
        System.arraycopy(mfg, 0, raw, 5, mfg.length);
        return raw;
    }

    @Test public void extractsStableManufacturerIdentityAcrossAddressRotation() {
        byte[] hwA = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06};
        byte[] hwB = {0x01, 0x02, 0x03, 0x04, 0x05, 0x07};
        String hashA1 = DeviceIdentity.extractAdvIdentityHash(buildAdvWithManufacturer(hwA, "Soundc"));
        String hashA2 = DeviceIdentity.extractAdvIdentityHash(buildAdvWithManufacturer(hwA, "soundC"));
        String hashB = DeviceIdentity.extractAdvIdentityHash(buildAdvWithManufacturer(hwB, "Soundc"));
        assertFalse(hashA1.isEmpty());
        assertEquals(hashA1, hashA2);
        assertNotEquals(hashA1, hashB);
        assertEquals("", DeviceIdentity.extractAdvIdentityHash(buildAdvWithManufacturer(hwA, "other!")));
        assertEquals("", DeviceIdentity.extractAdvIdentityHash(new byte[]{2, 1, 6}));
    }

    @Test public void extractsNonZeroDeviceSerialDigestFromInfoPacket() {
        byte[] payload = new byte[51];
        System.arraycopy("04.00".getBytes(StandardCharsets.US_ASCII), 0, payload, 3, 5);
        assertEquals("", DeviceIdentity.extractSerialHash(payload)); // all-zero serial ignored
        System.arraycopy("TESTSERIAL000001".getBytes(StandardCharsets.US_ASCII), 0, payload, 8, 16);
        String s1 = DeviceIdentity.extractSerialHash(payload);
        assertEquals(64, s1.length());
        payload[23] ^= 1;
        assertNotEquals(s1, DeviceIdentity.extractSerialHash(payload));
    }

    @Test public void evaluatesRotatedAndForeignCandidatesAccurately() {
        byte[] hw = {1, 2, 3, 4, 5, 6};
        byte[] foreign = {1, 2, 3, 4, 5, 9};
        String savedHash = DeviceIdentity.extractAdvIdentityHash(buildAdvWithManufacturer(hw, "Soundc"));
        String foreignHash = DeviceIdentity.extractAdvIdentityHash(buildAdvWithManufacturer(foreign, "Soundc"));

        // Address rotated from 11:22:33:44:55:66 to 77:88:99:AA:BB:CC, same hardware hash -> EXACT_IDENTITY
        assertEquals(DeviceIdentity.Match.EXACT_IDENTITY, DeviceIdentity.evaluateCandidate(
                "77:88:99:AA:BB:CC", savedHash, "Soundcore Work", true,
                "11:22:33:44:55:66", savedHash, "Soundcore Work"));

        // Foreign bean nearby -> MISMATCH
        assertEquals(DeviceIdentity.Match.MISMATCH, DeviceIdentity.evaluateCandidate(
                "77:88:99:AA:BB:CC", foreignHash, "Soundcore Work", true,
                "11:22:33:44:55:66", savedHash, "Soundcore Work"));

        // Same MAC address refresh -> SAME_ADDRESS
        assertEquals(DeviceIdentity.Match.SAME_ADDRESS, DeviceIdentity.evaluateCandidate(
                "11:22:33:44:55:66", "", "Soundcore Work", true,
                "11:22:33:44:55:66", "", "Soundcore Work"));

        // Upgrade transition (no savedAdvHash yet, address rotated, valid Soundcore UUID) -> CANDIDATE_FALLBACK
        assertEquals(DeviceIdentity.Match.CANDIDATE_FALLBACK, DeviceIdentity.evaluateCandidate(
                "77:88:99:AA:BB:CC", savedHash, "Soundcore Work", true,
                "11:22:33:44:55:66", "", "Soundcore Work"));
    }
}
