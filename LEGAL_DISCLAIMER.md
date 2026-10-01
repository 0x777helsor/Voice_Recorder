# Legal Disclaimer

**Read this before using SafeSignal to record anyone.**

---

## 1. SafeSignal does not make recording lawful

Recording audio is regulated differently in every jurisdiction. Depending on
where you are, recording a conversation you are part of may be:

- entirely unrestricted;
- permitted only where all parties consent;
- permitted only if you are a participant and the recording is for a specific
  purpose;
- restricted even where you are a participant;
- subject to notification requirements;
- an offence in some circumstances, including where a recording is later
  published.

**You are responsible for understanding and complying with the laws that apply
where you are, in every place where you use SafeSignal.** SafeSignal does not
provide jurisdiction-specific legal conclusions, because doing so reliably would
require legal advice across many jurisdictions and would be wrong in at least one
of them.

If you are unsure whether a particular recording is lawful where you are, consult
a qualified legal professional before making it.

---

## 2. SafeSignal does not produce guaranteed admissible evidence

SafeSignal uses **integrity-preserving storage and export mechanisms**:

- recorded audio is encrypted with AES-256-GCM, with each segment bound to its
  position and recording identity;
- a SHA-256 manifest records every segment's hash, length and order;
- the manifest is signed with a device-held key, and the verification key travels
  inside the export package.

These establish that **the recording SafeSignal holds has not been altered since
it was captured**. That is a meaningful and testable property.

It is **not** the same as legal admissibility or evidentiary weight. Those depend
on matters entirely outside this software, including:

- the applicable jurisdiction and its evidentiary rules;
- whether the recording was obtained lawfully, and by a person lawfully entitled
  to hold it;
- the circumstances of capture — was the device tampered with? was the microphone
  obstructed or was the phone in a pocket?
- chain of custody and how the recording came to be examined;
- the authenticity requirements of the relevant authority or court;
- whether the recorder was identified with sufficient certainty;
- whether a certificate or expert statement is required.

**A signed manifest does not prove that the audio depicts what someone claims it
depicts, that it was recorded at a particular time, or that the person who made
the recording was entitled to make it.**

---

## 3. What a timestamp in SafeSignal does and does not establish

Every recording records both a **device wall-clock** time and a **device
monotonic** time, and a synchronised recording additionally has a **server**
receipt time. These are kept distinct on purpose.

- **Monotonic time** establishes ordering and duration within this device's
  session. It cannot be edited by the user, and it is what SafeSignal uses for
  durations and event ordering.
- **Wall-clock time** is displayed to users. It can be changed by the user, can
  drift, and can be reset. It is not independently authoritative.
- **Server time** establishes when *the server received* the data. It says
  nothing about when the audio was captured.

**None of these independently proves the real-world time of an event.** SafeSignal
never claims that they do, and no part of the product should be read as making
such a claim.

---

## 4. Physical limits of the recording

A phone microphone has physical limits. Distance, walls, wind, handling,
orientation, obstruction, competing sounds, room acoustics and speaker volume all
affect what is captured and whether speech is intelligible.

**Software cannot recover speech that never reached the microphone.** SafeSignal
makes no claim about capture at any particular distance or through any
obstruction, and the product contains no copy suggesting otherwise.

---

## 5. Your responsibilities

By using SafeSignal you accept that:

1. **You** decide when recording is appropriate and lawful.
2. **You** are responsible for the lawfulness of every recording you make.
3. Recording other people may require their knowledge or consent.
4. Publishing or sharing a recording may be restricted or unlawful even if the
   recording itself was lawful.
5. Evidence you collect with SafeSignal should be reviewed by a qualified legal
   professional before being relied upon in any legal or disciplinary proceeding.
6. You should preserve the **original** evidence package unmodified. SafeSignal
   produces derivative copies for enhancement and transcription; those are
   clearly labelled and are not the original.

---

## 6. No warranty

SafeSignal is provided as-is, without warranty of any kind, to the extent
permitted by law. In particular, no warranty is given that:

- recordings will be captured successfully;
- recordings will be intelligible;
- evidence will be preserved across device loss or reset;
- evidence will be accepted by any court or authority;
- the software complies with the law of any particular jurisdiction;
- the software is free of defects.

See [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) for what is currently
unimplemented and unverified. In particular, **nothing in this project has been
tested on a physical device**, and the wake-word engine's accuracy has not been
measured.

---

## 7. Privacy of others you record

SafeSignal is designed to minimise the data it holds about you. It cannot
minimise the exposure of other people whose voices you record — that exposure is
inherent to the microphone. Consider what is proportionate and lawful before
arming Emergency Listening in a shared or public space.

---

*This document is a plain-language summary, not legal advice, and is not a
substitute for advice from a qualified professional in your jurisdiction.*