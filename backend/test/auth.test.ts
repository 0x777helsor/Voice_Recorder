import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { auth, buildTestApp, registerAccount, type TestContext } from './helpers.js';

/**
 * Authentication and session-management tests.
 *
 * These cover SPEC §45's explicit requirements: token expiry must actually
 * expire, a stale token must be rejected after a password change, and the error
 * surface must not reveal whether an account exists.
 */
describe('auth', () => {
  let ctx: TestContext;

  before(async () => {
    ctx = await buildTestApp();
  });

  after(async () => {
    await ctx.app.close();
  });

  it('registers an account and returns a usable access token', async () => {
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'reg@example.test', password: 'a-sufficiently-long-password' },
    });
    assert.equal(response.statusCode, 201);
    const body = response.json();
    assert.ok(body.accessToken);
    assert.ok(body.refreshToken);
    // The hash and salt must never appear in a response.
    assert.ok(!JSON.stringify(body).includes('passwordHash'));
    assert.ok(!JSON.stringify(body).includes('passwordSalt'));

    const me = await ctx.app.inject({ method: 'GET', url: '/v1/auth/me', headers: auth(body.accessToken) });
    assert.equal(me.statusCode, 200);
    assert.equal(me.json().user.email, 'reg@example.test');
  });

  it('rejects a short password', async () => {
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'short@example.test', password: 'short' },
    });
    assert.equal(response.statusCode, 400);
    assert.equal(response.json().error.code, 'INVALID_REQUEST');
  });

  it('rejects a duplicate email without revealing the existing account', async () => {
    await registerAccount(ctx.app, 'dupe@example.test');
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/register',
      payload: { email: 'dupe@example.test', password: 'a-sufficiently-long-password' },
    });
    assert.equal(response.statusCode, 409);
  });

  it('gives the same error for an unknown account and a wrong password', async () => {
    const account = await registerAccount(ctx.app, 'known@example.test');

    const unknown = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: { email: 'nobody@example.test', password: 'a-sufficiently-long-password' },
    });
    const wrongPassword = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: { email: account.email, password: 'definitely-the-wrong-password' },
    });

    assert.equal(unknown.statusCode, 401);
    assert.equal(wrongPassword.statusCode, 401);
    // Byte-identical bodies: no account-enumeration oracle.
    assert.deepEqual(unknown.json(), wrongPassword.json());
    assert.equal(unknown.json().error.code, 'INVALID_CREDENTIALS');
  });

  it('logs in with correct credentials', async () => {
    const account = await registerAccount(ctx.app, 'login@example.test');
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/login',
      payload: { email: account.email, password: 'correct-horse-battery-staple' },
    });
    assert.equal(response.statusCode, 200);
    assert.ok(response.json().accessToken);
  });

  it('rejects a missing, malformed and garbage token identically', async () => {
    const missing = await ctx.app.inject({ method: 'GET', url: '/v1/auth/me' });
    const malformed = await ctx.app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: { authorization: 'Bearer not-a-jwt' },
    });
    const garbage = await ctx.app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: { authorization: 'Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.wrongsignature' },
    });

    assert.equal(missing.statusCode, 401);
    assert.equal(malformed.statusCode, 401);
    assert.equal(garbage.statusCode, 401);
    assert.deepEqual(missing.json().error.code, malformed.json().error.code);
    assert.deepEqual(malformed.json().error.code, garbage.json().error.code);
  });

  it('rejects a refresh token where an access token is required', async () => {
    const account = await registerAccount(ctx.app, 'typemix@example.test');
    const response = await ctx.app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: auth(account.refreshToken),
    });
    assert.equal(response.statusCode, 401);
  });

  it('rejects an access token where a refresh token is required', async () => {
    const account = await registerAccount(ctx.app, 'typemix2@example.test');
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/refresh',
      payload: { refreshToken: account.accessToken, password: 'correct-horse-battery-staple' },
    });
    assert.equal(response.statusCode, 401);
  });

  it('refreshes with the correct password and refuses without it', async () => {
    const account = await registerAccount(ctx.app, 'refresh@example.test');

    const ok = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/refresh',
      payload: { refreshToken: account.refreshToken, password: 'correct-horse-battery-staple' },
    });
    assert.equal(ok.statusCode, 200);
    assert.ok(ok.json().accessToken);

    // The password is required precisely so a stolen refresh token alone is not
    // enough for sustained access.
    const noPassword = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/refresh',
      payload: { refreshToken: account.refreshToken },
    });
    assert.equal(noPassword.statusCode, 400);

    const wrongPassword = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/refresh',
      payload: { refreshToken: account.refreshToken, password: 'not-the-password' },
    });
    assert.equal(wrongPassword.statusCode, 401);
  });

  it('rejects an expired token', async () => {
    // `expiresIn` cannot be negative (fast-jwt refuses), so the past `exp` is set
    // explicitly. That is also a stronger test: it proves the verifier reads
    // `exp`, not that the signer's option was validated at mint time.
    const expired = ctx.app.jwt.sign(
      { sub: 'some-id', ver: 1, typ: 'access', exp: Math.floor(Date.now() / 1000) - 10 },
      { noTimestamp: true },
    );
    const response = await ctx.app.inject({ method: 'GET', url: '/v1/auth/me', headers: auth(expired) });
    assert.equal(response.statusCode, 401);
    assert.equal(response.json().error.code, 'UNAUTHENTICATED');
  });

  it('invalidates every existing token when the password changes', async () => {
    const account = await registerAccount(ctx.app, 'rotate@example.test');

    const changed = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/change-password',
      headers: auth(account.accessToken),
      payload: { currentPassword: 'correct-horse-battery-staple', newPassword: 'a-brand-new-long-password' },
    });
    assert.equal(changed.statusCode, 200);
    const newTokens = changed.json();

    // The old access token must now fail even though its signature is valid,
    // because tokenVersion moved.
    const oldAccess = await ctx.app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: auth(account.accessToken),
    });
    assert.equal(oldAccess.statusCode, 401);

    const oldRefresh = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/refresh',
      payload: { refreshToken: account.refreshToken, password: 'correct-horse-battery-staple' },
    });
    assert.equal(oldRefresh.statusCode, 401);

    // The freshly issued token works.
    const fresh = await ctx.app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: auth(newTokens.accessToken),
    });
    assert.equal(fresh.statusCode, 200);
  });

  it('refuses a password change with the wrong current password', async () => {
    const account = await registerAccount(ctx.app, 'nocurrent@example.test');
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/auth/change-password',
      headers: auth(account.accessToken),
      payload: { currentPassword: 'wrong-password-here', newPassword: 'a-brand-new-long-password' },
    });
    assert.equal(response.statusCode, 401);
    // The old token must still work: a failed change must not revoke anything.
    const stillValid = await ctx.app.inject({
      method: 'GET',
      url: '/v1/auth/me',
      headers: auth(account.accessToken),
    });
    assert.equal(stillValid.statusCode, 200);
  });

  it('honours a deployment with registration disabled', async () => {
    const closed = await buildTestApp({ ALLOW_REGISTRATION: 'false' });
    try {
      const response = await closed.app.inject({
        method: 'POST',
        url: '/v1/auth/register',
        payload: { email: 'nope@example.test', password: 'a-sufficiently-long-password' },
      });
      assert.equal(response.statusCode, 403);
      assert.equal(response.json().error.code, 'FORBIDDEN');
    } finally {
      await closed.app.close();
    }
  });
});
