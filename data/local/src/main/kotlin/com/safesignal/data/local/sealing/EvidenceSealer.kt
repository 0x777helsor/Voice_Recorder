package com.safesignal.data.local.sealing

import com.safesignal.core.crypto.EvidenceIntegrity
import com.safesignal.core.crypto.ManifestSigner
import com.safesignal.core.crypto.SignedManifest

/**
 * Turns a finished capture into a signed, verifiable claim about what it contains.
 *
 * ### What sealing actually is
 *
 * The segments are already on disk, encrypted and individually hashed. What is
 * missing is the thing that ties them together. Without a manifest, a set of files
 * says nothing about which belong together, what order they are in, or whether one
 * has been removed — an attacker with write access to the evidence directory can
 * delete segment 3 of 5 and nothing on disk will notice.
 *
 * The manifest closes that gap twice over:
 *
 *  * **Per-segment SHA-256**, folded in sequence order with the sequence number
 *    hashed alongside each digest. Reordering, removal and duplication are all
 *    detectable with no key material, by anyone holding only an export package.
 *  * **A detached ECDSA signature** over the canonical bytes, so a manifest
 *    rewritten to match substituted audio does not verify. Asymmetric, so
 *    verification needs only the public key, which ships inside the package — a
 *    lawyer or a court officer can check it without a secret and without calling us.
 *
 * ### What sealing deliberately does not claim
 *
 * It does not claim the audio is authentic, nor that it is admissible. A signature
 * shows a manifest has not changed since a device holding a particular key produced
 * it. Whether that satisfies a court depends on jurisdiction and case law this
 * software cannot answer. See LEGAL_DISCLAIMER.md.
 */
class EvidenceSealer(
    private val signer: ManifestSigner,
) {

    /**
     * Builds and signs the manifest for a finished recording.
     *
     * @param appVersion recorded so a verifier knows what produced the evidence.
     * @param deviceTimezoneId recorded alongside the wall-clock times, because a
     *   timestamp without its zone is ambiguous and this is evidence.
     * @param wakeWordEngineVersion null while the detector is not wired; recorded
     *   explicitly rather than omitted, so "no wake word" stays distinguishable
     *   from "this build did not say".
     */
    fun seal(
        capture: RecordingToSeal,
        appVersion: String,
        deviceTimezoneId: String,
        wakeWordEngineVersion: String?,
    ): SignedManifest = signer.sign(
        EvidenceIntegrity.buildManifest(
            recordingId = capture.recordingId,
            appVersion = appVersion,
            audioFormat = capture.audioFormat,
            sampleRateHz = capture.sampleRateHz,
            channels = capture.channels,
            segments = capture.segments,
            keyVersion = capture.wrappedKey.keyVersion,
            keyProvider = capture.wrappedKey.provider,
            startedAtWallClockMillis = capture.startedAtWallClockMillis,
            endedAtWallClockMillis = capture.endedAtWallClockMillis,
            startedElapsedRealtimeMillis = capture.startedElapsedRealtimeMillis,
            endedElapsedRealtimeMillis = capture.endedElapsedRealtimeMillis,
            activationSource = capture.activationSource,
            activationConfidence = capture.activationConfidence,
            wakeWordEngineVersion = wakeWordEngineVersion,
            deviceTimezoneId = deviceTimezoneId,
            isTestRecording = capture.isTestRecording,
        ),
    )
}
