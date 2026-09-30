package com.safesignal.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Key/value application settings (SPEC §85).
 *
 * Deliberately a table rather than DataStore or SharedPreferences: these values
 * are part of the evidence contract — the pre-buffer policy, the activation
 * threshold and the segment duration are all recorded alongside each recording —
 * and they must be readable in the same transactional snapshot as the recordings
 * they govern, and covered by backup exclusion as one unit.
 *
 * Rows are typed through [SettingsKeys] so a typo cannot silently create a
 * default-valued setting.
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * The persisted wake-word configuration (SPEC §85).
 *
 * Separate from [AppSettingsEntity] because it is the one settings record that
 * participates in evidence integrity: `phraseId` is copied onto every recording
 * so a recording can be traced back to the configuration that produced it.
 */
@Entity(tableName = "wake_word_configuration")
data class WakeWordConfigurationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLETON_ID,

    @ColumnInfo(name = "phrase_id")
    val phraseId: String,

    @ColumnInfo(name = "phrase_text")
    val phraseText: String,

    @ColumnInfo(name = "confidence_threshold")
    val confidenceThreshold: Float,

    @ColumnInfo(name = "cooldown_millis")
    val cooldownMillis: Long,

    @ColumnInfo(name = "pre_buffer_seconds")
    val preBufferSeconds: Int,

    @ColumnInfo(name = "engine_name")
    val engineName: String,

    @ColumnInfo(name = "engine_version")
    val engineVersion: String,

    /** False until the user records the enrolment phrase. */
    @ColumnInfo(name = "enrolled")
    val enrolled: Boolean,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** Canonical setting keys. Using constants prevents silent typo-created settings. */
object SettingsKeys {
    const val EMERGENCY_LISTENING_ENABLED = "emergency_listening_enabled"
    const val ONBOARDING_COMPLETED = "onboarding_completed"
    const val SEGMENT_DURATION_SECONDS = "segment_duration_seconds"
    const val MAX_DURATION_SECONDS = "max_duration_seconds"
    const val AUDIO_FORMAT = "audio_format"
    const val SAMPLE_RATE_HZ = "sample_rate_hz"
    const val RETENTION_POLICY = "retention_policy"
    const val SYNC_ENABLED = "sync_enabled"
    const val SYNC_WIFI_ONLY = "sync_wifi_only"
    const val TEST_AUTO_DELETE = "test_auto_delete"
    const val SCREENSHOT_PROTECTION = "screenshot_protection"

    val ALL: Set<String> = setOf(
        EMERGENCY_LISTENING_ENABLED,
        ONBOARDING_COMPLETED,
        SEGMENT_DURATION_SECONDS,
        MAX_DURATION_SECONDS,
        AUDIO_FORMAT,
        SAMPLE_RATE_HZ,
        RETENTION_POLICY,
        SYNC_ENABLED,
        SYNC_WIFI_ONLY,
        TEST_AUTO_DELETE,
        SCREENSHOT_PROTECTION,
    )
}