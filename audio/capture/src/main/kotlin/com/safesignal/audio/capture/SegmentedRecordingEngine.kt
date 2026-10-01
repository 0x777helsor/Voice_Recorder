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
import kotlinx.coroutines.launch
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
    private var nextSequence: Int = 0
    private var segmentStartedAt: ElapsedMillis = ElapsedMillis(0)
    private var preBuffer: RollingPreBuffer? = null
    private var qualityMonitor: AudioQualityMonitor = AudioQualityMonitor()
    private var quality: CaptureQuality = CaptureQuality.NORMAL
    private var paused = false
    private var stopReason: StopReasonHolder? = null
    private var pendingPreRoll: ByteArray = ByteArray(0)

    /**
     * When the recording's own clock starts: activation time, less any pre-roll.
     *
     * Measured from the microphone opening instead would be wrong twice over. The
     * app may listen for an hour before the wake word fires, and the duration
     * limit must not be spent on silence the user did not ask to record; and
     * segment 0 contains pre-roll audio that predates activation, so a timeline
     * starting at the microphone would misdate it.
     */
    private var evidenceStartedAt: ElapsedMillis = ElapsedMillis(0)

    /** Pre-roll milliseconds seeded into segment 0, for the manifest note. */
    private var seededPreRollMillis: Long = 0L

    /**
     * The result of the most recent successful [stop].
     *
     * Retained so that a stop triggered internally by [enforceLimits] and a
     * subsequent stop from the service layer resolve to the *same* finalized
     * recording rather than the second call failing with "no active recording
     * session" — a failure that would read to a user as the recorder crashing
     * exactly when it stopped itself to protect their storage.
     */
    private var lastFinalization: FinalizedRecording? = null

    private val activeConfig: RecordingConfig?
        get() = session?.config

    override fun state(): StateFlow<RecordingState> = _state.asStateFlow()

    override fun committedSegments(): List<CommittedSegment> = committed.toList()

    /**
     * Whether the next session is an explicitly labelled test.
     *
     * Set by the caller before [start]. A test recording must stay
     * distinguishable from real evidence for as long as it exists, so the flag
     * travels into [RecordingSession.isTestRecording], then into
     * [FinalizedRecording.isTestRecording] and the manifest. It used to be
     * hardcoded to `false`, which meant a test run produced evidence
     * indistinguishable from a genuine recording — exactly the confusion the
     * label exists to prevent.
     */
    var isTestSession: Boolean = false

    /**
     * Optional per-frame observer, called on the capture dispatcher.
     *
     * Exists so a wake-word detector can consume the capture stream without owning
     * a second microphone. Suspending so the detector can do real work, but it is
     * invoked **inline** on the capture path, so anything slow here directly delays
     * capture. That is the correct trade for a detector that must not miss the
     * audio it is deciding about; a detector that can afford to be sampled
     * periodically should subsample rather than return from this call slowly.
     *
     * Failures are swallowed and logged. A broken detector must not stop the
     * recorder, because a recording that stopped when the detector threw would lose
     * evidence to a non-critical component.
     */
    var frameObserver: (suspend (ShortArray) -> Unit)? = null

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
                isTestRecording = isTestSession,
            )

            keyMaterial = material
            writer = SegmentedEvidenceWriter(directory, recordingId, material)
            session = newSession
            committed.clear()
            nextSequence = 0
            pendingPreRoll = ByteArray(0)
            segmentBuffer = ByteArrayOutputStream(INITIAL_SEGMENT_BUFFER)
            segmentStartedAt = newSession.startedAtElapsed
            quality = CaptureQuality.NORMAL
            paused = false
            stopReason = null
            qualityMonitor = AudioQualityMonitor()
            lastFinalization = null
            seededPreRollMillis = 0L
            evidenceStartedAt = newSession.startedAtElapsed

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

            // With pre-roll enabled the engine opens in [RecordingState.Listening]:
            // frames accumulate in the RAM ring and nothing reaches evidence until
            // [trigger] confirms an activation. Without it, capture starts
            // immediately, because there is nothing to wait for.
            _state.value = if (preBuffer == null) {
                RecordingState.Recording
            } else {
                RecordingState.Listening
            }
            newSession
        }.onFailure {
            // Release first, then record the failure. The other order looked
            // correct and was not: `releaseQuietly` ends by setting [Idle], which
            // overwrote the [Failed] state a moment earlier, so a caller watching
            // the state flow — the notification, the UI, the sync queue — never
            // saw that the microphone could not be opened at all.
            releaseQuietly()
            _state.value = RecordingState.Failed(it.message ?: "start failed", recoverable = true)
        }
    }

    /** Attaches the frame pump. Kept separate from [start] so tests can inject frames. */
    fun attachPump(pump: com.safesignal.audio.capture.AudioFramePump, scope: CoroutineScope) {
        val config = activeConfig ?: return
        this.scope = scope
        val started = pump.start(config, this, scope)
        pumpJob = started

        // The pump is the only thing producing audio, and it can end without being
        // asked to: another app takes the microphone, the audio stack returns an
        // error, or the platform closes the record underneath us. It breaks out of
        // its loop and logs, but on its own that changes nothing here — the engine
        // would keep reporting `Recording` indefinitely, behind a live "recording"
        // notification, while the microphone produces nothing. A user glancing at
        // that notification would conclude their audio was being preserved when it
        // was not, which is the single most damaging thing this app could get
        // wrong.
        //
        // So the pump's end is observed, not assumed. Anything already captured is
        // sealed and the state is allowed to become terminal.
        scope.launch {
            started.join()
            val current = _state.value
            val stillCapturing = current is RecordingState.Recording ||
                current is RecordingState.Listening
            if (!stillCapturing) return@launch

            logger.e(
                "audio capture ended unexpectedly; finalizing what was captured",
                fields = mapOf(
                    "recording" to (session?.recordingId ?: "?"),
                    "state" to current.toString(),
                ),
            )
            if (stopReason == null) {
                stopReason = StopReasonHolder(FinalizationKind.RECORDER_FAILURE)
            }
            // Re-entrant-safe: `stop` takes the same mutex as any other stop and
            // returns the existing finalization if one already happened.
            stop()
        }
    }

    override suspend fun onFrame(frame: ShortArray) {
        if (paused) return
        val config = activeConfig ?: return

        // Every frame is offered to the observer before anything else, and in
        // **both** Listening and Recording states.
        //
        // The wake-word detector has to see exactly the samples that will end up in
        // the evidence, for two reasons. It is the only way detection and capture
        // stay in step — a detector fed by a second `AudioRecord` would be racing a
        // second open of a microphone the platform may not even grant. And the
        // pre-roll ring must hold the same audio the detector rejected on, or
        // "the five seconds before the phrase" would be five seconds of something
        // else.
        frameObserver?.let { observer ->
            runCatching { observer(frame) }
                .onFailure { logger.w("frame observer failed", it) }
        }

        when (_state.value) {
            // Listening: the ring only. These samples are evidence if and when
            // [trigger] arrives, and otherwise discarded on the next ring
            // overwrite. Writing them to segment 0 as well would duplicate the
            // pre-roll, which is why this branch returns instead of falling
            // through.
            RecordingState.Listening -> {
                preBuffer?.write(toBytes(frame))
                return
            }

            RecordingState.Recording -> Unit

            else -> return
        }

        // Pre-roll first, so activation can prepend exactly these samples.
        preBuffer?.write(toBytes(frame))

        val observation = qualityMonitor.onFrame(frame)
        if (observation.isSilent && quality == CaptureQuality.NORMAL && elapsedMillis() > SILENCE_GRACE_MS) {
            quality = CaptureQuality.NEAR_SILENT
        } else if (observation.isClipping) {
            quality = CaptureQuality.CLIPPED
        }

        appendPcm(frame, config)
        // May finalize the recording and move the state out of Recording, which
        // is why the result is checked again: any frame arriving after a
        // limit-triggered stop must be dropped rather than appended to a
        // finalized recording.
        enforceLimits(config)
    }

    /**
     * Confirms an activation (SPEC §12).
     *
     * Prepends the buffered pre-roll to segment 0, then starts writing. The
     * recording's clock is set here rather than at [start], so time spent merely
     * listening cannot exhaust the duration limit.
     *
     * Idempotent by design: a wake-word engine that fires twice for one
     * utterance must not prepend the ring twice. The second call is a no-op
     * success, and the error is logged rather than raised, because the second
     * detection is a detector artefact and not a user-visible failure.
     */
    override suspend fun trigger(): Result<Unit> = mutex.withLock {
        runCatching {
            val config = activeConfig ?: error("no active recording session")

            when (_state.value) {
                RecordingState.Recording -> {
                    logger.w("trigger ignored; already recording")
                    return@runCatching
                }

                RecordingState.Listening -> Unit

                else -> error("cannot trigger while ${_state.value}")
            }

            val drained = preBuffer?.drainTo() ?: ByteArray(0)
            seedPreRoll(drained)

            // The ring has done its job. Dropping it now means later audio cannot
            // be mistaken for pre-roll by a second activation.
            preBuffer?.clearSensitive()
            preBuffer = null

            seededPreRollMillis =
                drained.size * 1000L / config.bytesPerSecond.coerceAtLeast(1L)
            evidenceStartedAt = timeProvider.elapsed() - ElapsedMillis(seededPreRollMillis)
            _state.value = RecordingState.Recording

            logger.i(
                "recording activated",
                fields = mapOf(
                    "recording" to (session?.recordingId ?: "?"),
                    "preRollMillis" to seededPreRollMillis.toString(),
                ),
            )
        }.onFailure {
            _state.value = RecordingState.Failed(it.message ?: "trigger failed", recoverable = true)
        }
    }

    private fun appendPcm(frame: ShortArray, config: RecordingConfig) {
        val buffer = segmentBuffer ?: return
        val limit = config.segmentBytes().toInt()
        // The header is written with a placeholder data size and patched by
        // `commitActiveSegment` once the true payload length is known. Writing
        // `limit - WAV_HEADER_BYTES` up front produced a header claiming more
        // audio than the final partial segment actually held — a manifest that
        // described audio which was never recorded.
        val header = com.safesignal.audio.processing.WavWriter.header(
            sampleRateHz = config.sampleRateHz,
            channels = config.channels,
            bitsPerSample = config.bitsPerSample,
            dataSize = limit - WAV_HEADER_BYTES,
        )
        // "Empty buffer" is the test for a new segment, because it is the only
        // condition that is true exactly once per segment.
        //
        // A separate byte counter was used to decide this, and it was wrong: when
        // a frame landed exactly on the boundary the overflow was empty, the
        // counter stayed at zero, and the *next* frame wrote a second WAV header
        // into the middle of the payload. The file still played, so nothing
        // surfaced it — but the bytes after the first 44 were shifted, and a
        // decoder would have read them as noise.
        if (buffer.size() == 0) {
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
            commitActiveSegment()
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
        } else {
            buffer.write(pcm)
        }
    }

    /**
     * Enforces max duration and low storage (SPEC §29, §30).
     *
     * These limits are *hard stops*, not advisories. Recording a reason without
     * acting on it was the original defect here: capture continued past its
     * configured duration and kept writing until storage was genuinely exhausted,
     * which is exactly what SPEC §71 forbids, because a full volume can corrupt
     * the segment being written.
     *
     * Finalizing at the frame boundary rather than on a timer means the stop
     * cannot be starved by a busy dispatcher, and the segment holding the final
     * frames is committed intact instead of truncated.
     */
    private suspend fun enforceLimits(config: RecordingConfig) {
        if (stopReason != null) return

        val reason = when {
            elapsedMillis() >= config.maxDurationSeconds * 1000L -> FinalizationKind.MAX_DURATION
            availableStorage() < config.minFreeStorageBytes -> FinalizationKind.LOW_STORAGE
            else -> return
        }

        stopReason = StopReasonHolder(reason)
        logger.w(
            "recording limit reached; finalizing",
            fields = mapOf(
                "recording" to (session?.recordingId ?: "?"),
                "reason" to reason.name,
                "elapsedMillis" to elapsedMillis().toString(),
                "availableBytes" to availableStorage().toString(),
            ),
        )

        // `stop()` keeps a pre-set stop reason rather than overwriting it, so the
        // recording is labelled MAX_DURATION / LOW_STORAGE instead of being
        // mislabelled as user-requested.
        stop()
    }

    override suspend fun stop(): Result<FinalizedRecording> = mutex.withLock {
        runCatching {
            // Checked before dereferencing `session`: a stop triggered by a limit
            // has already finalized and cleared it, and re-entry is legitimate —
            // that is precisely how `enforceLimits` stops a recording. Reading
            // `session` first turned a self-protecting stop into a failure, which
            // would have surfaced as the recorder crashing at the moment it saved
            // the user's storage.
            if (_state.value == RecordingState.Stopped && lastFinalization != null) {
                return@withLock Result.success(lastFinalization!!)
            }

            val current = session ?: error("no active recording session")
            val config = current.config

            // Recorded so the evidence package can distinguish a recording the
            // user ended from one the engine ended to protect storage. A limit
            // has already set this, and must not be overwritten.
            if (stopReason == null) stopReason = StopReasonHolder(FinalizationKind.USER_REQUESTED)

            _state.value = RecordingState.Stopping

            // A pre-buffer drain is prepended to the FIRST segment at activation,
            // not at stop; recording may already have consumed it.
            commitActiveSegment()

            audioSource.close()
            pumpJob?.cancel()
            pumpJob = null

            val endedAt = timeProvider.elapsed()
            // Read the wrapped DEK *before* clearing. The plaintext key is zeroed
            // immediately below, and once that happens the wrapped blob is the only
            // thing that can ever decrypt this recording. Capturing it into the
            // result is what makes the audio recoverable; the alternative is
            // sealing a recording that nothing will ever be able to open.
            val material = keyMaterial
                ?: error("recording $current had no key material; it cannot be recovered")
            val result = FinalizedRecording(
                recordingId = current.recordingId,
                config = config,
                segments = committed.toList(),
                startedAtElapsed = evidenceStartedAt,
                endedAtElapsed = endedAt,
                startedAtWallClockMillis = current.startedAtWallClockMillis,
                endedAtWallClockMillis = timeProvider.now().epochMillis,
                activationSource = activationSource,
                activationConfidence = activationConfidence,
                isTestRecording = current.isTestRecording,
                notes = buildList {
                    stopReason?.let { add(it.kind.name) }
                    if (seededPreRollMillis > 0) add("preroll_ms=$seededPreRollMillis")
                    if (quality != CaptureQuality.NORMAL) add("capture_quality=${quality.name}")
                },
                wrappedKey = material.wrappedKey,
            )

            _state.value = RecordingState.Finalizing
            keyMaterial?.clear()
            _state.value = RecordingState.Stopped
            session = null
            lastFinalization = result
            result
        }.onFailure {
            _state.value = RecordingState.Failed(it.message ?: "stop failed", recoverable = true)
        }
    }

    /**
     * Seals and commits the active segment, then resets the buffer for the next.
     *
     * Takes no arguments because it has nothing to be told: the segment length
     * comes from the buffer itself, and whether a trailing partial segment is
     * acceptable is not this layer's decision. An earlier version accepted a
     * `config` and an `isComplete` flag and used neither, which read as a
     * policy that did not exist.
     */
    private fun commitActiveSegment() {
        val buffer = segmentBuffer ?: return
        val bytes = buffer.toByteArray()
        if (bytes.size <= WAV_HEADER_BYTES) {
            // Nothing but a header: no audio was captured. Writing a zero-length
            // segment would produce a manifest claiming audio that does not exist.
            buffer.reset()
            return
        }

        val currentWriter = writer ?: return

        // Patch the RIFF and data chunk sizes to the payload actually present.
        //
        // A full segment already has the correct sizes, because the buffer filled
        // to `limit`. The final partial segment — on a user stop, a max-duration
        // stop, or a low-storage stop — does not, and without this its header
        // would declare up to `segmentDurationSeconds` of audio that was never
        // captured. Players tolerate that; an evidence manifest does not.
        val actualDataSize = bytes.size - WAV_HEADER_BYTES
        patchWavDataSize(bytes, actualDataSize)

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
                "plaintextBytes" to actualDataSize.toString(),
            ),
        )

        nextSequence++
        buffer.reset()
        segmentStartedAt = timeProvider.elapsed()
    }

    /**
     * Patches a canonical 44-byte WAV header so the RIFF chunk size and the
     * `data` subchunk size reflect the bytes actually present in this segment.
     *
     * Offsets in a standard PCM WAV header:
     *
     * ```
     *  0  "RIFF"
     *  4  chunkSize        = 36 + dataSize   (file size minus 8)
     *  8  "WAVE"
     * 12  "fmt "
     * 16  subchunk1Size
     * 20  audioFormat, numChannels, sampleRate, byteRate, blockAlign, bitsPerSample
     * 36  "data"
     * 40  subchunk2Size    = dataSize        (PCM payload bytes)
     * ```
     *
     * Both fields are 32-bit little-endian. Byte 40 is patched from a literal
     * rather than derived from the format fields, so this stays correct for any
     * bit depth and channel count [WavWriter] supports.
     */
    private fun patchWavDataSize(buffer: ByteArray, dataSize: Int) {
        if (buffer.size < WAV_HEADER_BYTES) return
        val chunkSize = 36 + dataSize

        buffer[4] = (chunkSize and 0xFF).toByte()
        buffer[5] = ((chunkSize shr 8) and 0xFF).toByte()
        buffer[6] = ((chunkSize shr 16) and 0xFF).toByte()
        buffer[7] = ((chunkSize shr 24) and 0xFF).toByte()

        buffer[40] = (dataSize and 0xFF).toByte()
        buffer[41] = ((dataSize shr 8) and 0xFF).toByte()
        buffer[42] = ((dataSize shr 16) and 0xFF).toByte()
        buffer[43] = ((dataSize shr 24) and 0xFF).toByte()
    }

    /**
     * Prepends captured pre-roll to the first segment (SPEC §12).
     *
     * Called once at activation, immediately after the wake-word engine fires. The
     * bytes are the in-RAM ring from *before* activation, which is the entire point
     * of the feature; they are written into segment 0 so the manifest and the audio
     * stay consistent about what the recording contains.
     *
     * The bytes are defensively copied. The caller (the activation gate) hands us
     * a drain of the ring buffer; if we retained that array and the ring recycled
     * it, the prepended audio would silently mutate underneath the first segment.
     */
    fun seedPreRoll(preRoll: ByteArray) {
        if (preRoll.isEmpty()) return
        if (pendingPreRoll.isNotEmpty()) {
            // A second seed would corrupt the first segment's WAV header, which
            // already declares a fixed payload size. Refuse rather than append.
            logger.w("pre-roll seed ignored; a pre-roll is already pending")
            return
        }
        pendingPreRoll = preRoll.copyOf()
    }

    /**
     * Drains the in-RAM ring and seeds it as pre-roll.
     *
     * This is the wiring that was missing: without a caller, `RollingPreBuffer`
     * accumulated frames that were never written, so the pre-roll feature
     * silently did nothing while appearing to be configured. The activation gate
     * calls this at the moment a recording begins.
     *
     * @return the number of bytes seeded, so the caller can log whether the
     *   feature actually contributed anything.
     */
    fun drainPreRollIntoRecording(): Int {
        val buffer = preBuffer ?: return 0
        val drained = buffer.drainTo()
        seedPreRoll(drained)
        return drained.size
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
        session?.let { timeProvider.elapsed().elapsedRealtimeMs - evidenceStartedAt.elapsedRealtimeMs } ?: 0L

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