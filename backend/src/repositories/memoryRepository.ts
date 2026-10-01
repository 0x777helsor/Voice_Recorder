import { conflict, idempotencyMismatch } from '../domain/errors.js';
import { constantTimeEquals } from '../domain/primitives.js';
import type { ChunkRecord, Recording, UploadReceipt, User } from '../domain/types.js';
import type { Repository } from './Repository.js';

/**
 * In-memory persistence, used by the test suite and by `npm run dev` without a
 * database.
 *
 * ### Not a mock
 *
 * This is a real implementation of [Repository], not a stub that records calls.
 * The authorization tests exercise the same ownership-scoping code that
 * `PostgresRepository` enforces in SQL. If a route forgets `userId`, these tests
 * fail here exactly as they would fail there.
 *
 * ### What it deliberately does not emulate
 *
 * PostgreSQL's isolation guarantees. In particular, `appendReceipt` here reads
 * the chain head and appends under an in-process mutex, which is *not* the same
 * as `SELECT ... FOR UPDATE` followed by `INSERT` inside one transaction. The
 * concurrency test therefore proves the contract, not Postgres' behaviour. See
 * `docs/DECISIONS.md`.
 */
export class MemoryRepository implements Repository {
  private readonly users = new Map<string, User>();
  private readonly usersByEmail = new Map<string, string>();
  private readonly recordings = new Map<string, Recording>();
  private readonly chunks = new Map<string, ChunkRecord>();
  private readonly idempotency = new Map<
    string,
    { recordingId: string; payloadHash: string; chunkId: string }
  >();
  private readonly receipts: UploadReceipt[] = [];
  /** Serialises receipt-chain appends so concurrent finalisations cannot fork the chain. */
  private receiptChainTail: Promise<unknown> = Promise.resolve();

  // ------------------------------------------------------------------ users

  async createUser(user: User): Promise<User> {
    const emailKey = normaliseEmail(user.email);
    if (this.usersByEmail.has(emailKey)) {
      throw conflict('An account with that email already exists.');
    }
    this.users.set(user.id, user);
    this.usersByEmail.set(emailKey, user.id);
    return user;
  }

  async findUserByEmail(email: string): Promise<User | null> {
    const id = this.usersByEmail.get(normaliseEmail(email));
    return id ? (this.users.get(id) ?? null) : null;
  }

  async findUserById(id: string): Promise<User | null> {
    return this.users.get(id) ?? null;
  }

  async replaceCredentials(userId: string, passwordHash: string, passwordSalt: string): Promise<void> {
    const user = this.users.get(userId);
    if (!user) throw conflict('User not found.');
    this.users.set(userId, { ...user, passwordHash, passwordSalt });
  }

  /**
   * Bumps under the new credentials.
   *
   * Reading the user *after* the credential write matters: if a test or a bug
   * reordered these, the returned version would be based on stale state and the
   * freshly-minted tokens would carry a version the store does not recognise.
   */
  async bumpTokenVersion(userId: string): Promise<number> {
    const user = this.users.get(userId);
    if (!user) throw conflict('User not found.');
    const next = user.tokenVersion + 1;
    this.users.set(userId, { ...user, tokenVersion: next });
    return next;
  }

  // ------------------------------------------------------------- recordings

  async createRecording(recording: Recording): Promise<Recording> {
    if (this.recordings.has(recording.id)) throw conflict('Recording already exists.');
    this.recordings.set(recording.id, recording);
    return recording;
  }

  async findRecordingForUser(userId: string, clientReference: string): Promise<Recording | null> {
    for (const recording of this.recordings.values()) {
      if (recording.clientReference === clientReference && recording.userId === userId) {
        return recording;
      }
    }
    return null;
  }

  async findRecordingByIdForUser(userId: string, recordingId: string): Promise<Recording | null> {
    const recording = this.recordings.get(recordingId);
    if (!recording || recording.userId !== userId) return null;
    return recording;
  }

  async listRecordingsForUser(userId: string): Promise<Recording[]> {
    return [...this.recordings.values()]
      .filter((r) => r.userId === userId)
      .sort((a, b) => (a.createdAt < b.createdAt ? 1 : a.createdAt > b.createdAt ? -1 : 0));
  }

