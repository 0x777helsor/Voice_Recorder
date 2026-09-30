# Architecture

This document describes how SafeSignal is put together and, where a choice was
made, **why**. It is intended to be read alongside the code rather than instead
of it, and it deliberately calls out the places where the implementation is
incomplete.

---

## 1. Design principles, in order of priority

The specification asks for a reliable system rather than a feature-rich one. The
resulting priorities, in the order they were actually applied to trade-offs:

1. **Reliability over features.** Every subsystem defines its failure behaviour
   before its happy path.
2. **Local preservation over cloud capability.** The network is optional; the
   recorder is not.
3. **The original over an enhanced derivative.** Enhancement is off by default
   and can never overwrite a capture.
4. **Platform-compliant behaviour over capability.** Where Android forbids
   something, SafeSignal explains the limit instead of circumventing it.
5. **Honest reporting over a good-looking demo.** Unknown results are recorded as
   unknown. See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

---

## 2. Module graph

```
                    +-------------------+
                    |       :app        |  DI wiring, navigation
                    +---------+---------+
                              |
      +-----------------------+------------------------+
      |            |            |           |           |
  :service    :data:repository  :feature/*  ...      :core:common
      |            |                                    ^
      |            +-----------+-----------------------+
      |                        |
  :audio:capture          :data:local        :core:crypto
      |                        |                    ^
      +--------+---------------+                    |
               |                                    |
        :audio:processing                    :core:database
               ^
               |
        :audio:wakeword  ----------------------------+
```

Dependencies point in one direction only. There are no cycles, and no module
depends on `:app`.

The specification recommends a finer module split (`audio/{capture,processing,
quality,wakeword}`, `data/{local,remote,repository,models}`, nine `feature/*`
modules). This repository consolidates the closely-coupled ones. The **package**
structure still follows the recommended layout; only the Gradle module boundaries
are coarser. Fewer modules means a smaller build graph, faster incremental builds
and less duplicated configuration — which is itself a reliability property, since
every duplicated block is a chance for the modules to drift apart.

---

## 3. The state machine

`core:common/state` holds the single source of truth for runtime state. Nothing
else in the app may keep a parallel "is recording" boolean.

```
        IDLE
          |  user arms
          v
       ARMING -------------------> FAILED
          |  foreground service up, engine listening
          v
      LISTENING <-------------------+
          |  trigger fired           | activation rejected
          v                          | (low confidence, cooldown, duplicate)
      ACTIVATING -------------------+
          |  accepted
          v
      RECORDING
          |  stop / max duration / low storage / recorder error
          v
     FINALIZING --------------------> FAILED
          |  manifest sealed
          v
       STOPPED
          |  user returns home
          v
        IDLE
```

The transition table lives in `SafeSignalStateMachine.ALLOWED_TRANSITIONS` and is
the executable version of this diagram. Illegal transitions are **rejected and
recorded**, not silently coerced, so a programming error shows up in the incident
timeline instead of disappearing.

Two invariants are encoded directly in the table and asserted by tests:

- **`RECORDING → UPLOADING` is not an edge.** Synchronisation has its own
  `SyncPhase` enum precisely so that a network problem can never be mistaken for
  a capture problem. There is a test that fails if anyone ever adds an upload
  state to `SafeSignalPhase`.
- **A failure during `RECORDING` still leads to `FINALIZING`.** If audio was
  captured, it gets sealed. `FAILED` is reserved for situations where no evidence
  was ever at risk. "Stopped with errors" is a truer description than "failed",
  and losing that distinction would understate the user's position.

`SafeSignalFailure` carries a stage, a code, a user-facing message and whether
evidence was preserved. Failures are values that can be persisted and shown, not
log lines that disappear.

---

## 4. Activation

Activation is a decision, not a side effect. `core:common/activation/ActivationGate`
is a pure, synchronous, dependency-free class with exactly four rules:

