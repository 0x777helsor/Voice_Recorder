package com.safesignal.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One committed segment of a recording (SPEC §17).
 *
 * A row exists only once the sealed segment file has been written, closed and
 * hashed. The insert happens in the same transaction as the recording's counter
 * update, so the database can never claim a segment exists whose file does not
 * (SPEC §86).
 *
 * @property sealedFileName relative file name inside the recording's directory.
 *   Never an absolute path: the app's data directory moves between installs on
 *   some OEM builds, and an absolute path would silently break recovery.
 */
@Entity(
    tableName = "recording_segments",
    foreignKeys = [
        ForeignKey(
            entity = RecordingEntity::class,
            parentColumns = ["recording_id"],
            childColumns = ["recording_id"],
            // Deleting a recording removes its segment rows. The *files* are
            // removed by the store, not by Room: Room must never be the component
            // that destroys evidence.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recording_id", "sequence_number"], unique = true),
        Index(value = ["recording_id"]),
    ],
)
data class RecordingSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "recording_id")
    val recordingId: String,

    @ColumnInfo(name = "segment_id")
    val segmentId: String,

    /** Gap-free, zero-based. Detected as non-contiguous on recovery. */
    @ColumnInfo(name = "sequence_number")
    val sequenceNumber: Int,

    @ColumnInfo(name = "sealed_file_name")
    val sealedFileName: String,

    @ColumnInfo(name = "sealed_length_bytes")
    val sealedLengthBytes: Long,

    @ColumnInfo(name = "plaintext_length_bytes")
    val plaintextLengthBytes: Long,

    /** SHA-256 of the decrypted segment. Recorded in the manifest too. */
    @ColumnInfo(name = "sha256")
    val sha256: String,

    @ColumnInfo(name = "started_at_elapsed")
    val startedAtElapsed: Long,

    @ColumnInfo(name = "ended_at_elapsed")
    val endedAtElapsed: Long,

    /** False when the segment is the tail written at stop time and may be partial. */
    @ColumnInfo(name = "is_complete")
    val isComplete: Boolean,

    /** True once the server has confirmed this chunk. Drives resumable upload. */
    @ColumnInfo(name = "uploaded")
    val uploaded: Boolean,
)