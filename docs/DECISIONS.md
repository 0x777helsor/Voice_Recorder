# Decisions

Short records of choices that were not obvious, and that a future contributor
would otherwise be tempted to reverse.

---

## ADR-001: `AudioRecord` rather than `MediaRecorder`

**Context.** Both capture audio; only one exposes raw frames.

**Decision.** `AudioRecord`.

**Reasoning.** Three requirements drove this:

1. **Segmentation.** `MediaRecorder` writes one continuous container with no
   supported way to split it into independently sealed chunks. A crash leaves an
   unusable file. SafeSignal's integrity model depends on segments that are
   independently verifiable.
2. **Pre-roll integrity.** The RAM ring must contain the *same* samples the
   recorder will write. Sharing one reader is the only way to guarantee no gap
   and no duplication.
3. **Derivatives.** Enhancement and transcription need PCM, not a compressed
   container.

**Cost.** SafeSignal must write its own WAV container.

**Also decided:** `MediaRecorder.AudioSource.VOICE_RECOGNITION` rather than `MIC`.
That disables OEM noise-suppression and AGC pipelines, which would otherwise alter
the original capture and break the "original is immutable" invariant.

---

## ADR-002: Encryption metadata without `org.json` or a serialisation library

**Context.** The manifest must serialise to **byte-identical** canonical output on
any device and any app version, because a signature is computed over it.

**Decision.** A small `MiniJson` implementation in `core:common`.

**Reasoning.**

- `org.json` is an `android.jar` stub on the JVM, so testing manifest parsing
  off-device would require `isReturnDefaultValues = true`, which turns hard
  failures into silent nulls — the wrong behaviour in a security path.
- A general serialiser can change field ordering or float formatting between
  versions. A signature that verified yesterday would fail today, and users would
  be told their evidence was corrupt.

**Cost.** ~300 lines of code to maintain. Accepted: this is a security property,
not a convenience.

---

## ADR-003: Database is a cache of the filesystem, not the source of truth

**Context.** A crash can land between writing a segment file and committing its
database row.

**Decision.** On reconciliation, the file wins. Orphaned files are adopted into
the database; database rows without files are reported as missing rather than
deleted; corrupt files are quarantined, never removed.

**Reasoning.** It is the only ordering that never loses audio. The reverse — trusting
the database — would mean discarding a complete, sealed segment because its row
never landed.

**Only deletion permitted:** `.tmp` files, which were never atomically renamed and
so were never claimed by anything.

---

## ADR-004: Keystore keys do not require user authentication

**Context.** `setUserAuthenticationRequired(true)` would protect keys behind a
biometric or device-credential prompt.

**Decision.** `false`, with `setUnlockedDeviceRequired(true)` on API 28+.

**Reasoning.** A biometric prompt at the moment a recording is sealed would mean
activation could start a recording that then could never be finalized. The worst
possible failure for an evidence tool.

**Mitigation.** Keys are still non-exportable and unavailable while the device is
locked where the platform supports it. Documented in
[THREAT_MODEL.md](THREAT_MODEL.md) as an accepted risk.

---

## ADR-005: No key recovery path

**Decision.** Losing the Keystore key means losing unsynchronised recordings,
permanently.

**Reasoning.** Any recovery path requires someone to be able to decrypt the audio.
A backend that can do that is a backend holding plaintext audio, which is exactly
what the end-to-end design exists to prevent.

**Consequence.** Disclosed in onboarding, [PRIVACY.md](PRIVACY.md) § 10 and
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md). Users who need recordings to survive
device loss must enable synchronisation and back up their own key material.

---

## ADR-006: Consolidated Gradle modules

**Context.** The specification recommends `audio/{capture,processing,quality,
wakeword}`, `data/{local,remote,repository,models}` and nine `feature/*` modules.

**Decision.** A coarser module graph, with the **package** structure still
following the recommendation.

**Reasoning.** Fewer modules means a smaller build graph, faster incremental
builds and less duplicated configuration. Duplicated configuration is itself a
reliability problem: every copied block is a chance for two modules to drift.

---

## ADR-007: Version pins that are load-bearing

- **Hilt 2.57.2**, not 2.60+. Hilt ≥ 2.60 requires AGP ≥ 9.0 and fails the build.
- **Compose BOM 2025.10.01**, not 2026.x. Newer BOMs constrain lifecycle to 2.11,
  which requires AGP 9.1 and fails `checkDebugAarMetadata`.
- **Gradle 8.13 + AGP 8.13.2 + Kotlin 2.2.21 + KSP 2.2.21-2.0.4.** KSP's version
  must match Kotlin exactly.

Raising AGP requires raising Hilt in the same change. Both are in
`gradle/libs.versions.toml` with a comment saying so.

---

## ADR-008: Merge of a concurrent, incompatible draft

**Context.** While this work was in progress, another process wrote a parallel
implementation into the same repository with an incompatible module graph
(`:data`, `:feature:ui`, `:core:permissions`, `:audio:quality`) and a different
manifest model.

**Decision.** Consolidated to one design. Two genuinely valuable pieces were
adapted rather than discarded:

- `ActivationGate` — kept, rewritten against the `ActivationSource` vocabulary.
  The four suppression rules and their tests are unchanged.
- The tamper-detection test suite — kept, rewritten against the `SegmentCipher`
  API, and extended.

Eleven superseded files were removed.

**Reasoning.** Two incompatible architectures in one repository cannot compile.
One had to win, and partial deletion of either would leave both broken.

---

## ADR-009: Shipped wake-word engine is a template matcher

**Decision.** A local signal-processing template matcher, not a trained keyword
spotter.

**Reasoning.** A small trained model would score better on the wake-word matrix,
but its licence, provenance and per-device accuracy would all need verifying — and
an unverifiable model inside an evidence product is worse than a weaker one that is
fully understood.

**Cost.** Materially worse accuracy, particularly for whispered speech, accents,
television audio and a covered phone. Must be measured and reported honestly; see
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) § 2.

**Not blocked.** `WakeWordEngine` exists so a verified model can replace it without
touching the recording architecture.

---

## ADR-010: Pre-roll keeps the newest frame when the final write is ragged

**Decision.** When the buffer holds a partial frame, `drainTo` returns whole
frames and discards the *leading* partial byte.

**Reasoning.** For evidence, the most recent audio before activation is the part
worth preserving. A leading partial frame would also misalign the container.

**Status.** A judgement call, not a requirement. Asserted by a test so a future
change is deliberate rather than accidental.