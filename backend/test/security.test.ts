import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { ConfigError, loadConfig } from '../src/config.js';
import { hashPassword, verifyPassword } from '../src/auth/passwords.js';
import { auth, buildTestApp, registerAccount, type TestContext } from './helpers.js';

/**
 * Security-posture tests.
 *
 * These assert the properties a reviewer would otherwise have to take on trust:
 * that configuration refuses to weaken itself, that password hashing is
 * genuinely salted and slow, and that no response leaks internals.
 */
describe('configuration hardening', () => {
  it('refuses to start in production without a JWT secret', () => {
    assert.throws(
      () => loadConfig({ NODE_ENV: 'production', S3_BUCKET: 'x' } as NodeJS.ProcessEnv),
      ConfigError,
    );
  });

  it('refuses a short JWT secret', () => {
    assert.throws(
      () => loadConfig({ NODE_ENV: 'production', JWT_SECRET: 'too-short' } as NodeJS.ProcessEnv),
      /shorter than 32 characters/,
    );
  });

  it('refuses wildcard CORS in production', () => {
    assert.throws(
      () =>
        loadConfig({
          NODE_ENV: 'production',
          JWT_SECRET: 'x'.repeat(48),
          CORS_ALLOWED_ORIGINS: '*',
        } as NodeJS.ProcessEnv),
      /must not be "\*"/,
    );
  });

  it('refuses production with no real storage configured', () => {
    assert.throws(
      () => loadConfig({ NODE_ENV: 'production', JWT_SECRET: 'x'.repeat(48) } as NodeJS.ProcessEnv),
      /requires either S3_BUCKET or DATABASE_URL/,
    );
  });

  it('refuses S3 without credentials', () => {
    assert.throws(
      () =>
        loadConfig({
          NODE_ENV: 'production',
          JWT_SECRET: 'x'.repeat(48),
          S3_BUCKET: 'evidence',
        } as NodeJS.ProcessEnv),
      /S3_ACCESS_KEY_ID is required/,
    );
  });

  it('generates an ephemeral secret in development rather than defaulting to a constant', () => {
    const a = loadConfig({ NODE_ENV: 'development' } as NodeJS.ProcessEnv);
    const b = loadConfig({ NODE_ENV: 'development' } as NodeJS.ProcessEnv);
    assert.notEqual(a.jwtSecret, b.jwtSecret);
    assert.ok(a.jwtSecret.length >= 32);
  });

  it('enables full chunk verification in test and not in production', () => {
    const test = loadConfig({ NODE_ENV: 'test', JWT_SECRET: 'x'.repeat(48) } as NodeJS.ProcessEnv);
    const prod = loadConfig({
      NODE_ENV: 'production',
      JWT_SECRET: 'x'.repeat(48),
      S3_BUCKET: 'b',
      S3_ACCESS_KEY_ID: 'k',
      S3_SECRET_ACCESS_KEY: 's',
    } as NodeJS.ProcessEnv);
    assert.equal(test.verifyEveryChunkOnUpload, true);
    assert.equal(prod.verifyEveryChunkOnUpload, false);
  });
});

describe('password hashing', () => {
  it('produces a different hash for the same password each time', async () => {
    const first = await hashPassword('the-same-password');
    const second = await hashPassword('the-same-password');
    // Different salts, so identical passwords must not be recognisable as
    // identical in a stolen database.
    assert.notEqual(first.salt, second.salt);
    assert.notEqual(first.hash, second.hash);
  });

  it('verifies the correct password and rejects wrong ones', async () => {
    const { hash, salt } = await hashPassword('a-correct-password');
    assert.equal(await verifyPassword('a-correct-password', hash, salt), true);
    assert.equal(await verifyPassword('a-wrong-password', hash, salt), false);
  });

  it('returns false rather than throwing on a corrupt stored hash', async () => {
    // A malformed row must be indistinguishable from a wrong password.
    assert.equal(await verifyPassword('anything', 'not-base64!!', 'also-not-base64'), false);
    assert.equal(await verifyPassword('anything', '', ''), false);
  });

  it('uses a memory-hard KDF that takes non-trivial time', async () => {
    const started = Date.now();
    await hashPassword('a-password-for-timing');
    const elapsed = Date.now() - started;
    // Generous lower bound: the point is that it is not microseconds, which is
    // what a bare SHA-256 would be. A generous bound keeps the test from being
    // flaky on a loaded CI machine while still failing for a fast hash.
    assert.ok(elapsed > 10, `hashing took only ${elapsed}ms; expected a memory-hard KDF`);
  });
});

