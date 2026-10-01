import { Pool, type PoolClient } from 'pg';
import { conflict, idempotencyMismatch } from '../domain/errors.js';
import type { ChunkRecord, Recording, UploadReceipt, User } from '../domain/types.js';
import type { Repository } from './Repository.js';

/**
 * PostgreSQL persistence.
 *
 * ### The one thing this file must get right: ownership scoping in SQL
 *
 * Every statement that reads or writes a recording carries `AND user_id = $1`.
 * Doing the ownership check in the application layer instead would mean a single
 * forgotten predicate exposes another tenant's evidence, so the predicate is in
 * the query text where a reviewer can see it. `AND user_id = $1` on a
 * `SELECT` also means a foreign id returns zero rows rather than one row that
 * the caller must remember to filter.
 *
 * ### Concurrency
 *
 * `appendReceipt` runs inside `SERIALIZABLE` and re-reads the chain head inside
 * the transaction. Two concurrent finalizations of *different* recordings by the
 * same user would both read the same head, so one will fail serialization and be
 * retried by the route. That is the correct outcome: the alternative is a forked
 * audit chain, which would defeat the chain's only purpose.
 */
export class PostgresRepository implements Repository {
  private readonly pool: Pool;

  constructor(connectionString: string) {
    this.pool = new Pool({ connectionString, max: 10 });
  }

  async close(): Promise<void> {
    await this.pool.end();
  }

  /**
   * Typed query helper.
   *
   * `pg` returns `any` rows, so the row type is asserted at every call site via
   * `T`. The constraint is `object` rather than `Record<string, unknown>` because
   * the row interfaces declare only the columns they read, which is deliberate:
   * a mapper that forgot a column should be a compile error, not a silent
   * `undefined` in a receipt.
   */
  private query<T extends object>(text: string, values: unknown[] = []) {
    return this.pool.query(text, values) as Promise<{ rows: T[]; rowCount: number | null }>;
  }

  // ------------------------------------------------------------------ users

  async createUser(user: User): Promise<User> {
    try {
      const { rows } = await this.query<UserRow>(
        `INSERT INTO users (id, email, password_hash, password_salt, token_version, created_at)
         VALUES ($1, $2, $3, $4, $5, $6) RETURNING *`,
        [user.id, user.email, user.passwordHash, user.passwordSalt, user.tokenVersion, user.createdAt],
      );
      return mapUser(rows[0]!);
    } catch (cause) {
      // 23505 = unique_violation on the email index.
      if (isPgError(cause, '23505')) {
        throw conflict('An account with that email already exists.');
      }
      throw cause;
    }
  }

  async findUserByEmail(email: string): Promise<User | null> {
    const { rows } = await this.query<UserRow>(
      `SELECT * FROM users WHERE email = $1`,
      [email.trim().toLowerCase()],
    );
    return rows[0] ? mapUser(rows[0]) : null;
  }

  async findUserById(id: string): Promise<User | null> {
    const { rows } = await this.query<UserRow>(`SELECT * FROM users WHERE id = $1`, [id]);
    return rows[0] ? mapUser(rows[0]) : null;
  }

  async replaceCredentials(userId: string, passwordHash: string, passwordSalt: string): Promise<void> {
    const { rows } = await this.query<{ id: string }>(
      `UPDATE users SET password_hash = $2, password_salt = $3 WHERE id = $1 RETURNING id`,
      [userId, passwordHash, passwordSalt],
    );
    if (rows.length === 0) throw conflict('User not found.');
  }

  /**
   * Increments the token version.
   *
   * `RETURNING` gives the caller the post-increment value, so the version baked
   * into newly-minted tokens is read from the same statement that changed it.
   * There is no read-then-write window for a concurrent change to slip into.
   */
  async bumpTokenVersion(userId: string): Promise<number> {
    const { rows } = await this.query<{ token_version: number }>(
      `UPDATE users SET token_version = token_version + 1 WHERE id = $1 RETURNING token_version`,
      [userId],
    );
    if (rows.length === 0) throw conflict('User not found.');
    return rows[0]!.token_version;
  }

  // ------------------------------------------------------------- recordings

