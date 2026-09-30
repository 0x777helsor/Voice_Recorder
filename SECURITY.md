# Security

How SafeSignal protects evidence and the keys that unlock it, and — just as
importantly — what it does not protect against.

Read alongside [THREAT_MODEL.md](THREAT_MODEL.md), which describes the
adversaries, and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md), which states what
has not been verified.

---

## 1. Security goals, in order

1. **Evidence must be authentic.** If SafeSignal says a recording is intact, it
   is intact, and any alteration is detected.
2. **Evidence must be confidential at rest.** A lost or stolen phone must not
   yield playable audio.
3. **Recording must be visible.** A user, and Android, must always be able to see
   that the microphone is in use.
4. **No silent failure.** Every subsystem fails in a way that preserves evidence
   and tells the user what happened.

Non-goals, stated so they are not mistaken for oversights:

- Protecting evidence on a **fully compromised device** with root access and
  kernel-level instrumentation. No application can do this.
- Preventing a determined attacker who has physical possession of an *unlocked*
  device from recording the microphone with their own software.
- Providing **legal admissibility**. See [LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md).

---

## 2. Local encryption

### Envelope encryption

```
per-recording DEK (AES-256, CSPRNG, RAM only while recording)
     │
     ├─► each segment: AES-256-GCM, fresh 96-bit nonce
     │     AAD = recordingId ␀ segmentId ␀ sequenceNumber ␀ plaintextLength
     │
     └─► wrapped once by an Android Keystore KEK (AES-256-GCM)
           only the wrapped form is ever stored
```

**Why per-recording keys.** Rotating the wrapping key then costs 32 bytes per
recording instead of re-encrypting every segment, and a single compromised
recording cannot expose any other.

**Why the DEK is cleared after use.** It exists in memory only for the lifetime
of the capture. `RecordingKeyMaterial.clear()` overwrites the buffer. This is
best-effort — the JVM may have copied it — and the docs say so rather than
implying a guarantee that does not exist.

### The AAD binding

Binding identity into the authentication tag is what makes evidence
non-repudiable *structurally*:

| Tampering attempt | Detected by |
| --- | --- |
| Modify one ciphertext byte | GCM tag mismatch |
| Swap segment 3 and segment 7 | AAD `sequenceNumber` mismatch |
| Move a segment into another recording | AAD `recordingId` mismatch |
| Truncate a segment and re-append it | AAD `plaintextLength` mismatch |
| Relabel a segment | AAD `segmentId` mismatch |
| Replay an old nonce | CSPRNG nonce uniqueness |

The separator between AAD fields is `U+0000`, which cannot occur inside any
field. Without it, `("ab", "c")` and `("a", "bc")` would authenticate identically,
and a segment could be relabelled across the boundary. There is a test for this.

### Fail-closed

`CryptoException.AuthenticationFailed` and `CryptoException.MalformedCiphertext`
are the only outcomes of a failed open. There is no plaintext fallback, no
algorithm negotiation, no partial read, and no "best effort" recovery. A segment
that cannot be authenticated is quarantined, never presented as audio.

### Keystore configuration

| Setting | Value | Reason |
| --- | --- | --- |
| `setUserAuthenticationRequired` | `false` | A recording must be sealable without a prompt at activation time |
| `setUnlockedDeviceRequired` | `true` on API 28+ | Evidence at rest is unavailable while the device is locked |
| `setIsStrongBoxBacked` | hint, best-effort | Use a dedicated secure element where present |
| `setRandomizedEncryptionRequired` | `true` | Refuse deterministic encryption |

**The `false` on user authentication is a deliberate trade-off**, not an oversight.
Requiring a biometric or credential prompt would mean a recording could start but
never be finalized — the worst possible outcome for an evidence tool. The
mitigation is that the key still lives inside the Keystore and, where supported, is
unavailable while the device is locked.

---

## 3. Key custody

**Losing the Keystore key means losing the audio.** This is intended.

It is the correct behaviour for a stolen device, and the specification (SPEC §54)
explicitly forbids weakening it to make account recovery easier. There is no
"forgot my key" path, because a backend that could recover plaintext audio is a
backend that holds plaintext audio.

Consequences the user must understand before relying on SafeSignal:

- A factory reset destroys the key and therefore the evidence.
- Losing the device means losing any evidence not already synchronised.
- A compromised backend cannot decrypt recordings, so a compromised backend
  cannot help recover them either.
- Some OEM keystore implementations, and certain aggressive security software,
  can invalidate keys. SafeSignal surfaces this as `KeyUnavailable` and leaves the
  ciphertext in place rather than deleting evidence it cannot read.

### Rotation

Rotation re-wraps 32-byte DEKs. Audio segments are never re-encrypted.
`keyVersion` on every recording records which KEK version wrapped it, so a future
migration never has to guess.

---

## 4. Integrity evidence

Two independent mechanisms, because either alone leaves a gap:

- **AES-GCM tags** prove a segment was not altered — but only to a holder of the
  key, and they say nothing about a *missing* segment.
- **A signed manifest** proves the set of segments is complete and correctly
  ordered, and can be checked by anyone holding only the export package.

The manifest is serialised to a canonical byte form before hashing and signing.
Canonicality is a security property here: if field ordering or number formatting
could drift after an app upgrade, a signature that verified yesterday would fail
today and users would be told their evidence was corrupt. `MiniJson` plus a
round-trip byte-stability test make that impossible.

