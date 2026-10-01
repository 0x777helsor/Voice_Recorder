# Local Toolchain Setup

Exact steps used to build this project in a restricted Linux environment, without
root and with a partially blocked network. Most developers will not need this —
a normal Android Studio install provides everything. It is recorded because the
constraints here are unusual and would otherwise have to be rediscovered.

---

## What the build requires

| Requirement | Version | Notes |
| --- | --- | --- |
| JDK | 17 | AGP 8.13 requires 17+ |
| Gradle | 8.13 | Wrapper provided |
| Android SDK platform | 36 | `compileSdk`/`targetSdk` |
| Build tools | 36.0.0 | |
| Android Gradle Plugin | 8.13.2 | |
| Kotlin | 2.2.21 | |
| KSP | 2.2.21-2.0.4 | Must match the Kotlin version |
| Hilt | 2.57.2 | Newest line supporting AGP 8.x |

### Two version pins that will bite you

**Hilt ≥ 2.60 requires AGP ≥ 9.0.** Hilt 2.60.1 fails with:

```
The Hilt Android Gradle plugin is only compatible with Android Gradle plugin
(AGP) version 9.0.0 or higher
```

If you raise AGP, raise Hilt in the same change.

**A recent Compose BOM forces a newer lifecycle, which requires AGP 9.1.** Compose
BOM `2026.06.01` constrains `lifecycle-viewmodel-compose` to 2.11.0, which
requires AGP 9.1. With AGP 8.13 this fails `checkDebugAarMetadata`. This project
uses BOM `2025.10.01`, which is compatible.

---

## Network reachability

In the environment this was built in, the network was **selectively** blocked.
Worth knowing, because a "network is down" assumption produces the wrong
diagnosis:

| Host | Reachable |
| --- | --- |
| `repo.maven.apache.org` | yes |
| `dl.google.com/dl/android/maven2` (Google Maven) | yes |
| `dl.google.com/android/repository` (SDK packages) | yes |
| `downloads.gradle.org` (Gradle CDN) | yes |
| `plugins.gradle.org` | yes |
| `services.gradle.org` | **no** |
| `registry.npmjs.org` | **no** |
| `github.com` | **no** |

Two consequences:

1. `maven.google.com` **redirects** (HTTP 301) to `dl.google.com/dl/android/maven2/`.
   A `curl` without `-L` reports a 301 and looks like a missing artifact, which
   reads exactly like "this version does not exist". Use `-L` when probing.
2. `services.gradle.org` is blocked but `downloads.gradle.org` serves the same
   distribution files. If `./gradlew` cannot bootstrap, download
   `gradle-8.13-bin.zip` from `downloads.gradle.org` and run the `gradle` binary
   from the extracted distribution directly.

---

## The JDK truststore trap

This cost real time and is easy to misdiagnose as "no network".

A JDK extracted manually from a `.deb` (rather than installed with a package
manager) has `lib/security/cacerts` as a **symlink to
`/etc/ssl/certs/java/cacerts`**, which does not exist until
`ca-certificates-java` post-install runs. Java then fails every TLS request with:

```
SSLException: (internal_error) Unexpected error:
java.security.InvalidAlgorithmParameterException: the trustAnchors parameter must be non-empty
```

Gradle reports this as `Plugin [id: ...] was not found`, which sends you hunting
for a version problem instead of a TLS one. `curl` works fine, because it uses the
system CA bundle — that asymmetry is the giveaway.

**Fix** — build a JKS truststore from the system bundle:

```bash
csplit -z -f cert- -b "%03d.pem" /etc/ssl/certs/ca-certificates.crt \
  '/-----BEGIN CERTIFICATE-----/' '{*}'
i=0
for f in cert-*.pem; do
  [ -f "$f" ] || continue
  i=$((i+1))
  keytool -importcert -noprompt -alias "ca-$i" -file "$f" \
    -keystore "$HOME/certs/cacerts" -storepass changeit >/dev/null 2>&1
done

export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/certs/cacerts -Djavax.net.ssl.trustStorePassword=changeit"
```

Verify before building:

```bash
java -Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=changeit \
  -version   # or a two-line HTTP probe
```

A normal Android Studio JDK is not affected.

---

## Android SDK install

```bash
yes | sdkmanager --licenses
yes | sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Two notes that cost time:

- **Licenses must be piped into the install command too**, not just accepted
  once. Accepting them in a separate invocation can leave them unwritten, and the
  install then fails with `Skipping following packages as the license is not
  accepted`.
- Set `ANDROID_HOME`/`ANDROID_SDK_ROOT` **or** write `local.properties`. If
  `local.properties` exists with a stale `sdk.dir`, the build fails with
  `sdk.dir property in local.properties file. Problem: Directory does not exist`
  even when the SDK is installed and `ANDROID_HOME` is correct.

---

## Commands

```bash
./gradlew test           # unit tests
./gradlew lint           # Android Lint (abortOnError = true)
./gradlew assembleDebug  # debug APK
./gradlew assembleRelease
```

If the wrapper cannot bootstrap in a restricted network, use an extracted
Gradle distribution directly:

```bash
"$GRADLE_HOME/bin/gradle" test
```

---

## Current test coverage

| Module | Tests |
| --- | --- |
| `core:common` | 52 |
| `data:local` | 42 |
| `core:crypto` | 30 |
| `audio:processing` | 26 |
| **Total** | **150**, 0 failures |

All are JVM unit tests. There is **no instrumentation test coverage**, because
there is no emulator or device in this environment. See
[KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md).