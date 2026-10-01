/**
 * Domain types for the SafeSignal reference backend.
 *
 * ## The single most important property of this file
 *
 * There is **no type here that can hold audio**. The backend stores ciphertext
 * blobs, checksums and metadata. It cannot decrypt anything, because it never
 * receives a data-encryption key. That is deliberate (SPEC §43) and is the reason
 * a compromised database or object bucket yields nothing intelligible.
 *
 * ## What "chunk" means
 *
 * A chunk is one encrypted, independently verifiable evidence segment produced by
 * the Android client. The client sends the *sealed* bytes (header + nonce +
 * ciphertext + GCM tag) and the SHA-256 of those sealed bytes. The server checks
 * the checksum it receives against the object it stored. It cannot check the
 * plaintext hash, because it cannot compute it — so `plaintextSha256` is recorded
 * as a client-asserted value and labelled as such, never presented as verified.
 */

export type RecordingState =
  | 'UPLOAD_SESSION'
  | 'INCOMPLETE'
  | 'COMPLETE'
  | 'FINALIZED';

export type SyncPhase = 'LOCAL_ONLY' | 'QUEUED' | 'UPLOADING' | 'PARTIALLY_UPLOADED' | 'SYNCED' | 'FAILED' | 'RETRYING';

export interface User {
  id: string;
  email: string;
  passwordHash: string;
  passwordSalt: string;
  createdAt: string;
  /** Monotonic counter bumped on every token refresh. */
  tokenVersion: number;
}

/** Metadata for one recording. Never contains plaintext audio. */
export interface Recording {
  id: string;
  /** Owner. Every read and write is scoped by this. */
  userId: string;
  state: RecordingState;
  createdAt: string;
  updatedAt: string;
  /** Object storage prefix holding this recording's chunks. */
  storagePrefix: string;
  /** Opaque, server-generated id the client addresses this recording by. */
  clientReference: string;
  finalizedAt: string | null;
  manifestJson: string | null;
  receiptJson: string | null;
  /** SHA-256 over the canonical manifest bytes, computed by the server. */
  manifestSha256: string | null;
  /** Chunk count declared by the manifest and verified against stored objects. */
  declaredChunkCount: number | null;
  verifiedChunkCount: number | null;
}

export interface ChunkRecord {
  id: string;
  recordingId: string;
  /** Sequence number within the recording. Unique per recording. */
  sequenceNumber: number;
  /** Object key in the bucket. */
  objectKey: string;
  sealedBytes: number;
  /** SHA-256 of the sealed bytes, computed by the client, verified by the server. */
  sealedSha256: string;
  /**
   * SHA-256 of the plaintext, asserted by the client.
   *
   * NEVER treated as server-verified: the backend cannot compute it. This is
   * recorded so a future client-side verify can use it, and it is reported in the
   * receipt explicitly labelled `assertedByClient`.
   */
  plaintextSha256: string;
  /** Encryption metadata copied from the client manifest for the audit trail. */
  keyVersion: number;
  encryptionVersion: number;
  receivedAt: string;
}

/**
 * An immutable receipt issued once a recording is finalized.
 *
 * `auditChainHash` links each receipt to the previous one issued to the same
 * user, so removal or reordering of receipts is detectable after the fact. This
 * is *tamper evidence for the server's own records*; it is not a legal proof of
 * anything, and the API says so in its own words (SPEC §50).
 */
export interface UploadReceipt {
  receiptId: string;
  recordingId: string;
  userId: string;
  issuedAt: string;
  /** Server time. Explicitly distinct from the client's device timestamps. */
  serverIssuedAtEpochMillis: number;
  chunkCount: number;
  totalSealedBytes: number;
  manifestSha256: string;
  signatureAlgorithm: string;
  /** Public key third parties use to verify the client's manifest signature. */
  manifestSigningPublicKey: string;
  /** Hash of the previous receipt for this user, or the genesis value. */
  previousReceiptHash: string;
  /** `H(previousReceiptHash || canonicalReceiptBody)`. */
  auditChainHash: string;
  disclaimer: string;
}

export interface PresignedUpload {
  objectKey: string;
  url: string;
  method: 'PUT';
  headers: Record<string, string>;
  /** Epoch millis after which the URL is no longer accepted. */
  expiresAtEpochMillis: number;
}

export interface UploadSession {
  recordingId: string;
  sessionId: string;
  expiresAtEpochMillis: number;
  storagePrefix: string;
  /** Chunks the manifest declares, so a client can detect server-side loss. */
  expectedChunks: { sequenceNumber: number; sealedBytes: number; sealedSha256: string }[];
}

export interface PresignedDownload {
  recordingId: string;
  objectKey: string;
  url: string;
  expiresAtEpochMillis: number;
}

export const RECEIPT_DISCLAIMER =
  'This receipt records that SafeSignal received an encrypted, checksummed set of segments and ' +
  'verified their sizes and sealed-byte hashes. It is not a certificate of authenticity, not legal ' +
  'proof of when or where audio was recorded, and not an assurance of admissibility. Admissibility ' +
  'depends on the applicable jurisdiction and is decided by the relevant authority or court.';
