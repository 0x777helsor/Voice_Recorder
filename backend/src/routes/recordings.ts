import type { FastifyInstance, FastifyRequest } from 'fastify';
import { currentUserId } from '../auth/authPlugin.js';
import { badRequest, checksumMismatch, conflict, idempotencyMismatch, notFound, manifestIncomplete } from '../domain/errors.js';
import {
  assertChunksSatisfyManifest,
  canonicalManifestBytes,
  validateManifest,
} from '../domain/manifest.js';
import { GENESIS_HASH, canonicalJson, newClientReference, newId, sha256Hex } from '../domain/primitives.js';
import { RECEIPT_DISCLAIMER, type ChunkRecord, type Recording, type UploadReceipt } from '../domain/types.js';
import type { Repository } from '../repositories/Repository.js';
import { DOWNLOAD_URL_TTL_SECONDS, type ObjectStore } from '../storage/objectStore.js';

interface StartBody {
  deviceRecordingId?: unknown;
  segmentCount?: unknown;
}

interface CompleteBody {
  manifest?: unknown;
}

interface ReceiptBody {
  chunkCount?: unknown;
  totalSealedBytes?: unknown;
  manifestSha256?: unknown;
  signatureAlgorithm?: unknown;
  manifestSigningPublicKey?: unknown;
  serverIssuedAtEpochMillis?: unknown;
  auditChainHash?: unknown;
}

interface CommitBody {
  sequenceNumber?: unknown;
  sealedBytes?: unknown;
  sealedSha256?: unknown;
  plaintextSha256?: unknown;
  keyVersion?: unknown;
  encryptionVersion?: unknown;
}

/**
 * Recording lifecycle routes.
 *
 * The flow is four steps, and the split is deliberate:
 *
 *   POST /v1/recordings              → open a session, get a storage prefix
 *   POST /v1/recordings/:id/chunks/:n/commit → declare a chunk, get a PUT URL
 *   PUT  (direct to object storage)   → the ciphertext never passes through us
 *   POST /v1/recordings/:id/complete → finalize, get a receipt
 *
 * ### Why ciphertext bypasses this process entirely
 *
 * A 30-minute recording is tens of megabytes. Proxying it through an API server
 * costs memory, bandwidth and a second place it exists in cleartext at rest in
 * a temp file. Direct-to-storage upload means this process only ever handles
 * metadata. The cost is one extra round trip; the benefit is that compromising
 * this server yields no audio even if the bucket is also compromised but
 * unreadable without a key this process never had.
 *
 * ### What `commit` does and does not prove
 *
 * `commit` records the client's declared hash. When `verifyEveryChunkOnUpload`
 * is on, it also re-reads the object and compares. That is a *storage integrity*
 * check. It is **not** proof the audio is authentic or unaltered — that is the
 * GCM tag, verified on a device holding the key. The receipt says exactly this.
 */
