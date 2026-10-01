# SafeSignal

**User-controlled emergency audio evidence preservation for Android.**

SafeSignal lets you deliberately arm an emergency listening session *before* you
enter a situation where you might not be able to safely reach your phone. While
armed, the app listens **locally on the device** for a phrase you configure. If it
detects that phrase, it starts recording, encrypts the audio on the device, seals
it into independently verifiable segments, and — only if you have enabled it —
synchronises the encrypted evidence to a server you control.

SafeSignal is **not** a covert surveillance tool, and it is built so that it cannot
become one. It does not hide, does not disguise itself, does not bypass Android's
microphone indicators, and does not record without you having armed it first.

---

## Current status — read this before anything else

This repository contains **Phase 1** of the specification: the local vertical
slice, plus the first three phases of the recording path. Honest statement of what
is and is not finished:

| Area | State |
| --- | --- |
| Gradle build, module graph, lint configuration | Building clean |
| `core:common` — state machine, activation gate, logging, clocks, JSON, readiness, permissions | Implemented, unit-tested |
| `core:crypto` — AES-256-GCM envelope, Keystore KEK, SHA-256 manifest, ECDSA signing | Implemented, tamper-tested, keystore verified on device |
| `core:database` — Room schema, DAOs, transactional segment commit | Implemented; **not yet written to at `stop()`** |
| `audio:processing` — rolling pre-buffer, WAV segment writer, quality monitor | Implemented, unit-tested |
| `audio:capture` — `AudioRecord` source, frame pump, segmented engine | Implemented; capture verified on two physical devices |
| `data:local` — segmented encrypted writer, startup reconciler | Implemented, crash-recovery tested |
| `service` — foreground service, notification, recording controller | Implemented, verified on device |
| `app` — DI graph, manifest, readiness screen, launcher icon | Builds; minimal UI only |
| `backend` — manifest/digest receipt store, capability queries | Implemented, 83 tests. **No Android client calls it yet** |
| Wake-word detection, full UI, triggers, sync client, evidence export | **Not implemented** |

### What is verified on real hardware

Two physical phones were used, chosen because they differ in ways that matter:
a Samsung Galaxy A51 (API 33, Exynos, `ro.hardware.keystore=mdfpp`, no usable
StrongBox) and a Tecno BF7 (API 31, MediaTek, no StrongBox at all). The second
phone is what exposed the capture bugs the first one hid — see
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) §1.

| Behaviour | Verified how |
| --- | --- |
| Microphone capture, 48 kHz mono | Tecno BF7: 12 s from one tap, three segments sealed at 480,000 / 480,000 / 192,000 bytes plaintext |
| AES-256-GCM encryption of segments | Same run; 80 bytes of overhead per file = 12-byte IV + 16-byte GCM tag |
| Keystore key generation | Both phones, with and without StrongBox present |
| ECDSA signing public key non-null | Both phones; this was a real bug (see below) |
| Foreground service starts, notification visible | `dumpsys activity services`: `isForeground=true`, channel `safesignal.recording.v1`, `vis=PUBLIC`, one Stop action |
| Service releases the microphone on stop | Confirmed by `dumpsys activity services` returning no SafeSignal service |

**Three bugs were found only by running on hardware**, none of which any unit test
could reach because every one of them used a fake `KeyProvider` or a fake
`AudioSource`: StrongBox being a hard requirement rather than a hint (which meant
*no key was created at all*), `PrivateKey.getEncoded()` returning `null` (which
would have published an empty verification key inside every evidence package), and
the deprecated `AudioRecord.read(ByteBuffer, …)` overload being outright broken on
one ROM. The lesson is recorded in the commit messages rather than smoothed over.

### What is **not** verified

- **Any Android 14/15/16 device.** `targetSdk` is 36; the devices available are API
  33 and API 31. Android 14's mandatory `foregroundServiceType` enforcement and
  `ForegroundServiceStartNotAllowedException` for background-started microphone
  services are unvalidated on real hardware. This is the largest gap.
- Screen-off and lock-screen survival of the foreground service.
- Microphone contention: phone calls, other apps, Bluetooth route changes.
- Wake word, triggers, sync, export, and the manifest seal→store→verify round trip.
- Six of fifteen Gradle modules are still empty stubs.

### Verification performed

```text
./gradlew test            BUILD SUCCESSFUL   158 Android tests, 0 failures
./gradlew lint            BUILD SUCCESSFUL   abortOnError = true
cd backend && npm test    93 passing, 0 failing
cd backend && npx tsc --noEmit   clean
./gradlew assembleDebug   BUILD SUCCESSFUL   app-debug.apk
```

Counts are deduplicated across build variants. `./gradlew test` runs both `debug`
and `release` unit-test variants, and every test appears in both, so naively summing
the XML result files reports roughly double the real number — an earlier version of
this file claimed 150 where the true figure was 140.

Test counts by module:

| Module | Tests |
| --- | --- |
| `core:common` | 57 |
| `audio:capture` | 26 |
| `data:local` | 21 |
| `core:crypto` | 21 |
| `audio:processing` | 13 |
| `service` | 20 |
| **Android total** | **158** |
| `backend` | 93 |

Two tests in the list above deserve a caveat. The four `StrongBoxFallbackTest` cases
assert on the *fallback decision*, not on the resulting key, because
`KeyGenParameterSpec` getters return `null` under `isReturnDefaultValues`, making
JVM assertions on the spec meaningless. The keystore itself was verified on device
instead. The same applies to every assertion about `AudioRecord` configuration.

---

## The three invariants

Everything else is negotiable. These are not.