| Rule | Applies to | Why |
| --- | --- | --- |
| Confidence threshold | acoustic triggers only | A button press is not a probabilistic detection |
| Cooldown | all triggers | Stops a detector re-firing on the tail of one utterance |
| Duplicate suppression | all triggers | Stops a single spurious detection cascading |
| Already-recording guard | all triggers | A trigger must never restart a live session |

Each rule has its own test, including the boundary case where confidence exactly
equals the threshold.

**Activation is not authentication.** Anyone who can say the phrase can start a
recording. This is inherent to non-contact activation and is stated in the
onboarding copy rather than buried. See
[THREAT_MODEL.md](THREAT_MODEL.md).

---

## 5. Recording and evidence pipeline

```
AudioFramePump (one reader, AudioRecord)
        |
        v
SegmentedRecordingEngine.onFrame
        |
        +--> RollingPreBuffer   RAM only, bounded, opt-in, default OFF
        +--> AudioQualityMonitor
        +--> active segment ByteArrayOutputStream
                |
        segment full | max duration | low storage | stop
                v
        SegmentedEvidenceWriter.commitSegment()
                |
        seal (AES-256-GCM, fresh nonce, identity-bound AAD)
                |
        write .tmp -> fsync -> atomic rename -> fsync directory
                |
        Room transaction: segment row + recording counter
```

### Why `AudioRecord` and not `MediaRecorder`

`MediaRecorder` writes one continuous container and offers no supported way to
split it into independently sealed chunks, so a crash mid-recording leaves an
unusable file. `AudioRecord` yields raw frames, which gives SafeSignal exact
control of segment boundaries, allows the pre-roll to be drawn from the *same*
reader (no gap, no duplication), and makes crash recovery tractable: a partially
written segment is still a valid WAV prefix. The cost is writing our own
container, which is why `WavSegmentWriter` exists.

The source is `MediaRecorder.AudioSource.VOICE_RECOGNITION` rather than
`MIC`/`VOICE_COMMUNICATION`, deliberately: that disables OEM voice-enhancement
pipelines (noise suppression, AGC). Those would alter the original capture, which
the "original is immutable" invariant forbids.

### The commit protocol

Each segment is sealed, written to a temporary file, `fsync`ed, atomically renamed
into place, and only then recorded in Room — in a single transaction that also
increments the recording's segment counter.

There is therefore no window in which the database claims a segment exists whose
file does not, and no window in which a committed segment can be corrupt. A crash
leaves either a complete segment or a `.tmp` file that was never claimed by
anything.

Committed segments are **never overwritten**. `commitSegment` throws if the target
name already exists.

### Why PCM/WAV by default

Uncompressed PCM is the honest choice for evidence: the codec is a copy of the
microphone samples, so there is no encoder state to lose and no encoder bug that
can corrupt a long recording. AAC is offered for storage-constrained users with
the trade-off stated in the UI.

---

## 6. Encryption

Envelope encryption, one data-encryption key (DEK) per recording:

```
per-recording DEK (AES-256, random, RAM only)
     |
     +--> wraps each segment: AES-256-GCM, fresh 96-bit nonce per segment
     |
     +--> wrapped once by a Keystore KEK; only the wrapped form is stored
```

Details that matter:

- **AAD binds segment identity.** Recording id, segment id, sequence number and
  plaintext length go into the authentication tag, so a segment cannot be moved
  between positions, between recordings, or truncated and re-appended.
- **The AAD field separator is NUL**, which cannot appear in any field, so
  `("ab","c")` and `("a","bc")` cannot produce the same authenticated data.
- **Fail closed, always.** A tag mismatch raises `CryptoException.AuthenticationFailed`.
  There is no plaintext fallback, no algorithm downgrade, and no "best effort"
  recovery path.
- **Key custody is deliberately unforgiving.** Losing the Keystore key means
  losing the audio. That is the correct trade-off for a stolen device, and it is
  not softened to make account recovery easier. See §4 of [SECURITY.md](SECURITY.md).

---

## 7. Integrity

Two independent mechanisms, because neither alone is sufficient:

