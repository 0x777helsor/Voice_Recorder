package com.safesignal.audio.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The platform boundary for microphone access.
 *
 * Abstracted so that everything above it — segmentation, encryption, finalization,
 * recovery — is testable without a microphone, and so the recorder implementation
 * can be swapped (e.g. to a vendor-specific low-power path) in one place.
 */
interface AudioSource {

    /**
     * Opens the microphone.
     *
     * @throws SecurityException if `RECORD_AUDIO` is not granted. Callers must
     *   handle this rather than assume it cannot happen: the permission can be
     *   revoked from Settings while the app is running, and on Android 12+ a call
     *   from the background throws.
     * @throws IllegalStateException if the source cannot be initialised at all.
     */
    suspend fun open(config: RecordingConfig): Result<Unit>

    /**
     * Reads one frame of 16-bit PCM into [target].
     *
     * @return the number of bytes read, or a negative `AudioRecord.ERROR_*` code.
     */
    suspend fun read(target: ShortArray): Int

    suspend fun close()

    val isOpen: Boolean

    /** Sample rate actually granted by the device, which may differ from the request. */
    val actualSampleRateHz: Int
}

/** Reads frames and delivers them to a sink, one frame at a time. */
interface AudioFrameSink {
    /** Called on the capture dispatcher. Must not block for long. */
    suspend fun onFrame(frame: ShortArray)
}

/**
 * `AudioRecord`-backed capture (SPEC §15).
 *
 * Uses `AudioRecord` rather than `MediaRecorder` — the reasoning is recorded on
 * the [RecordingEngine] interface. In short: SafeSignal needs PCM access so it can
 * control segment boundaries and pre-roll exactly, and `MediaRecorder` offers
 * neither.
 *
 * ### Two platform realities this class encodes
 *
 *  1. **Contention.** Another app may take the microphone (a call, a voice
 *     messenger). `read()` then returns `ERROR_INVALID_OPERATION` or
 *     `ERROR_BAD_VALUE`. Those are surfaced as failures so already-committed
 *     segments can be finalized, rather than silently producing silence.
 *  2. **Silently zeroed capture.** Some devices and OEM audio stacks return
 *     success with all-zero buffers when the microphone is muted or obstructed.
 *     SafeSignal cannot distinguish that from genuine silence, which is exactly
 *     why the quality monitor reports a near-silent capture instead of claiming
 *     success. See [com.safesignal.audio.processing.AudioQualityMonitor].
 */
class AudioRecordSource(
    private val dispatchers: DispatcherProvider,
) : AudioSource {

    private var audioRecord: AudioRecord? = null
    private var grantedSampleRate: Int = 0

    /**
     * The buffer this record was constructed with, in samples.
     *
     * Kept so [read] can clamp to it. `AudioRecord.read` rejects a size larger
     * than the record's own capacity with `ERROR_BAD_VALUE`, and the only reliable
     * record of that capacity is what was requested at construction — the getter
     * is not available on every supported API level.
     */
    private var capacityShorts: Int = 0

    override val isOpen: Boolean get() = audioRecord?.state == AudioRecord.STATE_INITIALIZED

    override val actualSampleRateHz: Int get() = grantedSampleRate

    @SuppressLint("MissingPermission") // Guarded by the caller; see [open].
    override suspend fun open(config: RecordingConfig): Result<Unit> = withContext(dispatchers.audio) {
        runCatching {
            check(!isOpen) { "audio source is already open" }

            val channelMask = if (config.channels == 1) {
                AudioFormat.CHANNEL_IN_MONO
            } else {
                AudioFormat.CHANNEL_IN_STEREO
            }

            val minBufferSize = AudioRecord.getMinBufferSize(
                config.sampleRateHz,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBufferSize > 0) {
                "device does not support ${config.sampleRateHz}Hz mono 16-bit capture"
            }

            // 4x the minimum gives the reader room to ride out a scheduling delay
            // without the recorder's own buffer overrunning and dropping frames.
            //
            // Sizing only from the minimum is a trap. On several devices
            // `getMinBufferSize` at 48 kHz mono returns less than a single pump
            // read, so `AudioRecord.read` rejects every frame with
            // `ERROR_BAD_VALUE` (-2) and the app records nothing at all — silently,
            // apart from one log line. A phone that happened to report a larger
            // minimum hid this for as long as it was the only test device.
            // So: size for the read that will actually happen, then add headroom.
            val frameBytes = frameBytes(config)
            val bufferBytes = maxOf(minBufferSize * 4, frameBytes * 4)

            val record = AudioRecord(
                // VOICE_RECOGNITION disables OEM voice-enhancement pipelines such as
                // noise suppression and AGC. Those would alter the original capture,
                // which SPEC §20 forbids: the original must be what the microphone
                // produced, not what a vendor algorithm decided to keep.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                config.sampleRateHz,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("AudioRecord failed to initialise")
            }

            // Android 12+ throws here when the app lacks while-in-use permission.
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord did not enter the recording state"
            }

            audioRecord = record
            capacityShorts = bufferBytes / BYTES_PER_SAMPLE
            grantedSampleRate = record.sampleRate
        }
    }

    override suspend fun read(target: ShortArray): Int {
        val record = audioRecord ?: return AudioRecord.ERROR_INVALID_OPERATION

        // The primitive overload, not `read(ByteBuffer, offset, size)`.
        //
        // The ByteBuffer overload is deprecated and is in fact broken on at least
        // one shipping ROM: it rejects the call with `ERROR_BAD_VALUE` and logs
        // "AudioRecord.read() called with invalid blocking mode", so every single
        // frame fails and the app records nothing. The primitive overload is
        // non-deprecated, has no such failure, and avoids allocating a fresh
        // ByteBuffer twenty times a second.
        //
        // Clamped rather than trusted, for the same reason as the buffer sizing: a
        // read larger than the record's capacity is rejected outright, which is a
        // total loss of the recording rather than a partial one.
        val readShorts = if (capacityShorts > 0) {
            minOf(target.size, capacityShorts)
        } else {
            target.size
        }

        // Read straight into the caller's array. PCM_16BIT arrives little-endian,
        // which is the byte order of every ABI Android runs on, so no conversion
        // is needed — where the ByteBuffer overload paid for one on every frame.
        val read = record.read(target, 0, readShorts)
        return read
    }

    override suspend fun close() {
        withContext(dispatchers.audio) {
            audioRecord?.let { record ->
                runCatching { record.stop() }
                runCatching { record.release() }
            }
            audioRecord = null
            capacityShorts = 0
        }
    }
}

