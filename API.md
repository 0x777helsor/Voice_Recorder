# API and Backend

**Status: server implemented and tested; no Android client yet.** The backend in
[`backend/`](backend/) is complete — 93 tests, including 11 that hold
[OPENAPI.yaml](OPENAPI.yaml) to what the server actually does. **No Android code
calls it**: `data:remote` and `data:repository` are still empty modules, so the sync
path is designed and specified but not wired. An evidence product that silently
failed to sync would be worse than one that plainly had not been built yet, so the
distinction is kept sharp here.

The contract is described below and written out machine-readably in
[OPENAPI.yaml](OPENAPI.yaml), which is what a client integrator should read.

It was written before the client because the Android client's design is constrained
by the server contract, and the server contract is where most of the security
properties live. Building the client first against an unstated contract would mean
reworking it.

---

## 1. Non-negotiable property

```
PHONE                              SERVER
  |                                  |
  | original audio                    |
  v                                  |
local encryption                      |
  |                                  |
  v                                  |
encrypted chunk --------------------> | stores ciphertext only
  |                                  |
  |                    X  no key ever crosses this line
  v                                  |
trusted backend                      v
                                     object storage (ciphertext)
```

**The backend never receives a decryption key.** It stores bytes it cannot read.
This is why there is no "forgot my key" recovery path: a backend that could
recover plaintext audio is a backend that holds plaintext audio.

The consequence, stated plainly so nobody is surprised later: losing the device
keystore means losing unsynchronised recordings permanently. See
[PRIVACY.md](PRIVACY.md) § 10.

---

## 2. Endpoints

```text
POST   /auth/register
POST   /auth/login
POST   /auth/refresh
POST   /recordings
POST   /recordings/{id}/upload-session
POST   /recordings/{id}/chunks
POST   /recordings/{id}/finalize
GET    /recordings
GET    /recordings/{id}
GET    /recordings/{id}/download
DELETE /recordings/{id}
```

---

## 3. Upload flow

```text
1.  App authenticates (short-lived access token)
2.  POST /recordings                    -> server allocates recordingId
3.  POST /recordings/{id}/upload-session -> short-lived upload authorisation
4.  For each un-uploaded segment:
      POST /recordings/{id}/chunks      -> ciphertext + checksum + idempotency key
5.  Server validates each chunk's checksum before accepting it
6.  POST /recordings/{id}/finalize      -> submits the signed manifest
7.  Server verifies completeness: no gaps, no duplicates, hashes match
8.  Server marks the recording SYNCED and issues an immutable receipt
9.  App stores the receipt locally
```

Step 7 is where the server earns its keep: a client that uploaded three of four
segments must not be able to declare the package complete.

---

## 4. Idempotency

Every upload carries:

```text
recordingId + segmentId + sequenceNumber + checksum + idempotencyKey
```

The idempotency key is **derived deterministically** from the recording and the
operation, so a retry after a timeout presents the same key and the server
collapses it. It is never random per attempt — that would defeat the purpose.

The server must reject or safely ignore duplicates. **A network retry must never
produce a duplicate evidence segment.**

---

## 5. Security requirements

| Concern | Requirement |
| --- | --- |
| Transport | TLS only; no cleartext fallback |
| Authentication | Short-lived access tokens with refresh rotation |
| Authorisation | A user may access only their own recordings — enforced server-side on every path |
| Object storage | No public URLs; no guessable keys; no presigned URLs that outlive the request |
| Upload auth | Short-lived, scoped to one recording, one operation |
| Replay | Idempotency keys plus monotonic sequence checks |
| Tampering | Per-chunk checksum validated before acceptance; manifest verified at finalize |
| Audit | Access and deletion events recorded with actor, object and time |
| Error bodies | Must never echo ciphertext, keys or object-store internals back to the client |

### Authorisation checks that must be present

- **IDOR**: every `GET`/`DELETE` of `/recordings/{id}` verifies the authenticated
  user owns that recording. Not by id validation alone — by ownership.
- **Insecure direct object reference**: download URLs are issued per request, are
  short-lived, and are not derivable from the recording id.
- **Enumeration**: recording ids are 128-bit random values, so guessing is not a
  viable attack, but ownership checks must not rely on that.

---

## 6. Storage model

```text
recordings      (id, user_id, state, created_at, finalized_at, segment_count, receipt)
recording_chunks(recording_id, segment_id, sequence_number, checksum, size, uploaded_at)
users           (id, email, password_hash, created_at)
sessions        (id, user_id, refresh_token_hash, expires_at, revoked)
audit_events    (id, actor_user_id, action, object_type, object_id, occurred_at, ip)
```

