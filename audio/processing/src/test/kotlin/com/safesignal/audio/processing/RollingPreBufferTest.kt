package com.safesignal.audio.processing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rolling pre-buffer tests (SPEC §12, §75).
 *
 * The privacy-critical property is that nothing is persisted and nothing
 * accumulates without bound; the correctness-critical property is that a drained
 * buffer is exactly the audio that preceded activation, in order, frame-aligned.
 */
class RollingPreBufferTest {

    private fun buffer(capacityBytes: Int = 8, frameSize: Int = 2) =
        RollingPreBuffer(capacityBytes, frameSize)

    @Test
    fun `drain returns what was written when it fits`() {
        val b = buffer()
        b.write(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.drainTo())
    }

    @Test
    fun `buffer keeps only the most recent bytes when it overflows`() {
        val b = buffer(capacityBytes = 4, frameSize = 2)
        b.write(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(4, b.bufferedBytes)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), b.drainTo())
    }

    @Test
    fun `wrap-around across many writes preserves order`() {
        val b = buffer(capacityBytes = 6, frameSize = 2)
        // 5 writes of 2 bytes through a 6-byte ring: the tail must be the last 3 frames.
        for (i in 0 until 5) {
            b.write(byteArrayOf((i * 2).toByte(), (i * 2 + 1).toByte()))
        }
        assertEquals(6, b.bufferedBytes)
        assertArrayEquals(
            byteArrayOf(4, 5, 6, 7, 8, 9),
            b.drainTo(),
        )
    }

    @Test
    fun `drain empties the buffer so a duplicate activation cannot duplicate pre-roll`() {
        val b = buffer()
        b.write(byteArrayOf(1, 2, 3, 4))
        assertEquals(4, b.drainTo().size)
        assertEquals(0, b.bufferedBytes)
        assertEquals(0, b.drainTo().size)
    }

    @Test
    fun `a ragged partial frame is never emitted`() {
        val b = buffer(capacityBytes = 8, frameSize = 2)
        b.write(byteArrayOf(1, 2, 3)) // 1.5 frames
        val drained = b.drainTo()
        assertEquals("only whole frames may be returned", 2, drained.size)
        // The leading partial byte is dropped, keeping the NEWEST complete frame.
        // For evidence, the most recent audio before activation is the part worth
        // preserving; a leading partial frame would also misalign the container.
        assertArrayEquals(byteArrayOf(2, 3), drained)
    }

    @Test
    fun `write copies defensively so a reused read buffer cannot corrupt history`() {
        val b = buffer(capacityBytes = 4, frameSize = 2)
        val scratch = byteArrayOf(1, 2, 3, 4)
        b.write(scratch)
        // The capture loop reuses its buffer for the next AudioRecord.read().
        scratch[0] = 99
        scratch[1] = 98
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.drainTo())
    }

    @Test
    fun `a write larger than capacity keeps only the tail`() {
        val b = buffer(capacityBytes = 4, frameSize = 2)
        b.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), b.drainTo())
    }

    @Test
    fun `reset discards without returning anything`() {
        val b = buffer()
        b.write(byteArrayOf(1, 2, 3, 4))
        b.reset()
        assertEquals(0, b.bufferedBytes)
        assertEquals(0, b.drainTo().size)
    }

    @Test
    fun `clearSensitive overwrites the backing array`() {
        val b = buffer()
        b.write(byteArrayOf(1, 2, 3, 4))
        b.clearSensitive()
        assertEquals(0, b.bufferedBytes)
        assertEquals(0, b.drainTo().size)
    }

    @Test
    fun `capacity for five seconds at 48kHz mono 16-bit is the documented figure`() {
        // 5 s * 48000 * 2 bytes = 480,000
        assertEquals(480_000, RollingPreBuffer.capacityFor(5, 48_000, 1))
    }

    @Test
    fun `capacity is clamped so a long pre-roll cannot exhaust the heap`() {
        val huge = RollingPreBuffer.capacityFor(seconds = 3_600, sampleRateHz = 48_000, channels = 2)
        assertTrue("capacity must be clamped to MAX_BYTES", huge <= RollingPreBuffer.MAX_BYTES)
    }

    @Test
    fun `the default policy stores nothing`() {
        assertEquals(0, PreBufferPolicy.DISABLED.approximateBytes())
        assertTrue(!PreBufferPolicy.DISABLED.isEnabled)
        assertEquals(5, PreBufferPolicy.FIVE_SECONDS.seconds)
        assertEquals(PreBufferPolicy.TEN_SECONDS, PreBufferPolicy.fromSeconds(10))
        assertEquals(PreBufferPolicy.DISABLED, PreBufferPolicy.fromSeconds(999))
    }

    @Test
    fun `total bytes written keeps counting after the buffer is full`() {
        val b = buffer(capacityBytes = 4, frameSize = 2)
        b.write(ByteArray(8))
        assertEquals(8L, b.totalBytesWrittenTotal)
        assertEquals(4, b.bufferedBytes)
    }
}