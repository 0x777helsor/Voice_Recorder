# Privacy

A plain description of what SafeSignal does with data, when, and why. Written to
be read by a user, not only by a lawyer.

This is a **draft** describing the intended behaviour of the software. Parts of the
feature set it describes are not yet implemented; [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)
says which.

---

## The short version

SafeSignal records audio, which is about as sensitive as data gets. It does so
only after you deliberately switch Emergency Listening on. It listens for your
phrase **on the device** and never sends ambient audio anywhere to check for it.
It encrypts recordings on the device. It does not collect your location, your
contacts, your messages, or anything else about you.

---

## 1. What is collected

| Data | Why | When |
| --- | --- | --- |
| Recorded audio | The thing you asked it to preserve | Only after activation, while recording |
| Recording metadata | Describe the recording honestly | At recording time |
| Activation method and confidence | Show *how* recording started | At activation |
| App version, encryption version, device timezone | Reproducibility of the evidence package | At recording time |

### What is **never** collected

- Location
- Contacts
- SMS or call logs
- Camera, photos, video
- Browsing history or other app activity
- Device identifiers, advertising identifiers, IMEI, ad ID
- Microphone audio while merely armed, unless you enable pre-roll (see §4)
- Ambient audio used by the wake-word detector

The strongest enforcement for the "never" list is that there are **no columns**
for these in the database and no code paths that read them. Adding one would
require deliberately adding a field, a permission and a review — it is not a
matter of configuration.

---

## 2. The activation timeline

```
 you arm Emergency Listening
        |
        v
 device listens locally for your phrase   <-- audio analysed on device, not stored
        |
        |  your phrase is recognised
        v
 recording begins                          <-- audio now captured and encrypted
        |
        v
 recording stops
        |
        v
 recording sealed, hashed, kept on device
        |
        v
 (only if you enabled it) encrypted upload
```

### While armed, before activation

SafeSignal runs a local detector on microphone audio. That audio is analysed in
memory and **discarded**. It is not written to storage, not sent anywhere, and not
retained after analysis. The detector emits a confidence score and nothing else.

If you enable pre-roll (§4), a few seconds of audio are held in a **bounded RAM
buffer** — still not written to disk, and discarded if you do not activate.

### At activation

The detector emits an event: phrase identifier, confidence, monotonic timestamp,
wall-clock timestamp, engine version. **No audio is included**, because the event
type has no field capable of holding it.

Recording then begins, and audio is encrypted on the device within milliseconds.

---

## 3. Where audio goes

| Stage | Location | Protection |
| --- | --- | --- |
| Captured | Microphone → memory | — |
| Segment being written | Device RAM | Unencrypted briefly, then sealed |
| Sealed segments | App-private internal storage | AES-256-GCM |
| On your server (if enabled) | Your storage backend | AES-256-GCM — the server cannot decrypt it |

The backend **never receives a decryption key**. It stores ciphertext. A
compromised server cannot turn your recordings into audio.

### Temporary plaintext

Between `AudioRecord.read()` returning and the frame being sealed, audio exists
unencrypted in memory. This window is short and unavoidable — the audio has to be
processed before it can be encrypted. It is not written to disk unencrypted at any
point, and there is no swap file or cache copy.

---

## 4. Pre-roll

**Off by default.** You must actively choose 5, 10 or 15 seconds.

Pre-roll holds the last few seconds of audio *before* activation in a RAM buffer,
so that if an incident has already started when you manage to say the phrase, the
beginning is captured.

**What it costs you:** audio from before you asked for a recording is briefly held
in memory. It is never written to disk unless activation happens, and it is
discarded immediately if activation does not happen.

**Memory cost:** about 480 KB for 5 seconds, 1.4 MB for 15 seconds, at 48 kHz mono
16-bit. Bounded and fixed; it cannot grow.

**Why it is off by default:** capturing audio before the user asked is a genuine
privacy cost. It should be a choice, not a default.

---

## 5. Synchronisation

Off by default in Phase 1; the client is not built yet.

If you enable it, only **already-encrypted** segments are uploaded, over TLS, to a
server you configure. The server receives no key and cannot decrypt anything.

The local copy is **kept** after a successful upload by default. SafeSignal does
not delete your only copy of evidence automatically.

You can delete the local copy, the remote copy, or both, with explicit confirmation.
When the server confirms a remote deletion, the app says the remote copy is
deleted. It never claims a deletion the server did not confirm.

---

## 6. Enhancement and transcription

Neither is implemented. Both are **derivative** processing, and if implemented:

- Enhancement writes a **separate** file. The original is never overwritten.
- The interface always labels one thing "Original" and the other "Enhanced
  derivative", because presenting an enhanced copy as the original would be a lie
  about evidence.
- Enhancement is off by default.
- Transcription is off by default. A transcript is machine-generated and does not
  replace the audio.
- Cloud transcription requires a separate, explicit opt-in. SafeSignal will never
  silently send audio to an AI provider.

---

## 7. On-device versus cloud

The activation mechanism is **entirely on-device**.

SafeSignal does not use a cloud speech recogniser to check for your phrase, because
that would mean streaming every room you are in to a server just in case you might
need to say a word. That trade-off is unacceptable for this product.

The wake-word engine in this repository is a local signal-processing matcher. It is
not as accurate as a dedicated trained model, and its accuracy has not been
measured. See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

---

## 8. Analytics

SafeSignal has **no** analytics, no crash reporting service, and no telemetry.

If any were added, they must not contain audio, transcripts, or recording
identifiers, and would be disclosed here first. There is deliberately no analytics
SDK in the dependency list.

---

## 9. Retention

Default: **keep on this device**, indefinitely.

Optional policies (user-configured, never applied silently):

- keep indefinitely
- delete after N days
- manual deletion only

Automatic deletion requires an explicit policy the user chose. SafeSignal will
never delete local evidence immediately after a successful upload.

---

## 10. Account recovery — the hard trade-off

If you lose your device, you may lose any recording that has not been synchronised.

This is deliberate. SafeSignal does **not** provide a "forgot my key" recovery
path, because such a path requires someone to be able to decrypt your audio — and
that someone would then hold your recordings in the clear.

Concretely:

| Situation | Outcome |
| --- | --- |
| Device lost, recordings not uploaded | Recordings unrecoverable |
| Device lost, recordings uploaded | Recordings still unreadable — the server has no key |
| Credentials lost | Credentials can be reset; unsynced recordings still unreadable |
| Backend compromised | Attacker gets ciphertext, not audio |
| Device factory-reset | Keystore key destroyed; local recordings unreadable |

This is a genuine limitation of end-to-end encryption, not an oversight. If you
need recordings to survive device loss, enable synchronisation **and** keep a
backup of your device's Keystore material under your own control — SafeSignal
cannot do this for you.

---

## 11. Your rights

Because SafeSignal stores everything on your own device and, if you enable it,
on a server you operate, you are in control:

- you can export an evidence package and delete the original;
- you can delete local, remote, or both;
- you can disconnect your account;
- deleting the app removes local data.

The backend stores no plaintext audio, so there is no separate audio store to
request the deletion of.

---

## 12. A note on recording other people

SafeSignal's microphone captures whatever it can hear. If you use it in a
situation involving other people, recording them may require their consent or may
be otherwise restricted where you are.

**SafeSignal does not make recording lawful.** See
[LEGAL_DISCLAIMER.md](LEGAL_DISCLAIMER.md).