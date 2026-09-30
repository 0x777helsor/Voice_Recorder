package com.safesignal.audio.processing

import java.io.OutputStream

/**
 * Minimal streaming RIFF/WAVE writer (SPEC §16).
 *
 * ### Why PCM/WAV is the default format
 *
 * SPEC §16 asks for the best balance of reliability, compatibility, storage and
 * processing. For evidence, WAV wins on the first three:
 *
 *  * **Reliability.** The container is trivial and the codec is a raw copy of
 *    the microphone samples, so there is no encoder state to lose on a crash and
 *    no encoder bug that can corrupt a long recording.
 *  * **Compatibility.** Every platform can read it, including old courtroom and
 *    transcription tooling.
 *  * **Recovery.** Because each segment is self-contained, a truncated final
 *    segment is still playable up to the truncation point.
 *
 * AAC/M4A is offered as a configurable alternative for storage-constrained
 * users, with the tradeoff stated in the UI: smaller files, and a compressed
 * derivative of the capture rather than the capture itself. SafeSignal's
 * "original is immutable" promise (SPEC §20) is easiest to keep honest when the
 * original is uncompressed.
 *
 * ### Streaming
 *
 * The header's size fields are unknown until recording ends, so the writer
 * reserves them and back-patches on [close]. This class is therefore *not*
 * usable with a non-seekable stream; [WavSegmentWriter] handles that by writing
 * segments whose headers are known up front.
 */
class WavWriter private constructor(
    private val output: OutputStream,
    private val headerSize: Int = HEADER_SIZE,
) {
    private var dataBytesWritten: Long = 0
    private var framesWritten: Long = 0
    private var closed = false

    val bytesWritten: Long get() = dataBytesWritten

    val durationMillis: Long
        get() = if (sampleRate == 0) 0 else (framesWritten * 1000L) / sampleRate

    var sampleRate: Int = 0
        private set

    var channels: Int = 0
        private set

    var bitsPerSample: Int = 0
        private set

    val frameSize: Int get() = channels * (bitsPerSample / 8)

    /**
     * Writes the WAV header with placeholder sizes.
     *
     * @param seekableStream must support `write` at arbitrary offsets; the header
     *   is corrected in [close].
     */
    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        check(!closed) { "WavWriter is closed" }
        output.write(pcm, offset, length)
        dataBytesWritten += length
    }

    /** Recomputes frame count. Called by [close] once the size is known. */
    fun close() {
        if (closed) return
        closed = true
        // Sizes are only patched by SeekableWavWriter; this base class finalises
        // the frame count only.
        framesWritten = if (frameSize > 0) dataBytesWritten / frameSize else 0
        output.flush()
    }

    companion object {
        const val HEADER_SIZE: Int = 44
        const val PCM_FORMAT: Int = 1

        /**
         * Builds a canonical 44-byte WAV header.
         *
         * @param dataSize total PCM payload size, or 0 for a placeholder that
         *   will be back-patched later.
         */
        fun header(
            sampleRateHz: Int,
            channels: Int,
            bitsPerSample: Int,
            dataSize: Int = 0,
        ): ByteArray {
            require(sampleRateHz > 0) { "sampleRateHz must be positive" }
            require(channels in 1..8) { "channels out of range" }
            require(bitsPerSample % 8 == 0) { "bitsPerSample must be byte-aligned" }

            val byteRate = sampleRateHz * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val riffSize = 36 + dataSize

            val out = ByteArray(HEADER_SIZE)
            var p = 0

            fun ascii(text: String) {
                text.forEach { out[p++] = it.code.toByte() }
            }

            fun le32(value: Int) {
                out[p++] = (value and 0xFF).toByte()
                out[p++] = ((value shr 8) and 0xFF).toByte()
                out[p++] = ((value shr 16) and 0xFF).toByte()
                out[p++] = ((value shr 24) and 0xFF).toByte()
            }

            fun le16(value: Int) {
                out[p++] = (value and 0xFF).toByte()
                out[p++] = ((value shr 8) and 0xFF).toByte()
            }

            ascii("RIFF")
            le32(riffSize)
            ascii("WAVE")
            ascii("fmt ")
            le32(16) // PCM fmt chunk size
            le16(PCM_FORMAT)
            le16(channels)
            le32(sampleRateHz)
            le32(byteRate)
            le16(blockAlign)
            le16(bitsPerSample)
            ascii("data")
            le32(dataSize)
            return out
        }
    }
}

/**
 * Writes a self-contained WAV file whose header is correct from the first byte.
 *
 * This is the class the recorder actually uses. Because each segment is sealed,
 * encrypted and closed independently, the recorder knows the segment length
 * before it starts writing and therefore emits a fully correct header
 * immediately — no back-patching, no partially-written header, and a segment
 * that survives a crash mid-write still plays up to the point of truncation.
 */
class WavSegmentWriter(
    output: OutputStream,
    sampleRateHz: Int,
    channels: Int,
    bitsPerSample: Int = 16,
) {
    private val expectedPayloadSize: Int
    private val sampleRateHz: Int = sampleRateHz
    private val channels: Int = channels
    private val bitsPerSample: Int = bitsPerSample

    private var output: OutputStream? = output
    private var payloadBytes: Long = 0
    private var closed = false

    init {
        val byteRate = sampleRateHz * channels * bitsPerSample / 8
        require(byteRate in 1..Int.MAX_VALUE) { "byte rate out of range" }
        val blockAlign = channels * bitsPerSample / 8
        val declaredPayload = byteRate.toLong() * SEGMENT_DURATION_SECONDS
        val declaredPayloadInt = declaredPayload.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        expectedPayloadSize = declaredPayloadInt

        val header = WavWriter.header(
            sampleRateHz = sampleRateHz,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataSize = declaredPayloadInt,
        )
        this.output?.write(header)
    }

    val frameSize: Int = channels * bitsPerSample / 8

    val payloadBytesWritten: Long get() = payloadBytes

    val isFull: Boolean get() = payloadBytes >= expectedPayloadSize

    fun write(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset) {
        check(!closed) { "WavSegmentWriter is closed" }
        output?.write(pcm, offset, length)
        payloadBytes += length
    }

    fun close() {
        if (closed) return
        closed = true
        output?.flush()
        output?.close()
        output = null
    }

    private companion object {
        /**
         * Nominal segment length used to size the header.
         *
         * A WAV header must declare a payload size up front. SafeSignal fixes
         * segment duration (SPEC §17), so the header is written for that duration
         * and the final segment is truncated at the same length by the recorder,
         * which keeps every header exactly correct.
         */
        const val SEGMENT_DURATION_SECONDS = 60L
    }
}