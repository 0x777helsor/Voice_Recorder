import { canonicalJson, sha256Hex } from './primitives.js';
import { badRequest, manifestIncomplete } from './errors.js';

/**
 * Manifest validation.
 *
 * ### The server is not the audio's custodian, it is its courier
 *
 * The manifest arrives from the client and the server *shape-checks* it: the
 * declared chunk set must be structurally sound, self-consistent, and free of
 * path-traversal or oversized claims. What the server **cannot** do is decide
 * whether the manifest truthfully describes the audio — that requires the
 * signing key and the plaintext, and it has neither.
 *
 * So validation here is about *denying obvious abuse and detecting server-side
 * loss*, never about attesting to content. The receipt says so in its own text.
 */

export interface ManifestSegment {
  sequenceNumber: number;
  sealedBytes: number;
  sealedSha256: string;
  /** Client-asserted plaintext digest. Recorded, never verified. */
  plaintextSha256: string;
  keyVersion: number;
  encryptionVersion: number;
}

export interface EvidenceManifest {
  manifestVersion: number;
  recordingId: string;
  /** Client-side recording id, for cross-referencing the device's own log. */
  deviceRecordingId: string;
  startedAtUtc: string;
  endedAtUtc: string | null;
  /** Which trigger started this recording. Mirrors the Android enum. */
  activationSource: string;
  activationDetail: string | null;
  sampleRateHz: number;
  channels: number;
  bitsPerSample: number;
  segments: ManifestSegment[];
  /** Base64 ECDSA P-256 signature over `canonicalJson(manifestWithoutSignature)`. */
  signature: string;
  signatureAlgorithm: string;
  /** Base64 SPKI of the device signing public key. */
  signingPublicKey: string;
}

export interface ValidatedManifest {
  manifest: EvidenceManifest;
  manifestSha256: string;
  /** Sum of declared sealed bytes. Used to detect incomplete uploads. */
  declaredTotalBytes: number;
  sequenceNumbers: number[];
}

const SHA256_HEX = /^[0-9a-f]{64}$/;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;

/** Hard ceilings. A manifest claiming 4 million segments is rejected, not stored. */
export const MAX_SEGMENTS = 10_000;
export const MAX_SEGMENT_BYTES = 256 * 1024 * 1024;
export const MAX_TOTAL_BYTES = 8 * 1024 * 1024 * 1024;

const ACTIVATION_SOURCES = new Set([
  'VOICE',
  'PHYSICAL_BUTTON',
  'NOTIFICATION',
  'WIDGET',
  'BLUETOOTH',
  'HEADSET',
  'TEST',
]);

export function validateManifest(input: unknown): ValidatedManifest {
  const raw = requireObject(input, 'manifest');
  const manifest = raw as unknown as EvidenceManifest;

  if (typeof manifest.manifestVersion !== 'number' || manifest.manifestVersion < 1) {
    throw badRequest('manifestVersion must be a positive integer.');
  }
  for (const field of ['recordingId', 'deviceRecordingId', 'startedAtUtc'] as const) {
    if (!isNonEmptyString(manifest[field])) {
      throw badRequest(`${field} is required and must be a non-empty string.`);
    }
  }
  if (!ACTIVATION_SOURCES.has(manifest.activationSource)) {
    throw badRequest('activationSource is not a recognised activation source.', {
      received: String(manifest.activationSource),
    });
  }
  if (!isIsoTimestamp(manifest.startedAtUtc)) {
    throw badRequest('startedAtUtc must be an ISO-8601 timestamp.');
  }
  if (manifest.endedAtUtc !== null && !isIsoTimestamp(manifest.endedAtUtc)) {
    throw badRequest('endedAtUtc must be null or an ISO-8601 timestamp.');
  }
  if (!isPositiveInt(manifest.sampleRateHz) || manifest.sampleRateHz > 384_000) {
    throw badRequest('sampleRateHz must be a positive integer no greater than 384000.');
  }
  if (!isPositiveInt(manifest.channels) || manifest.channels > 8) {
    throw badRequest('channels must be an integer between 1 and 8.');
  }
  if (![8, 16, 24, 32].includes(manifest.bitsPerSample)) {
    throw badRequest('bitsPerSample must be one of 8, 16, 24, 32.');
  }
  if (!isNonEmptyString(manifest.signature) || !BASE64.test(manifest.signature)) {
    throw badRequest('signature is required and must be base64.');
  }
  if (!isNonEmptyString(manifest.signingPublicKey) || !BASE64.test(manifest.signingPublicKey)) {
    throw badRequest('signingPublicKey is required and must be base64.');
  }
  if (manifest.signatureAlgorithm !== 'SHA256withECDSA') {
    // Pinned rather than accepted from the client: an unexpected algorithm name
    // must fail loudly, not be recorded and ignored.
    throw badRequest('signatureAlgorithm must be SHA256withECDSA.', {
      received: String(manifest.signatureAlgorithm),
    });
  }
  // recordingId and deviceRecordingId end up in object keys. Reject anything
  // that could traverse out of the user's prefix.
  assertKeySafe(manifest.recordingId, 'recordingId');
  assertKeySafe(manifest.deviceRecordingId, 'deviceRecordingId');

  if (!Array.isArray(manifest.segments)) {
    throw badRequest('segments must be an array.');
  }
  if (manifest.segments.length === 0) {
    throw badRequest('A finalized recording must declare at least one segment.');
  }
  if (manifest.segments.length > MAX_SEGMENTS) {
    throw badRequest(`A manifest may declare at most ${MAX_SEGMENTS} segments.`, {
      received: manifest.segments.length,
    });
  }

  const seen = new Set<number>();
  let total = 0;
  for (const [index, segment] of manifest.segments.entries()) {
    const where = `segments[${index}]`;
    const seg = requireObject(segment, where);
    const s = seg as unknown as ManifestSegment;

    if (!isPositiveInt(s.sequenceNumber)) {
      throw badRequest(`${where}.sequenceNumber must be a positive integer.`);
    }
    if (seen.has(s.sequenceNumber)) {
      throw badRequest(`${where}.sequenceNumber duplicates an earlier segment.`, {
        sequenceNumber: s.sequenceNumber,
      });
    }
    seen.add(s.sequenceNumber);

    if (!isPositiveInt(s.sealedBytes) || s.sealedBytes > MAX_SEGMENT_BYTES) {
      throw badRequest(`${where}.sealedBytes must be a positive integer within limits.`, {
        limit: MAX_SEGMENT_BYTES,
      });
    }
    if (!isSha256(s.sealedSha256)) {
      throw badRequest(`${where}.sealedSha256 must be 64 lowercase hex characters.`);
    }
    if (!isSha256(s.plaintextSha256)) {
      throw badRequest(`${where}.plaintextSha256 must be 64 lowercase hex characters.`);
    }
    if (!isPositiveInt(s.keyVersion) || !isPositiveInt(s.encryptionVersion)) {
      throw badRequest(`${where}.keyVersion and .encryptionVersion must be positive integers.`);
    }
    total += s.sealedBytes;
    if (total > MAX_TOTAL_BYTES) {
      throw badRequest(`Declared total exceeds ${MAX_TOTAL_BYTES} bytes.`);
    }
  }

  const canonical = canonicalManifestBytes(manifest);
  return {
    manifest,
    manifestSha256: sha256Hex(canonical),
    declaredTotalBytes: total,
    sequenceNumbers: [...seen].sort((a, b) => a - b),
  };
}

