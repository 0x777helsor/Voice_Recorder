package com.safesignal.core.crypto

import com.safesignal.core.common.json.JsonValue
import com.safesignal.core.common.json.MiniJson
import com.safesignal.core.common.json.asArray
import com.safesignal.core.common.json.asObject
import com.safesignal.core.common.json.orNull
import com.safesignal.core.common.json.requiredInt
import com.safesignal.core.common.json.requiredLong
import com.safesignal.core.common.json.requiredString

/**
 * A segment entry inside a [RecordingManifest].
 *
 * Note what is *not* here: nothing about the audio content, nothing about the
 * user, nothing about location. A manifest is a structural record, and keeping it
 * content-free means it can be shown in a UI, exported in a support bundle, or
 * handed to a third party without a further privacy review.
 */
data class SegmentDigest(
    val sequenceNumber: Int,
    val segmentId: String,
    val sealedLengthBytes: Long,
    val plaintextLengthBytes: Long,
    val sha256: String,
)

/**
 * The integrity manifest for one finalized recording (SPEC §25, §26).
 *
 * The manifest is what makes a folder of encrypted blobs into an *evidence
 * package*: it states which segments exist, in which order, with which hashes,
 * and it is signed so that any later alteration is detectable.
 */
data class RecordingManifest(
    val recordingId: String,
    val manifestVersion: Int,
    val appVersion: String,
    val encryptionVersion: Int,
    val keyVersion: Int,
    val keyProvider: String,
    val audioFormat: String,
    val sampleRateHz: Int,
    val channels: Int,
    val segmentCount: Int,
    val totalSealedBytes: Long,
    val totalPlaintextBytes: Long,
    /** SHA-256 over the per-segment plaintexts, folded in sequence order. */
    val recordingSha256: String,
    val startedAtWallClockMillis: Long,
    val endedAtWallClockMillis: Long,
    val startedElapsedRealtimeMillis: Long,
    val endedElapsedRealtimeMillis: Long,
    val activationSource: String,
    val activationConfidence: Float?,
    val wakeWordEngineVersion: String?,
    val deviceTimezoneId: String,
    val segments: List<SegmentDigest>,
    val isTestRecording: Boolean,
) {
    /**
     * Canonical serialisation used for signing and hashing.
     *
     * Stability matters more than prettiness: a manifest must serialise to the
     * same bytes on any device and any app version, or signatures stop verifying
     * after an upgrade. Therefore:
     *
     *  * fields are emitted in a fixed order,
     *  * optional fields are emitted as explicit JSON `null` rather than omitted,
     *  * numbers are emitted without locale-dependent formatting,
     *  * segments are sorted by [SegmentDigest.sequenceNumber].
     *
     * Implemented with the project's own [MiniJson] rather than a general-purpose
     * serialiser because "stable bytes forever" is a requirement here, not a
     * preference: a library's field ordering or float formatting must never be
     * able to silently invalidate a signature.
     */
    fun toCanonicalJson(): String {
        val segmentsArray = JsonValue.Arr(
            segments.sortedBy { it.sequenceNumber }.map { segment ->
                jsonObject(
                    "sequenceNumber" to num(segment.sequenceNumber),
                    "segmentId" to JsonValue.Str(segment.segmentId),
                    "sealedLengthBytes" to num(segment.sealedLengthBytes),
                    "plaintextLengthBytes" to num(segment.plaintextLengthBytes),
                    "sha256" to JsonValue.Str(segment.sha256),
                )
            },
        )

        return MiniJson.write(
            jsonObject(
                "manifestVersion" to num(manifestVersion),
                "recordingId" to JsonValue.Str(recordingId),
                "appVersion" to JsonValue.Str(appVersion),
                "encryptionVersion" to num(encryptionVersion),
                "keyVersion" to num(keyVersion),
                "keyProvider" to JsonValue.Str(keyProvider),
                "audioFormat" to JsonValue.Str(audioFormat),
                "sampleRateHz" to num(sampleRateHz),
                "channels" to num(channels),
                "segmentCount" to num(segmentCount),
                "totalSealedBytes" to num(totalSealedBytes),
                "totalPlaintextBytes" to num(totalPlaintextBytes),
                "recordingSha256" to JsonValue.Str(recordingSha256),
                "startedAtWallClockMillis" to num(startedAtWallClockMillis),
                "endedAtWallClockMillis" to num(endedAtWallClockMillis),
                "startedElapsedRealtimeMillis" to num(startedElapsedRealtimeMillis),
                "endedElapsedRealtimeMillis" to num(endedElapsedRealtimeMillis),
                "activationSource" to JsonValue.Str(activationSource),
                "activationConfidence" to (
                    activationConfidence?.let { JsonValue.Num(it.toDouble(), it.toString()) } ?: JsonValue.Null
                    ),
                "wakeWordEngineVersion" to (
                    wakeWordEngineVersion?.let { JsonValue.Str(it) } ?: JsonValue.Null
                    ),
                "deviceTimezoneId" to JsonValue.Str(deviceTimezoneId),
                "isTestRecording" to JsonValue.Bool(isTestRecording),
                "segments" to segmentsArray,
            ),
        )
    }

    fun canonicalBytes(): ByteArray = toCanonicalJson().toByteArray(Charsets.UTF_8)

    /** SHA-256 of the canonical form; the value a manifest signature covers. */
    fun digest(): String = Digest.sha256Hex(canonicalBytes())

    /**
     * Parses a manifest back from its canonical JSON.
     *
     * Strict: a missing or malformed required field throws rather than yielding a
     * half-populated manifest, because a partially-parsed manifest could make a
     * corrupted package look intact.
     */
    companion object {
    fun fromJson(text: String): RecordingManifest {
              val root = MiniJson.parse(text).asObject()
          val segments = root.fields["segments"]
              ?.asArray()
              ?.items
              ?.map { item ->
                  val o = item.asObject()
                  SegmentDigest(
                      sequenceNumber = o.requiredInt("sequenceNumber"),
                      segmentId = o.requiredString("segmentId"),
                      sealedLengthBytes = o.requiredLong("sealedLengthBytes"),
                      plaintextLengthBytes = o.requiredLong("plaintextLengthBytes"),
                      sha256 = o.requiredString("sha256"),
                  )
              }
              .orEmpty()

          return RecordingManifest(
              recordingId = root.requiredString("recordingId"),
              manifestVersion = root.requiredInt("manifestVersion"),
              appVersion = root.requiredString("appVersion"),
              encryptionVersion = root.requiredInt("encryptionVersion"),
              keyVersion = root.requiredInt("keyVersion"),
              keyProvider = root.requiredString("keyProvider"),
              audioFormat = root.requiredString("audioFormat"),
              sampleRateHz = root.requiredInt("sampleRateHz"),
              channels = root.requiredInt("channels"),
              segmentCount = root.requiredInt("segmentCount"),
              totalSealedBytes = root.requiredLong("totalSealedBytes"),
              totalPlaintextBytes = root.requiredLong("totalPlaintextBytes"),
              recordingSha256 = root.requiredString("recordingSha256"),
              startedAtWallClockMillis = root.requiredLong("startedAtWallClockMillis"),
              endedAtWallClockMillis = root.requiredLong("endedAtWallClockMillis"),
              startedElapsedRealtimeMillis = root.requiredLong("startedElapsedRealtimeMillis"),
              endedElapsedRealtimeMillis = root.requiredLong("endedElapsedRealtimeMillis"),
              activationSource = root.requiredString("activationSource"),
              activationConfidence = (root.fields["activationConfidence"].orNull() as? JsonValue.Num)?.value?.toFloat(),
              wakeWordEngineVersion = (root.fields["wakeWordEngineVersion"].orNull() as? JsonValue.Str)?.value,
              deviceTimezoneId = root.requiredString("deviceTimezoneId"),
              segments = segments,
              isTestRecording = (root.fields["isTestRecording"] as? JsonValue.Bool)?.value ?: false,
          )
      }

          fun num(value: Long): JsonValue = JsonValue.Num(value.toDouble(), value.toString())
          fun num(value: Int): JsonValue = JsonValue.Num(value.toDouble(), value.toString())

          fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj =
              JsonValue.Obj(linkedMapOf(*pairs))
      }
  }

  /**
   * A manifest plus its detached signature.
   *
   * The signature is *detached* so the manifest stays readable by anyone: the
   * exported JSON is human-readable, and verification is a separate step.
   */
  data class SignedManifest(
      val manifest: RecordingManifest,
      /** Base64 signature over [RecordingManifest.canonicalBytes]. */
      val signatureBase64: String,
      /** Alias of the Keystore key that signed, for the verifier to locate the key. */
      val signingKeyAlias: String,
      /** Base64 of the verification public key, published so third parties can check. */
      val verificationKeyBase64: String,
  )