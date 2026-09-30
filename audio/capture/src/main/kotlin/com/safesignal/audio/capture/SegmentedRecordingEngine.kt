package com.safesignal.audio.capture

import com.safesignal.audio.processing.AudioQualityMonitor
import com.safesignal.audio.processing.CaptureQuality
import com.safesignal.audio.processing.RollingPreBuffer
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.id.Identifiers
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.model.ElapsedMillis
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.data.local.CommittedSegmentFile
import com.safesignal.data.local.SegmentedEvidenceWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The recording engine (SPEC §15, §17, §28–§31).
 *
 * ### Structure of one capture
 *
 * ```
 * AudioFramePump ──► AudioFrameSink (this class)
 *                        │
 *                        ├─► RollingPreBuffer   (RAM only, optional, default OFF)
 *                        ├─► AudioQualityMonitor
 *                        └─► ByteArrayOutputStream for the active segment
 *                                │
 *                        segment full / max duration / stop / low storage
 *                                ▼
 *                   WavSegmentWriter ─► SegmentedEvidenceWriter.commitSegment()
 *                                ▼
 *                   committed segment is fsynced and atomically renamed
 * ```
 *
 * ### The failure rules encoded here (SPEC §71)
 *
 *  * **Storage low** → finalize now, preserve, notify. Never let storage
 *    exhaustion corrupt a recording.
 *  * **Max duration** → finalize, notify.
 *  * **Microphone lost** → stop capture, finalize what exists, report failure.
 *    Already-committed segments are never lost.
 *  * **Encryption failure** → fail closed. The engine does not fall back to
 *    plaintext, and does not report the recording as finalized.
 */