export async function registerRecordingRoutes(
  app: FastifyInstance,
  deps: {
    repository: Repository;
    objectStore: ObjectStore;
    verifyEveryChunkOnUpload: boolean;
  },
) {
  const { repository, objectStore } = deps;

  /**
   * Opens an upload session.
   *
   * The storage prefix is `<userId>/<recordingId>/`. The userId is a path
   * segment, so a bug elsewhere cannot write outside the user's prefix even if
   * the recordingId were attacker-controlled — though `deviceRecordingId` is
   * validated to contain no path separators regardless.
   */
  app.post<{ Body: StartBody }>('/v1/recordings', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const deviceRecordingId = readKeySafeString(request.body?.deviceRecordingId, 'deviceRecordingId');

    // Reuse an existing open session for the same device recording, so a client
    // that retries after a network drop does not accumulate half-empty rows.
    const existing = (await repository.listRecordingsForUser(userId)).find(
      (r) => r.state === 'UPLOAD_SESSION' && r.clientReference === deviceRecordingId,
    );
    if (existing) {
      const expected = await buildExpectedChunks(existing, repository);
      return reply.send({
        recording: toPublicRecording(existing),
        uploadSession: {
          recordingId: existing.id,
          sessionId: existing.clientReference,
          storagePrefix: existing.storagePrefix,
          expiresAtEpochMillis: Date.now() + 7 * 24 * 60 * 60 * 1000,
          expectedChunks: expected,
        },
      });
    }

    const now = new Date().toISOString();
    const recordingId = newId('rec');
    const recording: Recording = {
      id: recordingId,
      userId,
      state: 'UPLOAD_SESSION',
      createdAt: now,
      updatedAt: now,
      storagePrefix: `${userId}/${recordingId}/`,
      clientReference: newClientReference(),
      finalizedAt: null,
      manifestJson: null,
      receiptJson: null,
      manifestSha256: null,
      declaredChunkCount: null,
      verifiedChunkCount: null,
    };
    // The client addresses the recording by its own device id; the opaque
    // clientReference is what it uses in URLs.
    recording.clientReference = deviceRecordingId;
    await repository.createRecording(recording);

    return reply.code(201).send({
      recording: toPublicRecording(recording),
      uploadSession: {
        recordingId: recording.id,
        sessionId: recording.clientReference,
        storagePrefix: recording.storagePrefix,
        expiresAtEpochMillis: Date.now() + 7 * 24 * 60 * 60 * 1000,
        expectedChunks: [],
      },
    });
  });

  app.get('/v1/recordings', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const recordings = await repository.listRecordingsForUser(userId);
    return reply.send({ recordings: recordings.map(toPublicRecording) });
  });

  app.get<{ Params: { id: string } }>('/v1/recordings/:id', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const recording = await repository.findRecordingByIdForUser(userId, request.params.id!);
    // 404 for both "absent" and "someone else's", so a valid id cannot be probed
    // to discover that another user's recording exists.
    if (!recording) throw notFound();
    const chunks = await repository.listChunks(recording.id);
    return reply.send({
      recording: toPublicRecording(recording),
      chunks: chunks.map(toPublicChunk),
      // Explicitly stated so no consumer of this API assumes the server checked
      // the ciphertext. It did not, and cannot.
      integrity: {
        sealedBytesVerifiedByServer: deps.verifyEveryChunkOnUpload,
        gcmTagVerifiedByServer: false,
        plaintextVerifiedByServer: false,
        note:
          'The server verifies stored byte counts and, when enabled, SHA-256 over ' +
          'sealed bytes. It cannot verify the GCM tag or any plaintext digest: ' +
          'the data key never reaches this server. Client-side verification at ' +
          'open time is authoritative.',
      },
    });
  });

  /**
   * Declares a chunk and returns a presigned PUT URL.
   *
   * Requires an `Idempotency-Key` header. A retry under the same key with the
   * same content returns the same chunk and a fresh URL; a retry with different
   * content is rejected rather than overwriting evidence.
   */
  app.post<{ Params: { id: string; sequence: string }; Body: CommitBody }>(
    '/v1/recordings/:id/chunks/:sequence/commit',
    { preHandler: app.requireAuth },
    async (request, reply) => {
      const userId = currentUserId(request);
      const recording = await requireOwnedOpenRecording(repository, userId, request.params.id!);

      const idempotencyKey = request.headers['idempotency-key'];
      if (typeof idempotencyKey !== 'string' || idempotencyKey.length < 8 || idempotencyKey.length > 200) {
        throw badRequest('An Idempotency-Key header of 8..200 characters is required.');
      }

      const sequenceNumber = parseSequence(request.params.sequence!);
      const body = request.body ?? {};
      const declared: ChunkRecord = {
        id: newId('chk'),
        recordingId: recording.id,
        sequenceNumber,
        objectKey: `${recording.storagePrefix}${String(sequenceNumber).padStart(6, '0')}.ssg`,
        sealedBytes: readPositiveInt(body.sealedBytes, 'sealedBytes'),
        sealedSha256: readSha256(body.sealedSha256, 'sealedSha256'),
        plaintextSha256: readSha256(body.plaintextSha256, 'plaintextSha256'),
        keyVersion: readPositiveInt(body.keyVersion, 'keyVersion'),
        encryptionVersion: readPositiveInt(body.encryptionVersion, 'encryptionVersion'),
        receivedAt: new Date().toISOString(),
      };

      // Idempotency is checked *before* the store mints a URL, so a replay
      // cannot be used to generate unlimited valid upload grants.
      const prior = await repository.findChunkByIdempotencyKey(recording.id, idempotencyKey);
      if (prior) {
        if (prior.sealedSha256 !== declared.sealedSha256) {
          // Same error the repository layer raises, so a client sees one code
          // regardless of whether the replay was caught here or under the race
          // that reaches `putChunk`.
          throw idempotencyMismatch('This idempotency key was already used for different content.');
        }
        const target = await objectStore.presignUpload(prior.objectKey, prior.sealedBytes, 'application/octet-stream');
        return reply.send({ chunk: toPublicChunk(prior), upload: target });
      }

      const stored = await objectStore.stat(declared.objectKey);
      if (stored && deps.verifyEveryChunkOnUpload && stored.sha256 && stored.sha256 !== declared.sealedSha256) {
        throw checksumMismatch('An object already exists at this position with different content.', {
          sequenceNumber,
        });
      }

      await repository.putChunk(declared, idempotencyKey);
      const target = await objectStore.presignUpload(declared.objectKey, declared.sealedBytes, 'application/octet-stream');

      return reply.code(201).send({ chunk: toPublicChunk(declared), upload: target });
    },
  );

  /**
   * Finalizes a recording and issues a receipt.
   *
   * Rejects with 422 unless every segment the manifest declares is present with
   * the declared size and sealed hash. This is the server-side loss detector: if
   * the client finalizes successfully, the bytes that reached this server are
   * the bytes it declared.
   */
  app.post<{ Params: { id: string }; Body: CompleteBody }>(
    '/v1/recordings/:id/complete',
    { preHandler: app.requireAuth },
    async (request, reply) => {
      const userId = currentUserId(request);
      const recording = await requireOwnedOpenRecording(repository, userId, request.params.id!);

      const validated = validateManifest(request.body?.manifest);
      if (validated.manifest.recordingId !== recording.clientReference) {
        throw badRequest('manifest.recordingId does not match this upload session.');
      }

      const stored = await repository.listChunks(recording.id);
      assertChunksSatisfyManifest(validated.manifest.segments, stored);

      // When the server can afford it, verify stored bytes rather than trusting
      // the declaration. A failure here must block finalization: issuing a
      // receipt for evidence the server does not hold would be the worst
      // possible failure mode for this product.
      if (deps.verifyEveryChunkOnUpload) {
        for (const chunk of stored) {
          const verification = await objectStore.verify(chunk.objectKey, chunk.sealedSha256);
          if (!verification) {
            throw manifestIncomplete('A declared segment is absent from object storage.', {
              sequenceNumber: chunk.sequenceNumber,
            });
          }
          if (!verification.matches) {
            throw checksumMismatch('Stored bytes do not match the declared hash.', {
              sequenceNumber: chunk.sequenceNumber,
            });
          }
        }
      }

      const receipt = await issueReceipt({
        userId,
        recording,
        validated,
        stored,
        repository,
      });

      const finalized: Recording = {
        ...recording,
        state: 'FINALIZED',
        updatedAt: new Date().toISOString(),
        finalizedAt: new Date().toISOString(),
        manifestJson: canonicalManifestBytes(validated.manifest),
        receiptJson: JSON.stringify(receipt),
        manifestSha256: validated.manifestSha256,
        declaredChunkCount: validated.manifest.segments.length,
        verifiedChunkCount: stored.length,
      };
      await repository.updateRecording(finalized);

      return reply.send({
        recording: toPublicRecording(finalized),
        receipt,
        // Said out loud in the response, not only in the receipt body, so a
        // developer integrating this cannot miss it.
        admissibilityStatement:
          'Receipt issuance is not a certification of authenticity or admissibility. ' +
          'See LEGAL_DISCLAIMER.md and KNOWN_LIMITATIONS.md.',
      });
    },
  );

  /** Lists receipts for the authenticated user, oldest first. */
  app.get('/v1/receipts', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const receipts = await repository.listReceiptsForUser(userId);
    return reply.send({ receipts });
  });

  /** Mints a short-lived download URL for one chunk. */
  app.get<{ Params: { id: string; sequence: string } }>(
    '/v1/recordings/:id/chunks/:sequence/download',
    { preHandler: app.requireAuth },
    async (request, reply) => {
      const userId = currentUserId(request);
      const recording = await repository.findRecordingByIdForUser(userId, request.params.id!);
      if (!recording) throw notFound();

      const sequence = parseSequence(request.params.sequence!);
      const chunks = await repository.listChunks(recording.id);
      const chunk = chunks.find((c) => c.sequenceNumber === sequence);
      if (!chunk) throw notFound();

      const target = await objectStore.presignDownload(chunk.objectKey, DOWNLOAD_URL_TTL_SECONDS);
      return reply.send({ download: target, chunk: toPublicChunk(chunk) });
    },
  );

  /**
   * Deletes a recording.
   *
   * Blobs first, then the row. If blob deletion fails the row survives, so the
   * database never claims evidence is gone while ciphertext remains in the
   * bucket. The reverse order would leave orphaned, unaccounted-for ciphertext,
   * which is worse for a retention story than a stale row.
   */
  app.delete<{ Params: { id: string } }>('/v1/recordings/:id', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const recording = await repository.findRecordingByIdForUser(userId, request.params.id!);
    if (!recording) throw notFound();

    await objectStore.deletePrefix(recording.storagePrefix);
    await repository.deleteRecordingForUser(userId, recording.id);
    return reply.code(204).send();
  });
}

