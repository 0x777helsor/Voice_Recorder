package com.safesignal.service


import com.safesignal.core.common.log.NoOpLogSink
import com.safesignal.core.common.log.RedactingLogger
import com.safesignal.data.local.sealing.EvidenceStore
import com.safesignal.core.crypto.RecordingKeyManager
import com.safesignal.core.crypto.testing.InMemoryKeyProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The seal-and-store step at the end of a capture.
 *
 * ### The bug this file exists for
 *
 * `RecordingEntity` has carried `wrapped_key` and `wrapped_key_iv` columns since the
 * schema was first written, and **nothing ever wrote them**. The plaintext data key
 * was zeroed the moment the last segment was sealed, and the wrapped blob — the only
 * remaining route back to it — was discarded. Every recording the app had ever
 * produced was encrypted with a key that was then thrown away, which makes the audio
 * permanently unopenable.
 *
 * Nothing caught it because nothing had ever tried to *open* a recording. The
 * capture pipeline, the encryption and the manifest all looked fine, and they were
 * fine; the key was simply not being kept.
 *
 * So the assertion that matters is the narrow one: the capture handed to the store
 * carries a usable wrapped key. Everything else here supports it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceArchivingTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyProvider = InMemoryKeyProvider()
    private val keyManager = RecordingKeyManager(keyProvider)
    private val logger = RedactingLogger(tag = "test", sink = NoOpLogSink)

    @Test
    fun `a finalized capture carries the wrapped data key`() = runTest {
        val store = RecordingEvidenceStore()
        val controller = controller(store)

        controller.startTest(TESTING_CONFIG)
        // The fake must suspend, or `AudioFramePump`'s busy loop hangs the
        // unconfined test dispatcher instead of failing it.
        advanceUntilIdle()
        controller.stop()

        val request = store.archived.single()
        assertTrue(
            "the wrapped key must not be empty; without it the audio is unrecoverable",
            request.capture.wrappedKey.wrappedKeyBytes.isNotEmpty(),
        )
        assertTrue("the capture must have sealed something", request.capture.segments.isNotEmpty())
        // AES-GCM: a 32-byte key wraps to 48 bytes of ciphertext — 32 plus the
        // 16-byte authentication tag. Asserting 32 would have passed against a key
        // that was never actually encrypted.
        assertEquals(48, request.capture.wrappedKey.wrappedKeyBytes.size)
        assertTrue(request.capture.wrappedKey.iv.isNotEmpty())
    }

    @Test
    fun `the wrapped key is different for each recording`() = runTest {
        // One key per recording, not a shared one. A shared key would mean one
        // compromised blob exposes every recording the device has ever made.
        val store = RecordingEvidenceStore()
        val controller = controller(store)

        controller.startTest(TESTING_CONFIG)
        advanceUntilIdle()
        controller.stop()
        controller.startTest(TESTING_CONFIG)
        advanceUntilIdle()
        controller.stop()

        val keys = store.archived.map { it.capture.wrappedKey.wrappedKeyBytes }
        assertEquals(2, keys.size)
        assertTrue("each recording must get its own data key", !keys[0].contentEquals(keys[1]))
    }

    @Test
    fun `a test capture is labelled as a test all the way into the store`() = runTest {
        // The label has to survive to the store, not just the engine. A test run
        // archived as real evidence is the confusion the flag exists to prevent.
        val store = RecordingEvidenceStore()
        val controller = controller(store)

        controller.startTest(TESTING_CONFIG)
        advanceUntilIdle()
        controller.stop()

        val request = store.archived.single()
        assertTrue(request.capture.isTestRecording)
        assertEquals("TEST", request.capture.activationSource)
    }

    @Test
    fun `the archived segment digests match what the engine sealed`() = runTest {
        val store = RecordingEvidenceStore()
        val controller = controller(store)

        controller.startTest(TESTING_CONFIG)
        advanceUntilIdle()
        val stopped = controller.stop().getOrThrow()

        val request = store.archived.single()
        assertEquals(
            stopped.segments.map { it.sha256 }.sorted(),
            request.capture.segments.map { it.sha256 }.sorted(),
        )
        assertEquals(stopped.totalSealedBytes, request.capture.totalSealedBytes)
    }

    @Test
    fun `a store failure never fails the capture`() = runTest {
        // The evidence is sealed on disk before the index is written, so a database
        // problem is a degraded index and not lost audio. Reporting the stop as
        // failed would tell the user their recording was gone when it is intact.
        val controller = controller(FailingEvidenceStore)

        controller.startTest(TESTING_CONFIG)
        advanceUntilIdle()
        val result = controller.stop()

        assertTrue("the capture must still report success: ${result.exceptionOrNull()}", result.isSuccess)
        // 60 s at a 5 s segment length. Asserting a smaller number would pass for the
        // wrong reason — a capture that had stopped early.
        assertEquals(12, result.getOrThrow().segments.size)
    }

    private fun kotlinx.coroutines.test.TestScope.controller(store: EvidenceStore) =
        EmergencyRecordingController(
            audioSourceFactory = ToneAudioSourceFactory(),
            evidenceRoot = File(temporaryFolder.root, "evidence-${counter++}").apply { mkdirs() },
            keyManager = keyManager,
            // Virtual time, so the engine's duration cap actually fires.
            timeProvider = SchedulerTimeProvider(testScheduler),
            dispatchers = StandardTestDispatchers(testScheduler),
            logger = logger,
            evidenceStore = store,
        )

    private companion object {
        var counter = 0

        /**
         * The controller's own TEST_CONFIG, with a duration that suits a unit test.
         *
         * `maxDurationSeconds` is only validated down to 60, which at 48 kHz mono is
         * ~5.7 MB and 1200 pump iterations. Shortened here so the test is quick —
         * the cap has to be reachable, which is the whole point of driving virtual
         * time rather than a frozen clock.
         */
        val TESTING_CONFIG = com.safesignal.audio.capture.RecordingConfig(
            segmentDurationSeconds = 5,
            maxDurationSeconds = 60,
            preBufferSeconds = 0,
        )
    }
}
