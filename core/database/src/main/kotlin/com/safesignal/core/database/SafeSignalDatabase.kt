package com.safesignal.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.safesignal.core.database.dao.ManifestDao
import com.safesignal.core.database.dao.RecordingDao
import com.safesignal.core.database.dao.SettingsDao
import com.safesignal.core.database.dao.TimelineDao
import com.safesignal.core.database.dao.UploadTaskDao
import com.safesignal.core.database.dao.WakeWordConfigurationDao
import com.safesignal.core.database.entity.AppSettingsEntity
import com.safesignal.core.database.entity.EvidenceManifestEntity
import com.safesignal.core.database.entity.RecordingEntity
import com.safesignal.core.database.entity.RecordingSegmentEntity
import com.safesignal.core.database.entity.TimelineEventEntity
import com.safesignal.core.database.entity.UploadTaskEntity
import com.safesignal.core.database.entity.WakeWordConfigurationEntity

/**
 * The SafeSignal database.
 *
 * ### What is never stored here (SPEC §85)
 *
 * No audio blobs, ever. A 60-minute recording is roughly 170 MB of PCM; putting
 * it in SQLite would mean database corruption could destroy evidence that exists
 * nowhere else, and would make backups enormous. The database holds **references
 * and cryptographic metadata only**; the bytes live in app-private files written
 * by `data:local`.
 *
 * ### Export schema
 *
 * `exportSchema = true`. Room writes the schema JSON to `core/database/schemas`
 * and an androidTest consumes it, so a migration that drops a column or changes a
 * type fails a test rather than silently losing evidence metadata in the field.
 */
@Database(
    entities = [
        RecordingEntity::class,
        RecordingSegmentEntity::class,
        UploadTaskEntity::class,
        EvidenceManifestEntity::class,
        AppSettingsEntity::class,
        WakeWordConfigurationEntity::class,
        TimelineEventEntity::class,
    ],
    version = SafeSignalDatabase.VERSION,
    exportSchema = true,
)
abstract class SafeSignalDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao

    abstract fun uploadTaskDao(): UploadTaskDao

    abstract fun manifestDao(): ManifestDao

    abstract fun settingsDao(): SettingsDao

    abstract fun wakeWordConfigurationDao(): WakeWordConfigurationDao

    abstract fun timelineDao(): TimelineDao

    companion object {
        const val VERSION = 1
        const val NAME = "safesignal.db"

        @Volatile
        private var instance: SafeSignalDatabase? = null

        fun get(context: Context): SafeSignalDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): SafeSignalDatabase =
            Room.databaseBuilder(context, SafeSignalDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                // No fallbackToDestructiveMigration(): SPEC §87 forbids silently
                // deleting potentially recoverable evidence. A failed migration
                // must surface as a recoverable error, not as an empty database.
                .build()

        /**
         * Migrations are declared explicitly and always accompanied by a test in
         * `androidTest` using `MigrationTestHelper` against the exported schema.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            // Migration 0 -> 1 is the initial schema, so no statements are needed
            // yet. The array exists now so that adding a migration is a one-line
            // change and the pattern is established from the first release.
        )
    }
}

/**
 * Creates a database with all tables, for instrumentation tests that need a
 * known-good starting point without the production singleton.
 */
internal fun createInMemoryDatabase(context: Context): SafeSignalDatabase =
    Room.inMemoryDatabaseBuilder(context, SafeSignalDatabase::class.java)
        .allowMainThreadQueries()
        .build()

/** Reference to SQLite types used in migrations, kept for greppability. */
internal typealias SqliteDatabase = SupportSQLiteDatabase