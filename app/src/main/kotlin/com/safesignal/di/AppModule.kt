package com.safesignal.di

import android.content.Context
import com.safesignal.core.common.concurrent.DefaultDispatcherProvider
import com.safesignal.core.common.concurrent.DispatcherProvider
import com.safesignal.core.common.permission.AndroidPermissionChecker
import com.safesignal.core.common.time.SystemTimeProvider
import com.safesignal.core.common.time.TimeProvider
import com.safesignal.core.crypto.AndroidKeystoreKeyProvider
import com.safesignal.core.crypto.KeyProvider
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.crypto.RecordingKeyManager
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