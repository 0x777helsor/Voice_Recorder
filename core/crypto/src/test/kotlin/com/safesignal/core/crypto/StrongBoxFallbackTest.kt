package com.safesignal.core.crypto

import android.security.keystore.StrongBoxUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Guards the StrongBox fallback that a real device proved necessary.
 *
 * A Galaxy A51 with no usable StrongBox threw `StrongBoxUnavailableException` out
 * of `generateKey` and produced no key at all, so SafeSignal could not encrypt
 * anything. The old code wrapped the *builder* call in `runCatching`, which never
 * caught it. These tests pin the two properties that matter:
 *
 *  1. StrongBox is attempted first, and a device that can provide it still gets it.
 *  2. Only a genuine StrongBox failure is retried. Everything else propagates, so
 *     the provider fails closed instead of quietly handing back a weaker key.
 *
 * They run on the JVM against the mockable `android.jar`, which is why the
 * fallback lives in a plain function rather than inside the Keystore-touching
 * class.
 */
class StrongBoxFallbackTest {

    @Test
    fun `strongbox is requested first`() {
        val attempts = mutableListOf<Boolean>()

        val result = generateKeyWithStrongBoxFallback { useStrongBox ->
            attempts += useStrongBox
            "key"
        }

        assertEquals("a device with a usable secure element must get it", listOf(true), attempts)
        assertEquals("key", result)
    }

    @Test
    fun `an unavailable strongbox is retried without it`() {
        val attempts = mutableListOf<Boolean>()

        val result = generateKeyWithStrongBoxFallback { useStrongBox ->
            attempts += useStrongBox
            if (useStrongBox) throw StrongBoxUnavailableException("Failed to generate key")
            "software-backed key"
        }

        assertEquals(listOf(true, false), attempts)
        assertEquals("software-backed key", result)
    }

    @Test
    fun `any other failure propagates on the first attempt`() {
        val attempts = mutableListOf<Boolean>()
        val failure = IllegalStateException("keystore is broken")

        val thrown = runCatching {
            generateKeyWithStrongBoxFallback<Unit> { useStrongBox ->
                attempts += useStrongBox
                throw failure
            }
        }.exceptionOrNull()

        assertSame("the original failure must surface unchanged", failure, thrown)
        assertEquals("must not silently degrade to an unchecked attempt", listOf(true), attempts)
    }

    @Test
    fun `a non-strongbox failure on the fallback attempt still propagates`() {
        val attempts = mutableListOf<Boolean>()

        val thrown = runCatching {
            generateKeyWithStrongBoxFallback<Unit> { useStrongBox ->
                attempts += useStrongBox
                throw if (useStrongBox) {
                    StrongBoxUnavailableException("Failed to generate key")
                } else {
                    IllegalStateException("still broken")
                }
            }
        }.exceptionOrNull()

        assertEquals(listOf(true, false), attempts)
        assertEquals("still broken", thrown?.message)
        assertEquals(IllegalStateException::class.java, thrown?.javaClass)
    }

    // The correctness of the two distinct KeyGenParameterSpecs — an AES key with
    // GCM block mode and no padding, an EC key with neither — is NOT asserted
    // here. The mockable android.jar returns null from every getter, so a test
    // would compare against stubs and prove nothing. That property is verified
    // where it is real: the on-device readiness probe performs an actual
    // wrap/unwrap against the platform Keystore, and a malformed spec makes it
    // fail. Claiming JVM coverage for it would be the same false confidence that
    // hid the StrongBox bug in the first place.
}
