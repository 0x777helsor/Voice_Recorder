# Deployment

Covers building release artefacts today, and the deployment obligations that will
apply once a backend exists.

---

## 1. Building a release

```bash
./gradlew clean test          # must be green first
./gradlew lint                # abortOnError = true
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk` (unsigned until a signing
config is provided).

### Release build characteristics

| Setting | Value | Reason |
| --- | --- | --- |
| `minifyEnabled` | `true` | R8/ProGuard |
| `shrinkResources` | `true` | Remove unused resources |
| `versionName` | `0.1.0` | Phase 1 |
| `applicationIdSuffix` | `.debug` **debug only** | Debug and release must never share an id |

### R8 and evidence

`proguard-rules.pro` keeps what evidence correctness depends on:

- Room-generated implementations, referenced by name;
- model classes used for (de)serialisation;
- `javax.crypto` and `java.security` provider surfaces, referenced by string.

**This matters more than usual.** A recording sealed by one version must still
decrypt and verify after an app upgrade. A rule that strips a class needed to read
a two-year-old evidence package would not produce a crash — it would produce
silently unreadable evidence.

Keep-alive rules are therefore deliberately generous. Verify by decrypting a
recording from a previous release after upgrading a release build before shipping.

---

## 2. Signing

**No signing material is committed, and none should be.**

```properties
# ~/.gradle/gradle.properties  (outside the repository)
SAFESIGNAL_STORE_FILE=/absolute/path/to/release.jks
SAFESIGNAL_STORE_PASSWORD=...
SAFESIGNAL_KEY_ALIAS=...
SAFESIGNAL_KEY_PASSWORD=...
```

Referenced from `app/build.gradle.kts` only when those properties are present, so a
developer build does not require them.

Requirements:

- keep the keystore offline, backed up in at least two places, and never in the
  repository;
- losing the signing key means the app can no longer be updated in place;
- **the signing key is not the evidence key.** They are unrelated. Losing the
  signing key does not make recordings unreadable; losing the Android Keystore key
  on a user's device does.

---

## 3. Provenance

Not implemented, and worth adding before any public release:

- reproducible builds (fixed timestamps, sorted inputs);
- verifiable builds, so a distributed APK can be shown to match its source;
- an SBOM of dependencies;
- release signing with transparency logging.

Currently, a user must trust that the APK they installed came from this source.
For an app that claims evidence-integrity properties, that is an uncomfortable
gap and is recorded in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

---

## 4. Distribution

SafeSignal handles sensitive recordings and has no legitimate reason to be
pre-installed on devices the user did not choose to install it on.

- Distribute as a signed APK / AAB through a source the user controls.
- No third-party store tracking SDKs (there are none).
- Consider Play Protect and standard app-verification expectations.

---

## 5. Permissions and store review

The app declares exactly four functional permission groups:

| Permission | Why | User-facing justification |
| --- | --- | --- |
| `RECORD_AUDIO` | Listen for the phrase; record after activation | Requested at onboarding with a full explanation |
| `POST_NOTIFICATIONS` | Persistent visibility of listening/recording state | Requested when arming |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Hold the microphone legally | Declared, not requested |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Synchronisation scheduling only | Declared, not requested |

**Never requested:** contacts, SMS, location, camera, accessibility, device
administrator. Adding any of them requires a specification change and a fresh
security review. See [SECURITY.md](SECURITY.md) § 6.

Store reviewers unfamiliar with an emergency-recording app will reasonably ask
about abuse potential. The honest answer is in
[THREAT_MODEL.md](THREAT_MODEL.md) § T4, including the accepted risk that voice
activation is not identity verification.

---

## 6. Backend deployment

Nothing to deploy yet. When a backend exists, the requirements are:

| Area | Requirement |
| --- | --- |
| TLS | Required; HSTS; no cleartext fallback |
| Object storage | Private; no public read path; no long-lived presigned URLs |
| Secrets | Secret manager; never in images, compose files or environment dumps |
| Database | Backed up — it holds integrity metadata that is not reproducible from the audio |
| Audit log | Append-only; access and deletion events recorded with actor and time |
| Migrations | Versioned and reversible; run before any client that depends on them |
| Deletion | Document that backup retention limits deletion guarantees — deleting from the primary bucket does not necessarily delete from backups |

`docker-compose.yml` for local backend development is not yet written.

---

## 7. Release checklist

Run before any release. Anything unticked must be reported as untested, not
assumed.

**Build and quality**
- [ ] `./gradlew test` green
- [ ] `./gradlew lint` clean
- [ ] `./gradlew assembleRelease` succeeds
- [ ] A recording sealed by the previous release still decrypts and verifies after upgrading
- [ ] R8 does not strip anything the evidence path needs

**Safety properties**
- [ ] Recording never begins without an explicit arming action
- [ ] Foreground notification is visible, accurate, and offers a stop action
- [ ] Android's microphone privacy indicator is left alone
- [ ] No accessibility service, device administrator, or overlay in the manifest
- [ ] No emergency-service dispatch in the MVP build

**Honesty**
- [ ] No string implies guaranteed admissibility
- [ ] No string implies capture beyond the microphone's physical reach
- [ ] Wake-word accuracy figures published, **including failures**
- [ ] Known limitations shipped with the app

**Verification status**
- [ ] Foreground-service behaviour checked on Android 12, 13, 14, 15, 16
- [ ] Microphone contention (call, another app) detected and reported
- [ ] Process-death recovery checked on a real device
- [ ] Any test that could not be run is listed explicitly as not run

**Do not ship if** any safety property is unverified. A build that cannot record
is a disappointing app; a build that records covertly is a different product
entirely.