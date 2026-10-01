# API and Backend

**Status: not implemented.** This document is the design contract for Phase 6–7 of
the specification. No backend code, no OpenAPI document and no upload client exist
in this repository yet.

It is written now because the Android client's design is constrained by the server
contract, and the server contract is where most of the security properties live.
Building the client first against an unstated contract would mean reworking it.

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

`OPENAPI.yaml` has not been written yet. It should be generated from, or written
alongside, an implementation rather than specified in advance and left to drift.
When it is written it must cover:

- the endpoints above;
- the error model (a consistent problem-details shape);
- authentication schemes;
- idempotency headers on every mutating upload operation;
- pagination on `GET /recordings`.

---

## 11. Deployment

No backend exists, so there is nothing to deploy. When one does:

- run behind TLS termination with HSTS;
- keep object storage private with no public read path;
- store secrets in a secret manager, never in images or compose files;
- back up the database (it holds no audio, but it holds integrity metadata that is
  not reproducible from the audio alone);
- document that **object-storage backup retention limits deletion guarantees** —
  deleting from the primary bucket does not necessarily delete from backups.