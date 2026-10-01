import { createHash, randomUUID, timingSafeEqual } from 'node:crypto';

/**
 * Cryptographic helpers used by the backend.
 *
 * The backend performs **no symmetric decryption of evidence**. It verifies
 * checksums and issues receipts. These helpers exist to make "we only verified a
 * hash, we did not verify the audio" an accurate and auditable statement.
 */

export function sha256Hex(input: Buffer | string): string {
  return createHash('sha256').update(input).digest('hex');
}

export function constantTimeEquals(a: string, b: string): boolean {
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  if (left.length !== right.length) {
    // Still perform a comparison so the early return does not leak length by timing.
    timingSafeEqual(left, left);
    return false;
  }
  return timingSafeEqual(left, right);
}

/** Stable JSON: object keys sorted recursively so a hash is reproducible. */
export function canonicalJson(value: unknown): string {
  return JSON.stringify(sortValue(value));
}

function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortValue);
  if (value && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .filter(([, v]) => v !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
    return Object.fromEntries(entries.map(([k, v]) => [k, sortValue(v)]));
  }
  return value;
}

export function newId(prefix: string): string {
  return `${prefix}_${randomUUID().replace(/-/g, '')}`;
}

/**
 * Opaque, non-sequential reference the client addresses a recording by.
 *
 * Distinct from the internal primary key so that a recording id appearing in a
 * client-side log cannot be used to enumerate another user's recordings.
 */
export function newClientReference(): string {
  return randomUUID().replace(/-/g, '');
}

export const GENESIS_HASH = '0'.repeat(64);
