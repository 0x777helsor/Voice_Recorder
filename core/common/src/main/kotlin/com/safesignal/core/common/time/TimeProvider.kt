package com.safesignal.core.common.time

import com.safesignal.core.common.model.DualTimestamp
import com.safesignal.core.common.model.ElapsedMillis
import com.safesignal.core.common.model.InstantEpochMillis

/**
 * Abstracts the two clocks SafeSignal depends on.
 *
 * Everything that measures time takes a [TimeProvider]. That keeps the state
 * machine, the activation gate, the pre-buffer and the sync retry policy
 * unit-testable without `Thread.sleep`, and it makes the wall-clock/monotonic
 * pairing explicit rather than incidental.
 */
interface TimeProvider {
    /** Device wall clock. User-facing only. Never trusted for ordering. */
    fun now(): InstantEpochMillis

    /** Monotonic clock since boot. Authoritative for ordering and durations. */
    fun elapsed(): ElapsedMillis

    /** Both, captured as close together as the platform allows. */
    fun nowDual(): DualTimestamp = DualTimestamp(wallClock = now(), elapsed = elapsed())
}

/**
 * Production implementation backed by the Android framework clocks.
 *
 * `System.currentTimeMillis()` for the wall clock and
 * `SystemClock.elapsedRealtime()` for the monotonic clock — the latter
 * deliberately *not* `nanoTime()`: `elapsedRealtime()` keeps counting while the
 * device is asleep, which is what a recording that spans a screen-off period
 * needs.
 */
class SystemTimeProvider(
    private val wallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val elapsedRealtimeMillis: () -> Long = {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.os.SystemClock.elapsedRealtime()
        } else {
            @Suppress("DEPRECATION")
            android.os.SystemClock.elapsedRealtime()
        }
    },
) : TimeProvider {
    override fun now(): InstantEpochMillis = InstantEpochMillis(wallClockMillis())

    override fun elapsed(): ElapsedMillis = ElapsedMillis(elapsedRealtimeMillis())
}