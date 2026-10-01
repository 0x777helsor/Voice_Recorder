# Threat Model

What SafeSignal defends, from whom, and — more importantly — what it cannot
defend against. This document is written so a reader can disagree with the
decisions in it.

Scope: the implemented Phase 1 (local capture, encryption, integrity, recovery).
Synchronisation, the backend and export are specified but not built, so their
threats are described as design requirements rather than assessed findings.

---

## 1. System context

```
        +-----------------+
        |     USER        |  arms deliberately, sees indicators, stops
        +--------+--------+
                 |
        +--------v---------+        +--------------------+
        | SafeSignal app   |------->| Android platform   |
        | (this codebase)  |        | Keystore, FGS,     |
        +--------+---------+        | privacy indicators |
                 |                  +--------------------+
     app-private files (encrypted)  |
     Room (metadata only)           |
                 |                  +--------------------+
                 +----------------->| Trusted backend    |   (not yet built)
                                    | (ciphertext only)  |
```

Everything inside the app boundary is designed. The Android platform and the
backend are trusted *within their stated roles* and cannot be assumed to be
benign if compromised — see §7.

---

## 2. Assets, ranked

| Asset | Why it matters | Protection |
| --- | --- | --- |
| Recorded audio | May be the only record of an incident | AES-256-GCM, Keystore-wrapped per-recording key |
| Evidence integrity | A recording nobody can trust is worthless | AEAD tags + signed SHA-256 manifest |
| Key material | Loss or theft of this equals loss or exposure of all audio | Android Keystore, non-exportable |
| User's arming intent | Coercive recording is the abuse this design must prevent | Explicit action required; visible always |
| Metadata | Can reveal when, where and how long a recording ran | Minimal, documented field set |
| The activation timeline | Shows what happened and in what order | Monotonic timestamps, persisted |

---

## 3. Adversaries

| # | Adversary | Capability | Assessed? |
| --- | --- | --- | --- |
| A1 | Thief with physical possession of an unlocked device | Read app storage, attempt to extract audio | Yes |
| A2 | Malicious app on the same device | Query shared storage, request microphone, inspect permissions | Yes |
| A3 | Network attacker | Observe and modify traffic in transit | Yes |
| A4 | Compromised backend or storage bucket | Read everything stored server-side | Yes |
| A5 | Coercive or abusive party using SafeSignal against someone | Arm it, record a person without consent | Yes |
| A6 | User with root / kernel-level instrumentation | Full control of the OS | Partially — explicitly not defended |
| A7 | Supply-chain compromise of SafeSignal's own build | Modify the app before distribution | Not defended; mitigated by pinning |
| A8 | Long-term metadata analysis | Correlate recording times, duration, sync activity | Yes |

---

## 4. Threats and mitigations

### T1 — Evidence altered in storage (A1, A2)

**Mitigation.** Every segment is sealed with AES-256-GCM under a per-recording
key. The AAD binds recording id, segment id, sequence number and plaintext
length, so modification, relocation, truncation and relabelling all fail
authentication. The manifest additionally detects *missing*, *reordered* and
*duplicated* segments without needing the key.

**Residual risk.** An attacker who can run code as the app's UID with access to
the Keystore can re-encrypt. Nothing in-app prevents that; Android's UID
isolation is the boundary.

### T2 — Evidence altered at the server (A4)

**Mitigation.** The backend stores only ciphertext and never holds a decryption
key, so a compromised bucket yields opaque bytes. Checksums are validated
server-side and the server issues an immutable receipt, which is stored locally
so it can be shown even if the server later disagrees.

**Residual risk.** The server can *delete* or *withhold* evidence. A signed
manifest lets a holder prove what was uploaded, but cannot force retention.

### T3 — Traffic interception or tampering (A3)

**Mitigation.** TLS for transport, short-lived upload authorisation, idempotency
keys to prevent replayed uploads, checksum verification, and evidence that is
meaningless without the device key regardless.

**Residual risk.** A compromised CA or a TLS-terminating middlebox can observe
metadata (sizes, timings, recording ids). Audio remains encrypted.

### T4 — Coercive or abusive recording (A5)

This is the threat that shapes the whole product, and the one most worth stating
plainly.

**Mitigations.**
- Recording never begins automatically. Installation alone records nothing.
- Arming requires an explicit user action and is visible on the lock screen.
- The activation phrase is chosen by the user; a person in danger can choose
  something only they know to say.
- Recording is stopped by a prominent, always-present control.
- SafeSignal provides no covert mode, no hidden recording, and no way to start
  without the visible indicators.

**Residual risk — accepted and disclosed.** Voice activation can be triggered by
*anyone who speaks the phrase*, and a physical trigger can be pressed by anyone
who reaches the phone. **Activation is not authentication.** A person who knows
the phrase can cause a recording that captures them. This is inherent to
non-contact activation; the mitigation is disclosure, and it appears in the
onboarding copy rather than only in this document.

Recording someone without their consent may be unlawful depending on
jurisdiction. See [LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md).

