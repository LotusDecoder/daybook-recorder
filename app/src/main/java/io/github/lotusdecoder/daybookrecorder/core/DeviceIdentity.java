package io.github.lotusdecoder.daybookrecorder.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Resolves physical D3200 / Soundcore Work identity across BLE Random Private Address (RPA) rotation:
 * 1. Advertisement Manufacturer Specific Data (AD type 0xFF, >=14 bytes with ASCII "sound" marker at [8..14])
 *    carries a stable 6-byte hardware identifier in [0..6].
 * 2. Device-info packet (0x01/0x01, payload >=24 bytes) carries the 16-byte hardware serial at [8..24].
 * Only SHA-256 digests are persisted; raw hardware identifiers never enter logs or preferences.
 */
public final class DeviceIdentity {
    public enum Match {
        EXACT_IDENTITY,     // Advertisement stable hardware digest matches persisted digest
        SAME_ADDRESS,       // Same BLE MAC address as currently selected
        CANDIDATE_FALLBACK, // Upgrade transition: no persisted adv digest yet, valid Soundcore candidate
        MISMATCH            // Known different device; never auto-connect
    }

    private DeviceIdentity() {}

    /**
     * Parses raw BLE scan record bytes (concatenated AD structures: [len][type][payload...])
     * and extracts the SHA-256 digest of the D3200 stable 6-byte manufacturer header if present.
     */
    public static String extractAdvIdentityHash(byte[] rawScanRecord) {
        if (rawScanRecord == null || rawScanRecord.length < 16) return "";
        int i = 0;
        while (i < rawScanRecord.length) {
            int len = rawScanRecord[i] & 0xFF;
            if (len == 0) break;
            if (i + 1 + len > rawScanRecord.length) break;
            int adType = rawScanRecord[i + 1] & 0xFF;
            int dataStart = i + 2;
            int dataLen = len - 1;
            if (adType == 0xFF && dataLen >= 14) {
                char[] mark = new char[6];
                for (int k = 0; k < 6; k++) {
                    mark[k] = (char) (rawScanRecord[dataStart + 8 + k] & 0x7F);
                }
                String ascii = new String(mark).toLowerCase(Locale.ROOT);
                if (ascii.contains("sound")) {
                    return sha256Hex(Arrays.copyOfRange(rawScanRecord, dataStart, dataStart + 6));
                }
            }
            i += 1 + len;
        }
        return "";
    }

    /**
     * Extracts the SHA-256 digest of the 16-byte device serial from a 0x01/0x01 response payload.
     */
    public static String extractSerialHash(byte[] infoPayload) {
        if (infoPayload == null || infoPayload.length < 24) return "";
        boolean nonZero = false;
        for (int i = 8; i < 24; i++) {
            if (infoPayload[i] != 0) {
                nonZero = true;
                break;
            }
        }
        if (!nonZero) return "";
        return sha256Hex(Arrays.copyOfRange(infoPayload, 8, 24));
    }

    /**
     * Evaluates whether a scanned Soundcore peripheral matches the user's bound recording bean.
     */
    public static Match evaluateCandidate(String candidateAddress, String candidateAdvHash, String candidateName,
                                          boolean hasSoundcoreServiceUuid,
                                          String savedAddress, String savedAdvHash, String savedName) {
        if (candidateAddress == null || candidateAddress.isEmpty()) return Match.MISMATCH;

        // If both sides have the stable 6-byte manufacturer digest, it is authoritative.
        if (savedAdvHash != null && !savedAdvHash.isEmpty()
                && candidateAdvHash != null && !candidateAdvHash.isEmpty()) {
            return savedAdvHash.equals(candidateAdvHash) ? Match.EXACT_IDENTITY : Match.MISMATCH;
        }

        // Exact BLE MAC match (cache refresh when address has not rotated yet).
        if (savedAddress != null && !savedAddress.isEmpty()
                && savedAddress.equalsIgnoreCase(candidateAddress)) {
            return Match.SAME_ADDRESS;
        }

        // If we already know the bound bean's adv digest, do not blindly connect to an unverified address
        // unless it advertises the Soundcore service UUID and matching name (to be gated by serial_hash on 01/01).
        boolean isSoundcore = hasSoundcoreServiceUuid
                || (candidateAdvHash != null && !candidateAdvHash.isEmpty());
        if (!isSoundcore && candidateName != null) {
            String lower = candidateName.toLowerCase(Locale.ROOT);
            isSoundcore = lower.contains("soundcore") || lower.contains("d3200") || lower.contains("work");
        }
        if (!isSoundcore) return Match.MISMATCH;

        if (savedAdvHash != null && !savedAdvHash.isEmpty()) {
            // Candidate lacks manufacturer frame in this packet; only allow fallback if name matches.
            if (savedName != null && !savedName.isEmpty() && candidateName != null
                    && savedName.equalsIgnoreCase(candidateName) && hasSoundcoreServiceUuid) {
                return Match.CANDIDATE_FALLBACK;
            }
            return Match.MISMATCH;
        }

        // Upgrade transition from <=0.2.0: savedAdvHash is not yet recorded, address rotated while sleeping.
        if (savedName != null && !savedName.isEmpty() && candidateName != null && !candidateName.isEmpty()
                && !savedName.equalsIgnoreCase(candidateName) && !hasSoundcoreServiceUuid) {
            return Match.MISMATCH;
        }
        return Match.CANDIDATE_FALLBACK;
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte v : digest) sb.append(String.format(Locale.ROOT, "%02x", v & 0xFF));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortDeviceKey(String address) {
        return sha256Hex(address.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
