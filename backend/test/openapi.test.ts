import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { buildApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { testConfig } from './helpers.js';

/**
 * Keeps the OpenAPI document honest about the running service.
 *
 * `OPENAPI.yaml` is the contract a client integrator reads instead of the source.
 * That makes it exactly the kind of document that goes stale silently: the server
 * keeps working, its tests keep passing, and the specification keeps describing an
 * API that no longer exists. These tests fail when the two disagree.
 *
 * The validator dependency is not declared, so the structural checks here are
 * deliberately dependency-free — parsing the YAML with a small hand-rolled reader
 * is worse than a library, so instead this file checks the properties that
 * actually matter and are cheap to verify: every route is documented, every
 * documented route exists, no dangling `$ref`s, and the security-critical
 * guarantees are stated in the document rather than only in the code.
 *
 * A full structural validation of the document was run out of band with
 * `openapi-spec-validator`, which reports it valid against OpenAPI 3.1.
 */

/**
 * Locates the repository root from this file, whether it runs from `test/` (tsc,
 * direct node) or `dist/test/` (the compiled output the suite actually runs).
 *
 * The spec lives at the repository root, one level above `backend/`, so it is
 * found by walking up until the marker appears rather than by assuming a fixed
 * depth. Guessing `../..` works under `tsx` and fails under `dist/test/`, which is
 * exactly the kind of environment-dependent breakage worth removing.
 */
function repoRoot(): string {
  let dir = dirname(fileURLToPath(import.meta.url));
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'OPENAPI.yaml'))) return dir;
    dir = resolve(dir, '..');
  }
  throw new Error('Could not locate OPENAPI.yaml above ' + fileURLToPath(import.meta.url));
}

const ROOT = repoRoot();
const specText = readFileSync(resolve(ROOT, 'OPENAPI.yaml'), 'utf8');

/**
 * The document with all runs of whitespace collapsed to single spaces.
 *
 * YAML block scalars and folded prose wrap at ~80 columns, so a sentence a human
 * reads as one line is several lines in the file. Matching prose against the raw
 * text would fail on a line break that carries no meaning, which is a test that
 * breaks when the file is re-wrapped rather than when the guarantee is lost.
 */
const specProse = specText.replace(/\s+/g, ' ');

/** The server sources, next to this file's package root. */
const SERVER_ROOT = resolve(ROOT, 'backend');

/** Paths declared in the spec, as `/v1/recordings/{id}` style strings. */
function documentedPaths(): string[] {
  const paths: string[] = [];
  const inPathSection = specText.split('\npaths:')[1]?.split('\ncomponents:')[0] ?? '';
  for (const match of inPathSection.matchAll(/^ {2}(\/[^\s:]*):/gm)) {
    if (match[1] !== undefined) paths.push(match[1]);
  }
  return paths;
}

/**
 * Recursively collects `.ts` files.
 *
 * `fs.glob` is available in modern Node but its iterator type varies by version,
 * and this project targets a range of them. A plain directory walk has no such
 * problem and needs no dependency.
 */
async function collectTypeScriptFiles(dir: string): Promise<string[]> {
  const { readdir } = await import('node:fs/promises');
  const { join } = await import('node:path');

  const entries = await readdir(dir, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await collectTypeScriptFiles(full)));
    } else if (entry.name.endsWith('.ts')) {
      files.push(full);
    }
  }
  return files;
}

/** Every `app.<verb>('<path>'` registration in the server source. */
async function registeredPaths(): Promise<string[]> {
  const files = await collectTypeScriptFiles(resolve(SERVER_ROOT, 'src'));
  const found = new Set<string>();
  for (const file of files) {
    const source = readFileSync(file, 'utf8');
    for (const match of source.matchAll(/app\.(?:get|post|put|delete|patch)(?:<[^>]*>)?\(\s*'([^']+)'/g)) {
      // `matchAll` yields `string | undefined` for group 1 under this tsconfig.
      const path = match[1] ?? '';
      // Express-style `:id` params become the OpenAPI `{id}` form.
      found.add(path.replace(/:(\w+)/g, '{$1}'));
    }
  }
  return [...found];
}

/** Escapes a `$ref` segment for use inside a regular expression. */
function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