/**
 * Drives an [AudioSource], feeding frames to an [AudioFrameSink] until stopped.
 *
 * The read loop lives here rather than in the source so that one reader serves
 * both consumers — the recorder and the wake-word detector — and there is
 * therefore only ever one reader of the microphone. Two readers would contend and
 * would risk splitting the pre-roll from the recording it is supposed to precede.
 */
class AudioFramePump(
    private val source: AudioSource,
    private val dispatchers: DispatcherProvider,
    private val logger: SafeLogger,
) {
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts pumping frames to [sink].
     *
     * @param config used only to size the read buffer.
     */
    fun start(config: RecordingConfig, sink: AudioFrameSink, scope: CoroutineScope): Job {
        check(job?.isActive != true) { "audio pump is already running" }
        val framesPerRead = config.sampleRateHz / READS_PER_SECOND
        val buffer = ShortArray(config.channels * framesPerRead)

        // The job that is returned must be the job that is running. This used to
        // return a standalone `SupervisorJob` that the launched coroutine was not a
        // child of, so cancelling the returned job — which is what the engine does
        // on stop — cancelled nothing. The pump only ever ended because closing the
        // audio source made its next read fail, which meant "stop" was relying on
        // an error path rather than doing what it said.
        val created = scope.launch(dispatchers.audio) {
            while (isActive) {
                val read = source.read(buffer)
                when {
                    read > 0 -> sink.onFrame(buffer.copyOf(read))
                    read == 0 -> Unit // no data available this poll; do not spin hot
                    else -> {
                        // Negative codes are AudioRecord errors. Surface and stop:
                        // pretending to record while the microphone is gone would
                        // produce a silent "recording" that misleads everyone.
                        logger.e(
                            "AudioRecord read failed",
                            fields = mapOf("errorCode" to read.toString()),
                        )
                        break
                    }
                }
            }
        }
        job = created
        return created
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** Factory so DI can choose the platform implementation in one place. */
interface AudioSourceFactory {
    suspend fun create(): AudioSource
}

class DefaultAudioSourceFactory(
    private val dispatchers: DispatcherProvider,
) : AudioSourceFactory {
    override suspend fun create(): AudioSource = AudioRecordSource(dispatchers)
}

/** Mutable recorder state holder shared with the UI layer. */
/** 16-bit PCM: two bytes per sample. */
internal const val BYTES_PER_SAMPLE: Int = 2

/**
 * Frame cadence driven by [AudioFramePump]: roughly 20 reads per second, which is
 * responsive enough to react to a stop request and cheap enough not to drain the
 * battery.
 *
 * Declared here, next to [frameBytes], so the source that sizes the `AudioRecord`
 * buffer and the pump that fills it cannot disagree about how large a frame is.
 * They did disagree once, and the result was an app that recorded nothing.
 */
internal const val READS_PER_SECOND: Int = 20

/**
 * Bytes in one pump frame for [config].
 *
 * This is the minimum a record's buffer must be able to hold, otherwise the very
 * first read is rejected.
 */
internal fun frameBytes(config: RecordingConfig): Int =
    config.channels * config.sampleRateHz / READS_PER_SECOND * BYTES_PER_SAMPLE

class RecordingStateHolder(initial: RecordingState = RecordingState.Idle) {
    private val _state = MutableStateFlow<RecordingState>(initial)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun set(value: RecordingState) {
        _state.value = value
    }
}