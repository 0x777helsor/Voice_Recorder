package com.safesignal.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.safesignal.core.database.entity.RecordingEntity
import com.safesignal.core.database.entity.RecordingSegmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Recording and segment persistence (SPEC §85, §86).
 *
 * ### The transactional rule this DAO exists to enforce
 *
 * SPEC §86 requires that "segment committed + segment hash stored + recording
 * metadata updated" cannot leave the database claiming a segment exists when the
 * file does not. [commitSegment] is therefore a single `@Transaction`: callers
 * write and hash the sealed file *first*, then call this once. There is
 * deliberately no public method that inserts a segment row on its own.
 */
@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecording(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE recording_id = :recordingId LIMIT 1")
    suspend fun getRecording(recordingId: String): RecordingEntity?

    @Query("SELECT * FROM recordings ORDER BY started_at_wall_clock DESC")
    fun observeRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE is_test_recording = 0 ORDER BY started_at_wall_clock DESC")
    fun observeEvidenceRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE session_state != 'SEALED'")
    suspend fun getUnsealedRecordings(): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE upload_state = 'QUEUED' OR upload_state = 'RETRYING'")
    fun observeQueuedRecordings(): Flow<List<RecordingEntity>>

    @Query("UPDATE recordings SET session_state = :state, updated_at = :now WHERE recording_id = :recordingId")
    suspend fun updateSessionState(recordingId: String, state: String, now: Long)

    @Query(
        """
        UPDATE recordings
        SET ended_at_wall_clock = :endedWallClock,
            ended_at_elapsed = :endedElapsed,
            duration_millis = :duration,
            segment_count = :segmentCount,
            file_size_bytes = :fileSize,
            sealed_bytes = :sealedBytes,
            sha256 = :sha256,
            capture_quality = :quality,
            session_state = :state,
            upload_state = :uploadState,
            updated_at = :now
        WHERE recording_id = :recordingId
        """,
    )
    suspend fun updateFinalized(
        recordingId: String,
        endedWallClock: Long,
        endedElapsed: Long,
        duration: Long,
        segmentCount: Int,
        fileSize: Long,
        sealedBytes: Long,
        sha256: String?,
        quality: String,
        state: String,
        uploadState: String,
        now: Long,
    )

    @Query("UPDATE recordings SET upload_state = :state, updated_at = :now WHERE recording_id = :recordingId")
    suspend fun updateUploadState(recordingId: String, state: String, now: Long)

    @Query("UPDATE recordings SET upload_receipt = :receipt, updated_at = :now WHERE recording_id = :recordingId")
    suspend fun updateUploadReceipt(recordingId: String, receipt: String?, now: Long)

    @Query("UPDATE recordings SET retention_policy = :policy, delete_after = :deleteAfter, updated_at = :now WHERE recording_id = :recordingId")
    suspend fun updateRetention(recordingId: String, policy: String, deleteAfter: Long?, now: Long)

    @Query("DELETE FROM recordings WHERE recording_id = :recordingId")
    suspend fun deleteRecording(recordingId: String)

    // Retention selection is a SELECT, not a DELETE: SPEC §53 requires explicit
    // user confirmation, so the caller must see the rows before anything is removed.
    @Query("SELECT * FROM recordings WHERE delete_after IS NOT NULL AND delete_after <= :now")
    suspend fun findExpiredByRetention(now: Long): List<RecordingEntity>

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun count(): Int

    // ------------------------------------------------------------------ segments

    @Query("SELECT * FROM recording_segments WHERE recording_id = :recordingId ORDER BY sequence_number ASC")
    suspend fun getSegments(recordingId: String): List<RecordingSegmentEntity>

    @Query("SELECT * FROM recording_segments WHERE recording_id = :recordingId ORDER BY sequence_number ASC")
    fun observeSegments(recordingId: String): Flow<List<RecordingSegmentEntity>>

    @Query("SELECT * FROM recording_segments WHERE recording_id = :recordingId AND uploaded = 0 ORDER BY sequence_number ASC")
    suspend fun getUnuploadedSegments(recordingId: String): List<RecordingSegmentEntity>

    @Query("UPDATE recording_segments SET uploaded = 1 WHERE recording_id = :recordingId AND sequence_number IN (:sequenceNumbers)")
    suspend fun markSegmentsUploaded(recordingId: String, sequenceNumbers: List<Int>)

    @Query("SELECT COUNT(*) FROM recording_segments WHERE recording_id = :recordingId")
    suspend fun countSegments(recordingId: String): Int

    @Query("SELECT MAX(sequence_number) FROM recording_segments WHERE recording_id = :recordingId")
    suspend fun maxSequenceNumber(recordingId: String): Int?

    @Query("DELETE FROM recording_segments WHERE recording_id = :recordingId")
    suspend fun deleteSegments(recordingId: String)

    /**
     * Atomically records one committed segment and bumps the recording counter.
     *
     * @throws androidx.room.SQLiteConstraintException if the sequence number is a
     *   duplicate, which the unique index enforces. A duplicate here means a
     *   replayed commit, and failing loudly is better than silently discarding
     *   evidence.
     */
    @Transaction
    suspend fun commitSegment(segment: RecordingSegmentEntity, now: Long) {
        insertSegment(segment)
        bumpSegmentCount(segment.recordingId, now)
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: RecordingSegmentEntity)

    @Query("UPDATE recordings SET segment_count = segment_count + 1, updated_at = :now WHERE recording_id = :recordingId")
    suspend fun bumpSegmentCount(recordingId: String, now: Long)
}