/**
 * Canonical bytes covered by the device signature.
 *
 * The signature field itself is excluded — a signature cannot cover itself. The
 * key ordering is fixed by [canonicalJson], so the client and server agree
 * without negotiating a serialisation format.
 */
export function canonicalManifestBytes(manifest: EvidenceManifest): string {
  const { signature: _signature, ...rest } = manifest;
  return canonicalJson(rest);
}

/**
 * Compares the stored chunk set against the manifest.
 *
 * Two distinct failures, reported differently:
 *
 *  - a **missing** sequence number means the server lost data, or the client
 *    tried to finalize early. This is the `MANIFEST_INCOMPLETE` case and is
 *    never finalizable.
 *  - a **mismatched size or hash** means what was stored is not what was
 *    declared. Also fatal, and surfaced as a conflict so the operator can
 *    investigate rather than the client retrying forever.
 */
export function assertChunksSatisfyManifest(
  expected: ManifestSegment[],
  actual: Array<{ sequenceNumber: number; sealedBytes: number; sealedSha256: string }>,
): void {
  const bySequence = new Map(actual.map((c) => [c.sequenceNumber, c]));
  const missing: number[] = [];
  const mismatched: Array<{ sequenceNumber: number; field: string }> = [];

  for (const want of expected) {
    const got = bySequence.get(want.sequenceNumber);
    if (!got) {
      missing.push(want.sequenceNumber);
      continue;
    }
    if (got.sealedBytes !== want.sealedBytes) mismatched.push({ sequenceNumber: want.sequenceNumber, field: 'sealedBytes' });
    if (got.sealedSha256 !== want.sealedSha256) mismatched.push({ sequenceNumber: want.sequenceNumber, field: 'sealedSha256' });
  }
  const unexpected = actual
    .map((c) => c.sequenceNumber)
    .filter((n) => !expected.some((e) => e.sequenceNumber === n));

  if (missing.length > 0 || mismatched.length > 0 || unexpected.length > 0) {
    throw manifestIncomplete('Stored segments do not satisfy the declared manifest.', {
      missingSequenceNumbers: missing,
      mismatched,
      unexpectedSequenceNumbers: unexpected,
    });
  }
}

// ------------------------------------------------------------------ helpers

function requireObject(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw badRequest(`${field} must be an object.`);
  }
  return value as Record<string, unknown>;
}

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

function isPositiveInt(value: unknown): value is number {
  return typeof value === 'number' && Number.isInteger(value) && value > 0;
}

function isSha256(value: unknown): value is string {
  return typeof value === 'string' && SHA256_HEX.test(value);
}

function isIsoTimestamp(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed);
}

/**
 * Rejects identifiers that could escape a storage prefix or a database query.
 *
 * Only the characters SafeSignal itself emits are allowed. This is stricter than
 * necessary — a legitimate id never contains punctuation here — and strictness is
 * the correct default for a value that becomes part of an object key.
 */
function assertKeySafe(value: string, field: string): void {
  if (!/^[A-Za-z0-9_-]{1,128}$/.test(value)) {
    throw badRequest(`${field} must contain only letters, digits, underscore or hyphen (max 128).`);
  }
}
