package com.safesignal.core.common.model

/**
 * A wall-clock instant expressed as epoch milliseconds.
 *
 * SafeSignal stores device wall-clock time as a primitive rather than
 * `java.time.Instant` for two reasons:
 *
 *  1. `java.time` on API 26 devices requires core library desugaring, which adds
 *     a build-time dependency we do not otherwise need.
 *  2. Evidence metadata is serialised to JSON and must remain readable and
 *     stable across app versions and across any future migration to
 *     `java.time`. The wire format does not change.
 *
 * IMPORTANT: a wall clock on a user device is *not* authoritative. It can be
 * changed by the user, drift with NTP, or be reset. SafeSignal therefore always
 * pairs a wall-clock value with a monotonic value, and treats the wall clock as
 * a presentation convenience only. See SPEC §51 and THREAT_MODEL.md §
 * "Timestamp integrity".
 */
@JvmInline
value class InstantEpochMillis(val epochMillis: Long) : Comparable<InstantEpochMillis> {
    override fun compareTo(other: InstantEpochMillis): Int = epochMillis.compareTo(other.epochMillis)
}

/**
 * A duration measured with `SystemClock.elapsedRealtime()`.
 *
 * Monotonic: unaffected by clock changes, NTP corrections or user edits. This
 * is what SafeSignal uses for every duration, ordering and cooldown decision.
 */
@JvmInline
value class ElapsedMillis(val elapsedRealtimeMs: Long) : Comparable<ElapsedMillis> {
    override fun compareTo(other: ElapsedMillis): Int = elapsedRealtimeMs.compareTo(other.elapsedRealtimeMs)

    operator fun minus(other: ElapsedMillis): ElapsedMillis =
        ElapsedMillis(elapsedRealtimeMs - other.elapsedRealtimeMs)

    operator fun plus(deltaMillis: Long): ElapsedMillis = ElapsedMillis(elapsedRealtimeMs + deltaMillis)
}

/**
 * A pair of timestamps captured together.
 *
 * Every event written to the incident timeline carries both. Keeping them in a
 * single type makes it impossible to record "when" without also recording
 * "when, according to a clock we can trust".
 */
data class DualTimestamp(
    val wallClock: InstantEpochMillis,
    val elapsed: ElapsedMillis,
) {
    companion object {
        /**
         * Server-issued timestamps, when a recording has been synchronized.
         * Distinct from [wallClock] on purpose — see SPEC §51.
         */
        fun serverReceipt(epochMillis: Long): InstantEpochMillis = InstantEpochMillis(epochMillis)
    }
}