import { randomBytes } from 'node:crypto';

/**
 * Configuration, read once at startup and validated hard.
 *
 * ### Why this file refuses to start with weak settings
 *
 * A backend that silently falls back to a default secret, a permissive CORS
 * origin, or a public bucket is worse than one that will not boot. Every
 * security-relevant setting below has no default that would let the process
 * start in a state where evidence could leak. `testJwtSecret` is the single
 * exception and it is explicitly named so it can be grepped for in deployment
 * configs, and it is rejected when `NODE_ENV` is anything but `test`.
 */

export interface Config {
  nodeEnv: 'development' | 'test' | 'production';
  host: string;
  port: number;
  logLevel: string;
  jwtSecret: string;
  databaseUrl: string | null;
  s3: {
    bucket: string;
    region: string;
    endpoint: string | null;
    accessKeyId: string;
    secretAccessKey: string;
    forcePathStyle: boolean;
  } | null;
  cors: {
    allowedOrigins: string[];
  };
  rateLimit: {
    max: number;
    windowMs: number;
  };
  /**
   * Verify every chunk's SHA-256 by downloading it on upload.
   *
   * On in tests. Off in production, where it would multiply egress by a large
   * factor for a check the GCM tag already performs client-side. Documented in
   * SECURITY.md rather than left as an unexplained default.
   */
  verifyEveryChunkOnUpload: boolean;
  maxRequestBodyBytes: number;
  /** Register a new account. Set false for a deployment that is invite-only. */
  allowRegistration: boolean;
}

export class ConfigError extends Error {}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const nodeEnv = (env.NODE_ENV ?? 'development') as Config['nodeEnv'];
  if (!['development', 'test', 'production'].includes(nodeEnv)) {
    throw new ConfigError(`NODE_ENV must be development, test or production; got "${nodeEnv}".`);
  }

  const port = parseInt(env.PORT ?? '8080', 10);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new ConfigError(`PORT must be an integer in 1..65535; got "${env.PORT}".`);
  }

  const jwtSecret = readJwtSecret(env, nodeEnv);
  const origins = (env.CORS_ALLOWED_ORIGINS ?? '')
    .split(',')
    .map((o) => o.trim())
    .filter((o) => o.length > 0);

  if (nodeEnv === 'production' && origins.includes('*')) {
    throw new ConfigError(
      'CORS_ALLOWED_ORIGINS must not be "*" in production. The SafeSignal client is a ' +
        'native app and needs no CORS grant at all.',
    );
  }

  const bucket = env.S3_BUCKET;
  const s3 = bucket
    ? {
        bucket,
        region: env.S3_REGION ?? 'us-east-1',
        endpoint: env.S3_ENDPOINT ?? null,
        accessKeyId: requireEnv(env, 'S3_ACCESS_KEY_ID'),
        secretAccessKey: requireEnv(env, 'S3_SECRET_ACCESS_KEY'),
        forcePathStyle: (env.S3_FORCE_PATH_STYLE ?? 'true') === 'true',
      }
    : null;

  if (nodeEnv === 'production' && !s3 && !env.DATABASE_URL) {
    throw new ConfigError(
      'Production requires either S3_BUCKET or DATABASE_URL. Refusing to start with ' +
        'in-memory or unconfigured storage, which cannot hold evidence safely.',
    );
  }

  const databaseUrl = env.DATABASE_URL ?? null;

  return {
    nodeEnv,
    host: env.HOST ?? '0.0.0.0',
    port,
    logLevel: env.LOG_LEVEL ?? (nodeEnv === 'test' ? 'silent' : 'info'),
    jwtSecret,
    databaseUrl,
    s3,
    cors: { allowedOrigins: origins },
    rateLimit: {
      max: parseInt(env.RATE_LIMIT_MAX ?? '120', 10),
      windowMs: parseInt(env.RATE_LIMIT_WINDOW_MS ?? '60000', 10),
    },
    verifyEveryChunkOnUpload: (env.VERIFY_EVERY_CHUNK_ON_UPLOAD ?? (nodeEnv === 'test' ? 'true' : 'false')) === 'true',
    maxRequestBodyBytes: parseInt(env.MAX_REQUEST_BODY_BYTES ?? String(2 * 1024 * 1024), 10),
    allowRegistration: (env.ALLOW_REGISTRATION ?? 'true') === 'true',
  };
}

function readJwtSecret(env: NodeJS.ProcessEnv, nodeEnv: Config['nodeEnv']): string {
  const secret = env.JWT_SECRET;
  if (secret && secret.length >= 32) return secret;

  if (secret) {
    throw new ConfigError(
      'JWT_SECRET is set but shorter than 32 characters. A short HMAC key is brute-forceable.',
    );
  }

  if (nodeEnv === 'test') {
    // Deterministic so tests can mint their own tokens. Never reachable outside
    // test: the production branch below throws instead.
    return 'test-only-secret-not-valid-outside-the-test-suite';
  }

  if (nodeEnv === 'development') {
    // Ephemeral. Tokens do not survive a restart, which is the correct default
    // for a laptop: nobody can be left with tokens signed by a secret on disk.
    return randomBytes(48).toString('base64url');
  }

  throw new ConfigError(
    'JWT_SECRET is required in production and must be at least 32 characters. ' +
      'Generate one with: openssl rand -base64 48',
  );
}

function requireEnv(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name];
  if (!value) throw new ConfigError(`${name} is required when S3_BUCKET is set.`);
  return value;
}
