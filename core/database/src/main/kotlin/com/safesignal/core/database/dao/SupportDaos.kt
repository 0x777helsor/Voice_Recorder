package com.safesignal.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.safesignal.core.database.entity.AppSettingsEntity
import com.safesignal.core.database.entity.EvidenceManifestEntity
import com.safesignal.core.database.entity.SettingsKeys
import com.safesignal.core.database.entity.TimelineEventEntity
import com.safesignal.core.database.entity.UploadTaskEntity
import com.safesignal.core.database.entity.WakeWordConfigurationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: UploadTaskEntity)

    @Query("SELECT * FROM upload_tasks WHERE recording_id = :recordingId LIMIT 1")
    suspend fun getTask(recordingId: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE state IN ('QUEUED', 'RETRYING') AND next_attempt_at <= :now ORDER BY next_attempt_at ASC")
    suspend fun getRunnableTasks(now: Long): List<UploadTaskEntity>

    @Query("SELECT * FROM upload_tasks WHERE state IN ('QUEUED', 'RETRYING') AND next_attempt_at <= :now ORDER BY next_attempt_at ASC")
    fun observeRunnableTasks(now: Long): Flow<List<UploadTaskEntity>>

    @Query(
        """
        UPDATE upload_tasks
        SET state = :state,
            attempt_count = :attemptCount,
            next_attempt_at = :nextAttemptAt,
            uploaded_segments = :uploadedSegments,
            last_error_code = :errorCode,
            updated_at = :now
        WHERE recording_id = :recordingId
        """,
    )
    suspend fun updateProgress(
        recordingId: String,
        state: String,
        attemptCount: Int,
        nextAttemptAt: Long,
        uploadedSegments: Int,
        errorCode: String?,
        now: Long,
    )

    @Query("DELETE FROM upload_tasks WHERE recording_id = :recordingId")
    suspend fun deleteTask(recordingId: String)

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE state = 'QUEUED' OR state = 'RETRYING'")
    fun observePendingCount(): Flow<Int>
}

@Dao
interface ManifestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(manifest: EvidenceManifestEntity)

    @Query("SELECT * FROM evidence_manifests WHERE recording_id = :recordingId LIMIT 1")
    suspend fun get(recordingId: String): EvidenceManifestEntity?

    @Query("SELECT * FROM evidence_manifests WHERE recording_id = :recordingId LIMIT 1")
    fun observe(recordingId: String): Flow<EvidenceManifestEntity?>
}

@Dao
interface SettingsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingsEntity)

    @Query("SELECT * FROM app_settings WHERE key = :key LIMIT 1")
    suspend fun get(key: String): AppSettingsEntity?

    @Query("SELECT value FROM app_settings WHERE key = :key LIMIT 1")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM app_settings WHERE key = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM app_settings")
    suspend fun getAll(): List<AppSettingsEntity>

    /** Upserts only keys the caller knows about; a typo cannot create a default. */
    suspend fun putAll(settings: List<AppSettingsEntity>) {
        settings.forEach { put(it) }
    }

    companion object {
        val VALID_KEYS: Set<String> = SettingsKeys.ALL
    }
}

@Dao
interface WakeWordConfigurationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: WakeWordConfigurationEntity)

    @Query("SELECT * FROM wake_word_configuration WHERE id = 1 LIMIT 1")
    suspend fun get(): WakeWordConfigurationEntity?

    @Query("SELECT * FROM wake_word_configuration WHERE id = 1 LIMIT 1")
    fun observe(): Flow<WakeWordConfigurationEntity?>
}

@Dao
interface TimelineDao {

    @Insert
    suspend fun append(event: TimelineEventEntity)

    @Query("SELECT * FROM activation_timeline WHERE recording_id = :recordingId ORDER BY elapsed_at ASC")
    suspend fun getForRecording(recordingId: String): List<TimelineEventEntity>

    @Query("SELECT * FROM activation_timeline WHERE recording_id = :recordingId ORDER BY elapsed_at ASC")
    fun observeForRecording(recordingId: String): Flow<List<TimelineEventEntity>>

    @Query("SELECT * FROM activation_timeline ORDER BY elapsed_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TimelineEventEntity>
}