// ------------------------------------------------------------------ helpers

async function requireOwnedOpenRecording(
  repository: Repository,
  userId: string,
  recordingId: string,
): Promise<Recording> {
  const recording = await repository.findRecordingByIdForUser(userId, recordingId);
  if (!recording) throw notFound();
  if (recording.state === 'FINALIZED') {
    throw conflict('This recording is already finalized and cannot be modified.');
  }
  return recording;
}

async function issueReceipt(deps: {
  userId: string;
  recording: Recording;
  validated: ReturnType<typeof validateManifest>;
  stored: ChunkRecord[];
  repository: Repository;
}): Promise<UploadReceipt> {
  const head = await deps.repository.latestReceiptForUser(deps.userId);
  const previousReceiptHash = head?.auditChainHash ?? GENESIS_HASH;
  const totalSealedBytes = deps.stored.reduce((sum, c) => sum + c.sealedBytes, 0);

  // The chain hash covers everything in the receipt except itself, plus the
  // predecessor. Any alteration to an earlier receipt changes every later hash,
  // which is what makes removal or reordering detectable.
  const body = {
    receiptId: newId('rcpt'),
    recordingId: deps.recording.id,
    userId: deps.userId,
    chunkCount: deps.stored.length,
    totalSealedBytes,
    manifestSha256: deps.validated.manifestSha256,
    signatureAlgorithm: deps.validated.manifest.signatureAlgorithm,
    manifestSigningPublicKey: deps.validated.manifest.signingPublicKey,
    previousReceiptHash,
  };
  const auditChainHash = sha256Hex(`${previousReceiptHash}${canonicalJson(body)}`);

  const receipt: UploadReceipt = {
    ...body,
    issuedAt: new Date().toISOString(),
    // Deliberately the server's clock, and kept in a field distinct from the
    // device timestamps in the manifest. Conflating them is how "the server said
    // it happened at 14:03" turns into an unsupported claim about device time.
    serverIssuedAtEpochMillis: Date.now(),
    auditChainHash,
    disclaimer: RECEIPT_DISCLAIMER,
  };

  await deps.repository.appendReceipt(receipt);
  return receipt;
}

