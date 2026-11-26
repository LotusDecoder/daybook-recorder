package io.github.lotusdecoder.daybookrecorder.core;

/** The size reported by the device, distinct from the 160 audio bytes we save per frame. */
public enum RecordingSizeUnit {
    AUDIO_BYTES(160), TRANSPORT_BYTES(166);
    public final int bytesPerFrame;
    RecordingSizeUnit(int bytesPerFrame) { this.bytesPerFrame=bytesPerFrame; }
    public static boolean usesVerifiedTransportProtocol(String firmware) {
        return firmware!=null && firmware.matches("04\\.\\d{2}");
    }
    public static RecordingSizeUnit forFirmware(String firmware) {
        // Supported 04.x firmware includes transport overhead in its reported byte count.
        // Other firmware uses the audio size until its reporting unit is known.
        // Select the unit by firmware even when a byte count fits both formats.
        return usesVerifiedTransportProtocol(firmware)?TRANSPORT_BYTES:AUDIO_BYTES;
    }
}