1. **If the network fails, SafeSignal still preserves the recording locally.**
   The network is a synchronisation mechanism, never an activation mechanism and
   never a recording mechanism. `RECORDING → UPLOADING` is not a legal state
   transition, the state machine rejects it, and a test fails if anyone adds an
   upload state to `SafeSignalPhase`.

2. **The original recording is never destructively modified.**
   Audio is captured raw, sealed as-is, and hashed. Enhancement and transcription
   would produce *derivatives*, stored separately and labelled as such.

3. **SafeSignal is never covert.**
   Recording only ever begins after you arm it. A persistent foreground-service
   notification is always shown. Android's microphone privacy indicator is left
   alone. There is no accessibility service, no device administrator, no overlay
   trick, no boot-time activation, and no mechanism intended to defeat platform
   privacy controls.

And a fourth, softer but important one:

4. **SafeSignal never claims more than the microphone can physically deliver.**
   Distance, walls, wind, handling, orientation, obstruction, room acoustics and
   speaker volume all affect intelligibility. No "long range", "hears everything"
   or "records through walls" claim appears anywhere in the product, and
   [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) says plainly that a successful test
   in a quiet room justifies no such claim.

---

## Build and test

Requires JDK 17 and an Android SDK with `compileSdk 36` installed.

```bash
./gradlew test           # unit tests
./gradlew lint           # Android Lint
./gradlew assembleDebug  # debug APK
```

| Component | Version | Why that version |
| --- | --- | --- |
| Gradle | 8.13 | — |
| Android Gradle Plugin | 8.13.2 | — |
| Kotlin | 2.2.21 | — |
| KSP | 2.2.21-2.0.4 | Must match Kotlin exactly |
| Hilt | 2.57.2 | Hilt ≥ 2.60 requires AGP ≥ 9.0 |
| Compose BOM | 2025.10.01 | Newer BOMs force lifecycle 2.11 → requires AGP 9.1 |
| compileSdk / targetSdk | 36 | — |
| minSdk | 26 | Per specification |

Two of those pins are load-bearing and will bite anyone who raises a version
casually. See [docs/DECISIONS.md](docs/DECISIONS.md) ADR-007.

Environment setup, including a non-obvious JDK truststore failure mode, is in
[docs/TOOLCHAIN.md](docs/TOOLCHAIN.md).

---

## Architecture at a glance

```
                     USER EXPLICITLY ARMS
                              |
                              v
                  +------------------------+
                  | Emergency Listening    |   foreground service,
                  | (microphone, visible)  |   persistent notification
                  +-----------+------------+
                              |
              +---------------+---------------+
              |                               |
              v                               v
      Local wake word                  Physical / test trigger
              |                               |
              +---------------+---------------+
                              |
                              v
                    ACTIVATION GATE
         (confidence, cooldown, duplicate suppression)
                              |
                              v
                      RECORDING ENGINE
                              |
                optional RAM pre-roll (opt-in, default OFF)
                              |
                              v
                     SEGMENTATION
                              |
                              v
                      AES-256-GCM SEAL
                              |
                              v
                  LOCAL EVIDENCE STORE
                              |
              +---------------+---------------+
              |                               |
        no internet                      internet
              |                               |
              v                               v
         LOCAL ONLY                    upload queue  (not yet built)
        (kept on device)                        |
                                        resumable upload
                                                  |
                                                  v
                                          INTEGRITY RECEIPT
```

Package layout follows the specification's recommended structure, consolidated
into fewer Gradle modules to keep the build graph small:

```
core/common      state machine, activation gate, logging, clocks, JSON
core/crypto      envelope encryption, manifest, signing
core/database    Room schema
audio/capture    AudioRecord source, frame pump, recording engine
audio/wakeword   wake-word boundary, mock and local template engines
audio/processing pre-buffer, WAV writer, quality monitor
data/local       segmented encrypted store, startup reconciler
data/remote      sync boundary (not yet implemented)
data/repository  orchestration, sync worker (not yet implemented)
service          foreground service, notification, recording controller
feature/*        Compose UI (not yet implemented)
app              DI graph, manifest, minimal readiness screen
```

---

## Documentation

| Document | Purpose |
| --- | --- |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Modules, data flow, state machine, failure model |
| [SECURITY.md](SECURITY.md) | Cryptographic design, key custody, hardening checklist |
| [THREAT_MODEL.md](THREAT_MODEL.md) | Assets, adversaries, accepted risks |
| [PRIVACY.md](PRIVACY.md) | What is collected, when, and what is never collected |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | What is unverified, incomplete or worse than advertised |
| [QA_CHECKLIST.md](QA_CHECKLIST.md) | Manual test matrices, including the wake-word honesty matrix |
| [API.md](API.md) | Backend design contract, and how the server is hardened |
| [OPENAPI.yaml](OPENAPI.yaml) | Machine-readable contract, held to the running service by tests |
| [LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md) | Recording-law and admissibility caveats |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Release build, signing, release checklist |
| [ANDROID_COMPATIBILITY_MATRIX.md](ANDROID_COMPATIBILITY_MATRIX.md) | Per-API-level behaviour |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Why non-obvious choices were made |
| [docs/TOOLCHAIN.md](docs/TOOLCHAIN.md) | Build environment setup |

---

## Safety boundary

SafeSignal records audio. That is inherently sensitive, and in many jurisdictions
recording other people requires their consent or is otherwise restricted.
**Recording law varies by jurisdiction and SafeSignal does not make recording
lawful.** See [LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md).

SafeSignal also does not claim that anything it produces is automatically
admissible as evidence. It uses integrity-preserving storage and export mechanisms.
Evidentiary weight depends on the applicable jurisdiction, circumstances,
authenticity requirements and the relevant authority or court. Consult a qualified
legal professional where appropriate.

---

## Licence

See [LICENSE](LICENSE).