  async updateRecording(recording: Recording): Promise<Recording> {
    const existing = this.recordings.get(recording.id);
    if (!existing || existing.userId !== recording.userId) {
      // Do not create a row on update: a missing row and a foreign row are
      // indistinguishable to the caller by design.
      throw conflict('Recording not found.');
    }
    this.recordings.set(recording.id, recording);
    return recording;
  }

  async deleteRecordingForUser(userId: string, recordingId: string): Promise<boolean> {
    const existing = this.recordings.get(recordingId);
    if (!existing || existing.userId !== userId) return false;
    this.recordings.delete(recordingId);
    for (const [id, chunk] of this.chunks) {
      if (chunk.recordingId === recordingId) this.chunks.delete(id);
    }
    for (const [key, record] of this.idempotency) {
      if (record.recordingId === recordingId) this.idempotency.delete(key);
    }
    return true;
  }

  // ----------------------------------------------------------------- chunks

  async putChunk(
    chunk: ChunkRecord,
    idempotencyKey: string,
  ): Promise<{ stored: boolean; chunk: ChunkRecord }> {
    const key = `${chunk.recordingId}:${idempotencyKey}`;
    const existing = this.idempotency.get(key);
    if (existing) {
      // A replay is only acceptable if it is byte-for-byte the same request.
      // Otherwise the client is retrying under a key it already used for
      // different content, which must never silently overwrite evidence.
      if (existing.payloadHash !== chunk.sealedSha256) {
        throw idempotencyMismatch(
          'This idempotency key was already used for different content.',
        );
      }
      const prior = this.chunks.get(existing.chunkId);
      if (prior) return { stored: false, chunk: prior };
    }
    this.chunks.set(chunk.id, chunk);
    this.idempotency.set(key, {
      recordingId: chunk.recordingId,
      payloadHash: chunk.sealedSha256,
      chunkId: chunk.id,
    });
    return { stored: true, chunk };
  }

  async listChunks(recordingId: string): Promise<ChunkRecord[]> {
    return [...this.chunks.values()]
      .filter((c) => c.recordingId === recordingId)
      .sort((a, b) => a.sequenceNumber - b.sequenceNumber);
  }

  async findChunkByIdempotencyKey(
    recordingId: string,
    idempotencyKey: string,
  ): Promise<ChunkRecord | null> {
    const record = this.idempotency.get(`${recordingId}:${idempotencyKey}`);
    if (!record) return null;
    return this.chunks.get(record.chunkId) ?? null;
  }

  // --------------------------------------------------------------- receipts

  async appendReceipt(receipt: UploadReceipt): Promise<void> {
    // Chain the operation so two concurrent finalisations cannot both read the
    // same head and produce two receipts claiming the same predecessor.
    const run = this.receiptChainTail.then(async () => {
      const head = await this.latestReceiptForUser(receipt.userId);
      if (head && !constantTimeEquals(head.auditChainHash, receipt.previousReceiptHash)) {
        throw conflict('Audit chain head moved; retry.');
      }
      this.receipts.push(receipt);
    });
    this.receiptChainTail = run.catch(() => undefined);
    await run;
  }

  async latestReceiptForUser(userId: string): Promise<UploadReceipt | null> {
    const mine = this.receipts.filter((r) => r.userId === userId);
    if (mine.length === 0) return null;
    return mine.reduce((latest, r) => (r.issuedAt > latest.issuedAt ? r : latest));
  }

  async listReceiptsForUser(userId: string): Promise<UploadReceipt[]> {
    return this.receipts
      .filter((r) => r.userId === userId)
      .sort((a, b) => (a.issuedAt < b.issuedAt ? -1 : 1));
  }

  // --------------------------------------------------------------- test aid

  /** Test-only escape hatch; never called from route code. */
  clear(): void {
    this.users.clear();
    this.usersByEmail.clear();
    this.recordings.clear();
    this.chunks.clear();
    this.idempotency.clear();
    this.receipts.length = 0;
    this.receiptChainTail = Promise.resolve();
  }
}

function normaliseEmail(email: string): string {
  return email.trim().toLowerCase();
}