describe('OPENAPI.yaml', () => {
  it('documents every route the server exposes', async () => {
    const documented = new Set(documentedPaths());
    const registered = await registeredPaths();

    const undocumented = registered.filter((path) => !documented.has(path));
    assert.deepEqual(
      undocumented,
      [],
      `Routes exist in code but not in OPENAPI.yaml: ${undocumented.join(', ')}`,
    );
  });

  it('documents no route that does not exist', async () => {
    const documented = documentedPaths();
    const registered = new Set(await registeredPaths());

    const phantom = documented.filter((path) => !registered.has(path));
    assert.deepEqual(
      phantom,
      [],
      `OPENAPI.yaml documents routes that are not registered: ${phantom.join(', ')}`,
    );
  });

  it('has no dangling $ref', () => {
    // Both quote styles, because the document is hand-written and either is valid YAML.
    const refs = [...specText.matchAll(/\$ref:\s*(?:'|")#\/([^'"]+)(?:'|")/g)].map((m) => m[1] ?? '');
    assert.ok(refs.length > 0, 'expected the document to use $ref');

    const missing = refs.filter((ref) => {
      // Walk the document by indentation. Every `$ref` in this document is a
      // `#/components/<group>/<name>` pointer, so the first segment is a top-level
      // key at indent 0 and each later segment is nested one level deeper.
      const segments = ref.split('/');
      let lines = specText.split('\n');
      let indent = -1;

      for (const segment of segments) {
        indent += 1;
        // Indentation increases by two per level in this document, starting at 0.
        const want = indent * 2;
        const key = new RegExp(`^ {${want}}(?:${escapeForRegExp(segment)}):`);
        const at = lines.findIndex((line) => key.test(line));
        if (at === -1) return true;
        // Descend into everything strictly more indented than the key we matched.
        lines = lines.slice(at + 1).filter((line) => {
          if (line.trim() === '') return true;
          return line.length - line.trimStart().length > want;
        });
      }
      return false;
    });

    assert.deepEqual([...new Set(missing)], [], `unresolved $ref targets: ${missing.join(', ')}`);
  });

  it('declares no schema that can hold audio', () => {
    // The single most important structural property of this API: there is no field
    // anywhere in it that could accept or return plaintext audio. If someone adds
    // one, this fails.
    //
    // Only *property keys* are examined. Prose is allowed — in fact the document is
    // full of sentences explaining that audio does not transit it — so a check that
    // simply looked for the word "audio" anywhere would flag the very sentences that
    // document the guarantee.
    const propertyKey = /^\s+([A-Za-z_][A-Za-z0-9_]*):/;
    const audioish = /audio|pcm|wav|samples|transcript|decoded/i;

    const offenders = specText
      .split('\n')
      .map((line, index) => ({ line, index: index + 1 }))
      .filter(({ line }) => {
        const key = propertyKey.exec(line)?.[1];
        return key !== undefined && audioish.test(key);
      });

    assert.deepEqual(offenders, [], `audio-capable fields found: ${JSON.stringify(offenders)}`);
  });

  it('states that the server cannot verify the GCM tag or the plaintext digest', () => {
    // These guarantees are the reason receipts are worded the way they are. If they
    // are dropped from the contract, a client integrator may reasonably assume
    // otherwise.
    for (const marker of [
      /serverVerifiesGcmTag/,
      /serverVerifiesPlaintext/,
      /serverHoldsDecryptionKey/,
      /never verified by this server/i,
      // The API field name, and separately the qualification itself. Both matter:
      // renaming the field to `plaintextSha256` would strip the caveat from every
      // call site that reads the response.
      /plaintextSha256AssertedByClient/,
      /never verified by the server/i,
    ]) {
      assert.ok(marker.test(specProse), `OPENAPI.yaml no longer states: ${marker}`);
    }
  });

  it('documents 404-not-403 cross-tenant behaviour', () => {
    assert.ok(/404, never 403/i.test(specProse), 'OPENAPI.yaml no longer states 404-not-403');
    assert.ok(/NOT_FOUND/.test(specProse), 'OPENAPI.yaml no longer declares NOT_FOUND');
  });

  it('documents that audio never transits the API', () => {
    assert.ok(
      /no endpoint anywhere in this API that accepts or returns audio plaintext/i.test(specProse),
      'OPENAPI.yaml no longer states that audio never transits the API',
    );
  });
});

describe('spec and running service agree on capabilities', () => {
  it('reports exactly the verification abilities the spec permits', async () => {
    const app = await buildApp({ config: testConfig() });
    const response = await app.inject({ method: 'GET', url: '/v1/capabilities' });
    const body = response.json();

    // Three capabilities are structurally impossible for this server, so if any of
    // them ever reports true the service has started doing something it must not.
    assert.equal(body.serverHoldsDecryptionKey, false);
    assert.equal(body.serverVerifiesGcmTag, false);
    assert.equal(body.serverVerifiesPlaintext, false);
    // The one configuration-dependent capability. Asserting its type rather than a
    // fixed value keeps this honest as defaults change.
    assert.equal(typeof body.serverVerifiesSealedSha256, 'boolean');

    await app.close();
  });

  it('refuses to describe itself as more capable than it is', async () => {
    const config = testConfig();
    const app = await buildApp({ config });
    const body = (await app.inject({ method: 'GET', url: '/v1/capabilities' })).json();

    assert.ok(
      /not proof of authenticity|not a certificate/i.test(body.disclaimer),
      `disclaimer overclaims: ${body.disclaimer}`,
    );
    assert.ok(config.jwtSecret.length > 0);
    await app.close();
  });
});

describe('loadConfig', () => {
  it('rejects a production boot that would be unsafe', async () => {
    // Guards the documented refusal. An evidence store that starts with a guessable
    // signing key is worse than one that does not start.
    assert.throws(() =>
      loadConfig({ NODE_ENV: 'production', JWT_SECRET: 'short' } as Record<string, string>),
    );
  });
});