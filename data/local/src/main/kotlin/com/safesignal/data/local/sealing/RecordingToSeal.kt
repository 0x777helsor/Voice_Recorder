package com.safesignal.data.local.sealing

import com.safesignal.core.crypto.SegmentDigest
import com.safesignal.core.crypto.WrappedKey

/**
 * A finished recording, described in terms sealing and storage actually need.
 *
 * ### Why this type exists instead of taking `FinalizedRecording`
 *
 * `:data:local` does not depend on `:audio:capture`, and adding that dependency to
 * shorten a signature would invert the architecture: the storage layer would depend
 * on the shape of a live capture, and every change to `FinalizedRecording` — an
 * internal detail of the recorder — would ripple into the evidence store. Capture
 * already depends on storage; the reverse edge is what would be wrong.
 *
 * The conversion lives in `:audio:capture` instead, as an extension on
 * `FinalizedRecording`, so the dependency points the correct way and the boundary
 * is crossed in exactly one place.
 *
 * ### Why the digests arrive rather than being recomputed
 *
 * Sealing runs after the plaintext data key has been zeroed, so the segments are
 * deliberately unreadable at this point. The engine computed these digests while it
 * still held the key, immediately after sealing each segment. Nothing here
 * re-derives them, and a digest that *had* to be recomputed would be computed over
 * the wrong bytes.
 */
data class RecordingToSeal(
    val recordingId: String,

    /** Per-segment digests from the engine, in sequence order. */
    val segments: List<SegmentDigest>,

    val audioFormat: String,
    val sampleRateHz: Int,
    val channels: Int,

    val startedAtWallClockMillis: Long,
    val endedAtWallClockMillis: Long,

    /** Monotonic, from boot. Authoritative for duration and ordering. */
    val startedElapsedRealtimeMillis: Long,
    val endedElapsedRealtimeMillis: Long,

    val activationSource: String,
    val activationConfidence: Float?,
    val isTestRecording: Boolean,

    val durationMillis: Long,
    val totalSealedBytes: Long,

    /** Full size of a segment at this configuration, for the `is_complete` check. */
    val fullSegmentBytes: Long,

    /**
     * The data key, wrapped by the Keystore key-establishment key.
     *
     * **Ciphertext, and the only remaining way to decrypt this recording.** The
     * plaintext key is zeroed the instant the last segment is sealed. If this is
     * dropped, the audio is unrecoverable — which is what was happening, silently,
     * because the `wrapped_key` columns existed and nothing ever wrote them.
     */
    val wrappedKey: WrappedKey,
) {
    /**
     * Whether a segment filled its configured duration.
     *
     * A recording stopped at an arbitrary moment has a legitimately short final
     * segment. A reader comparing lengths needs to know that is expected rather than
     * evidence of truncation, so it is computed rather than left to assumption.
     */
    fun isSegmentComplete(sequenceNumber: Int): Boolean {
        val finalSequence = segments.maxOfOrNull { it.sequenceNumber } ?: return true
        val segment = segments.firstOrNull { it.sequenceNumber == sequenceNumber } ?: return false
        return sequenceNumber != finalSequence || segment.plaintextLengthBytes >= fullSegmentBytes
    }
}
