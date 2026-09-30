package com.safesignal.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A pending synchronization job for one recording (SPEC §41, §46, §48).
 *
 * Idempotency is modelled here rather than in the network layer so that a crash
 * between "sent" and "acknowledged" is recoverable: [idempotencyKey] is derived
 * deterministically from the recording and the operation, so a retry presents the
 * same key to the server and is collapsed rather than duplicated.
 */
@Entity(
    tableName = "upload_tasks",
    indices = [
        Index(value = ["recording_id"], unique = true),
        Index(value = ["state"]),
        Index(value = ["next_attempt_at"]),
    ],
)
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "recording_id")
    val recordingId: String,

    /** QUEUED / UPLOADING / PARTIALLY_UPLOADED / SYNCED / FAILED / RETRYING. */
    @ColumnInfo(name = "state")
    val state: String,

    /** Next allowed attempt. Drives exponential backoff without a scheduler. */
    @ColumnInfo(name = "next_attempt_at")
    val nextAttemptAt: Long,

    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int,

    /**
     * Stable key for this (recording, operation) pair. Regenerated — never
     * random per attempt — so a retry is server-side idempotent.
     */
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,

    @ColumnInfo(name = "uploaded_segments")
    val uploadedSegments: Int,

    @ColumnInfo(name = "total_segments")
    val totalSegments: Int,

    @ColumnInfo(name = "last_error_code")
    val lastErrorCode: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)