  async createRecording(recording: Recording): Promise<Recording> {
    const { rows } = await this.query<RecordingRow>(
      `INSERT INTO recordings (
         id, user_id, state, created_at, updated_at, storage_prefix,
         client_reference, finalized_at, manifest_json, receipt_json,
         manifest_sha256, declared_chunk_count, verified_chunk_count)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
       RETURNING *`,
      [
        recording.id,
        recording.userId,
        recording.state,
        recording.createdAt,
        recording.updatedAt,
        recording.storagePrefix,
        recording.clientReference,
        recording.finalizedAt,
        recording.manifestJson,
        recording.receiptJson,
        recording.manifestSha256,
        recording.declaredChunkCount,
        recording.verifiedChunkCount,
      ],
    );
    return mapRecording(rows[0]!);
  }

  async findRecordingForUser(userId: string, clientReference: string): Promise<Recording | null> {
    const { rows } = await this.query<RecordingRow>(
      `SELECT * FROM recordings WHERE client_reference = $1 AND user_id = $2`,
      [clientReference, userId],
    );
    return rows[0] ? mapRecording(rows[0]) : null;
  }

  async findRecordingByIdForUser(userId: string, recordingId: string): Promise<Recording | null> {
    const { rows } = await this.query<RecordingRow>(
      `SELECT * FROM recordings WHERE id = $1 AND user_id = $2`,
      [recordingId, userId],
    );
    return rows[0] ? mapRecording(rows[0]) : null;
  }

  async listRecordingsForUser(userId: string): Promise<Recording[]> {
    const { rows } = await this.query<RecordingRow>(
      `SELECT * FROM recordings WHERE user_id = $1 ORDER BY created_at DESC`,
      [userId],
    );
    return rows.map(mapRecording);
  }

  async updateRecording(recording: Recording): Promise<Recording> {
    const { rows }: { rows: RecordingRow[] } = await this.query<RecordingRow>(
      `UPDATE recordings SET
         state = $3, updated_at = $4, finalized_at = $5, manifest_json = $6,
         receipt_json = $7, manifest_sha256 = $8, declared_chunk_count = $9,
         verified_chunk_count = $10
       WHERE id = $1 AND user_id = $2
       RETURNING *`,
      [
        recording.id,
        recording.userId,
        recording.state,
        recording.updatedAt,
        recording.finalizedAt,
        recording.manifestJson,
        recording.receiptJson,
        recording.manifestSha256,
        recording.declaredChunkCount,
        recording.verifiedChunkCount,
      ],
    );
    const updated = rows[0];
    if (!updated) throw conflict('Recording not found.');
    return mapRecording(updated);
  }