The signing key is **asymmetric** (ECDSA P-256) and held in the Keystore, so
verification needs only a public key — which ships inside the export. A third
party can check integrity without a secret, and without SafeSignal's servers
having to be involved in evidence handling.

### What a signature does and does not prove

**Proves:** the manifest has not been altered since it was signed, and the
signature matches the key published alongside it.

**Does not prove:** that this key belongs to the person who recorded the audio, or
that the audio was recorded when the manifest claims. Establishing provenance
requires comparing the embedded public key against one obtained out of band from
the device owner.

SafeSignal says this plainly rather than letting "signed" imply more than it does.

---

## 5. Logging

`SafeLogger` redacts before anything reaches a sink. Two layers:

1. **Key-name matching.** Fields whose names look secret-ish (`token`, `key`,
   `nonce`, `hash`, `authorization`, …) are dropped entirely.
2. **Value shaping.** High-entropy strings are truncated to a short correlatable
   prefix; free-form messages are length-capped and stripped of control characters.

Never logged: raw audio, transcript contents, keys, nonces, authentication tokens,
upload credentials, activation-phrase recordings, or segment plaintext.

Recording identifiers *are* logged, but truncated (`8d2f0c9a…`). They are needed to
correlate a support report with an evidence package, and they carry no
personal data.

There is a `RecordingLogSink` that applies the same redaction as production,
which lets a test assert "no secret ever reaches a sink" as a property rather
than a code-review promise.

---

## 6. Platform compliance

SafeSignal does not attempt to defeat Android's privacy or power controls.

| Prohibited | SafeSignal's position |
| --- | --- |
| Hiding the microphone indicator | Never attempted; Android's indicator is left alone |
| Hiding the foreground service | The notification is always visible and always says what is happening |
| Bypassing FGS background-start rules | Arming happens through a user-visible foreground action |
| AccessibilityService for microphone access | Never used |
| Device administrator | Never used |
| Overlay abuse / tapjacking | Never used |
| Boot-time microphone activation | Never attempted |
| OEM exploits, root, system privileges | Never used |

**AccessibilityService deserves a specific note.** It could be used to detect the
wake word outside the microphone, or to survive background restrictions. Both
would make SafeSignal a surveillance tool operated through a permission the user
grants for a different reason. It is never used, and adding it requires a
specification change plus a fresh security review.

---

## 7. Filesystem and export

- Evidence lives in **app-private internal storage**. It is excluded from backup
  (a restored backup has no Keystore key, so it would be unreadable ciphertext
  anyway) and from the media scanner.
- Segment commit is atomic: `.tmp` → `fsync` → `rename` → `fsync` directory.
  Committed segments are never overwritten.
- Startup reconciliation quarantines rather than deletes anything it cannot prove
  is worthless.
- Export requires explicit confirmation and warns that sharing the package may
  expose sensitive information.
- Recording identifiers are 128-bit CSPRNG values with no timestamp component, so
  an id in a URL or log reveals neither the recording count nor its time.

---

## 8. Screenshot and lock-screen exposure

Sensitive recording details should not leak through the recents screen, lock
screen or notification. The notification states *that* SafeSignal is recording and
offers a stop action; it does not show audio content, recording titles derived from
transcripts, or any recording detail. Whether to additionally block screenshots
and recents previews is a user-configurable setting, and the default should favour
privacy over convenience.

*Status: not yet implemented. Flagged in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).*

---

## 9. Security review checklist

Run before any release. Items are unchecked because they have not been performed
against a built, running application.

**Authorisation of activation**
- [ ] No activation path exists without an explicit prior arming action
- [ ] Activation cannot be triggered remotely
- [ ] Rejected activations do not leak audio
- [ ] Test-activation recordings can never be presented as evidence

**Cryptography**
- [x] Single-bit tampering in ciphertext fails closed (unit-tested)
- [x] Wrong key fails closed (unit-tested)
- [x] Truncation fails closed (unit-tested)
- [x] Segment relocation fails closed (unit-tested)
- [x] Manifest tampering invalidates the signature (unit-tested)
- [x] AAD field boundaries are unambiguous (unit-tested)
- [ ] No plaintext key, nonce or transcript reaches any sink (needs the
      end-to-end log capture test)
- [ ] Key rotation is exercised against a populated database

**Privacy**
- [ ] No audio is persisted before activation unless pre-roll is explicitly enabled
- [ ] Pre-roll is bounded and RAM-only in a memory trace
- [ ] No analytics transmit recording identifiers
- [ ] Screenshots and recents previews behave as documented
- [ ] Notifications leak no recording content

**Storage and recovery**
- [x] Committed segments survive process death (unit-tested)
- [x] Interrupted commits are not counted as evidence (unit-tested)
- [x] Corrupt segments are quarantined, not deleted (unit-tested)
- [x] Sequence gaps are detectable (unit-tested)
- [ ] Recovery from a genuine process kill on a device

**Platform**
- [ ] Foreground-service behaviour verified on Android 12 through the newest
      available release
- [ ] Microphone contention (call, another app) detected and reported
- [ ] Notification stop action verified under a locked screen
- [ ] No OEM battery manager silently prevents re-arming without telling the user

**Honesty**
- [ ] No user-facing string implies guaranteed admissibility
- [ ] No user-facing string implies capture beyond the microphone's physical reach
- [ ] Wake-word accuracy figures are measured and published, including failures
- [ ] Known limitations are shipped with the app, not only in this repository