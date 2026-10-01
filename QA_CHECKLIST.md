# QA Checklist

Manual verification for SafeSignal. Nothing here has been executed — this is the
plan, and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) says so plainly.

Fill in actual results, including failures. A matrix with honest "failed" rows is
worth far more than one with optimistic blanks, and the wake-word matrix in
particular is required to report bad results.

---

## 1. First run and permissions

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 1.1 | Fresh install | No microphone prompt at install; app opens to onboarding | |
| 1.2 | Deny microphone permission | Clear explanation; app still usable; readiness shows not ready | |
| 1.3 | Grant microphone | Readiness advances | |
| 1.4 | Deny notifications | Arming warns that the stop control will not be visible; user may proceed knowingly | |
| 1.5 | Revoke microphone in Settings while armed | SafeSignal reports microphone unavailable; no silent "recording" | |
| 1.6 | Request "don't ask again" | Deep-links to app settings with an explanation | |

**Critical check:** after a fresh install and before any arming action, the
microphone must not be opened. Verify with `adb shell dumpsys media.audio_flinger`
or the Android privacy indicator.

---

## 2. Arming and visibility

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 2.1 | Enable Emergency Listening while app is foreground | Foreground notification appears immediately | |
| 2.2 | Notification content | Says "Emergency Listening Active"; no misleading text | |
| 2.3 | Android privacy indicator | Microphone indicator visible in status bar | |
| 2.4 | Lock screen | Notification visible with listening state | |
| 2.5 | Stop action from notification | Stops recording; state updates | |
| 2.6 | Disable listening | Microphone released; indicator clears | |

**Never acceptable:** hidden notification, hidden privacy indicator, or a service
that cannot be stopped by the user.

---

## 3. Recording lifecycle

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 3.1 | Activate via test trigger | Recording starts within one frame | |
| 3.2 | Record 60 s | Multiple segments, each independently sealed | |
| 3.3 | Stop via notification | Finalizes: hashes, manifest, state update | |
| 3.4 | Stop via in-app button | Identical outcome | |
| 3.5 | Reach maximum duration | Finalizes safely; user notified | |
| 3.6 | Play back original | Audio is intelligible and matches what was said | |
| 3.7 | Verify integrity | Manifest verifies; no issues reported | |
| 3.8 | Kill app during recording | Committed segments survive; next launch reconciles | |

---

## 4. Crash and process-death recovery

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 4.1 | `adb shell am force-stop` mid-recording | Committed segments preserved | |
| 4.2 | Kill the process between write and rename | `.tmp` discarded, never counted as evidence | |
| 4.3 | Reboot device | No automatic microphone capture; UI explains re-arming is required | |
| 4.4 | Corrupt one segment byte | Detected; quarantined, never deleted | |
| 4.5 | Delete a segment file | Detected as missing; reported | |
| 4.6 | Swap two segment files | Detected as reordered or failing authentication | |
| 4.7 | Low storage during recording | Finalizes before exhaustion; evidence preserved; user warned | |

---

## 5. Audio environment matrix

**Purpose:** establish what the microphone actually captures. Do **not** infer
general capability from a single favourable result.

| Condition | Result | Notes |
| --- | --- | --- |
| Quiet room, 30 cm | | |
| Quiet room, 1 m | | |
| Crowded room | | |
| Street traffic | | |
| Wind | | |
| Two speakers overlapping | | |
| Whispering | | |
| Normal speech | | |
| Shouting | | |
| Phone in a pocket | | |
| Phone in a bag | | |
| Phone face-down | | |
| Phone face-up | | |
| Phone several metres away | | |
| Bluetooth headset | | |
| Headset disconnected mid-recording | | |
| Another app using the microphone | | |
| Incoming call during recording | | |
| Low battery | | |
| Battery saver on | | |
| Thermal throttling | | |
| Screen locked | | |
| No internet | | |
| Poor internet | | |
| Storage nearly full | | |

Report intelligibility and any dropouts. State plainly that no result here
establishes capability at any unmeasured distance or through any obstruction.

---

## 6. Wake-word matrix

**Required reporting** (SPEC §64): false-acceptance rate, false-rejection rate,
detection latency, battery cost — including the cases where performance is poor.

