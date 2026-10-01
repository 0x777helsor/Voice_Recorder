import type { ChunkRecord, Recording, UploadReceipt, User } from '../domain/types.js';

/**
 * Persistence boundary.
 *
 * Two implementations exist: `PostgresRepository` (production) and
 * `MemoryRepository` (tests and local development without a database). The
 * interface is deliberately narrow so the HTTP layer cannot accidentally reach
 * around ownership checks.
 *
 * ### Ownership
 *
 * Every recording read and write takes `userId` and scopes by it **inside the
 * repository**, not in the route handler. A route that forgets to pass `userId`
 * gets a type error rather than a cross-tenant read.
 */
export interface Repository {
  // ---------------------------------------------------------------- users
  createUser(user: User): Promise<User>;
  findUserByEmail(email: string): Promise<User | null>;
  findUserById(id: string): Promise<User | null>;
  /**
   * Replaces password material without changing the email or id.
   *
   * Separate from `createUser` on purpose: a password change must never be able
   * to collide with the unique email index or create a second row.
   */
  replaceCredentials(userId: string, passwordHash: string, passwordSalt: string): Promise<void>;
  /** Increments and returns the token version, invalidating all outstanding tokens. */
  bumpTokenVersion(userId: string): Promise<number>;

  // ------------------------------------------------------------ recordings
  createRecording(recording: Recording): Promise<Recording>;
  /** Returns null when the recording does not exist **or** belongs to another user. */
  findRecordingForUser(userId: string, clientReference: string): Promise<Recording | null>;
  findRecordingByIdForUser(userId: string, recordingId: string): Promise<Recording | null>;
  listRecordingsForUser(userId: string): Promise<Recording[]>;
  updateRecording(recording: Recording): Promise<Recording>;
  deleteRecordingForUser(userId: string, recordingId: string): Promise<boolean>;

  // --------------------------------------------------------------- chunks
  /**
   * Inserts a chunk, or returns the existing one when `idempotencyKey` matches.
   *
   * Returns `{ stored: false }` for a duplicate that is *identical*, and throws
   * `idempotencyMismatch` for a duplicate key whose payload differs — a replayed
   * request must never silently overwrite evidence.
   */
  putChunk(chunk: ChunkRecord, idempotencyKey: string): Promise<{ stored: boolean; chunk: ChunkRecord }>;
  listChunks(recordingId: string): Promise<ChunkRecord[]>;
  findChunkByIdempotencyKey(recordingId: string, idempotencyKey: string): Promise<ChunkRecord | null>;

  // -------------------------------------------------------------- receipts
  /** Appends a receipt to the user's audit chain. Must be atomic with the chain head read. */
  appendReceipt(receipt: UploadReceipt): Promise<void>;
  latestReceiptForUser(userId: string): Promise<UploadReceipt | null>;
  listReceiptsForUser(userId: string): Promise<UploadReceipt[]>;
}

/** Idempotency record stored alongside each chunk. */
export interface IdempotencyRecord {
  key: string;
  recordingId: string;
  payloadHash: string;
  createdAt: string;
}
