package com.safesignal.service

import android.util.Log
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.DefaultAudioSourceFactory
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.LogRecord
import com.safesignal.core.common.log.LogSink
import com.safesignal.core.common.log.RedactingLogger
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.log.Severity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bindings the recording stack needs and nothing else provides.
 *
 * Both bindings live here rather than in `:app` so that `:service` is usable —
 * and testable — on its own. An Android library that depends on the application
 * module for its own dependencies is not a library, it is a fragment.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    /**
     * The production logger.
     *
     * Bound to the [LogcatLogSink] through [RedactingLogger], which means every
     * record passes through `Redactor` before it reaches logcat: field names that
     * look secret are dropped, and high-entropy values are truncated. The recorder
     * logs while handling audio and key material, so this is the layer that keeps a
     * wrapped key or a recording id out of a system log any app can read.
     */
    @Provides
    @Singleton
    fun provideSafeLogger(): SafeLogger = RedactingLogger(tag = TAG, sink = LogcatLogSink())

    /**
     * The platform audio source.
     *
     * Behind a factory so the coordinator can create a fresh source per recording
     * rather than sharing one. Sharing an `AudioRecord` across sessions would mean
     * a failed stop left the next recording attached to a half-open device.
     */
    @Provides
    @Singleton
    fun provideAudioSourceFactory(dispatchers: DispatcherProvider): AudioSourceFactory =
        DefaultAudioSourceFactory(dispatchers)

    private const val TAG = "SafeSignal"
}

/**
 * Writes [LogRecord]s to logcat.
 *
 * ### Why logcat at all, given the privacy position
 *
 * Records are already scrubbed by `RedactingLogger` before they arrive here, and
 * nothing this sink adds is unrecoverable from the record itself: the tag is fixed
 * and the fields are redacted. Logcat is the right sink for a field-support app
 * because a user reporting "it did not record" needs *something* on the device
 * that can be read without a debug build attached, and the alternative — writing
 * evidence into a file the app must then protect with the very key material under
 * investigation — is worse.
 *
 * ### Severity
 *
 * The severity is mapped straight through, which required `LogRecord` to carry one.
 * It previously did not, and this sink guessed from the presence of a throwable;
 * see the note below.
 */
/**
 * Writes [LogRecord]s to logcat at the priority the record actually carries.
 *
 * The severity is mapped straight through. An earlier version of this sink could not,
 * because `LogRecord` had no severity field, and fell back to "INFO, or WARN if a
 * throwable happened to be attached". That mislabelled deliberate `logger.e(...)`
 * calls with no exception as `Log.i` — an error filed as information, which is
 * invisible to anyone reading logcat by priority. It is worth recording that the fix
 * was made in the core logging contract rather than patched in here, because a sink
 * that guesses at severity cannot be relied on to report errors correctly.
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
            // WARN and ERROR differ in priority, not just in the throwable. Errors
            // that carry a stack trace go to `Log.e` so the trace is preserved.
            Severity.WARN -> if (record.throwable != null) Log.w(record.tag, line, record.throwable) else Log.w(record.tag, line)
            Severity.ERROR -> if (record.throwable != null) Log.e(record.tag, line, record.throwable) else Log.e(record.tag, line)
        }
    }
}