**No plaintext audio in PostgreSQL.** Audio goes to object storage as ciphertext;
the database holds only references and checksums. Chunk rows record *that* a
segment exists and what it hashes to — never its content.

---

## 7. Client interface

The Android side will depend on this abstraction, not on HTTP directly:

```kotlin
interface EvidenceSyncRepository {
    suspend fun createUploadSession(recordingId: String): Result<UploadSession>
    suspend fun uploadChunk(
        recordingId: String,
        chunk: EncryptedChunk,
        metadata: ChunkMetadata,
    ): Result<UploadReceipt>
    suspend fun finalizeRecording(
        recordingId: String,
        manifest: RecordingManifest,
    ): Result<UploadReceipt>
    suspend fun getUploadStatus(recordingId: String): UploadStatus
    suspend fun deleteRemoteRecording(recordingId: String): Result<Unit>
}
```

Storage providers are abstracted behind this. A self-hosted server and an
S3-compatible backend should both be implementable without touching the recorder.

**No cloud credentials are ever hard-coded in the application.**

---

## 8. Sync state machine

```text
LOCAL_ONLY -> QUEUED -> UPLOADING -> PARTIALLY_UPLOADED -> SYNCED
                 ^           |              |
                 |           v              v
                 +------  RETRYING <--------+
                              |
                              v
                            FAILED
```

`PARTIALLY_UPLOADED` is a real, resumable state, not an error: it means some
segments are confirmed and the rest remain. Resumption is driven by the server's
record of which chunks it has, not by client bookkeeping alone.

### The one rule that matters

**A network failure produces `SyncPhase.FAILED`. It never produces
`SafeSignalPhase.FAILED`.**

The two state machines are deliberately separate. The Android state machine has no
upload states at all, and there is a test asserting that adding one fails the
build. This is the structural enforcement of "the network is a synchronisation
mechanism, not a recording mechanism".

---

## 9. Background work

WorkManager handles resumable upload, constrained by:

- network availability (and Wi-Fi-only if the user chose it);
- battery conditions where appropriate;
- exponential backoff with jitter;
- idempotent retry.

**WorkManager is never the microphone recorder.** Recording runs in a foreground
service because the platform requires it for microphone access; a background
worker cannot hold the microphone, and pretending otherwise would produce a
"recording" that silently captures nothing.

---

## 10. OpenAPI

[OPENAPI.yaml](OPENAPI.yaml) exists: OpenAPI 3.1, all 14 routes, validated against
the 3.1 schema with `openapi-spec-validator`.

It is written alongside the implementation and **held to it by tests**, because a
specification left to drift is worse than none — a client integrator would read it
as a description of the service rather than of a plan for one. `backend/test/openapi.test.ts`
fails when:

- a route exists in code but not in the document;
- the document describes a route that is not registered;
- any `$ref` does not resolve;
- any property key anywhere in the document could hold audio (`decodedAudioWav`,
  `pcmSamples`, `transcript` and the like);
- the document stops stating that the server cannot verify the GCM tag or the
  plaintext digest, or that audio never transits the API;
- the running service's `/v1/capabilities` reports a capability the contract says
  is structurally impossible.

Both the last two directions matter. The tests fail if the *server* starts claiming
more than it can do, as well as if the *document* stops warning about it. Each was
verified to fail when the guarantee was deliberately broken, since a test that has
never been seen to fail is not evidence of anything.

Two things the document deliberately does **not** do:

- **No `problem+json`.** The error envelope is `{ error: { code, message, details? } }`
  with a closed `code` enum, which is what the implementation uses. Describing a
  media type the server does not emit would be a small lie in a document whose
  whole purpose is accuracy.
- **No pagination.** `GET /v1/recordings` returns everything for the authenticated
  user. That is fine at this stage and would need addressing before any account
  accumulated enough recordings to matter; it is recorded as a limitation rather
  than specified and not built.

---

## 11. Deployment

The backend exists and runs, but **it has never been deployed anywhere.** It uses
in-memory repositories by default and there is no production database driver,
object-store adapter, or deployment manifest in this repository. Treat everything
below as requirements the code satisfies in development and has not been shown to
satisfy in production.

- run behind TLS termination with HSTS;
- keep object storage private with no public read path;
- store secrets in a secret manager, never in images or compose files;
- back up the database (it holds no audio, but it holds integrity metadata that is
  not reproducible from the audio alone);
- document that **object-storage backup retention limits deletion guarantees** —
  deleting from the primary bucket does not necessarily delete from backups.