  async deleteRecordingForUser(userId: string, recordingId: string): Promise<boolean> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const deleted = await client.query(
        `DELETE FROM recordings WHERE id = $1 AND user_id = $2 RETURNING id`,
        [recordingId, userId],
      );
      if (deleted.rowCount === 0) {
        // Roll back rather than commit an empty transaction, and report "not
        // found" identically to the foreign-id case.
        await client.query('ROLLBACK');
        return false;
      }
      // Chunks are removed by ON DELETE CASCADE in the schema. The object-store
      // blobs are *not* removed here: the route deletes them before calling this,
      // and a failed blob delete must not leave the database claiming the
      // recording is gone while ciphertext is still in the bucket. See
      // routes/recordings.ts deleteRecording.
      await client.query('COMMIT');
      return true;
    } catch (cause) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw cause;
    } finally {
      client.release();
    }
  }

  // ----------------------------------------------------------------- chunks

  async putChunk(
    chunk: ChunkRecord,
    idempotencyKey: string,
  ): Promise<{ stored: boolean; chunk: ChunkRecord }> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      const prior = await client.query<ChunkRow>(
        `SELECT c.* FROM chunks c
         JOIN chunk_idempotency i ON i.chunk_id = c.id
         WHERE i.recording_id = $1 AND i.idempotency_key = $2
         FOR UPDATE`,
        [chunk.recordingId, idempotencyKey],
      );

      if (prior.rowCount && prior.rowCount > 0) {
        const existing = prior.rows[0]!;
        if (existing.sealed_sha256 !== chunk.sealedSha256) {
          await client.query('ROLLBACK');
          throw idempotencyMismatch('This idempotency key was already used for different content.');
        }
        await client.query('COMMIT');
        return { stored: false, chunk: mapChunk(existing) };
      }

      const inserted = await client.query<ChunkRow>(
        `INSERT INTO chunks (
           id, recording_id, sequence_number, object_key, sealed_bytes,
           sealed_sha256, plaintext_sha256, key_version, encryption_version, received_at)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)
         RETURNING *`,
        [
          chunk.id,
          chunk.recordingId,
          chunk.sequenceNumber,
          chunk.objectKey,
          chunk.sealedBytes,
          chunk.sealedSha256,
          chunk.plaintextSha256,
          chunk.keyVersion,
          chunk.encryptionVersion,
          chunk.receivedAt,
        ],
      );
      await client.query(
        `INSERT INTO chunk_idempotency (recording_id, idempotency_key, chunk_id, created_at)
         VALUES ($1,$2,$3,$4)`,
        [chunk.recordingId, idempotencyKey, chunk.id, chunk.receivedAt],
      );
      await client.query('COMMIT');
      return { stored: true, chunk: mapChunk(inserted.rows[0]!) };
    } catch (cause) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw cause;
    } finally {
      client.release();
    }
  }

  async listChunks(recordingId: string): Promise<ChunkRecord[]> {
    const { rows } = await this.query<ChunkRow>(
      `SELECT * FROM chunks WHERE recording_id = $1 ORDER BY sequence_number`,
      [recordingId],
    );
    return rows.map(mapChunk);
  }

  async findChunkByIdempotencyKey(
    recordingId: string,
    idempotencyKey: string,
  ): Promise<ChunkRecord | null> {
    const { rows } = await this.query<ChunkRow>(
      `SELECT c.* FROM chunks c
       JOIN chunk_idempotency i ON i.chunk_id = c.id
       WHERE i.recording_id = $1 AND i.idempotency_key = $2`,
      [recordingId, idempotencyKey],
    );
    return rows[0] ? mapChunk(rows[0]) : null;
  }

  // --------------------------------------------------------------- receipts

  async appendReceipt(receipt: UploadReceipt): Promise<void> {
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN ISOLATION LEVEL SERIALIZABLE');
      const head = await client.query<{ audit_chain_hash: string }>(
        `SELECT audit_chain_hash FROM receipts
         WHERE user_id = $1 ORDER BY issued_at DESC LIMIT 1`,
        [receipt.userId],
      );
      const expected = head.rows[0]?.audit_chain_hash ?? GENESIS_HASH;
      if (expected !== receipt.previousReceiptHash) {
        await client.query('ROLLBACK');
        throw conflict('Audit chain head moved; retry.');
      }
      await client.query(
        `INSERT INTO receipts (
           receipt_id, recording_id, user_id, issued_at, server_issued_at_epoch_millis,
           chunk_count, total_sealed_bytes, manifest_sha256, signature_algorithm,
           manifest_signing_public_key, previous_receipt_hash, audit_chain_hash, disclaimer)
         VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)`,
        [
          receipt.receiptId,
          receipt.recordingId,
          receipt.userId,
          receipt.issuedAt,
          receipt.serverIssuedAtEpochMillis,
          receipt.chunkCount,
          receipt.totalSealedBytes,
          receipt.manifestSha256,
          receipt.signatureAlgorithm,
          receipt.manifestSigningPublicKey,
          receipt.previousReceiptHash,
          receipt.auditChainHash,
          receipt.disclaimer,
        ],
      );
      await client.query('COMMIT');
    } catch (cause) {
      await client.query('ROLLBACK').catch(() => undefined);
      throw cause;
    } finally {
      client.release();
    }
  }

  async latestReceiptForUser(userId: string): Promise<UploadReceipt | null> {
    const { rows } = await this.query<ReceiptRow>(
      `SELECT * FROM receipts WHERE user_id = $1 ORDER BY issued_at DESC LIMIT 1`,
      [userId],
    );
    return rows[0] ? mapReceipt(rows[0]) : null;
  }

  async listReceiptsForUser(userId: string): Promise<UploadReceipt[]> {
    const { rows } = await this.query<ReceiptRow>(
      `SELECT * FROM receipts WHERE user_id = $1 ORDER BY issued_at ASC`,
      [userId],
    );
    return rows.map(mapReceipt);
  }

  /** Raw pool access, used by the migration runner. */
  getPool(): Pool {
    return this.pool;
  }
}

