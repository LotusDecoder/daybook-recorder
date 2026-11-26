package io.github.lotusdecoder.daybookrecorder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import io.github.lotusdecoder.daybookrecorder.core.Protocol;
import io.github.lotusdecoder.daybookrecorder.core.RecordingAssembler;
import io.github.lotusdecoder.daybookrecorder.core.RecordingSizeUnit;

/** Two separate reads from offset zero when the device describes a longer file as a single frame. */
final class OfflineReadback {
    enum Result { EXACT, INCOMPLETE, READ_AGAIN, MATCHED, DIFFERENT, SHORTER }

    static final int FRAME_MILLIS = 20;
    final File attempt;
    private final File reference, original, legacyPart;

    OfflineReadback(RecordingStore store, String id) {
        attempt = store.file(id, ".readback");
        reference = store.file(id, ".readback-first");
        original = store.file(id, ".readback-original");
        legacyPart = store.file(id, ".part");
    }

    static OfflineReadback forRequest(RecordingStore store, String id, String firmware, boolean live, long listDuration) {
        if (live || !RecordingSizeUnit.usesVerifiedTransportProtocol(firmware)) return null;
        OfflineReadback check = new OfflineReadback(store, id);
        return listDuration == FRAME_MILLIS || check.reference.isFile() || check.original.isFile() ? check : null;
    }

    static boolean closedEntry(Protocol.Entry entry, int listType, boolean deviceReportedIdle) {
        if (entry == null || entry.size != FRAME_MILLIS) return false;
        // The fallback inventory omits the end timestamp. Require an explicit idle status on this link.
        return listType == 0x1b ? entry.endSeconds > 0 : listType == 0x1a && deviceReportedIdle;
    }

    // The caller has a matching decrypted header and a settled transfer-end notification.
    Result verify(RecordingAssembler audio, long headBytes, boolean closedIdleFile, boolean clean)
            throws IOException {
        RecordingSizeUnit unit = RecordingSizeUnit.TRANSPORT_BYTES;
        boolean exact = audio.complete(headBytes, unit);
        if (!exact && (headBytes != unit.bytesPerFrame || !closedIdleFile || !clean
                || audio.frames() <= 1 || !audio.contiguous())) return Result.INCOMPLETE;
        // A later one-packet response cannot replace a longer copy seen before the upgrade or retry.
        if (audio.bytes() < Math.max(legacyPart.length(), Math.max(reference.length(), original.length())))
            return Result.SHORTER;
        if (exact) return Result.EXACT;

        audio.close();
        boolean hadReference = reference.isFile();
        if (hadReference && reference.length() == attempt.length()
                && RecordingStore.hash(reference).equals(RecordingStore.hash(attempt))) return Result.MATCHED;
        // Keep the first divergent audio even if subsequent reads consistently return different content.
        if (hadReference && !original.exists())
            Files.move(reference.toPath(), original.toPath(), StandardCopyOption.ATOMIC_MOVE);
        // A legacy or interrupted download is never evidence of an independent completed transfer.
        Files.move(attempt.toPath(), reference.toPath(), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        return hadReference ? Result.DIFFERENT : Result.READ_AGAIN;
    }
}
