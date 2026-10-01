import fp from 'fastify-plugin';
import fastifyJwt from '@fastify/jwt';
import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { unauthenticated } from '../domain/errors.js';
import type { Repository } from '../repositories/Repository.js';

/**
 * Authentication and request identity.
 *
 * ### Token design
 *
 * Two token types with different jobs:
 *
 *  - **access token** — short-lived (default 15 min), carries `sub`, `ver`.
 *  - **refresh token** — long-lived (default 30 days), carries `sub`, `ver`, and
 *    a `typ: "refresh"` marker.
 *
 * `ver` is the user's `tokenVersion`. Every password change and every explicit
 * "sign out everywhere" bumps it, which invalidates all outstanding tokens
 * immediately. This is the standard defence against a stolen refresh token that
 * cannot be individually revoked.
 *
 * The refresh endpoint requires a refresh token *and* the current password.
 * That makes a stolen refresh token alone insufficient, and it means revoking
 * access is a matter of changing the password the attacker does not know.
 */
export interface AuthClaims {
  sub: string;
  ver: number;
  /** `access` or `refresh`. A refresh token is rejected by `requireAuth`. */
  typ: 'access' | 'refresh';
}

declare module 'fastify' {
  interface FastifyInstance {
    /** Signs a short-lived access token. */
    issueAccessToken(userId: string, tokenVersion: number): string;
    issueRefreshToken(userId: string, tokenVersion: number): string;
    /** Verifies a token of the given type. Throws if invalid or wrong type. */
    verifyToken(token: string, typ: 'access' | 'refresh'): AuthClaims;
    /** Requires a valid access token; attaches `request.currentUser`. */
    requireAuth(request: FastifyRequest, reply: FastifyReply): Promise<void>;
  }

  interface FastifyRequest {
    currentUser?: { id: string; tokenVersion: number };
  }
}

export const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
export const REFRESH_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;

export const authPlugin = fp(async (app: FastifyInstance, opts: { jwtSecret: string }) => {
  await app.register(fastifyJwt, { secret: opts.jwtSecret });

  app.decorate('issueAccessToken', (userId: string, tokenVersion: number) =>
    app.jwt.sign({ sub: userId, ver: tokenVersion, typ: 'access' }, { expiresIn: ACCESS_TOKEN_TTL_SECONDS }),
  );

  app.decorate('issueRefreshToken', (userId: string, tokenVersion: number) =>
    app.jwt.sign({ sub: userId, ver: tokenVersion, typ: 'refresh' }, { expiresIn: REFRESH_TOKEN_TTL_SECONDS }),
  );

  app.decorate('verifyToken', (token: string, typ: 'access' | 'refresh') => {
    let claims: AuthClaims;
    try {
      claims = app.jwt.verify<AuthClaims>(token);
    } catch (cause) {
      // Expired, malformed and wrong-signature all collapse to one failure, so a
      // client cannot learn *why* a token was rejected.
      throw unauthenticated('Token is invalid or has expired.');
    }
    if (claims.typ !== typ) {
      throw unauthenticated('Token is invalid or has expired.');
    }
    return claims;
  });

  app.decorate('requireAuth', async (request: FastifyRequest, _reply: FastifyReply) => {
    const header = request.headers.authorization;
    if (!header || !header.startsWith('Bearer ')) {
      throw unauthenticated('A Bearer access token is required.');
    }
    const claims = app.verifyToken(header.slice('Bearer '.length).trim(), 'access');

    // A token signed with the right secret but carrying a stale version was
    // issued before a password change. Reject it here rather than trusting the
    // signature alone.
    const repository = app.hasDecorator('repository')
      ? (app as unknown as { repository: Repository }).repository
      : undefined;
    if (repository) {
      const user = await repository.findUserById(claims.sub);
      if (!user) throw unauthenticated('Token is invalid or has expired.');
      if (user.tokenVersion !== claims.ver) {
        throw unauthenticated('Token is invalid or has expired.');
      }
    }
    request.currentUser = { id: claims.sub, tokenVersion: claims.ver };
  });
});

/**
 * Reads the authenticated user id off the request.
 *
 * Only valid after `requireAuth` has run. Throws rather than returning
 * `undefined` so a route that forgets the preHandler fails loudly.
 */
export function currentUserId(request: FastifyRequest): string {
  if (!request.currentUser) {
    throw unauthenticated('Authentication required.');
  }
  return request.currentUser.id;
}
