# Android Compatibility Matrix

Behaviour that differs across Android versions, and how SafeSignal handles each.
The project's build targets `compileSdk 36` with `minSdk 26`.

This matrix is maintained alongside the code because Android's foreground-service
and permission rules are a **first-class architectural constraint**, not a detail.
The specification is explicit: SafeSignal must start the microphone service
through an Android-permitted, user-visible path and must never attempt to bypass
those restrictions.

---

## 1. Version coverage

| Android | API | Status |
| --- | --- | --- |
| 8.0 Oreo | 26 | minimumSdk — built and unit-tested on the JVM |
| 8.1 | 27 | not verified on device |
| 9 Pie | 28 | not verified on device |
| 10 | 29 | not verified on device |
| 11 | 30 | not verified on device |
| 12L | 32 | **behaviourally significant**, see §2 |
| 12 | 31 | **behaviourally significant**, see §2 |
| 13 | 33 | notification permission, see §3 |
| 14 | 34 | **behaviourally significant**, see §4 |
| 15 | 35 | not verified on device |
| 16 | 36 | `compileSdk`/`targetSdk` |
| 17 | 37 | available in the SDK; **not targeted** |

**Nothing has been run on a device.** "Built and unit-tested on the JVM" means
compiled and exercised by JVM unit tests only. See
[KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) § 1.

---

## 2. Android 12 (API 31–32) — foreground service starts

**Platform change.** Apps may no longer start a foreground service from the
background. `ForegroundServiceStartNotAllowedException` is thrown if they try.

**SafeSignal's response.** Emergency Listening is armed through an explicit user
action while the app is in the foreground. There is no path that starts the
microphone service from the background, and none will be added.

**Consequence for the user.** If the service is killed by an OEM battery manager,
re-arming requires opening the app. SafeSignal does not attempt to restart itself
silently, and explains why.

---

## 3. Android 13 (API 33) — notification permission

**Platform change.** `POST_NOTIFICATIONS` became a runtime permission. A
foreground service can start without it, but its notification is not shown.

**Why this matters here specifically.** SafeSignal's persistent notification is
part of its safety contract — it is how the user knows the microphone is in use
and how they stop recording. A missing notification would undermine the "never
covert" guarantee.

**SafeSignal's response.** The permission is requested at the moment the user
arms listening, with an explanation. If denied, SafeSignal warns that the stop
control will not be visible and requires the user to proceed knowingly.

---

## 4. Android 14 (API 34) — microphone foreground service type

**Platform change.** A foreground service using the microphone must declare the
`microphone` service type and hold `FOREGROUND_SERVICE_MICROPHONE`, and while-in-use
conditions apply.

**SafeSignal's response.**

- The manifest declares `android:foregroundServiceType="microphone"` on the
  listening service.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` are declared.
- `RECORD_AUDIO` is requested contextually during onboarding, before arming.
- Promotion to foreground happens inside `startForeground()` immediately, before
  capture begins.
- `SecurityException` and `ForegroundServiceStartNotAllowedException` are caught
  and reported to the user in plain language, rather than crashing.

**Separation of service types.** Recording uses the `microphone` type.
Synchronisation, when implemented, uses `dataSync`. SafeSignal deliberately does
**not** use a data-sync service to hold the microphone: conflating them would make
a network problem look like a capture problem, which is exactly the confusion the
state machine exists to prevent.

---

## 5. Android 15–17

`compileSdk`/`targetSdk` is 36. API 37 is present in the available SDK but is not
targeted.

**Position.** SafeSignal does not assume behaviour from an older Android version
applies to a newer one. Before raising `compileSdk`/`targetSdk`, the foreground
service, permission and background-execution behaviour of the new release must be
re-checked against current platform documentation rather than assumed. Newer
restrictions are to be accommodated with a user-visible path, never circumvented.

---

## 6. Per-version verification status

| Behaviour | Verified? |
| --- | --- |
| Microphone capture at configured rate/channels | **Yes** — 48 kHz mono, 12 s, 3 segments, Tecno BF7 (API 31) |
| Keystore wrapping, StrongBox presence | **Yes** — both test phones lack StrongBox; fallback exercised |
| Foreground service starts and releases the microphone | **Yes** — both test phones |
| Notification visibility and stop action | **Yes** — `isForeground=true`, channel present, `vis=PUBLIC`, one action |
| Foreground service survives screen-off / lock | **No** |
| Android 14+ `foregroundServiceType` enforcement | **No** — no API 34+ hardware |
| Background-started microphone FGS refusal | **No** — no API 34+ hardware |
| Microphone contention detection (calls, other apps) | **No** |
| Audio focus and Bluetooth routing changes | **No** |
| Battery and thermal behaviour | **No** |
| OEM battery managers terminating the service | **No** |
| Process-death recovery on device | **No** — JVM unit tests only |
| Keystore key invalidation by a lock-screen change | **No** |

---

## 7. OEM variance

Battery management varies significantly between manufacturers. Some aggressively
terminate long-running foreground services; others restrict which apps may hold
the microphone in the background.

SafeSignal's position:

1. Do not attempt to bypass power management. No wake-lock abuse, no
   manufacturer exploits, no undocumented APIs.
2. Provide honest user guidance through legitimate Android settings flows, if the
   user chooses to open them.
3. Detect that listening has stopped and say so, rather than continuing to display
   an "armed" state that is no longer true.

A user who disables battery optimisation may get longer listening sessions. That
is their choice, made through a legitimate settings screen, and it is not a
guarantee: SafeSignal does not promise indefinite operation.

---

## 8. Hardware-dependent triggers

| Trigger | Availability |
| --- | --- |
| Notification action | Universal — implemented as design, not yet built |
| Home-screen widget | Universal — not yet built |
| Media/headset button | Device-dependent; must not be promised |
| Volume-key gesture | Device-dependent; must not be promised |

Where a mechanism is unavailable, SafeSignal must display:

> "This trigger is not supported on this device."

It must never emulate missing hardware behaviour through an accessibility service
or a hidden overlay.