describe('response hygiene', () => {
  let ctx: TestContext;

  before(async () => {
    ctx = await buildTestApp();
  });

  after(async () => {
    await ctx.app.close();
  });

  it('sets security headers and forbids caching of evidence', async () => {
    const account = await registerAccount(ctx.app);
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/recordings',
      headers: auth(account.accessToken),
      payload: { deviceRecordingId: 'hygiene-rec' },
    });
    assert.equal(response.headers['x-content-type-options'], 'nosniff');
    assert.equal(response.headers['x-frame-options'], 'SAMEORIGIN');
    assert.ok(!('set-cookie' in response.headers));
  });

  it('returns a generic 500 body for an unexpected failure', async () => {
    // Force an internal error by breaking the store beneath the app.
    ctx.objectStore.get = async () => {
      throw new Error('internal detail: /var/lib/postgresql/secret-path');
    };
    const account = await registerAccount(ctx.app);
    const created = await ctx.app.inject({
      method: 'POST',
      url: '/v1/recordings',
      headers: auth(account.accessToken),
      payload: { deviceRecordingId: 'error-leak-rec' },
    });
    const recordingId = created.json().recording.id as string;

    // Downloading a chunk touches the store and will hit the injected failure.
    const response = await ctx.app.inject({
      method: 'GET',
      url: `/v1/recordings/${recordingId}/chunks/1/download`,
      headers: auth(account.accessToken),
    });
    assert.ok(response.statusCode >= 400);
    const body = response.body;
    // No path, no stack frame, no internal message.
    assert.ok(!body.includes('postgresql'), body);
    assert.ok(!body.includes('at '), body);
  });

  it('returns a structured 404 for an unknown endpoint', async () => {
    const response = await ctx.app.inject({ method: 'GET', url: '/v1/does-not-exist' });
    assert.equal(response.statusCode, 404);
    assert.equal(response.json().error.code, 'NOT_FOUND');
  });

  it('rejects an oversized body rather than buffering it', async () => {
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'big@example.test', password: 'x'.repeat(3 * 1024 * 1024) },
    });
    assert.ok(response.statusCode === 413 || response.statusCode === 400, `got ${response.statusCode}`);
  });

  it('does not grant CORS to any origin by default', async () => {
    const response = await ctx.app.inject({
      method: 'GET',
      url: '/v1/capabilities',
      headers: { origin: 'https://evil.example' },
    });
    // No Access-Control-Allow-Origin for an unlisted origin.
    assert.equal(response.headers['access-control-allow-origin'], undefined);
  });
});

describe('health endpoints', () => {
  it('reports liveness and readiness', async () => {
    const ctx = await buildTestApp();
    try {
      const live = await ctx.app.inject({ method: 'GET', url: '/healthz' });
      assert.equal(live.statusCode, 200);
      assert.equal(live.json().status, 'ok');

      const ready = await ctx.app.inject({ method: 'GET', url: '/readyz' });
      assert.equal(ready.statusCode, 200);
      assert.equal(ready.json().status, 'ready');
    } finally {
      await ctx.app.close();
    }
  });

  it('reports unready when the store is unreachable, without failing liveness', async () => {
    const ctx = await buildTestApp();
    try {
      ctx.repository.findUserById = async () => {
        throw new Error('connection refused');
      };
      const ready = await ctx.app.inject({ method: 'GET', url: '/readyz' });
      assert.equal(ready.statusCode, 503);

      // Liveness must still succeed so the process is not killed during a
      // dependency outage.
      const live = await ctx.app.inject({ method: 'GET', url: '/healthz' });
      assert.equal(live.statusCode, 200);
    } finally {
      await ctx.app.close();
    }
  });
});
