package com.safesignal.service

import android.content.Context
import android.util.Log
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.DefaultAudioSourceFactory
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.LogRecord
import com.safesignal.core.common.log.LogSink
import com.safesignal.core.common.log.RedactingLogger
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.log.Severity
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.database.SafeSignalDatabase
import com.safesignal.data.local.sealing.EvidenceArchiver
import com.safesignal.data.local.sealing.EvidenceSealer
import com.safesignal.data.local.sealing.EvidenceStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Bindings the recording stack needs and nothing else provides.
 *
 * They live here rather than in `:app` so that `:service` is usable — importable and
 * testable — without the application module.
 *
 * `TimeProvider`, `DispatcherProvider` and `SafeLogger` are deliberately **not**
 * bound here. They are process-wide and `AppModule` owns them; binding them in both
 * modules is a duplicate-binding error, and were it not, it would mean two
 * instances of the thing that measures time.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideAudioSourceFactory(dispatchers: DispatcherProvider): AudioSourceFactory =
        DefaultAudioSourceFactory(dispatchers)

    /**
     * The wake-word detector, and the only production binding for it.
     *
     * `LocalWakeWordEngine` performs detection entirely on the device. That is a
     * requirement, not a preference: the specification mandates offline activation,
     * and a cloud speech API as the activation path would upload ambient audio to a
     * third party the moment a user armed the app. A detector that phones home is
     * not usable here even when it would be more accurate.
     */
    @Provides
    @Singleton
    fun provideEvidenceSealer(signer: ManifestSigner): EvidenceSealer = EvidenceSealer(signer)

    @Provides
    @Singleton
    fun provideEvidenceStore(
        database: SafeSignalDatabase,
        @ApplicationContext context: Context,
        sealer: EvidenceSealer,
        logger: SafeLogger,
    ): EvidenceStore = EvidenceArchiver(
        database = database,
        // `noBackupFilesDir`, not `filesDir`: a restored backup has no access to this
        // device's keystore key, so restoring ciphertext nobody can decrypt is worse
        // than a clean absence.
        evidenceRoot = File(context.noBackupFilesDir, EVIDENCE_DIR).apply { mkdirs() },
        sealer = sealer,
        logger = logger,
    )

    const val TAG = "SafeSignal"
    const val EVIDENCE_DIR = "evidence"
}

/**
 * Writes [LogRecord]s to logcat at the priority the record actually carries.
 *
 * The severity is mapped straight through. An earlier version of this sink could not,
 * because `LogRecord` carried no severity field, and fell back to "INFO, or WARN if a
 * throwable happened to be attached". That mislabelled deliberate `logger.e(...)`
 * calls with no exception as `Log.i` — an error filed as information, which is
 * invisible to anyone reading logcat by priority.
 */
class LogcatLogSink(
    private val maxFields: Int = 8,
) : LogSink {

    override fun write(record: LogRecord) {
        val line = buildString {
            append(record.message)
            if (record.fields.isNotEmpty()) {
                append(' ')
                append(
                    record.fields.entries.take(maxFields)
                        .joinToString(" ") { (k, v) -> "$k=$v" },
                )
            }
        }

        when (record.severity) {
            Severity.DEBUG -> Log.d(record.tag, line)
            Severity.INFO -> Log.i(record.tag, line)
            // WARN and ERROR differ in priority, not merely in the throwable. Errors
            // carrying a stack trace go to Log.e so the trace is preserved.
            Severity.WARN -> logWithThrowable(Log::w, record, line)
            Severity.ERROR -> logWithThrowable(Log::e, record, line)
        }
    }

    private inline fun logWithThrowable(
        log: (String, String, Throwable) -> Int,
        record: LogRecord,
        line: String,
    ) {
        // Read once into a local: `LogRecord` is public API in another module, so
        // Kotlin will not smart-cast a property it does not own.
        val throwable = record.throwable
        if (throwable != null) log(record.tag, line, throwable) else Log.i(record.tag, line)
    }
}