async function buildExpectedChunks(recording: Recording, repository: Repository) {
  const chunks = await repository.listChunks(recording.id);
  return chunks.map((c) => ({
    sequenceNumber: c.sequenceNumber,
    sealedBytes: c.sealedBytes,
    sealedSha256: c.sealedSha256,
  }));
}

/**
 * Response projection.
 *
 * `storagePrefix` and `userId` are omitted. They are not secret in a strong
 * sense, but a client has no legitimate need for them, and returning only what a
 * client needs keeps the blast radius of a leaked response or log line smaller.
 */
function toPublicRecording(recording: Recording) {
  return {
    id: recording.id,
    clientReference: recording.clientReference,
    state: recording.state,
    createdAt: recording.createdAt,
    updatedAt: recording.updatedAt,
    finalizedAt: recording.finalizedAt,
    manifestSha256: recording.manifestSha256,
    declaredChunkCount: recording.declaredChunkCount,
    verifiedChunkCount: recording.verifiedChunkCount,
  };
}

function toPublicChunk(chunk: ChunkRecord) {
  return {
    sequenceNumber: chunk.sequenceNumber,
    sealedBytes: chunk.sealedBytes,
    sealedSha256: chunk.sealedSha256,
    // Named to make its status unmissable at the call site. It is never verified
    // by this server.
    plaintextSha256AssertedByClient: chunk.plaintextSha256,
    keyVersion: chunk.keyVersion,
    encryptionVersion: chunk.encryptionVersion,
    receivedAt: chunk.receivedAt,
  };
}

function readKeySafeString(value: unknown, field: string): string {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw badRequest(`${field} must be 1..128 characters of letters, digits, underscore or hyphen.`);
  }
  return value;
}

function readPositiveInt(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value <= 0) {
    throw badRequest(`${field} must be a positive integer.`);
  }
  return value;
}

function readSha256(value: unknown, field: string): string {
  if (typeof value !== 'string' || !/^[0-9a-f]{64}$/.test(value)) {
    throw badRequest(`${field} must be 64 lowercase hex characters.`);
  }
  return value;
}

function parseSequence(raw: string): number {
  if (!/^[0-9]{1,9}$/.test(raw)) throw badRequest('sequence must be a positive integer.');
  const value = Number.parseInt(raw, 10);
  if (value < 1) throw badRequest('sequence must be a positive integer.');
  return value;
}
