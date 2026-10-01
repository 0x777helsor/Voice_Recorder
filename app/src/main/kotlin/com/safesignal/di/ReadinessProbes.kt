package com.safesignal.di

import android.util.Log
import com.safesignal.core.common.readiness.Capability
import com.safesignal.core.common.readiness.CapabilityStatus
import com.safesignal.core.common.readiness.ProbeOutcome
import com.safesignal.core.common.readiness.storageOutcome
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.crypto.RecordingKeyManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real readiness probes (SPEC §32).
 *
 * Every probe here performs the actual operation rather than inferring it from
 * configuration. `Encryption` creates and wraps a real key; `Storage` stats the
 * real evidence directory. The previous readiness screen hardcoded both as ready,
 * which is how it came to show a user two green ticks that nothing had verified.
 *
 * Failures are returned as [ProbeOutcome.NotReady] rather than thrown: a device
 * with a broken keystore should read "not ready", not crash the app at launch.
 */
@Singleton
class ReadinessProbes @Inject constructor(
    private val keyManager: RecordingKeyManager,
    private val manifestSigner: ManifestSigner,
    private val evidenceRoot: File,
) {

    /**
     * Verifies that a recording key can be produced, wrapped and unwrapped, and
     * that a signing key can be created.
     *
     * This is the cheapest honest test of "can this device actually protect
     * evidence". It creates the device keys if absent — they are required for
     * the app to function anyway — and clears the probe key immediately.
     */
    fun encryption(): ProbeOutcome = try {
        val probeId = "readiness-probe"
        val material = keyManager.createRecordingKeyMaterial(probeId)

        // Wrap, then unwrap, then compare. A keystore that can create a key but
        // cannot unwrap it would produce recordings nobody can ever read back.
        val unwrapped = keyManager.unwrapRecordingKey(material)
        val roundTripped = unwrapped.contentEquals(material.dataKey)

        unwrapped.fill(0)
        material.clear()

        // Forces creation of the EC signing key, which manifest sealing needs.
        manifestSigner.verificationKey()

        if (roundTripped) {
            ProbeOutcome.Ready
        } else {
            ProbeOutcome.NotReady("key wrap/unwrap round trip did not match").also { log(it) }
        }
    } catch (t: Throwable) {
        // Deliberately broad. Any failure here means evidence cannot be protected
        // on this device, which is exactly what this row must report.
        ProbeOutcome.NotReady("encryption probe failed: ${t.javaClass.simpleName}: ${t.message}").also { log(it) }
    }

    /** Verifies the evidence directory exists, is writable, and has room. */
    fun storage(): ProbeOutcome = try {
        if (!evidenceRoot.exists()) evidenceRoot.mkdirs()
        storageOutcome(
            exists = evidenceRoot.exists(),
            canWrite = evidenceRoot.canWrite(),
            freeBytes = evidenceRoot.usableSpace,
            minimumFreeBytes = MINIMUM_FREE_BYTES,
        )
    } catch (t: Throwable) {
        ProbeOutcome.NotReady("storage probe failed: ${t.javaClass.simpleName}: ${t.message}").also { log(it) }
    }

    /**
     * Probe failures are logged, not just returned.
     *
     * A silently-swallowed keystore failure is indistinguishable from a working
     * app to anyone triaging a support report, and the whole reason this probe
     * exists is to make that failure visible.
     */
    private fun log(outcome: ProbeOutcome) {
        val reason = (outcome as? ProbeOutcome.NotReady)?.reason ?: return
        Log.w(TAG, reason)
    }

    /**
     * The foreground service does not exist yet, so this reports not-ready.
     *
     * It is written as a real check against the manifest rather than a constant,
     * so that when `:service` lands the row flips to ready on its own instead of
     * needing someone to remember to change a `true`.
     */
    fun foregroundService(declared: Boolean): ProbeOutcome =
        if (declared) ProbeOutcome.Ready else ProbeOutcome.NotReady("no foreground service declared")

    /** The wake-word detector is not wired into the capture loop yet. */
    fun wakeWord(detectorAvailable: Boolean): ProbeOutcome =
        if (detectorAvailable) ProbeOutcome.Ready else ProbeOutcome.NotReady("wake-word detector not wired")

    companion object {
        private const val TAG = "SafeSignalReadiness"

        /**
         * Matches `RecordingConfig.minFreeStorageBytes`'s default (SPEC §30).
         * Below this, capture finalizes itself mid-incident.
         */
        const val MINIMUM_FREE_BYTES: Long = 64L * 1024 * 1024

        /** Convenience for building a report from these probes. */
        fun status(capability: Capability, outcome: ProbeOutcome): CapabilityStatus =
            CapabilityStatus(capability, outcome)
    }
}
