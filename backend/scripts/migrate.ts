import { readdir, readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Pool } from 'pg';

/**
 * Migration runner.
 *
 * Deliberately minimal: numbered `.sql` files applied in lexicographic order, each
 * in its own transaction, with the applied set recorded in a `schema_migrations`
 * table. There is no down-migration and no automatic rollback.
 *
 * ### Why no down-migrations
 *
 * A migration that has been applied may already have had real evidence written
 * against it. Reversing it would either fail or, worse, silently succeed while
 * destroying data. The honest operation for a schema change that must be undone
 * is a new forward migration plus an explicit, separately-reviewed data-handling
 * plan — not a `down` script nobody runs under pressure.
 *
 * An advisory lock serialises concurrent runners, so two instances starting at
 * once cannot both apply file 0002.
 */

const MIGRATIONS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'migrations');

/**
 * Advisory-lock key for `pg_advisory_lock`.
 *
 * A fixed value well below `2^63` so it can be sent as a plain integer (passing
 * a BigInt through `pg` serialises it as a string, which the parameter type
 * cannot infer). It only needs to be stable across runs, which is why it is a
 * constant rather than derived from the environment.
 */
const LOCK_KEY = 6_576_262_363;

async function main(): Promise<void> {
  const databaseUrl = process.env.DATABASE_URL;
  if (!databaseUrl) {
    process.stderr.write('DATABASE_URL is required.\n');
    process.exit(78);
  }

  const pool = new Pool({ connectionString: databaseUrl, max: 1 });
  const client = await pool.connect();

  try {
    // Arbitrary but fixed key. Held for the whole run so a second instance waits.
    await client.query('SELECT pg_advisory_lock($1)', [LOCK_KEY]);

    await client.query(`
      CREATE TABLE IF NOT EXISTS schema_migrations (
        filename    TEXT PRIMARY KEY,
        checksum    TEXT NOT NULL,
        applied_at  TIMESTAMPTZ NOT NULL DEFAULT now()
      )
    `);

    const files = (await readdir(MIGRATIONS_DIR)).filter((f) => f.endsWith('.sql')).sort();
    const applied = await client.query<{ filename: string; checksum: string }>(
      'SELECT filename, checksum FROM schema_migrations',
    );
    const known = new Map(applied.rows.map((r) => [r.filename, r.checksum]));

    let count = 0;
    for (const filename of files) {
      const sql = await readFile(join(MIGRATIONS_DIR, filename), 'utf8');
      const checksum = createHash('sha256').update(sql).digest('hex');
      const previous = known.get(filename);

      if (previous !== undefined) {
        if (previous !== checksum) {
          // Editing an applied migration is the single most common way to end up
          // with two environments whose schemas differ in ways nobody notices.
          throw new Error(
            `Migration ${filename} has changed since it was applied ` +
              `(recorded ${previous.slice(0, 12)}, file ${checksum.slice(0, 12)}). ` +
              'Applied migrations are immutable; add a new file instead.',
          );
        }
        continue;
      }

      await client.query('BEGIN');
      try {
        await client.query(sql);
        await client.query('INSERT INTO schema_migrations (filename, checksum) VALUES ($1, $2)', [
          filename,
          checksum,
        ]);
        await client.query('COMMIT');
        process.stdout.write(`applied ${filename}\n`);
        count += 1;
      } catch (cause) {
        await client.query('ROLLBACK');
        throw new Error(
          `Migration ${filename} failed: ${cause instanceof Error ? cause.message : String(cause)}`,
        );
      }
    }

    process.stdout.write(count === 0 ? 'database already up to date\n' : `${count} migration(s) applied\n`);
  } finally {
    await client.query('SELECT pg_advisory_unlock($1)', [LOCK_KEY]).catch(() => undefined);
    client.release();
    await pool.end();
  }
}

main().catch((cause) => {
  process.stderr.write(`${cause instanceof Error ? cause.message : String(cause)}\n`);
  process.exit(1);
});
