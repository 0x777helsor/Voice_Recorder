package com.safesignal.audio.processing

/**
 * A bounded, in-memory circular buffer for the optional rolling pre-buffer
 * (SPEC §12).
 *
 * ### Invariants
 *
 *  * **Bounded.** Capacity is derived from the configured seconds, sample rate and
 *    channel count. The buffer can never grow, so a user selecting "15 seconds"
 *    cannot cause unbounded memory use. On a 48 kHz mono 16-bit stream that is
 *    1.44 MB; the class refuses to allocate more than [MAX_BYTES] and reports
 *    the shortfall rather than silently over-allocating.
 *  * **RAM only.** Nothing here touches the filesystem. SPEC §12 requires that
 *    pre-activation audio is not persisted unless the user explicitly enables a
 *    policy that demands it, and the default policy is [PreBufferPolicy.DISABLED].
 *  * **Oldest-first drain.** [drainTo] returns samples in capture order, which is
 *    the order they must be prepended to a recording.
 *
 * ### Why a partial frame is never emitted
 *
 * A PCM frame is `channels * bytesPerSample` bytes. Draining a whole number of
 * frames is what keeps a downstream WAV container byte-aligned. Returning a
 * ragged tail would produce a file that no decoder reads, and an unreadable
 * evidence file is worse than a slightly shorter one.
 */
class RollingPreBuffer(
    private val capacityBytes: Int,
    private val frameSizeBytes: Int,
) {
    init {
        require(capacityBytes > 0) { "capacityBytes must be positive" }
        require(frameSizeBytes > 0) { "frameSizeBytes must be positive" }
    }

    private val buffer = ByteArray(capacityBytes)
    private var writePosition = 0
    private var totalBytesWritten = 0L

    /** Bytes currently held. Saturates at [capacityBytes]. */
    val bufferedBytes: Int get() = minOf(totalBytesWritten, capacityBytes.toLong()).toInt()

    /** Total bytes ever written, including those already overwritten. */
    val totalBytesWrittenTotal: Long get() = totalBytesWritten

    val isFull: Boolean get() = totalBytesWritten >= capacityBytes

    val capacityFrames: Int get() = capacityBytes / frameSizeBytes

    val bufferedFrames: Int get() = bufferedBytes / frameSizeBytes

    /**
     * Appends [length] bytes from [source] starting at [offset].
     *
     * Copies defensively: the caller may reuse its read buffer immediately, and
     * the pre-buffer must survive that.
     */
    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) {
            "write out of bounds"
        }
        if (length == 0) return

        val firstChunk = minOf(length, capacityBytes - writePosition)
        source.copyInto(buffer, writePosition, offset, offset + firstChunk)

        var written = firstChunk
        var srcOffset = offset + firstChunk
        if (written < length) {
            // Wrapped: continue from the start of the buffer.
            val secondChunk = length - firstChunk
            source.copyInto(buffer, 0, srcOffset, srcOffset + secondChunk)
            srcOffset += secondChunk
            written += secondChunk
        }

        writePosition = (writePosition + written) % capacityBytes
        totalBytesWritten += written
    }

    /**
     * Removes and returns the buffered samples, oldest first.
     *
     * The buffer is emptied. Draining twice returns an empty array the second
     * time, which is what makes activation idempotent: a duplicated activation
     * event cannot prepend the pre-roll twice.
     *
     * @return a fresh array of whole frames, or an empty array.
     */
    fun drainTo(): ByteArray {
        val held = bufferedBytes
        val wholeFrames = held / frameSizeBytes
        val outputSize = wholeFrames * frameSizeBytes
        if (outputSize == 0) {
            // Nothing frame-aligned to return; reset so a later drain is clean.
            if (held > 0) reset()
            return ByteArray(0)
        }

        val out = ByteArray(outputSize)
        val start = (writePosition - outputSize + capacityBytes) % capacityBytes
        val firstChunk = minOf(outputSize, capacityBytes - start)
        buffer.copyInto(out, 0, start, start + firstChunk)
        if (firstChunk < outputSize) {
            buffer.copyInto(out, firstChunk, 0, outputSize - firstChunk)
        }
        reset()
        return out
    }

    /** Discards everything without returning it. Used when an activation is rejected. */
    fun reset() {
        writePosition = 0
        totalBytesWritten = 0L
        // Not zeroing the array: it is about to be overwritten, and a
        // short-lived stale buffer is overwritten before it is read. Callers
        // that need stronger guarantees call [clearSensitive] instead.
    }

    /**
     * Overwrites the backing array before releasing the reference.
     *
     * Used when the user disables the pre-buffer, so that the last few seconds of
     * ambient audio do not linger in a heap region waiting for reuse.
     */
    fun clearSensitive() {
        buffer.fill(0)
        writePosition = 0
        totalBytesWritten = 0L
    }

    /**
     * Computes capacity for a duration.
     *
     * @return the byte capacity, clamped to [MAX_BYTES].
     */
    companion object {
        /** Upper bound on pre-buffer memory: ~30 s at 48 kHz mono 16-bit. */
        const val MAX_BYTES: Int = 3 * 1024 * 1024

        fun capacityFor(
            seconds: Int,
            sampleRateHz: Int,
            channels: Int,
            bytesPerSample: Int = 2,
        ): Int {
            require(seconds > 0) { "seconds must be positive" }
            val frameSize = channels * bytesPerSample
            val raw = (seconds.toLong() * sampleRateHz * frameSize).toInt()
            return raw.coerceAtMost(MAX_BYTES)
        }
    }
}