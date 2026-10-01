import { randomBytes, scrypt as scryptCb, timingSafeEqual } from 'node:crypto';
import { promisify } from 'node:util';

const scrypt = promisify(scryptCb) as (
  password: string | Buffer,
  salt: string | Buffer,
  keylen: number,
  options?: { N?: number; r?: number; p?: number; maxmem?: number },
) => Promise<Buffer>;

/**
 * Password hashing for the reference backend.
 *
 * ## Choice: scrypt from the Node standard library
 *
 * `scrypt` is a memory-hard KDF in `node:crypto`, so there is no native addon to
 * compile and no supply-chain dependency. Argon2id would be the stronger choice
 * and is the right production answer, but adding it here means adding a native
 * module to a repository whose most important property is that it is auditable
 * end to end. The trade-off is written down here rather than left implicit:
 *
 *   - N = 2^15, r = 8, p = 1 → roughly 32 MB and ~80 ms per hash on this hardware
 *   - `maxmem` is raised explicitly because Node's default (32 MB) is borderline
 *
 * Moving to Argon2id is a change to this file only; nothing else stores or
 * verifies passwords.
 */

const PARAMS = { N: 32768, r: 8, p: 1, maxmem: 96 * 1024 * 1024 } as const;
const KEY_LENGTH = 64;
const SALT_LENGTH = 16;

export async function hashPassword(password: string): Promise<{ hash: string; salt: string }> {
  if (typeof password !== 'string' || password.length === 0) {
    throw new Error('password must be a non-empty string');
  }
  if (password.length > 1024) {
    // Bounded so a very large body cannot be used to burn server CPU.
    throw new Error('password must be at most 1024 characters');
  }
  const salt = randomBytes(SALT_LENGTH);
  const derived = await scrypt(password, salt, KEY_LENGTH, PARAMS);
  return { hash: derived.toString('base64'), salt: salt.toString('base64') };
}

/**
 * Constant-time verification.
 *
 * Returns false rather than throwing for a malformed stored hash: a corrupt row
 * must not be distinguishable from a wrong password by timing or by status code.
 */
export async function verifyPassword(
  password: string,
  storedHash: string,
  storedSalt: string,
): Promise<boolean> {
  try {
    const salt = Buffer.from(storedSalt, 'base64');
    const expected = Buffer.from(storedHash, 'base64');
    if (salt.length === 0 || expected.length === 0) return false;
    const derived = await scrypt(password, salt, expected.length, PARAMS);
    if (derived.length !== expected.length) return false;
    return timingSafeEqual(derived, expected);
  } catch {
    return false;
  }
}
