import type { FastifyInstance } from 'fastify';
import { buildApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { MemoryRepository } from '../src/repositories/memoryRepository.js';
import { MemoryObjectStore } from '../src/storage/objectStore.js';

/**
 * Test harness.
 *
 * Builds the real app over in-memory storage. Every test in this suite therefore
 * runs the actual routes, the actual auth plugin, the actual error handler and
 * the actual manifest validator — not stand-ins. Only Postgres and S3 are
 * substituted, and neither is where the security properties under test live.
 */

export interface TestContext {
  app: FastifyInstance;
  repository: MemoryRepository;
  objectStore: MemoryObjectStore;
}

export function testConfig(overrides: Record<string, string> = {}) {
  return loadConfig({
    NODE_ENV: 'test',
    JWT_SECRET: 'test-only-secret-not-valid-outside-the-test-suite',
    LOG_LEVEL: 'silent',
    RATE_LIMIT_MAX: '100000',
    ...overrides,
  } as NodeJS.ProcessEnv);
}

export async function buildTestApp(overrides: Record<string, string> = {}): Promise<TestContext> {
  const repository = new MemoryRepository();
  const objectStore = new MemoryObjectStore();
  const app = await buildApp({
    config: testConfig(overrides),
    repository,
    objectStore,
  });
  await app.ready();
  return { app, repository, objectStore };
}

export interface Account {
  id: string;
  email: string;
  accessToken: string;
  refreshToken: string;
}

let accountCounter = 0;

/** Registers a fresh account. Password is fixed-length so length rules always pass. */
export async function registerAccount(
  app: FastifyInstance,
  email = `user${++accountCounter}@example.test`,
  password = 'correct-horse-battery-staple',
): Promise<Account> {
  const response = await app.inject({
    method: 'POST',
    url: '/v1/auth/register',
    payload: { email, password },
  });
  if (response.statusCode !== 201) {
    throw new Error(`register failed: ${response.statusCode} ${response.body}`);
  }
  const body = response.json();
  return {
    id: body.user.id,
    email: body.user.email,
    accessToken: body.accessToken,
    refreshToken: body.refreshToken,
  };
}

export function auth(token: string) {
  return { authorization: `Bearer ${token}` };
}

/** A 64-character hex digest, for tests that need a well-formed but arbitrary hash. */
export function fakeSha256(seed: string): string {
  // Not a real hash. Any 64 lowercase hex chars satisfy validation, and using a
  // obviously-fake value makes it obvious in a failure that no crypto is involved.
  return seed.padEnd(64, '0').slice(0, 64).replace(/[^0-9a-f]/g, 'a');
}

/** Builds a structurally valid manifest for a given chunk set. */
export function buildManifest(opts: {
  recordingId: string;
  deviceRecordingId: string;
  segments: Array<{ sequenceNumber: number; sealedBytes: number; sealedSha256: string }>;
  activationSource?: string;
}): Record<string, unknown> {
  return {
    manifestVersion: 1,
    recordingId: opts.recordingId,
    deviceRecordingId: opts.deviceRecordingId,
    startedAtUtc: '2026-10-01T00:00:00.000Z',
    endedAtUtc: '2026-10-01T00:01:00.000Z',
    activationSource: opts.activationSource ?? 'VOICE',
    activationDetail: null,
    sampleRateHz: 48000,
    channels: 1,
    bitsPerSample: 16,
    segments: opts.segments.map((s) => ({
      ...s,
      plaintextSha256: fakeSha256(`pt-${s.sequenceNumber}`),
      keyVersion: 1,
      encryptionVersion: 1,
    })),
    signature: 'QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVphYmNkZWZnaGlqa2xtbm9wcXJzdHV2d3h5eg==',
    signatureAlgorithm: 'SHA256withECDSA',
    signingPublicKey: 'QUJDREVGRw==',
  };
}
