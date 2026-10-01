package com.safesignal.audio.capture

import com.safesignal.core.crypto.SegmentDigest
import com.safesignal.data.local.sealing.RecordingToSeal

/**
 * Crosses from a live capture into the storage layer's vocabulary.
 *
 * The conversion lives here rather than in `:data:local` because the dependency
 * runs capture → storage, never the reverse. `:data:local` deliberately does not
 * know what a `FinalizedRecording` is, so this is the one place the two vocabularies
 * meet. That also means a change to the capture's internal shape touches exactly
 * one function instead of the evidence store.
 */
fun FinalizedRecording.toRecordingToSeal(): RecordingToSeal = RecordingToSeal(
    recordingId = recordingId,
    // The engine's own digests, taken at the moment each segment was sealed while
    // the plaintext key still existed. Never recomputed: the key is zeroed by now,
    // and the manifest field is defined to hold the *plaintext* digest.
    segments = segments.sortedBy { it.sequenceNumber }.map { segment ->
        SegmentDigest(
            sequenceNumber = segment.sequenceNumber,
            segmentId = segment.segmentId,
            sealedLengthBytes = segment.sealedLengthBytes,
            plaintextLengthBytes = segment.plaintextLengthBytes,
            sha256 = segment.sha256,
        )
    },
    audioFormat = config.format.name,
    sampleRateHz = config.sampleRateHz,
    channels = config.channels,
    startedAtWallClockMillis = startedAtWallClockMillis,
    endedAtWallClockMillis = endedAtWallClockMillis,
    startedElapsedRealtimeMillis = startedAtElapsed.elapsedRealtimeMs,
    endedElapsedRealtimeMillis = endedAtElapsed.elapsedRealtimeMs,
    activationSource = activationSource,
    activationConfidence = activationConfidence,
    isTestRecording = isTestRecording,
    durationMillis = durationMillis,
    totalSealedBytes = totalSealedBytes,
    fullSegmentBytes = config.segmentBytes(),
    // Captured from the key material before it was zeroed. Without it the audio is
    // unrecoverable, and the `wrapped_key` columns would go on holding null.
    wrappedKey = wrappedKey,
)
