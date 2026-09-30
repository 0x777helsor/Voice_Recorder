package com.safesignal.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recording session (SPEC §18).
 *
 * ### What is deliberately absent
 *
 * No location, no contacts, no device identifiers, no "what else was happening"
 * fields. SPEC §95 forbids over-collection, and the strongest enforcement is not
 * having the column. Any future feature needing one must justify it explicitly.
 *
 * ### Consistency contract
 *
 * A row here may only exist while its segment files exist. See
 * `EvidenceStoreReconciler` for how that is enforced on startup: the database is
 * treated as a cache of the filesystem, never as the source of truth about
 * evidence.
 */
@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["started_at_wall_clock"]),
        Index(value = ["upload_state"]),
        Index(value = ["session_state"]),
    ],
)
data class RecordingEntity(
    @PrimaryKey
    @ColumnInfo(name = "recording_id")
    val recordingId: String,

    /** Wall clock at capture start. User-facing only; never trusted for ordering. */
    @ColumnInfo(name = "started_at_wall_clock")
    val startedAtWallClock: Long,

    @ColumnInfo(name = "ended_at_wall_clock")
    val endedAtWallClock: Long?,

    /** Monotonic start/end. Authoritative for duration and ordering. */
    @ColumnInfo(name = "started_at_elapsed")
    val startedAtElapsed: Long,

    @ColumnInfo(name = "ended_at_elapsed")
    val endedAtElapsed: Long?,

    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long,

    @ColumnInfo(name = "segment_count")
    val segmentCount: Int,

    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long,

    @ColumnInfo(name = "sealed_bytes")
    val sealedBytes: Long,

    /**
     * SHA-256 over the whole recording, matching `recordingSha256` in the
     * manifest. Denormalised so the history list can show an integrity badge
     * without loading and re-hashing every segment.
     */
    @ColumnInfo(name = "sha256")
    val sha256: String?,

    @ColumnInfo(name = "encryption_version")
    val encryptionVersion: Int,

    @ColumnInfo(name = "key_version")
    val keyVersion: Int,

    @ColumnInfo(name = "key_provider")
    val keyProvider: String,

    /** Wrapped data-encryption key. Ciphertext; safe to store. */
    @ColumnInfo(name = "wrapped_key")
    val wrappedKey: ByteArray,

    @ColumnInfo(name = "wrapped_key_iv")
    val wrappedKeyIv: ByteArray,

    @ColumnInfo(name = "app_version")
    val appVersion: String,

    @ColumnInfo(name = "device_timezone")
    val deviceTimezone: String,

    @ColumnInfo(name = "activation_source")
    val activationSource: String,

    @ColumnInfo(name = "activation_confidence")
    val activationConfidence: Float?,

    @ColumnInfo(name = "wake_word_engine_version")
    val wakeWordEngineVersion: String?,

    @ColumnInfo(name = "audio_format")
    val audioFormat: String,

    @ColumnInfo(name = "sample_rate_hz")
    val sampleRateHz: Int,

    @ColumnInfo(name = "channels")
    val channels: Int,

    /**
     * Capture quality verdict (NORMAL / NEAR_SILENT / CLIPPED / INTERRUPTED).
     * Recorded so a reader later knows the capture was degraded rather than
     * guessing from silence.
     */
    @ColumnInfo(name = "capture_quality")
    val captureQuality: String,

    /** True for Test Activation recordings, which are never uploaded by default. */
    @ColumnInfo(name = "is_test_recording")
    val isTestRecording: Boolean,

    /**
     * LOCAL_ONLY / QUEUED / UPLOADING / PARTIALLY_UPLOADED / SYNCED / FAILED /
     * RETRYING. Deliberately a string rather than a Room enum so the column can
     * be added to without a migration when the sync state machine grows a state.
     */
    @ColumnInfo(name = "upload_state")
    val uploadState: String,

    /** Server-issued receipt, once synchronized. Null otherwise. */
    @ColumnInfo(name = "upload_receipt")
    val uploadReceipt: String?,

    /** Device retention policy: KEEP_INDEFINITELY / MANUAL_DELETION / DELETE_AFTER_DAYS. */
    @ColumnInfo(name = "retention_policy")
    val retentionPolicy: String,

    /** Timestamp at which an automatic retention policy may delete the local copy. */
    @ColumnInfo(name = "delete_after")
    val deleteAfter: Long?,

    /**
     * Session lifecycle: RECORDING / FINALIZING / SEALED / FAILED.
     * Anything other than SEALED means recovery must run on next start.
     */
    @ColumnInfo(name = "session_state")
    val sessionState: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RecordingEntity) return false
        return recordingId == other.recordingId &&
            startedAtWallClock == other.startedAtWallClock &&
            endedAtWallClock == other.endedAtWallClock &&
            startedAtElapsed == other.startedAtElapsed &&
            endedAtElapsed == other.endedAtElapsed &&
            durationMillis == other.durationMillis &&
            segmentCount == other.segmentCount &&
            fileSizeBytes == other.fileSizeBytes &&
            sealedBytes == other.sealedBytes &&
            sha256 == other.sha256 &&
            encryptionVersion == other.encryptionVersion &&
            keyVersion == other.keyVersion &&
            keyProvider == other.keyProvider &&
            wrappedKey.contentEquals(other.wrappedKey) &&
            wrappedKeyIv.contentEquals(other.wrappedKeyIv) &&
            appVersion == other.appVersion &&
            deviceTimezone == other.deviceTimezone &&
            activationSource == other.activationSource &&
            activationConfidence == other.activationConfidence &&
            wakeWordEngineVersion == other.wakeWordEngineVersion &&
            audioFormat == other.audioFormat &&
            sampleRateHz == other.sampleRateHz &&
            channels == other.channels &&
            captureQuality == other.captureQuality &&
            isTestRecording == other.isTestRecording &&
            uploadState == other.uploadState &&
            uploadReceipt == other.uploadReceipt &&
            retentionPolicy == other.retentionPolicy &&
            deleteAfter == other.deleteAfter &&
            sessionState == other.sessionState &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = recordingId.hashCode()
        result = 31 * result + startedAtWallClock.hashCode()
        result = 31 * result + segmentCount
        result = 31 * result + wrappedKey.contentHashCode()
        result = 31 * result + wrappedKeyIv.contentHashCode()
        result = 31 * result + uploadState.hashCode()
        result = 31 * result + sessionState.hashCode()
        return result
    }
}