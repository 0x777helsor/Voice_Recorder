# Known Limitations

This document exists because the specification requires that unresolved problems
be reported rather than hidden. It is written to be read by someone deciding
whether to rely on SafeSignal, and by whoever picks this up next.

---

## 1. Android 14+ is entirely unverified

**Status: blocking for any real-world claim.**

This section used to say nothing had ever run on hardware. That stopped being true,
and the correction matters: **three real bugs** were found by running on phones that
no unit test could reach, because every unit test uses a fake `KeyProvider` or a fake
`AudioSource`. A passing suite here means very little about the platform.

What has now been verified on two physical phones — a Galaxy A51 (API 33, Exynos) and
a Tecno BF7 (API 31, MediaTek):

- microphone capture at 48 kHz mono, producing correctly sized sealed segments;
- AES-256-GCM segment encryption, with the expected 80-byte per-file overhead;
- Keystore key generation, with and without StrongBox present;
- foreground service start, notification visibility, and microphone release on stop.

What has **not** been verified, and remains the largest gap in the project:

- **any Android 14, 15 or 16 device.** `targetSdk` is 36; the available hardware is
  API 33 and API 31, and the emulator image is API 34. Android 14 made
  `foregroundServiceType` mandatory and introduced
  `ForegroundServiceStartNotAllowedException` for microphone services started from
  the background. SafeSignal's *entire* recording model depends on starting a
  microphone foreground service, so this is precisely the behaviour that most needs
  checking and it has not been checked on real hardware.
- that the foreground service survives screen-off, lock screen, and process death
  the way the design assumes;
- that a phone call, another app's microphone use, or a Bluetooth route change is
  detected correctly;
- that microphone contention produces the errors the code expects;
- battery consumption, thermal behaviour, or wake-word detection rates.

### 1.1 Six bugs found on hardware, none of which a unit test could reach

Recorded here because the ratio is the point: every one of these needed a real
phone, and all of them passed 100% of the test suite.

| Bug | Symptom | Why tests missed it |
| --- | --- | --- |
| `setIsStrongBoxBacked` treated as a hint | **No key created at all** on either test phone; encryption impossible | Every test uses a fake `KeyProvider` |
| `PrivateKey.getEncoded()` | Threw NPE; would have published an **empty** verification key in every evidence package | Same |
| EC signing key built with cipher parameters | Invalid for sign/verify | Same |
| `AudioRecord.read(ByteBuffer, …)` | `ERROR_BAD_VALUE` on every frame; **recorded nothing** | Every test uses a fake `AudioSource` |
| `AudioRecord` buffer sized only from `getMinBufferSize` | Reads rejected outright where the minimum is smaller than one pump frame | Hidden by the A51, which reported a larger minimum |
| Dead pump left the engine in `Recording` | A live "recording" notification over a silent microphone — the false reassurance this app exists to prevent | No test ends a pump mid-recording |

Two further defects were found by running the finished UI, and are the reason this
section exists in this form:

- **A successful recording reported a failure.** The engine finalizes itself when
  capture ends, so the service's subsequent `stop()` found nothing in progress and
  returned a failure. The user was told "SafeSignal could not start recording"
  seconds after a clean 12-second capture had been sealed. Real evidence, described
  to its owner as a failure.
- **The summary notification was destroyed on creation.** `finish()` posted the
  summary and then called `cancelAll()` in the same breath. The only durable record
  that the microphone had been used lasted about a second.

The lesson is not "add more tests" — it is that a test double cannot tell you the
platform behaves as you assumed, and a green suite is not evidence about hardware.
Documentation asserting unimplemented graceful degradation made the first of these
worse: it described behaviour that did not exist, and the description was what
convinced everyone the code was fine.

Nothing in this repository should be described as "production ready", and the
project does not make that claim.

---

## 2. Wake-word accuracy is unmeasured, and the shipped engine is weak

**Status: known, disclosed, and the largest single quality gap.**

`LocalWakeWordEngine` is a **signal-processing template matcher**, not a trained
keyword spotter. It compares the energy profile and syllable envelope of a spoken
phrase against a template built from the user's own enrolment recording. It runs
entirely on-device with no network access, which satisfies the offline requirement
genuinely — but its accuracy is well below that of a dedicated keyword-spotting
model.

It has **never been measured**. SPEC §64 requires reporting false-acceptance rate,
false-rejection rate, detection latency and battery cost, across a matrix that
includes whispering, television speech, music, accents and a phone in a pocket.
**Those numbers do not exist yet.** Producing them requires hardware.

Expect the shipped detector to be materially worse than a commercial wake-word
engine, particularly for:
- whispered phrases;
- any accent not represented in enrolment;
- speech over television or music;
- the phone being covered, in a pocket or a bag;
- overlapping speakers.

The [`WakeWordEngine`](audio/wakeword/src/main/kotlin/com/safesignal/audio/wakeword/WakeWordEngine.kt)
interface exists so a verified on-device model can replace this implementation
without touching the recording architecture. That work has not been done.

The `MockWakeWordEngine` is **not** a production activation path and reports
`isProductionReady = false`. It is used for Test Activation mode, where simulating
a detection is the point.

---

