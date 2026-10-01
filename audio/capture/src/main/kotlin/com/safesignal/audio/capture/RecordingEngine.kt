package com.safesignal.audio.capture

import com.safesignal.core.common.model.ElapsedMillis
import kotlinx.coroutines.flow.StateFlow

/**
 * Audio capture configuration (SPEC §16).
 *
 * Every field is user-configurable, and no field carries a claim that it
 * improves intelligibility. SPEC §16 and §98 forbid implying that a higher
 * sample rate or bit depth makes speech easier to recover at distance — it does
 * not. These settings trade storage against fidelity only.
 */
data class RecordingConfig(
    val format: AudioFormat = AudioFormat.PCM_WAV,
    val sampleRateHz: Int = 48_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 16,
    /** Encoder bitrate for compressed formats only. Ignored for PCM. */
    val bitRate: Int = 128_000,
    /**
     * Segment length. Shorter segments bound the amount of audio at risk from a
     * crash and keep the sync queue responsive; longer segments reduce
     * per-segment overhead. Default chosen for 15–30 s crash exposure.
     */
    val segmentDurationSeconds: Int = 30,
    /** Hard cap. Recording always finalizes when reached (SPEC §29). */
    val maxDurationSeconds: Int = 60 * 60,
    /**
     * Seconds of pre-roll to prepend, or 0 for none (SPEC §12).
     *
     * Default 0: capturing audio before the user asked for a recording is a real
     * privacy cost and must be opted into. The audio comes from a bounded RAM ring
     * that is otherwise discarded, so nothing is persisted pre-activation.
     */
    val preBufferSeconds: Int = 0,
    /**
     * Free-space floor below which recording finalizes safely (SPEC §30).
     *
     * Sized for a plausible worst case: at 48 kHz/16-bit mono a 60 s segment is
     * ~5.8 MB, so 64 MB leaves room for several segments plus manifest work.
     */
    val minFreeStorageBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(format != AudioFormat.PCM_WAV || bitsPerSample == 16 || bitsPerSample == 8) {
            "PCM supports 8 or 16 bits per sample in this implementation"
        }
        require(sampleRateHz in 8_000..48_000) { "sampleRateHz out of supported range" }
        require(channels in 1..2) { "channels must be 1 or 2" }
        require(segmentDurationSeconds in 5..300) { "segmentDurationSeconds out of range" }
        require(maxDurationSeconds in 60..6 * 60 * 60) { "maxDurationSeconds out of range" }
        require(maxDurationSeconds >= segmentDurationSeconds) {
            "maxDurationSeconds must be at least one segment"
        }
        require(preBufferSeconds in 0..15) { "preBufferSeconds must be 0 or 5, 10 or 15" }
    }

    val bytesPerFrame: Int get() = channels * bitsPerSample / 8

    /** Uncompressed throughput, used for storage pre-flight checks. */
    val bytesPerSecond: Long
        get() = when (format) {
            AudioFormat.PCM_WAV -> sampleRateHz.toLong() * bytesPerFrame
            // A deliberately conservative estimate for AAC: real bitrate is
            // configurable, and over-estimating would stop recording early while
            // under-estimating risks exhausting storage.
            AudioFormat.AAC_M4A -> bitRate / 8L
        }

    /** Bytes one full segment occupies, including the WAV header. */
    fun segmentBytes(): Long = bytesPerSecond * segmentDurationSeconds + WAV_HEADER_BYTES

    /** Free space needed to guarantee the whole configured duration. */
    fun requiredStorageBytes(): Long = bytesPerSecond * maxDurationSeconds + WAV_HEADER_BYTES * maxSegments()

    fun maxSegments(): Int = (maxDurationSeconds + segmentDurationSeconds - 1) / segmentDurationSeconds

    private companion object {
        const val WAV_HEADER_BYTES = 44L
    }
}

enum class AudioFormat(val userLabel: String, val isLossless: Boolean) {
    /** Uncompressed PCM in a WAV container. Default for evidence. */
    PCM_WAV("WAV (uncompressed)", isLossless = true),

    /** AAC in an M4A container. Smaller, lossy. */
    AAC_M4A("M4A (AAC, compressed)", isLossless = false),
}

/** Recorder lifecycle. Deliberately independent of [com.safesignal.core.common.state.SafeSignalPhase]. */
sealed interface RecordingState {
    data object Idle : RecordingState
    data object Starting : RecordingState

    /**
     * Microphone open, nothing written to evidence yet (SPEC §12).
     *
     * Reached only when `preBufferSeconds > 0`. Frames arriving in this state
     * go into the bounded RAM ring and nowhere else — they are discarded unless
     * [RecordingEngine.trigger] arrives. This state exists so that "pre-roll"
     * has an observable meaning: before this was explicit, frames captured
     * while listening were written both to the ring and to segment 0, which
     * would have duplicated the pre-roll audio.
     */
    data object Listening : RecordingState

    data object Recording : RecordingState

    /** Active segment is being flushed and sealed. */
    data object Stopping : RecordingState

