package com.safesignal.service

import android.util.Log
import com.safesignal.audio.capture.AudioSourceFactory
import com.safesignal.audio.capture.DefaultAudioSourceFactory
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.LogRecord
import com.safesignal.core.common.log.LogSink
import com.safesignal.core.common.log.RedactingLogger
import com.safesignal.core.common.log.SafeLogger
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
 * `LogRecord` does not carry a level, because `SafeLogger` filters by level before
 * delegating. Everything therefore lands at `Log.i`, with failures at `Log.w`. That
 * is a real limitation: a caller cannot currently distinguish an informational
 * record from an error in logcat, only by message. Fixing it means adding the level
 * to `LogRecord`, which touches the core logging contract, so it is recorded in
 * KNOWN_LIMITATIONS.md rather than smuggled in here.
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

        if (record.throwable != null) {
            Log.w(record.tag, line, record.throwable)
        } else {
            Log.i(record.tag, line)
        }
    }
}