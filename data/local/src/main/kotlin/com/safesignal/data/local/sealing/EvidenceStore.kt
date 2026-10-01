package com.safesignal.data.local.sealing

/**
 * What sealing a finished recording needs beyond the capture itself.
 *
 * Bundled into one type because the archiver's signature had grown to seven
 * parameters, and every call site had to remember the order. An argument mix-up
 * there would write a manifest that disagreed with the database row, which is
 * exactly the kind of quiet inconsistency this design is meant to prevent.
 *
 * Defaults are supplied for the fields that are genuinely constant today
 * (`appVersion`, retention) and required for the ones that are facts about this
 * particular recording.
 */
data class ArchiveRequest(
    val capture: RecordingToSeal,

    /** Recorded in the manifest and the row, so a verifier knows what produced this. */
    val appVersion: String,

    /** A timestamp without its zone is ambiguous, and this is evidence. */
    val deviceTimezoneId: String,

    /**
     * Detector version, or null when no detector was involved.
     *
     * Recorded rather than omitted so "no wake word" stays distinguishable from
     * "this build did not say".
     */
    val wakeWordEngineVersion: String?,

    /** NORMAL / NEAR_SILENT / CLIPPED, so a reader knows whether capture was degraded. */
    val captureQuality: String,

    /** KEEP_INDEFINITELY / MANUAL_DELETION / DELETE_AFTER_DAYS. */
    val retentionPolicy: String,

    val nowWallClockMillis: Long,
)

/**
 * Stores a finished recording: its signed manifest on disk, its metadata in the
 * database.
 *
 * An interface so that the capture pipeline can be tested without Room or a
 * Keystore. `EvidenceArchiver` is the only production implementation.
 */
interface EvidenceStore {
    /**
     * Seals and stores. Must not throw for a storage problem: the evidence is
     * already sealed on disk, so a failure here is a degraded index, not lost
     * audio, and reporting it as a lost recording would be wrong.
     */
    suspend fun archive(request: ArchiveRequest)
}