| Condition | True activations | False activations | Missed | Latency | Notes |
| --- | --- | --- | --- | --- | --- |
| Quiet room, close | | | | | |
| Quiet room, 1 m | | | | | |
| Crowded room | | | | | |
| Traffic | | | | | |
| Multiple speakers | | | | | |
| Accent variation | | | | | |
| Whispered phrase | | | | | |
| Similar phrase ("record this now") | | | | | |
| Television speech | | | | | |
| Radio speech | | | | | |
| Music | | | | | |
| Background conversation | | | | | |
| Phone in pocket | | | | | |
| Battery saver on | | | | | |
| Low-power mode | | | | | |

**Expect poor results in the bottom half of this table with the shipped
signal-processing engine.** Report them. A false-acceptance figure high enough to
trigger unwanted recordings is a privacy harm and must be disclosed, not smoothed
over.

---

## 7. Interruptions and contention

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 7.1 | Incoming call | Contention detected; committed segments preserved; failure reported | |
| 7.2 | Another app opens the microphone | Same | |
| 7.3 | Bluetooth route change | Detected; recording continues or fails clearly | |
| 7.4 | Audio focus stolen | Handled without losing committed segments | |
| 7.5 | Microphone permission revoked mid-recording | Reported; already-captured audio preserved | |

**Never acceptable:** a recording that silently continues producing silence while
reporting that it is recording.

---

## 8. Encryption and integrity

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 8.1 | Inspect stored files | Files are `SSEG`-framed ciphertext; no WAV visible | |
| 8.2 | Search app storage for plaintext audio | None found | |
| 8.3 | Flip one ciphertext byte | Decryption fails closed | |
| 8.4 | Truncate a segment | Fails closed | |
| 8.5 | Swap segments | Fails authentication | |
| 8.6 | Alter the manifest | Signature verification fails | |
| 8.7 | Alter a manifest checksum | Verification fails | |
| 8.8 | Confirm no key in SharedPreferences, logs or DB columns | Confirmed | |

---

## 9. Privacy verification

| # | Step | Expected | Result |
| --- | --- | --- | --- |
| 9.1 | Armed for 10 min without activation | No audio file created | |
| 9.2 | Pre-roll disabled | No pre-activation audio anywhere on disk | |
| 9.3 | Pre-roll enabled, armed without activating | Audio held in RAM only; nothing on disk | |
| 9.4 | Capture logcat during a full session | No key, token, transcript or audio content | |
| 9.5 | Inspect notification content | No recording content leaked | |
| 9.6 | Inspect recents preview | No recording content | |
| 9.7 | Attempt adb backup | Evidence excluded | |
| 9.8 | Verify no analytics SDK present | Dependency list clean | |

---

## 10. Failure-mode verification

| Failure | Expected behaviour | Result |
| --- | --- | --- |
| Microphone denied | Report; no recording claimed | |
| Keystore invalidated | Report; evidence left encrypted, not deleted | |
| Storage exhausted | Finalize safely; preserve; warn | |
| Encryption failure | Fail closed; no plaintext written | |
| Wake-word engine fails to load | Physical/test trigger still available | |
| Network down before recording | **Activation and recording still work** | |
| Network down during recording | **Recording continues** | |
| Backend unreachable | Retry later; local copy untouched | |
| Process killed during finalize | Reconciliation recovers or reports | |

---

## 11. Full acceptance scenario

The end-to-end path from SPEC §107:

```text
open app -> grant microphone -> complete onboarding -> run SafeSignal test
  -> test succeeds -> enable Emergency Listening -> notification appears
  -> wake-word engine active -> DISABLE INTERNET -> say the activation phrase
  -> activation occurs locally -> pre-roll preserved (if enabled)
  -> recording starts -> audio is segmented -> each segment encrypted
  -> recording continues without internet -> stop recording
  -> recording finalized -> SHA-256 manifest generated
  -> RESTORE INTERNET -> chunks enter upload queue (Phase 6)
```

Steps up to "recording finalized" are implemented today. The synchronisation
steps are not implemented and must not be marked as passing.

---

## Result summary

| Area | Pass | Fail | Not run |
| --- | --- | --- | --- |
| Permissions | | | |
| Visibility | | | |
| Recording lifecycle | | | |
| Crash recovery | | | |
| Audio environments | | | |
| Wake word | | | |
| Interruptions | | | |
| Encryption | | | |
| Privacy | | | |
| Failure modes | | | |
| Acceptance scenario | | | |

**Not run** is an acceptable and honest entry. A fabricated pass is not.