    /** Capture ended; hashes and manifest pending. */
    data object Finalizing : RecordingState

    /** Capture ended normally. */
    data object Stopped : RecordingState

    data class Failed(val reason: String, val recoverable: Boolean) : RecordingState
}

/** A committed, independently verifiable piece of a recording (SPEC §17). */
data class CommittedSegment(
    val recordingId: String,
    val segmentId: String,
    val sequenceNumber: Int,
    val sealedFileName: String,
    val sealedLengthBytes: Long,
    val plaintextLengthBytes: Long,
    val sha256: String,
    val startedElapsed: ElapsedMillis,
    val endedElapsed: ElapsedMillis,
)

/** A recording session that is in progress. */
data class RecordingSession(
    val recordingId: String,
    val config: RecordingConfig,
    val startedAtElapsed: ElapsedMillis,
    val startedAtWallClockMillis: Long,
    val isTestRecording: Boolean,
)

/** The result of stopping a recording. */
data class FinalizedRecording(
    val recordingId: String,
    val config: RecordingConfig,
    val segments: List<CommittedSegment>,
    /**
     * When the *evidence* began — activation time, minus any pre-roll duration.
     *
     * Not the moment the microphone opened. With pre-roll enabled those differ
     * by up to 15 seconds, and the timeline written to the manifest has to
     * cover the audio that is actually in segment 0. Listening time before
     * activation is deliberately excluded: it is not evidence.
     */
    val startedAtElapsed: ElapsedMillis,
    val endedAtElapsed: ElapsedMillis,
    val startedAtWallClockMillis: Long,
    val endedAtWallClockMillis: Long,
    val activationSource: String,
    val activationConfidence: Float?,
    val isTestRecording: Boolean,
    val notes: List<String> = emptyList(),
) {
    val totalSealedBytes: Long get() = segments.sumOf { it.sealedLengthBytes }

    val durationMillis: Long get() = (endedAtElapsed.elapsedRealtimeMs - startedAtElapsed.elapsedRealtimeMs)

    /** True when every segment was committed and hashed successfully. */
    val isComplete: Boolean get() = segments.isNotEmpty() && segments.all { it.sha256.isNotBlank() }
}

/**
 * The audio capture boundary (SPEC §15).
 *
 * ### Why `AudioRecord` and not `MediaRecorder`
 *
 * Both were evaluated against the requirements, and `AudioRecord` wins for this
 * product for four reasons that matter more than the API's ergonomics:
 *
 *  1. **Segmentation.** `MediaRecorder` writes a single continuous container and
 *     provides no supported way to split it into independently sealed chunks. A
 *     crash mid-recording leaves an unusable file. `AudioRecord` yields raw
 *     frames, so SafeSignal controls segment boundaries exactly.
 *  2. **Crash recovery.** A partially written segment is still a valid WAV prefix
 *     up to the truncation point, so the preceding segments are provably intact.
 *  3. **Pre-roll.** The RAM ring buffer must consume the *same* samples that the
 *     recorder will write. Sharing one reader is the only way to guarantee that
 *     with no gap and no duplication.
 *  4. **Derivatives.** Enhancement and transcription both need PCM access, not a
 *     compressed container.
 *
 * The cost is that SafeSignal must write its own container, which is why
 * [com.safesignal.audio.processing.WavSegmentWriter] exists. That is a deliberate,
 * bounded cost.
 *
 * ### Contract
 *
 *  - [start] acquires the microphone. Throwing `SecurityException` when
 *    `RECORD_AUDIO` is not granted is expected and handled by the caller.
 *  - [stop] must flush, seal and return every committed segment.
 *  - Implementations must not hold an entire recording in memory.
 */
interface RecordingEngine {

    /**
     * Opens the microphone and begins a session.
     *
     * The resulting state depends on the configuration: [RecordingState.Listening]
     * when `config.preBufferSeconds > 0`, otherwise [RecordingState.Recording].
     * The caller does not choose; it reacts, because the choice is the user's
     * pre-roll setting and the engine owns the pre-roll ring.
     */
    suspend fun start(config: RecordingConfig): Result<RecordingSession>

    /**
     * Confirms an activation, moving [RecordingState.Listening] to
     * [RecordingState.Recording] and seeding any pre-roll into segment 0.
     *
     * Called when the wake word fires or the user presses record. Must be
     * idempotent: a duplicate wake-word detection must not prepend the pre-roll
     * twice. A no-op success when the engine is already recording.
     */
    suspend fun trigger(): Result<Unit>

    suspend fun stop(): Result<FinalizedRecording>

    /**
     * Pauses capture while keeping the microphone and session state.
     *
     * On Android, releasing the microphone during a pause would drop the
     * foreground-service justification, so this stops reading frames rather than
     * releasing the device.
     */
    suspend fun pause(): Result<Unit>

    suspend fun resume(): Result<Unit>

    fun state(): StateFlow<RecordingState>

    /** Segments committed so far, for crash-recovery reconciliation. */
    fun committedSegments(): List<CommittedSegment>

    suspend fun release()
}