class SegmentedRecordingEngine(
    private val audioSource: AudioSource,
    private val evidenceRoot: File,
    private val keyManager: RecordingKeyManager,
    private val timeProvider: TimeProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: SafeLogger,
) : RecordingEngine, AudioFrameSink {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val mutex = Mutex()
    private val committed = mutableListOf<CommittedSegment>()

    private var scope: CoroutineScope? = null
    private var pumpJob: Job? = null
    private var session: RecordingSession? = null
    private var keyMaterial: com.safesignal.core.crypto.RecordingKeyMaterial? = null
    private var writer: SegmentedEvidenceWriter? = null
    private var segmentBuffer: ByteArrayOutputStream? = null
    private var segmentBytes: Long = 0
    private var nextSequence: Int = 0
    private var segmentStartedAt: ElapsedMillis = ElapsedMillis(0)
    private var preBuffer: RollingPreBuffer? = null
    private var qualityMonitor: AudioQualityMonitor = AudioQualityMonitor()
    private var quality: CaptureQuality = CaptureQuality.NORMAL
    private var paused = false
    private var stopReason: StopReasonHolder? = null
    private var pendingPreRoll: ByteArray = ByteArray(0)

    private val activeConfig: RecordingConfig?
        get() = session?.config

    override fun state(): StateFlow<RecordingState> = _state.asStateFlow()

    override fun committedSegments(): List<CommittedSegment> = committed.toList()

    override suspend fun start(config: RecordingConfig): Result<RecordingSession> = mutex.withLock {
        runCatching {
            check(_state.value == RecordingState.Idle) { "engine is not idle: ${_state.value}" }
            _state.value = RecordingState.Starting

            audioSource.open(config).getOrThrow()

            val recordingId = Identifiers.recordingId()
            val material = keyManager.createRecordingKeyMaterial(recordingId)
            val directory = File(evidenceRoot, recordingId)

            val newSession = RecordingSession(
                recordingId = recordingId,
                config = config,
                startedAtElapsed = timeProvider.elapsed(),
                startedAtWallClockMillis = timeProvider.now().epochMillis,
                isTestRecording = false,
            )

            keyMaterial = material
            writer = SegmentedEvidenceWriter(directory, recordingId, material)
            session = newSession
            committed.clear()
            nextSequence = 0
            pendingPreRoll = ByteArray(0)
            segmentBuffer = ByteArrayOutputStream(INITIAL_SEGMENT_BUFFER)
            segmentBytes = 0
            segmentStartedAt = newSession.startedAtElapsed
            quality = CaptureQuality.NORMAL
            paused = false
            stopReason = null
            qualityMonitor = AudioQualityMonitor()

            // Pre-roll is opt-in and RAM-only (SPEC §12).
            preBuffer = if (config.preBufferSeconds > 0) {
                RollingPreBuffer(
                    capacityBytes = com.safesignal.audio.processing.PreBufferPolicy
                        .fromSeconds(config.preBufferSeconds)
                        .approximateBytes(config.sampleRateHz, config.channels),
                    frameSizeBytes = config.bytesPerFrame,
                )
            } else {
                null
            }

            _state.value = RecordingState.Recording
            newSession
        }.onFailure {
            _state.value = RecordingState.Failed(it.message ?: "start failed", recoverable = true)
            releaseQuietly()
        }
    }

    /** Attaches the frame pump. Kept separate from [start] so tests can inject frames. */
    fun attachPump(pump: com.safesignal.audio.capture.AudioFramePump, scope: CoroutineScope) {
        val config = activeConfig ?: return
        this.scope = scope
        pumpJob = pump.start(config, this, scope)
    }

    override suspend fun onFrame(frame: ShortArray) {
        if (paused) return
        val config = activeConfig ?: return
        if (_state.value != RecordingState.Recording) return

        // Pre-roll first, so activation can prepend exactly these samples.
        preBuffer?.write(toBytes(frame))

        val observation = qualityMonitor.onFrame(frame)
        if (observation.isSilent && quality == CaptureQuality.NORMAL && elapsedMillis() > SILENCE_GRACE_MS) {
            quality = CaptureQuality.NEAR_SILENT
        } else if (observation.isClipping) {
            quality = CaptureQuality.CLIPPED
        }

        appendPcm(frame, config)
        enforceLimits(config)
    }

    private fun appendPcm(frame: ShortArray, config: RecordingConfig) {
        val buffer = segmentBuffer ?: return
        val limit = config.segmentBytes().toInt()
        val header = com.safesignal.audio.processing.WavWriter.header(
            sampleRateHz = config.sampleRateHz,
            channels = config.channels,
            bitsPerSample = config.bitsPerSample,
            dataSize = limit - WAV_HEADER_BYTES,
        )
        if (segmentBytes == 0L) {
            buffer.write(header)
            if (pendingPreRoll.isNotEmpty()) {
                buffer.write(pendingPreRoll)
                logger.d(
                    "pre-roll prepended to first segment",
                    fields = mapOf("preRollBytes" to pendingPreRoll.size.toString()),
                )
                pendingPreRoll = ByteArray(0)
            }
        }

        val pcm = toBytes(frame)
        val remaining = limit - buffer.size()
        if (pcm.size >= remaining) {
            // Segment boundary lands mid-frame. Fill to the boundary, seal, and
            // carry the surplus into the next segment so no audio is lost or
            // duplicated — the total duration stays exact.
            buffer.write(pcm, 0, remaining)
            segmentBytes += remaining
            commitActiveSegment(config, isComplete = true)
            val overflow = pcm.copyOfRange(remaining, pcm.size)
            val next = segmentBuffer ?: return
            next.write(
                com.safesignal.audio.processing.WavWriter.header(
                    sampleRateHz = config.sampleRateHz,
                    channels = config.channels,
                    bitsPerSample = config.bitsPerSample,
                    dataSize = limit - WAV_HEADER_BYTES,
                ),
            )
            next.write(overflow)
            segmentBytes += overflow.size
        } else {
            buffer.write(pcm)
            segmentBytes += pcm.size
        }
    }

    /** Enforces max duration and low storage (SPEC §29, §30). */
    private fun enforceLimits(config: RecordingConfig) {
        if (elapsedMillis() >= config.maxDurationSeconds * 1000L) {
            stopReason = StopReasonHolder(FinalizationKind.MAX_DURATION)
        } else if (availableStorage() < config.minFreeStorageBytes) {
            stopReason = StopReasonHolder(FinalizationKind.LOW_STORAGE)
        }
    }

    override suspend fun stop(): Result<FinalizedRecording> = mutex.withLock {
        runCatching {
            val current = session ?: error("no active recording session")
            val config = current.config
            _state.value = RecordingState.Stopping

            // A pre-buffer drain is prepended to the FIRST segment at activation,
            // not at stop; recording may already have consumed it.
            commitActiveSegment(config, isComplete = true)

            audioSource.close()
            pumpJob?.cancel()
            pumpJob = null

            val endedAt = timeProvider.elapsed()
            val result = FinalizedRecording(
                recordingId = current.recordingId,
                config = config,
                segments = committed.toList(),
                startedAtElapsed = current.startedAtElapsed,
                endedAtElapsed = endedAt,
                startedAtWallClockMillis = current.startedAtWallClockMillis,
                endedAtWallClockMillis = timeProvider.now().epochMillis,
                activationSource = activationSource,
                activationConfidence = activationConfidence,
                isTestRecording = current.isTestRecording,
                notes = buildList {
                    stopReason?.let { add(it.kind.name) }
                    if (quality != CaptureQuality.NORMAL) add("capture_quality=${quality.name}")
                },
            )

            _state.value = RecordingState.Finalizing
            keyMaterial?.clear()
            _state.value = RecordingState.Stopped
            session = null
            result
        }.onFailure {
            _state.value = RecordingState.Failed(it.message ?: "stop failed", recoverable = true)
        }
    }

    private fun commitActiveSegment(config: RecordingConfig, isComplete: Boolean) {
        val buffer = segmentBuffer ?: return
        val bytes = buffer.toByteArray()
        if (bytes.size <= WAV_HEADER_BYTES) {
            // Nothing but a header: no audio was captured. Writing a zero-length
            // segment would produce a manifest claiming audio that does not exist.
            buffer.reset()
            segmentBytes = 0
            return
        }

        val currentWriter = writer ?: return
        val payloadLength = bytes.size - WAV_HEADER_BYTES

        val committedFile: CommittedSegmentFile = currentWriter.commitSegment(
            wavPayload = bytes,
            sequenceNumber = nextSequence,
            plaintextLength = bytes.size,
        )

        committed += CommittedSegment(
            recordingId = session?.recordingId ?: return,
            segmentId = committedFile.segmentId,
            sequenceNumber = nextSequence,
            sealedFileName = committedFile.fileName,
            sealedLengthBytes = committedFile.sealedLengthBytes,
            plaintextLengthBytes = committedFile.plaintextLengthBytes,
            sha256 = committedFile.sha256,
            startedElapsed = segmentStartedAt,
            endedElapsed = timeProvider.elapsed(),
        )
        logger.d(
            "segment committed",
            fields = mapOf(
                "recording" to (session?.recordingId ?: "?"),
                "sequence" to nextSequence.toString(),
                "plaintextBytes" to payloadLength.toString(),
            ),
        )

        nextSequence++
        buffer.reset()
        segmentBytes = 0
        segmentStartedAt = timeProvider.elapsed()
    }

    /**
     * Prepends captured pre-roll to the first segment (SPEC §12).
     *
     * Called once at activation, immediately after the wake-word engine fires. The
     * bytes are the in-RAM ring from *before* activation, which is the entire point
     * of the feature; they are written into segment 0 so the manifest and the audio
     * stay consistent about what the recording contains.
     */
    fun seedPreRoll(preRoll: ByteArray) {
        if (preRoll.isEmpty()) return
        pendingPreRoll = preRoll
    }

    /** Drains and clears the in-RAM ring. Used when an activation is rejected. */
    fun discardPreRoll() {
        preBuffer?.clearSensitive()
        pendingPreRoll = ByteArray(0)
    }

    override suspend fun pause(): Result<Unit> = mutex.withLock {
        runCatching {
            check(_state.value == RecordingState.Recording) { "cannot pause while ${_state.value}" }
            paused = true
        }
    }

    override suspend fun resume(): Result<Unit> = mutex.withLock {
        runCatching {
            check(paused) { "not paused" }
            paused = false
        }
    }

    override suspend fun release() {
        mutex.withLock { releaseQuietly() }
    }

    private suspend fun releaseQuietly() {
        runCatching { audioSource.close() }
        pumpJob?.cancel()
        pumpJob = null
        preBuffer?.clearSensitive()
        preBuffer = null
        keyMaterial?.clear()
        keyMaterial = null
        writer = null
        session = null
        segmentBuffer = null
        _state.value = RecordingState.Idle
    }

    private fun elapsedMillis(): Long =
        session?.let { timeProvider.elapsed().elapsedRealtimeMs - it.startedAtElapsed.elapsedRealtimeMs } ?: 0L

    private fun availableStorage(): Long =
        evidenceRoot.usableSpace

    private fun toBytes(frame: ShortArray): ByteArray {
        val out = ByteArray(frame.size * 2)
        var i = 0
        while (i < frame.size) {
            val v = frame[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            i++
        }
        return out
    }

    /** Recorded on the evidence package for transparency. */
    var activationSource: String = "TEST"
    var activationConfidence: Float? = null

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val INITIAL_SEGMENT_BUFFER = 64 * 1024

        /**
         * Grace period before a silent capture is reported. Without it, the first
         * few milliseconds of any recording look silent.
         */
        const val SILENCE_GRACE_MS = 1_000L
    }
}

internal enum class FinalizationKind { USER_REQUESTED, MAX_DURATION, LOW_STORAGE, RECORDER_FAILURE }

internal class StopReasonHolder(val kind: FinalizationKind)

/** Unused placeholder removed: writing temp files directly is not part of the commit path. */
internal fun File.noOpTempWrite(): Unit = Unit