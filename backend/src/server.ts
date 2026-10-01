import { buildApp } from './app.js';
import { ConfigError, loadConfig } from './config.js';
import { PostgresRepository } from './repositories/postgresRepository.js';
import { MemoryRepository } from './repositories/memoryRepository.js';
import { MemoryObjectStore, S3ObjectStore } from './storage/objectStore.js';
import type { ObjectStore } from './storage/objectStore.js';
import type { Repository } from './repositories/Repository.js';

/**
 * Process entrypoint.
 *
 * ### Shutdown
 *
 * SIGTERM triggers an orderly stop: stop accepting requests, let in-flight ones
 * finish, close the pool. `stopGracePeriodMs` is set above the usual 10 s because
 * a `complete` request that is verifying stored chunks can legitimately take
 * longer than that, and being killed mid-finalize is the one outcome that can
 * leave a recording in `UPLOAD_SESSION` with a receipt the client never received.
 * Such a session is recoverable — the client retries `complete`, which is
 * idempotent — but it is better not to create the situation.
 */
const SHUTDOWN_GRACE_MS = 30_000;

async function main(): Promise<void> {
  let config: ReturnType<typeof loadConfig>;
  try {
    config = loadConfig();
  } catch (cause) {
    // A misconfiguration must be a loud, immediate failure with an actionable
    // message. Falling back to defaults here would start a service that cannot
    // hold evidence safely.
    const message = cause instanceof ConfigError ? cause.message : String(cause);
    process.stderr.write(`SafeSignal cannot start.\n\n${message}\n`);
    process.exit(78); // EX_CONFIG
  }

  const { repository, objectStore } = await buildStorage(config);

  const app = await buildApp({ config, repository, objectStore });

  let shuttingDown = false;
  const stop = async (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, 'shutting down');
    try {
      await app.close();
      if (repository instanceof PostgresRepository) await repository.close();
      process.exit(0);
    } catch (cause) {
      app.log.error({ err: cause }, 'shutdown failed');
      process.exit(1);
    }
  };

  process.on('SIGTERM', () => void stop('SIGTERM'));
  process.on('SIGINT', () => void stop('SIGINT'));

  // An unhandled rejection leaves the process in an unknown state. For a service
  // holding evidence, dying loudly and being restarted by the supervisor is
  // better than continuing with a corrupted request handler.
  process.on('unhandledRejection', (reason) => {
    app.log.fatal({ err: reason }, 'unhandled rejection');
    void stop('unhandledRejection');
  });

  await app.listen({ host: config.host, port: config.port });
  app.log.info(
    {
      environment: config.nodeEnv,
      repository: repository.constructor.name,
      objectStore: objectStore.constructor.name,
      verifyEveryChunkOnUpload: config.verifyEveryChunkOnUpload,
    },
    'SafeSignal backend listening',
  );
}

/**
 * Chooses persistence.
 *
 * Falls back to in-memory storage **only** outside production. In production the
 * config loader has already refused to start without a real database, so this
 * branch cannot silently lose evidence. The refusal lives in `loadConfig` rather
 * than here so it can be tested without constructing a pool.
 */
async function buildStorage(config: ReturnType<typeof loadConfig>): Promise<{
  repository: Repository;
  objectStore: ObjectStore;
}> {
  const repository: Repository = config.databaseUrl
    ? new PostgresRepository(config.databaseUrl)
    : new MemoryRepository();

  if (config.s3) {
    // Imported lazily so a deployment with no bucket never loads the AWS SDK.
    const { S3Client } = await import('@aws-sdk/client-s3');
    const { getSignedUrl } = await import('@aws-sdk/s3-request-presigner');
    const client = new S3Client({
      region: config.s3.region,
      ...(config.s3.endpoint ? { endpoint: config.s3.endpoint, forcePathStyle: config.s3.forcePathStyle } : {}),
      credentials: {
        accessKeyId: config.s3.accessKeyId,
        secretAccessKey: config.s3.secretAccessKey,
      },
    });
    const bucket = config.s3.bucket;
    return {
      repository,
      objectStore: new S3ObjectStore({
        // The SDK's command union is wider than the subset this code issues, and
        // its outputs are interface-typed rather than index-signature-typed.
        // Both casts are confined to these two one-line adapters so the rest of
        // the file stays honestly typed.
        client: {
          send: (command) =>
            client.send(command as never) as unknown as Promise<Record<string, unknown>>,
        },
        presigner: {
          sign: async ({ Key, Method, ExpiresIn }) =>
            getSignedUrl(client as never, { Bucket: bucket, Key, Method, ExpiresIn } as never),
        },
        bucket,
      }),
    };
  }

  return { repository, objectStore: new MemoryObjectStore() };
}

main().catch((cause) => {
  process.stderr.write(`Fatal startup error: ${cause instanceof Error ? cause.stack : String(cause)}\n`);
  process.exit(1);
});