## 3. Physical triggers are not implemented

SPEC §11 requires non-voice activation, because a person in an emergency may be
unable to speak. Only the abstraction exists so far. Notification actions, a
home-screen widget and supported media/headset keys are all still to do.

Nothing in the product may promise a specific hardware gesture, because Android
does not expose one uniformly. Any future implementation must say plainly:

> "This trigger is not supported on this device."

when it is unavailable, and must never emulate missing hardware behaviour through
accessibility services or hidden overlays.

---

## 4. Foreground service start is the platform's biggest constraint

SPEC §103 says that when a requested behaviour is prohibited by Android, the
correct response is to explain the limitation and implement the closest legitimate
alternative.

Android 12+ restricts starting foreground services from the background, and
Android 14+ applies additional while-in-use requirements to microphone foreground
services. SafeSignal must therefore be armed **through a user-visible action while
the app is in the foreground**. That is not a workaround — it is the platform's
intent, and evading it would make SafeSignal exactly the kind of covert tool this
project refuses to build.

The consequence for the user: re-arming after the app is killed, or after some OEM
battery managers terminate the service, requires opening the app and confirming.
SafeSignal will not attempt to restart the microphone silently.

---

## 5. Synchronisation, backend and evidence export do not exist yet

Phases 6–8 of the specification are unimplemented. There is no upload client, no
backend, no OpenAPI document and no export packaging in this repository.

This does **not** weaken the central invariant: local encrypted preservation is
the primary path, and it is what has been built. It does mean the "network is only
a synchronisation mechanism" property is currently true *by construction* rather
than by demonstration.

---

## 6. Encryption trade-offs are deliberate and worth stating plainly

- **No user authentication on Keystore keys.** SafeSignal cannot require a
  biometric or device-credential prompt at the moment a recording is sealed: if
  activation started a recording, it must be able to finish sealing it without
  further interaction. On devices that support it, keys are additionally marked
  `setUnlockedDeviceRequired(true)` so they are unavailable while locked.
- **Losing the Keystore key means losing the audio.** This is the intended
  behaviour for a stolen device and it is *not* softened to make account recovery
  easier, per SPEC §54. There is no "forgot my key" path, because a backend that
  could recover plaintext audio would be a backend that holds plaintext audio.
- **Segment files are only checked structurally at startup.** Full authenticated
  verification runs when a recording is opened or exported. Checking every segment
  on every launch would be slow, and on a device with a broken keystore impossible.

---

## 7. Microphone physics is the real ceiling

No software can recover speech that never reached the microphone. Distance, walls,
wind, handling, orientation, obstruction, competing sounds, room acoustics and
speaker volume all affect intelligibility.

The app must never imply otherwise. A measured "it worked in my quiet room" result
does not justify any claim about a phone in a pocket several metres away. The
manual test matrix in [QA_CHECKLIST.md](QA_CHECKLIST.md) exists partly to make that
discipline explicit.

---

## 8. Known rough edges in the code

- The pre-roll ring returns the **newest** whole frame when the final write is
  ragged, discarding the leading partial byte. That is the intended behaviour
  (most recent audio is the useful part, and a partial frame would misalign the
  container) and it is asserted by a test, but it is a judgement call rather than a
  requirement.
- `AndroidKeystoreKeyProvider.wrapKey` asks for StrongBox, which is a hard
  requirement rather than a hint: `generateKey` throws `StrongBoxUnavailableException`
  and creates no key. An earlier version described the request as an ignorable hint
  and claimed the key "lands in the software-backed keystore" when it did not — in
  practice no key was created at all and encryption was impossible on devices
  without StrongBox. The retry without StrongBox is narrow on purpose: it catches
  that one exception and never disables secure hardware. This is described
  correctly in [SECURITY.md](SECURITY.md) §2.
- `PrivateKey.getEncoded()` returns `null` for Android Keystore keys, because they
  are non-exportable by design. Code that needs a verification key must read it from
  `getCertificate().getPublicKey()`. This was handled incorrectly once and would
  have shipped an empty public key inside every evidence package.
- `Identifiers.recordingId()` is a 128-bit CSPRNG hex string rather than a UUID.
  This is deliberate — opaque, timestamp-free — but it differs from the usual
  convention and is worth knowing if a future component expects UUID formatting.
- `KeyGenParameterSpecBuilderCompat` duplicates a small amount of Android
  keystore-builder logic so that the `StrongBox` and `P` version checks stay in one
  place. It is a readability aid, not an abstraction with independent value.
- Room has no migrations yet (`version = 1`, empty `MIGRATIONS` array). The
  pattern is established so the first migration is a one-line addition, but no
  migration has actually been written or tested.

---

## 9. Deliberately absent

The following are **not** implemented and must not be added without a
specification change and a fresh security review:

- emergency-service or police contact (SPEC §69 excludes it from the MVP);
- location, contacts, SMS, camera, or any telemetry containing recording content;
- analytics that transmit audio, transcripts, or recording identifiers;
- background or boot-time microphone activation;
- accessibility-service or device-administrator usage;
- any bypass of Android privacy indicators, foreground-service rules or power
  management;
- cloud speech recognition as a production activation path.