// ------------------------------------------------------------------- mapping

interface UserRow {
  id: string;
  email: string;
  password_hash: string;
  password_salt: string;
  token_version: number;
  created_at: Date;
}

interface RecordingRow {
  id: string;
  user_id: string;
  state: Recording['state'];
  created_at: Date;
  updated_at: Date;
  storage_prefix: string;
  client_reference: string;
  finalized_at: Date | null;
  manifest_json: string | null;
  receipt_json: string | null;
  manifest_sha256: string | null;
  declared_chunk_count: number | null;
  verified_chunk_count: number | null;
}

interface ChunkRow {
  id: string;
  recording_id: string;
  sequence_number: number;
  object_key: string;
  sealed_bytes: number;
  sealed_sha256: string;
  plaintext_sha256: string;
  key_version: number;
  encryption_version: number;
  received_at: Date;
}

interface ReceiptRow {
  receipt_id: string;
  recording_id: string;
  user_id: string;
  issued_at: Date;
  server_issued_at_epoch_millis: number;
  chunk_count: number;
  total_sealed_bytes: number;
  manifest_sha256: string;
  signature_algorithm: string;
  manifest_signing_public_key: string;
  previous_receipt_hash: string;
  audit_chain_hash: string;
  disclaimer: string;
}

function mapUser(row: UserRow): User {
  return {
    id: row.id,
    email: row.email,
    passwordHash: row.password_hash,
    passwordSalt: row.password_salt,
    tokenVersion: row.token_version,
    createdAt: row.created_at.toISOString(),
  };
}

function mapRecording(row: RecordingRow): Recording {
  return {
    id: row.id,
    userId: row.user_id,
    state: row.state,
    createdAt: row.created_at.toISOString(),
    updatedAt: row.updated_at.toISOString(),
    storagePrefix: row.storage_prefix,
    clientReference: row.client_reference,
    finalizedAt: row.finalized_at ? row.finalized_at.toISOString() : null,
    manifestJson: row.manifest_json,
    receiptJson: row.receipt_json,
    manifestSha256: row.manifest_sha256,
    declaredChunkCount: row.declared_chunk_count,
    verifiedChunkCount: row.verified_chunk_count,
  };
}

function mapChunk(row: ChunkRow): ChunkRecord {
  return {
    id: row.id,
    recordingId: row.recording_id,
    sequenceNumber: row.sequence_number,
    objectKey: row.object_key,
    sealedBytes: row.sealed_bytes,
    sealedSha256: row.sealed_sha256,
    plaintextSha256: row.plaintext_sha256,
    keyVersion: row.key_version,
    encryptionVersion: row.encryption_version,
    receivedAt: row.received_at.toISOString(),
  };
}

function mapReceipt(row: ReceiptRow): UploadReceipt {
  return {
    receiptId: row.receipt_id,
    recordingId: row.recording_id,
    userId: row.user_id,
    issuedAt: row.issued_at.toISOString(),
    serverIssuedAtEpochMillis: row.server_issued_at_epoch_millis,
    chunkCount: row.chunk_count,
    totalSealedBytes: row.total_sealed_bytes,
    manifestSha256: row.manifest_sha256,
    signatureAlgorithm: row.signature_algorithm,
    manifestSigningPublicKey: row.manifest_signing_public_key,
    previousReceiptHash: row.previous_receipt_hash,
    auditChainHash: row.audit_chain_hash,
    disclaimer: row.disclaimer,
  };
}

function isPgError(cause: unknown, code: string): boolean {
  return (
    typeof cause === 'object' &&
    cause !== null &&
    (cause as { code?: unknown }).code === code
  );
}

const GENESIS_HASH = '0'.repeat(64);

/** Re-exported so the migration script can use the same pool accessor type. */
export type { PoolClient };
