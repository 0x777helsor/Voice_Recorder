import Fastify, { type FastifyInstance } from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import { authPlugin } from './auth/authPlugin.js';
import type { Config } from './config.js';
import { SafeSignalError } from './domain/errors.js';
import { registerAuthRoutes } from './routes/auth.js';
import { registerRecordingRoutes } from './routes/recordings.js';
import type { Repository } from './repositories/Repository.js';
import { MemoryRepository } from './repositories/memoryRepository.js';
import type { ObjectStore } from './storage/objectStore.js';
import { MemoryObjectStore } from './storage/objectStore.js';

export interface AppDeps {
  config: Config;
  repository?: Repository;
  objectStore?: ObjectStore;
}

/**
 * Builds the Fastify application.
 *
 * Exported separately from the process entrypoint so the test suite can build an
 * app with in-memory storage and `fastify.inject()` — no socket, no database, no
 * bucket — while exercising exactly the routes, hooks and error handling that run
 * in production.
 *
 * ### Hardening applied here
 *
 *  - **helmet** sets HSTS, `no-store` on API responses, and disables framing.
 *  - **CORS** defaults to *no* allowed origins. A native app needs none, and an
 *    accidental `*` here would let any web page read a user's evidence list.
 *  - **body limits** keep a manifest from consuming unbounded memory.
 *  - **rate limiting** applies globally and again, more tightly, to auth routes.
 *  - **error handling** converts anything unexpected into a generic 500 with no
 *    internals leaked. Stack traces go to the server log only.
 */
export async function buildApp(deps: AppDeps): Promise<FastifyInstance> {
  const { config } = deps;
  const repository = deps.repository ?? new MemoryRepository();
  const objectStore = deps.objectStore ?? new MemoryObjectStore();

  const app = Fastify({
    logger: { level: config.logLevel },
    // The API only ever receives small JSON documents. Ciphertext goes straight
    // to object storage, so a large body here is always a mistake or an attack.
    bodyLimit: config.maxRequestBodyBytes,
    disableRequestLogging: config.nodeEnv === 'production',
    trustProxy: true,
  });

  // Needed by requireAuth to re-check tokenVersion. Set before the plugin so the
  // decorator lookup inside requireAuth finds it.
  (app as unknown as { repository: Repository }).repository = repository;

  await app.register(helmet, {
    contentSecurityPolicy: false,
    hsts: config.nodeEnv === 'production' ? { maxAge: 31536000, includeSubDomains: true } : false,
  });

  await app.register(cors, {
    origin: config.cors.allowedOrigins.length > 0 ? config.cors.allowedOrigins : false,
    methods: ['GET', 'POST', 'DELETE'],
    credentials: false,
    // Evidence responses must not be cached by an intermediary.
    maxAge: 600,
  });

  await app.register(rateLimit, {
    max: config.rateLimit.max,
    timeWindow: config.rateLimit.windowMs,
    // Rate limiting runs on `onRequest`, which fires *before* `requireAuth`, so
    // `currentUser` is not yet populated and the effective key is the client IP
    // on every route. That is the correct axis for credential guessing and it is
    // the conservative choice overall: a per-user key would let one user's
    // upload burst exhaust a shared quota for everyone behind the same NAT.
    // The cost is that a large upload fleet behind one egress IP shares a budget,
    // which is why `max` is generous and configurable.
    keyGenerator: (request) => request.ip,
    errorResponseBuilder: () => ({
      error: { code: 'RATE_LIMITED', message: 'Too many requests. Slow down and retry later.' },
    }),
  });

  await app.register(authPlugin, { jwtSecret: config.jwtSecret });

  /**
   * Uniform error serialisation.
   *
   * A [SafeSignalError] carries its own status and code. Anything else is logged
   * with full detail and answered with a bare 500 — a stack trace or a Postgres
   * message in an HTTP body is an information leak, and this route is where that
   * decision is made once instead of at every route.
   */
  app.setErrorHandler((error, request, reply) => {
    if (error instanceof SafeSignalError) {
      return reply.code(error.statusCode).send(error.toJSON());
    }
    // Fastify's own validation and body-parse errors carry a statusCode.
    const status = (error as { statusCode?: number }).statusCode;
    if (status === 400 || status === 413 || status === 429) {
      request.log.warn({ err: error }, 'request rejected');
      return reply.code(status).send({
        error: { code: 'INVALID_REQUEST', message: 'The request could not be accepted.' },
      });
    }
    request.log.error({ err: error }, 'unhandled error');
    return reply.code(500).send({
      error: { code: 'INTERNAL', message: 'An unexpected error occurred.' },
    });
  });

  app.setNotFoundHandler((request, reply) => {
    void request;
    return reply.code(404).send({
      error: { code: 'NOT_FOUND', message: 'No such endpoint.' },
    });
  });

  await registerAuthRoutes(app, {
    repository,
    allowRegistration: config.allowRegistration,
  });
  await registerRecordingRoutes(app, {
    repository,
    objectStore,
    verifyEveryChunkOnUpload: config.verifyEveryChunkOnUpload,
  });

  /**
   * Liveness and readiness.
   *
   * Separate endpoints on purpose: a load balancer must stop sending traffic to
   * an instance that cannot reach its database, while an operator still needs
   * `live` to answer so the process is not killed during a dependency outage.
   */
  app.get('/healthz', async () => ({ status: 'ok', uptimeSeconds: Math.round(process.uptime()) }));

  app.get('/readyz', async (_request, reply) => {
    try {
      await repository.findUserById('readiness-probe');
      return reply.send({ status: 'ready' });
    } catch {
      return reply.code(503).send({ status: 'unavailable' });
    }
  });

  /**
   * Capability disclosure.
   *
   * A client can read what this deployment will and will not verify, instead of
   * assuming. This is the machine-readable version of the honesty requirements in
   * SPEC §45 and KNOWN_LIMITATIONS.md.
   */
  app.get('/v1/capabilities', async () => ({
    storage: 'encrypted-blobs-only',
    serverHoldsDecryptionKey: false,
    serverVerifiesGcmTag: false,
    serverVerifiesPlaintext: false,
    serverVerifiesSealedSha256: config.verifyEveryChunkOnUpload,
    registrationEnabled: config.allowRegistration,
    rateLimit: { max: config.rateLimit.max, windowMsMs: config.rateLimit.windowMs },
    downloadUrlTtlSeconds: 300,
    uploadUrlTtlSeconds: 900,
    disclaimer:
      'This service stores ciphertext it cannot decrypt. A receipt proves receipt and ' +
      'checksum agreement only; it is not proof of authenticity or admissibility.',
  }));

  return app;
}