| Mechanism | Detects | Needs the key? | Third party can run it? |
| --- | --- | --- | --- |
| AES-GCM tag per segment | modified, moved, truncated segments | yes | no |
| SHA-256 per segment in a signed manifest | missing, reordered, duplicated segments, rewritten manifests | no | yes |

The manifest is serialised to a **canonical** byte form before hashing and signing.
`MiniJson` exists for this: a library's field ordering or float formatting must
never be able to silently invalidate a signature after an app upgrade. The
canonical form is covered by a round-trip test asserting byte stability.

The aggregate recording hash folds in the sequence number alongside each segment
digest, so two different orderings of the same segments produce different
aggregates. Hashing only the concatenated digests would let reordering through
undetected.

The signature is asymmetric (ECDSA P-256, Keystore-held) and detached, so a third
party — a lawyer, a court officer — can verify the package using only the public
key embedded in the export. This matters: a symmetric MAC would require giving the
verifier a secret, or phoning home, and the specification forbids making evidence
handling depend on SafeSignal infrastructure.

**This does not make a recording legally admissible.** See
[LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md).

---

## 8. Crash recovery and reconciliation

On start, `EvidenceStoreReconciler` compares the filesystem against the database.
The governing rule is that **the database is a cache of the filesystem, not the
source of truth about evidence**. If a crash happened between writing a file and
committing its row, the file wins — that is the only ordering that never loses
audio.

Resolution rules:

| Situation | Action | Rationale |
| --- | --- | --- |
| `.tmp` file | delete, report | Never renamed into place, so nothing claimed it |
| Segment failing the structural check | **quarantine**, never delete | Might be recoverable with a key or after a bug fix |
| File on disk, no database row | adopt into the database | The file is stronger evidence |
| Database row, no file | report as missing | Keep the gap visible |
| Unexpected file | report, leave untouched | Not ours to delete |

Reconciliation is structural only (magic bytes and length), because it runs on
every launch and must work on a device whose keystore is broken. Full
authenticated verification happens when a recording is opened or exported.

---

## 9. Failure model

Every subsystem declares its behaviour on failure (SPEC §71):

| Subsystem | On failure |
| --- | --- |
| Wake word | Physical/test trigger remains available; readiness screen reports it |
| Microphone | Stop capture, finalize committed segments, report clearly |
| Storage | Finalize before exhaustion, preserve, notify |
| Encryption | Fail closed; never emit plaintext; never mark evidence finalized |
| Network / backend | **Recording continues.** Sync enters `FAILED` and retries |
| State machine | Reject illegal transitions and record the rejection |

The single most important row is the network one. It is enforced structurally: a
network failure can only ever move `SyncPhase`, never `SafeSignalPhase`.

---

## 10. Data model

Room holds **references and cryptographic metadata only**. No audio blobs, ever:
170 MB of PCM in SQLite would mean database corruption could destroy evidence
that exists nowhere else.

| Table | Purpose |
| --- | --- |
| `recordings` | Session metadata, sealed-key blob, sync and retention state |
| `recording_segments` | One row per committed, hashed segment |
| `upload_tasks` | Resumable sync queue with deterministic idempotency keys |
| `evidence_manifests` | Canonical signed manifest bytes, stored verbatim |
| `app_settings` | Key/value user configuration |
| `wake_word_configuration` | Phrase, threshold, enrolment state |
| `activation_timeline` | Monotonic event log per recording |

`evidence_manifests.canonical_json` stores the exact signed bytes rather than
re-serialising from columns, because a manifest that is rebuilt on read could
drift and invalidate its own signature after an upgrade.

`Retention` selection is a `SELECT`, not a `DELETE`: deletion requires explicit
user confirmation, so the caller must see the rows before anything is removed.

---

## 11. What is not built yet

- Foreground service, notification, DI wiring, Compose UI.
- Wake-word enrolment UI and any measured accuracy figures.
- Physical triggers.
- Synchronisation client, backend, OpenAPI, export packaging.

`core:common`, `core:crypto`, `core:database`, `audio/*` and `data:local` are
implemented and unit-tested. The rest of the pipeline is specified but not written.
See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).