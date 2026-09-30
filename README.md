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
slice. Honest statement of what is and is not finished:

| Area | State |
| --- | --- |
| Gradle build, module graph, lint configuration | Implemented, builds |
| `core:common` — state machine, activation gate, logging, clocks, JSON | Implemented, unit-tested |
| `core:crypto` — AES-256-GCM envelope, Keystore KEK, SHA-256 manifest, ECDSA signing | Implemented, tamper-tested |
| `core:database` — Room schema, DAOs, transactional segment commit | Implemented |
| `audio:processing` — rolling pre-buffer, WAV segment writer, quality monitor | Implemented, unit-tested |
| `audio:capture` — `AudioRecord` source, frame pump, segmented engine | Implemented |
| `data:local` — segmented encrypted writer, startup reconciler | Implemented, crash-recovery tested |
| Foreground service, Compose UI, DI wiring | **Not yet implemented** |
| Wake-word tuning, physical triggers | Stub interfaces only |
| Synchronisation client, backend, evidence export | **Not yet implemented** |

Nothing in this repository has been run on a physical device or emulator. See
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) — which is deliberately blunt about
what is unverified.

---

## The three invariants

Everything else is negotiable. These are not.

1. **If the network fails, SafeSignal still preserves the recording locally.**
   The network is a synchronisation mechanism, never an activation mechanism and
   never a recording mechanism. `RECORDING → UPLOADING` is not a legal state
   transition, and the state machine rejects it.

2. **The original recording is never destructively modified.**
   Audio is captured raw, sealed as-is, and hashed. Enhancement and transcription
   would produce *derivatives*, stored separately and labelled as such.

3. **SafeSignal is never covert.**
   Recording only ever begins after you arm it. A persistent foreground-service
   notification is always shown. Android's microphone privacy indicator is left
   alone. There is no accessibility service, no device administrator, no overlay
   trick, no boot-time activation, and no mechanism intended to defeat platform
   privacy controls.

There is a fourth, softer but important one:

4. **SafeSignal never claims more than the microphone can physically deliver.**
   Distance, walls, wind, handling, orientation, obstruction, room acoustics and
   speaker volume all affect intelligibility. The app contains no copy suggesting
   otherwise, and no "long range", "hears everything" or "records through walls"
   claim appears anywhere in the product.

---

## Build and test

Requires JDK 17 and an Android SDK with `compileSdk 36` installed.

```bash
./gradlew test          # unit tests
./gradlew lint          # Android Lint
./gradlew assembleDebug # APK
```

The project's toolchain versions are pinned in `gradle/libs.versions.toml`:

| Component | Version |
| --- | --- |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| KSP | 2.2.21-2.0.4 |
| Hilt | 2.57.2 |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |

Hilt 2.57.x is the newest line supporting AGP 8.x; Hilt ≥ 2.60 requires AGP ≥ 9.
If you raise the AGP version, raise Hilt in the same change.

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
                optional RAM pre-buffer (opt-in)
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
         LOCAL ONLY                    upload queue  (Phase 6)
        (kept on device)                        |
                                        resumable upload
                                                  |
                                                  v
                                          INTEGRITY RECEIPT
```

Package layout follows the specification's recommended structure, consolidated
into fewer Gradle modules to keep the build graph small:

```
core/common     state machine, activation gate, logging, clocks, JSON
core/crypto     envelope encryption, manifest, signing
core/database   Room schema
audio/capture   AudioRecord source, frame pump, recording engine
audio/wakeword  wake-word boundary, mock and local template engines
audio/processing pre-buffer, WAV writer, quality monitor
data/local      segmented encrypted store, startup reconciler
data/remote     synchronisation boundary (not yet implemented)
data/repository orchestration, sync worker (not yet implemented)
service         foreground service (not yet implemented)
feature/*       Compose UI (not yet implemented)
app             DI wiring, navigation (not yet implemented)
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
| [LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md) | Recording-law and admissibility caveats |
| [ANDROID_COMPATIBILITY_MATRIX.md](ANDROID_COMPATIBILITY_MATRIX.md) | Per-API-level behaviour |

---

## Safety boundary

SafeSignal records audio. That is inherently sensitive, and in many
jurisdictions recording other people requires their consent or is otherwise
restricted. **Recording law varies by jurisdiction and SafeSignal does not make
recording lawful.** See [LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md).

SafeSignal also does not claim that anything it produces is automatically
admissible as evidence. It uses integrity-preserving storage and export
mechanisms. Evidentiary weight depends on the applicable jurisdiction,
circumstances, authenticity requirements and the relevant authority or court.
Consult a qualified legal professional where appropriate.

---

## Licence

See [LICENSE](LICENSE).