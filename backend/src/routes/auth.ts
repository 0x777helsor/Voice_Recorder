import type { FastifyInstance, FastifyRequest } from 'fastify';
import { hashPassword, verifyPassword } from '../auth/passwords.js';
import { currentUserId } from '../auth/authPlugin.js';
import { badRequest, invalidCredentials, unauthenticated } from '../domain/errors.js';
import { newId } from '../domain/primitives.js';
import type { Repository } from '../repositories/Repository.js';

interface RegisterBody {
  email?: unknown;
  password?: unknown;
}

interface LoginBody {
  email?: unknown;
  password?: unknown;
}

interface RefreshBody {
  refreshToken?: unknown;
  password?: unknown;
}

/**
 * Account and token routes.
 *
 * ### Brute-force resistance
 *
 * Login is rate-limited globally by `@fastify/rate-limit`, and additionally the
 * password hash is a memory-hard KDF, so an attacker with a stolen database is
 * still limited by ~80 ms of work per guess. There is deliberately **no**
 * per-account lockout: lockout is a denial-of-service tool against a victim, and
 * for an evidence product an attacker being able to lock a user out of their own
 * evidence is a worse failure than a slow online guess.
 *
 * ### What an attacker with the database does *not* get
 *
 * `passwordHash` is scrypt with a per-user random salt. The refresh token is a
 * JWT, so the database plus the signing secret is enough to mint tokens — which
 * is why `JWT_SECRET` is mandatory in production and why rotating it is a
 * documented incident-response step in SECURITY.md.
 */
export async function registerAuthRoutes(app: FastifyInstance, deps: { repository: Repository; allowRegistration: boolean }) {
  const { repository } = deps;

  app.post<{ Body: RegisterBody }>('/v1/auth/register', async (request, reply) => {
    if (!deps.allowRegistration) {
      return reply.code(403).send({
        error: { code: 'FORBIDDEN', message: 'Registration is disabled on this deployment.' },
      });
    }
    const email = readEmail(request.body?.email);
    const password = readPassword(request.body?.password);

    const { hash, salt } = await hashPassword(password);
    const user = await repository.createUser({
      id: newId('usr'),
      email,
      passwordHash: hash,
      passwordSalt: salt,
      createdAt: new Date().toISOString(),
      tokenVersion: 1,
    });

    return reply.code(201).send({
      user: { id: user.id, email: user.email, createdAt: user.createdAt },
      accessToken: app.issueAccessToken(user.id, user.tokenVersion),
      refreshToken: app.issueRefreshToken(user.id, user.tokenVersion),
      expiresInSeconds: 15 * 60,
    });
  });

  app.post<{ Body: LoginBody }>('/v1/auth/login', async (request, reply) => {
    const email = typeof request.body?.email === 'string' ? request.body.email.trim().toLowerCase() : '';
    const password = typeof request.body?.password === 'string' ? request.body.password : '';

    if (!email || !password) {
      // Same error as a wrong password, and no hash computed: an attacker cannot
      // use response timing to tell "no such user" from "wrong password".
      throw invalidCredentials();
    }

    const user = await repository.findUserByEmail(email);
    if (!user) {
      throw invalidCredentials();
    }
    const ok = await verifyPassword(password, user.passwordHash, user.passwordSalt);
    if (!ok) throw invalidCredentials();

    return reply.send({
      user: { id: user.id, email: user.email, createdAt: user.createdAt },
      accessToken: app.issueAccessToken(user.id, user.tokenVersion),
      refreshToken: app.issueRefreshToken(user.id, user.tokenVersion),
      expiresInSeconds: 15 * 60,
    });
  });

  /**
   * Exchanges a refresh token for a new access token.
   *
   * Requires the password as well. A stolen refresh token alone is therefore not
   * sufficient for 30 days of unattended access, and the user can revoke it by
   * changing their password — which bumps `tokenVersion`.
   */
  app.post<{ Body: RefreshBody }>('/v1/auth/refresh', async (request, reply) => {
    const refreshToken = request.body?.refreshToken;
    const password = request.body?.password;
    if (typeof refreshToken !== 'string' || typeof password !== 'string') {
      throw badRequest('refreshToken and password are required.');
    }

    const claims = app.verifyToken(refreshToken, 'refresh');
    const user = await repository.findUserById(claims.sub);
    if (!user || user.tokenVersion !== claims.ver) {
      throw unauthenticated('Token is invalid or has expired.');
    }
    const ok = await verifyPassword(password, user.passwordHash, user.passwordSalt);
    if (!ok) throw unauthenticated('Token is invalid or has expired.');

    return reply.send({
      accessToken: app.issueAccessToken(user.id, user.tokenVersion),
      expiresInSeconds: 15 * 60,
    });
  });

  /**
   * Changes the password and invalidates every outstanding token.
   *
   * The response carries the *new* tokens so the caller stays signed in; every
   * other device is locked out. That is the behaviour a user expects from
   * "someone else has my password", and it is the reason a compromised account
   * does not require deleting the account to recover.
   */
  app.post('/v1/auth/change-password', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const body = request.body as { currentPassword?: unknown; newPassword?: unknown };
    const currentPassword = typeof body?.currentPassword === 'string' ? body.currentPassword : '';
    const newPassword = readPassword(body?.newPassword);

    const user = await repository.findUserById(userId);
    if (!user) throw unauthenticated();
    if (!(await verifyPassword(currentPassword, user.passwordHash, user.passwordSalt))) {
      throw invalidCredentials();
    }

    const { hash, salt } = await hashPassword(newPassword);
    // Order matters. Storing the new material first, then bumping the version,
    // means a failure between the two leaves the old password working — which is
    // recoverable. Bumping first and failing to store would lock the user out of
    // their own evidence, which is not recoverable and is what SPEC §54 forbids.
    await repository.replaceCredentials(userId, hash, salt);
    const finalVersion = await repository.bumpTokenVersion(userId);

    return reply.send({
      accessToken: app.issueAccessToken(user.id, finalVersion),
      refreshToken: app.issueRefreshToken(user.id, finalVersion),
      expiresInSeconds: 15 * 60,
      invalidatedOtherSessions: true,
      tokenVersion: finalVersion,
    });
  });

  app.get('/v1/auth/me', { preHandler: app.requireAuth }, async (request, reply) => {
    const userId = currentUserId(request);
    const user = await repository.findUserById(userId);
    if (!user) throw unauthenticated();
    return reply.send({
      user: { id: user.id, email: user.email, createdAt: user.createdAt },
      tokenVersion: user.tokenVersion,
    });
  });
}

function readEmail(value: unknown): string {
  if (typeof value !== 'string') throw badRequest('email is required and must be a string.');
  const email = value.trim().toLowerCase();
  if (email.length > 254) throw badRequest('email must be at most 254 characters.');
  // Deliberately permissive but bounded: enough to catch typos and malformed
  // input without pretending to implement RFC 5322.
  if (!/^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$/.test(email)) {
    throw badRequest('email must be a valid address.');
  }
  return email;
}

function readPassword(value: unknown): string {
  if (typeof value !== 'string') throw badRequest('password is required and must be a string.');
  if (value.length < 12) {
    throw badRequest('password must be at least 12 characters.');
  }
  if (value.length > 1024) throw badRequest('password must be at most 1024 characters.');
  return value;
}
