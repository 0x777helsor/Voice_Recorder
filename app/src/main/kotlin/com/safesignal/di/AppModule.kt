package com.safesignal.di

import android.content.Context
import com.safesignal.core.common.concurrent.DefaultDispatcherProvider
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.log.LogRecord
import com.safesignal.core.common.log.LogSink
import com.safesignal.core.common.log.RedactingLogger
import com.safesignal.core.common.log.SafeLogger
import com.safesignal.core.common.log.Severity
import com.safesignal.core.common.permission.AndroidPermissionChecker
import com.safesignal.service.LogcatLogSink
import com.safesignal.core.common.time.SystemTimeProvider
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.core.crypto.AndroidKeystoreKeyProvider
import com.safesignal.core.crypto.KeyProvider
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.core.database.SafeSignalDatabase
import com.safesignal.audio.wakeword.LocalWakeWordEngine
import com.safesignal.audio.wakeword.WakeWordEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * The application graph.
 *
 * Two rules are enforced by how this file is written:
 *
 *  1. **The production [KeyProvider] is bound here and nowhere else.** The
 *     test double lives in `core:crypto/testing` and is never referenced from
 *     `main`, so a software-backed key cannot silently reach a release build.
 *  2. **Evidence lives in app-private internal storage.** `noBackupFilesDir` is
 *     used rather than `filesDir` because a restored backup has no access to
 *     this device's keystore key; restoring ciphertext the user can never decrypt
 *     would be worse than a clean absence.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()

    @Provides
    @Singleton
    fun provideKeyProvider(): KeyProvider = AndroidKeystoreKeyProvider()

    @Provides
    @Singleton
    fun provideRecordingKeyManager(keyProvider: KeyProvider): RecordingKeyManager =
        RecordingKeyManager(keyProvider)

    @Provides
    @Singleton
    fun provideManifestSigner(keyProvider: KeyProvider): ManifestSigner =
        ManifestSigner(keyProvider)

    /**
     * The wake-word detector, and the only production binding for it.
     *
     * `LocalWakeWordEngine` performs detection entirely on the device. That is a
     * requirement, not a preference: the specification mandates offline activation,
     * and a cloud speech API as the activation path would upload ambient audio to a
     * third party the moment a user armed the app. A detector that phones home is
     * not usable here even when it would be more accurate.
     */
    /**
     * The production logger.
     *
     * Every line passes through [RedactingLogger] first, so a careless call site
     * cannot leak a token, a key or a recording id into logcat. Logcat is the sink
     * because a user reporting "it did not record" needs something readable on the
     * device without a debug build attached.
     */
    @Provides
    @Singleton
    fun provideSafeLogger(): SafeLogger = RedactingLogger(tag = "SafeSignal", sink = LogcatLogSink())

    @Provides
    @Singleton
    fun provideWakeWordEngine(
        timeProvider: TimeProvider,
        dispatchers: DispatcherProvider,
    ): WakeWordEngine = LocalWakeWordEngine(timeProvider, dispatchers)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafeSignalDatabase =
        SafeSignalDatabase.get(context)

    @Provides
    @Singleton
    fun provideEvidenceRoot(@ApplicationContext context: Context): File =
        File(context.noBackupFilesDir, EVIDENCE_DIR).apply { mkdirs() }

    /**
     * The permission checker is constructed with the application context.
     *
     * It only reads permission state, which is process-wide, so a long-lived
     * instance cannot go stale in a way that matters — and the Activity is
     * supplied per call for the rationale check, which is genuinely
     * Activity-scoped.
     */
    @Provides
    @Singleton
    fun providePermissionChecker(@ApplicationContext context: Context): AndroidPermissionChecker =
        AndroidPermissionChecker(context)

    private const val EVIDENCE_DIR = "evidence"
}