### T5 — Spurious activation (not adversarial, but safety-critical)

A false activation produces an unnecessary recording of bystanders — a privacy
harm caused by the tool itself.

**Mitigation.** `ActivationGate` applies a confidence threshold, a cooldown, a
duplicate-suppression window and an already-recording guard, each with its own
test. Sub-threshold detections are still reported for the recognition test mode so
users can *measure* their own false-accept rate rather than assume it is low.

**Residual risk.** The shipped wake-word engine is a weak template matcher and
its false-accept rate is unmeasured. See
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) § 2.

### T6 — Accidental activation while armed

**Mitigation.** Test mode is clearly labelled and excluded from evidence upload
by default. A user who has armed the session has already accepted that audio will
be captured on activation; the armed state is displayed continuously.

### T7 — Denial of service by exhausting storage (A1)

**Mitigation.** Free space is checked per frame. On approaching the threshold the
recording finalizes safely, preserving committed segments, and notifies the user.
Storage exhaustion never silently corrupts a recording.

### T8 — Evidence loss from process death (not adversarial)

**Mitigation.** Segment commit is atomic (`.tmp` → `fsync` → `rename` →
`fsync` directory), recorded in Room in a transaction with the segment counter.
Startup reconciliation adopts orphaned files and quarantines anything it cannot
verify. Deleting a `.tmp` file is the only deletion, and it is a file nothing
ever claimed as evidence.

### T9 — Fully compromised device (A6)

**Explicitly not defended.** On a rooted device with kernel access, an attacker
can read the microphone stream directly, dump process memory, or hook the Keystore
calls. No application-layer cryptography defends against that.

**Stated plainly:** *no application can guarantee evidence confidentiality on a
completely compromised device.* The mitigations that do hold are `setUnlockedDeviceRequired`
(where supported), non-exportable keys, and the fact that recording is visible.

### T10 — Metadata leakage (A8, A3)

**Mitigation.** No location, contacts, SMS, camera or unrelated device data is
collected. Recording ids are 128-bit random values with no timestamp component.
Logs are redacted before they reach any sink.

**Residual risk.** Timing and size metadata is unavoidable for a synchronised
system: the fact that *a* recording occurred at *approximately* a time is
observable by anyone watching the network or the device.

### T11 — Backup leakage

**Mitigation.** Evidence storage is excluded from backup. This is not merely
tidiness: a restored backup has no Keystore key, so it would contain unreadable
ciphertext that the user could never decrypt and might believe was lost.

### T12 — Screenshot and recents-preview leakage

**Mitigation.** The notification states only that SafeSignal is recording and
offers a stop action; it contains no recording content. Screenshot and
recents-preview blocking is planned as a user setting.

**Status:** not implemented. The notification half of this mitigation *is* done and
verified on device; screenshot and recents-preview blocking is not.

### T13 — Supply-chain (A7)

**Mitigation.** Dependency versions are pinned centrally. Keystore-backed signing
means the manifest signature would not survive a modified build producing new
manifests — but note this protects *against* tampering with stored evidence, not
against a malicious build distributed to users in the first place. Verifiable
builds and reproducible builds are not implemented.

---

## 5. Trust boundaries

1. **App ↔ Android.** The Keystore, the foreground-service rules and the privacy
   indicators are trusted to behave as documented. SafeSignal does not attempt to
   circumvent any of them.
2. **App ↔ filesystem.** App-private storage is trusted for confidentiality only
   as far as Android's UID isolation provides. Integrity is provided by AEAD
   regardless of who wrote the bytes.
3. **App ↔ backend.** The backend is trusted to store and return bytes, and
   explicitly **not** trusted with keys. It can withhold; it cannot read.
4. **App ↔ user.** The user's consent to arm is the authorisation for capture.
   There is no other path.

---

## 6. Accepted risks

These are deliberate, documented decisions — not oversights:

| Risk | Why accepted |
| --- | --- |
| Voice activation is not identity verification | Non-contact activation is the product's purpose; the alternative is a PIN, which cannot be spoken by someone who cannot reach the phone |
| Keystore keys do not require user authentication | Otherwise a recording could start but never be sealed |
| Losing the device key means losing unsynced audio | Weakening this for convenience would put a plaintext-recovery path into the backend |
| A weak wake-word engine ships in Phase 1 | It is fully local and offline-first; a verified model can replace it behind the existing interface |
| Metadata about timing and size is observable | Unavoidable for any synchronised design; audio itself remains encrypted |

---

## 7. Explicit non-goals

SafeSignal will not, and must not, gain the following capabilities:

- hidden or disguised recording;
- microphone-indicator or foreground-service concealment;
- accessibility-service or device-administrator abuse;
- remote or covert activation;
- boot-time microphone capture;
- collection of location, contacts, SMS, camera, or unrelated device data;
- analytics containing recording identifiers, audio or transcripts;
- emergency-service dispatch in the MVP.

Adding any of these requires an explicit specification change and a fresh security
review. They are not backlog items.