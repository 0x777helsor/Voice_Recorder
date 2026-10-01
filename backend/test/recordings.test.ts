import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { after, before, describe, it } from 'node:test';
import { auth, buildManifest, buildTestApp, fakeSha256, registerAccount, type TestContext } from './helpers.js';

/**
 * Recording lifecycle, authorization and integrity tests.
 *
 * The authorization tests are the most important in this file: SPEC §45 requires
 * that one user can never see or modify another user's evidence, and the way to
 * prove that is to try, from a real second account, against real ids obtained
 * from the first.
 */
describe('recordings', () => {
  let ctx: TestContext;

  before(async () => {
    ctx = await buildTestApp();
  });

  after(async () => {
    await ctx.app.close();
  });

  async function openSession(token: string, deviceId: string) {
    const response = await ctx.app.inject({
      method: 'POST',
      url: '/v1/recordings',
      headers: auth(token),
      payload: { deviceRecordingId: deviceId },
    });
    // 201 on first open, 200 when an open session is reused.
    assert.ok(
      response.statusCode === 201 || response.statusCode === 200,
      `unexpected ${response.statusCode}: ${response.body}`,
    );
    const body = response.json();
    return { recordingId: body.recording.id as string, clientReference: body.recording.clientReference as string };
  }

  async function commitChunk(
    token: string,
    recordingId: string,
    sequence: number,
    sealed: Buffer,
    idempotencyKey = `idem-${recordingId}-${sequence}-${Math.random().toString(36).slice(2)}`,
  ) {
    const digest = createHash('sha256').update(sealed).digest('hex');
    const response = await ctx.app.inject({
      method: 'POST',
      url: `/v1/recordings/${recordingId}/chunks/${sequence}/commit`,
      headers: { ...auth(token), 'idempotency-key': idempotencyKey },
      payload: {
        sealedBytes: sealed.length,
        sealedSha256: digest,
        plaintextSha256: fakeSha256(`pt-${sequence}`),
        keyVersion: 1,
        encryptionVersion: 1,
      },
    });
    assert.equal(response.statusCode, 201, response.body);
    return response.json();
  }

  /** Same request, but asserting a specific rejection. Used for negative cases. */
  async function commitChunkExpecting(
    token: string,
    recordingId: string,
    sequence: number,
    sealed: Buffer,
    expectedStatus: number,
  ) {
    const response = await ctx.app.inject({
      method: 'POST',
      url: `/v1/recordings/${recordingId}/chunks/${sequence}/commit`,
      headers: {
        ...auth(token),
        'idempotency-key': `neg-${recordingId}-${sequence}-${Math.random().toString(36).slice(2)}`,
      },
      payload: {
        sealedBytes: sealed.length,
        sealedSha256: createHash('sha256').update(sealed).digest('hex'),
        plaintextSha256: fakeSha256(`pt-${sequence}`),
        keyVersion: 1,
        encryptionVersion: 1,
      },
    });
    assert.equal(response.statusCode, expectedStatus, response.body);
    return response.json();
  }

  describe('session lifecycle', () => {
    it('opens a session and returns an opaque reference', async () => {
      const account = await registerAccount(ctx.app);
      const session = await openSession(account.accessToken, 'dev-rec-001');
      assert.ok(session.recordingId.startsWith('rec_'));
      assert.equal(session.clientReference, 'dev-rec-001');
    });

    it('is idempotent for the same device recording id', async () => {
      const account = await registerAccount(ctx.app);
      const first = await openSession(account.accessToken, 'dev-rec-idem');
      const second = await openSession(account.accessToken, 'dev-rec-idem');
      // A retry after a dropped connection must not create a second half-empty
      // recording.
      assert.equal(second.recordingId, first.recordingId);
    });

    it('rejects a device recording id that could traverse a storage prefix', async () => {
      const account = await registerAccount(ctx.app);
      for (const bad of ['../other-user', 'a/b', 'a\u0000b', '', 'x'.repeat(200)]) {
        const response = await ctx.app.inject({
          method: 'POST',
          url: '/v1/recordings',
          headers: auth(account.accessToken),
          payload: { deviceRecordingId: bad },
        });
        assert.equal(response.statusCode, 400, `expected rejection for ${JSON.stringify(bad)}`);
      }
    });

    it('requires an Idempotency-Key on chunk commit', async () => {
      const account = await registerAccount(ctx.app);
      const session = await openSession(account.accessToken, 'dev-rec-noidem');
      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/chunks/1/commit`,
        headers: auth(account.accessToken),
        payload: {
          sealedBytes: 10,
          sealedSha256: fakeSha256('x'),
          plaintextSha256: fakeSha256('y'),
          keyVersion: 1,
          encryptionVersion: 1,
        },
      });
      assert.equal(response.statusCode, 400);
    });

    it('rejects a replayed idempotency key carrying different content', async () => {
      const account = await registerAccount(ctx.app);
      const session = await openSession(account.accessToken, 'dev-rec-replay');
      const sealed = Buffer.from('sealed-bytes-one');
      const key = 'stable-key-for-this-chunk';
      await commitChunk(account.accessToken, session.recordingId, 1, sealed, key);

      // Same key, different bytes. This must not overwrite the evidence already
      // committed under that key.
      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/chunks/1/commit`,
        headers: { ...auth(account.accessToken), 'idempotency-key': key },
        payload: {
          sealedBytes: 99,
          sealedSha256: fakeSha256('different'),
          plaintextSha256: fakeSha256('y'),
          keyVersion: 1,
          encryptionVersion: 1,
        },
      });
      assert.equal(response.statusCode, 409);
      assert.equal(response.json().error.code, 'IDEMPOTENCY_MISMATCH');
    });

    it('replays an identical commit without storing a second chunk', async () => {
      const account = await registerAccount(ctx.app);
      const session = await openSession(account.accessToken, 'dev-rec-identical');
      const sealed = Buffer.from('exactly-the-same-bytes');
      const key = 'identical-replay-key';
      await commitChunk(account.accessToken, session.recordingId, 1, sealed, key);
      const replay = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/chunks/1/commit`,
        headers: { ...auth(account.accessToken), 'idempotency-key': key },
        payload: {
          sealedBytes: sealed.length,
          sealedSha256: createHash('sha256').update(sealed).digest('hex'),
          plaintextSha256: fakeSha256('pt-1'),
          keyVersion: 1,
          encryptionVersion: 1,
        },
      });
      assert.equal(replay.statusCode, 200);
      const detail = await ctx.app.inject({
        method: 'GET',
        url: `/v1/recordings/${session.recordingId}`,
        headers: auth(account.accessToken),
      });
      assert.equal(detail.json().chunks.length, 1);
    });
  });

  describe('authorization between users', () => {
    it('does not leak another user\'s recording on read', async () => {
      const victim = await registerAccount(ctx.app);
      const attacker = await registerAccount(ctx.app);
      const session = await openSession(victim.accessToken, 'victim-private-rec');

      const response = await ctx.app.inject({
        method: 'GET',
        url: `/v1/recordings/${session.recordingId}`,
        headers: auth(attacker.accessToken),
      });
      // 404, not 403: a 403 would confirm the id is real.
      assert.equal(response.statusCode, 404);
      assert.equal(response.json().error.code, 'NOT_FOUND');
    });

    it('does not allow another user to commit chunks to it', async () => {
      const victim = await registerAccount(ctx.app);
      const attacker = await registerAccount(ctx.app);
      const session = await openSession(victim.accessToken, 'victim-commit-rec');
      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/chunks/1/commit`,
        headers: { ...auth(attacker.accessToken), 'idempotency-key': 'attacker-key-1234' },
        payload: {
          sealedBytes: 10,
          sealedSha256: fakeSha256('a'),
          plaintextSha256: fakeSha256('b'),
          keyVersion: 1,
          encryptionVersion: 1,
        },
      });
      assert.equal(response.statusCode, 404);
    });

    it('does not allow another user to finalize or delete it', async () => {
      const victim = await registerAccount(ctx.app);
      const attacker = await registerAccount(ctx.app);
      const session = await openSession(victim.accessToken, 'victim-finalize-rec');

      const finalize = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/complete`,
        headers: auth(attacker.accessToken),
        payload: { manifest: buildManifest({ recordingId: 'victim-finalize-rec', deviceRecordingId: 'victim-finalize-rec', segments: [] }) },
      });
      assert.equal(finalize.statusCode, 404);

      const remove = await ctx.app.inject({
        method: 'DELETE',
        url: `/v1/recordings/${session.recordingId}`,
        headers: auth(attacker.accessToken),
      });
      assert.equal(remove.statusCode, 404);
    });

    it('does not list another user\'s recordings', async () => {
      const victim = await registerAccount(ctx.app);
      const attacker = await registerAccount(ctx.app);
      await openSession(victim.accessToken, 'victim-listed-rec');
      const response = await ctx.app.inject({
        method: 'GET',
        url: '/v1/recordings',
        headers: auth(attacker.accessToken),
      });
      assert.equal(response.statusCode, 200);
      const references = response.json().recordings.map((r: { clientReference: string }) => r.clientReference);
      assert.ok(!references.includes('victim-listed-rec'));
    });

    it('requires authentication for every route', async () => {
      const session = await openSession((await registerAccount(ctx.app)).accessToken, 'unauth-probe-rec');
      const routes: Array<[string, string]> = [
        ['GET', '/v1/recordings'],
        ['POST', '/v1/recordings'],
        ['GET', `/v1/recordings/${session.recordingId}`],
        ['DELETE', `/v1/recordings/${session.recordingId}`],
        ['GET', '/v1/receipts'],
        ['GET', `/v1/recordings/${session.recordingId}/chunks/1/download`],
      ];
      for (const [method, url] of routes) {
        const response = await ctx.app.inject({ method: method as 'GET', url });
        assert.equal(response.statusCode, 401, `${method} ${url} should require auth`);
      }
    });
  });

  describe('finalization and integrity', () => {
    it('completes a well-formed recording and issues a receipt', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'finalize-happy-rec';
      const session = await openSession(account.accessToken, deviceId);

      const sealedOne = Buffer.from('segment-one-ciphertext');
      const sealedTwo = Buffer.from('segment-two-ciphertext');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealedOne);
      const c2 = await commitChunk(account.accessToken, session.recordingId, 2, sealedTwo);

      // The client really does PUT to object storage directly.
      ctx.objectStore.completeUpload(c1.upload.url, sealedOne);
      ctx.objectStore.completeUpload(c2.upload.url, sealedTwo);

      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/complete`,
        headers: auth(account.accessToken),
        payload: {
          manifest: buildManifest({
            recordingId: deviceId,
            deviceRecordingId: deviceId,
            segments: [
              { sequenceNumber: 1, sealedBytes: sealedOne.length, sealedSha256: c1.chunk.sealedSha256 },
              { sequenceNumber: 2, sealedBytes: sealedTwo.length, sealedSha256: c2.chunk.sealedSha256 },
            ],
          }),
        },
      });

      assert.equal(response.statusCode, 200, response.body);
      const body = response.json();
      assert.equal(body.recording.state, 'FINALIZED');
      assert.equal(body.recording.declaredChunkCount, 2);
      assert.equal(body.recording.verifiedChunkCount, 2);
      assert.equal(body.receipt.chunkCount, 2);
      assert.equal(body.receipt.manifestSha256, body.recording.manifestSha256);
      // The disclaimer must be present, not buried in docs.
      assert.match(body.receipt.disclaimer, /not a certificate of authenticity/i);
      assert.ok(body.admissibilityStatement);
    });

    it('refuses to finalize when a declared segment was never uploaded', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'incomplete-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('only-one-segment');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);
      ctx.objectStore.completeUpload(c1.upload.url, sealed);

      // The manifest declares two; only one arrived.
      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/complete`,
        headers: auth(account.accessToken),
        payload: {
          manifest: buildManifest({
            recordingId: deviceId,
            deviceRecordingId: deviceId,
            segments: [
              { sequenceNumber: 1, sealedBytes: sealed.length, sealedSha256: c1.chunk.sealedSha256 },
              { sequenceNumber: 2, sealedBytes: 999, sealedSha256: fakeSha256('missing') },
            ],
          }),
        },
      });
      assert.equal(response.statusCode, 422);
      assert.equal(response.json().error.code, 'MANIFEST_INCOMPLETE');
      assert.deepEqual(response.json().error.details.missingSequenceNumbers, [2]);

      // And the recording must still be open, not silently finalized.
      const detail = await ctx.app.inject({
        method: 'GET',
        url: `/v1/recordings/${session.recordingId}`,
        headers: auth(account.accessToken),
      });
      assert.equal(detail.json().recording.state, 'UPLOAD_SESSION');
    });

    it('refuses to finalize when stored bytes do not match the declared hash', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'corrupt-bytes-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('the-real-ciphertext');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);

      // Storage silently returns different bytes than the client declared — the
      // exact corruption this check exists to catch.
      ctx.objectStore.completeUpload(c1.upload.url, Buffer.from('CORRUPTED-not-the-real-thing'));

      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/complete`,
        headers: auth(account.accessToken),
        payload: {
          manifest: buildManifest({
            recordingId: deviceId,
            deviceRecordingId: deviceId,
            segments: [{ sequenceNumber: 1, sealedBytes: sealed.length, sealedSha256: c1.chunk.sealedSha256 }],
          }),
        },
      });
      assert.equal(response.statusCode, 422);
      assert.equal(response.json().error.code, 'CHECKSUM_MISMATCH');
    });

    it('refuses to finalize when the declared bytes were never PUT at all', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'never-uploaded-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('declared-but-absent');
      // Commit declares it; the client then dies before the PUT.
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);

      const response = await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/complete`,
        headers: auth(account.accessToken),
        payload: {
          manifest: buildManifest({
            recordingId: deviceId,
            deviceRecordingId: deviceId,
            segments: [{ sequenceNumber: 1, sealedBytes: sealed.length, sealedSha256: c1.chunk.sealedSha256 }],
          }),
        },
      });
      assert.equal(response.statusCode, 422);
      assert.equal(response.json().error.code, 'MANIFEST_INCOMPLETE');
    });

    it('refuses to modify a finalized recording', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'frozen-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('frozen-segment');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);
      ctx.objectStore.completeUpload(c1.upload.url, sealed);

      const manifest = {
        manifest: buildManifest({
          recordingId: deviceId,
          deviceRecordingId: deviceId,
          segments: [{ sequenceNumber: 1, sealedBytes: sealed.length, sealedSha256: c1.chunk.sealedSha256 }],
        }),
      };
      await ctx.app.inject({
        method: 'POST',
        url: `/v1/recordings/${session.recordingId}/complete`,
        headers: auth(account.accessToken),
        payload: manifest,
      });

      // Adding a chunk after the receipt exists would let the stored set diverge
      // from what the receipt attests to.
      const late = await commitChunkExpecting(
        account.accessToken,
        session.recordingId,
        2,
        Buffer.from('late-segment'),
        409,
      );
      assert.equal(late.error.code, 'CONFLICT');
    });

    it('chains receipts so removal or reordering is detectable', async () => {
      const account = await registerAccount(ctx.app);
      const hashes: string[] = [];
      for (let i = 1; i <= 3; i += 1) {
        const deviceId = `chain-rec-${i}`;
        const session = await openSession(account.accessToken, deviceId);
        const sealed = Buffer.from(`chain-segment-${i}`);
        const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);
        ctx.objectStore.completeUpload(c1.upload.url, sealed);
        const response = await ctx.app.inject({
          method: 'POST',
          url: `/v1/recordings/${session.recordingId}/complete`,
          headers: auth(account.accessToken),
          payload: {
            manifest: buildManifest({
              recordingId: deviceId,
              deviceRecordingId: deviceId,
              segments: [{ sequenceNumber: 1, sealedBytes: sealed.length, sealedSha256: c1.chunk.sealedSha256 }],
            }),
          },
        });
        assert.equal(response.statusCode, 200, response.body);
        hashes.push(response.json().receipt.auditChainHash);
      }

      // Every hash differs, so each receipt commits to its predecessor.
      assert.equal(new Set(hashes).size, 3);

      const list = await ctx.app.inject({ method: 'GET', url: '/v1/receipts', headers: auth(account.accessToken) });
      const receipts = list.json().receipts;
      assert.ok(receipts.length >= 3);
      // Oldest first, and each links to the one before.
      const recent = receipts.slice(-3);
      for (let i = 1; i < recent.length; i += 1) {
        assert.equal(recent[i]!.previousReceiptHash, recent[i - 1]!.auditChainHash);
      }
    });

    it('does not cross-link receipts between users', async () => {
      const first = await registerAccount(ctx.app);
      const second = await registerAccount(ctx.app);

      async function finalizeFor(token: string, deviceId: string) {
        const session = await openSession(token, deviceId);
        const sealed = Buffer.from(`${deviceId}-segment`);
        const c1 = await commitChunk(token, session.recordingId, 1, sealed);
        ctx.objectStore.completeUpload(c1.upload.url, sealed);
        const response = await ctx.app.inject({
          method: 'POST',
          url: `/v1/recordings/${session.recordingId}/complete`,
          headers: auth(token),
          payload: {
            manifest: buildManifest({
              recordingId: deviceId,
              deviceRecordingId: deviceId,
              segments: [{ sequenceNumber: 1, sealedBytes: sealed.length, sealedSha256: c1.chunk.sealedSha256 }],
            }),
          },
        });
        assert.equal(response.statusCode, 200, response.body);
        return response.json().receipt;
      }

      const a = await finalizeFor(first.accessToken, 'user-a-rec');
      const b = await finalizeFor(second.accessToken, 'user-b-rec');
      // The second user's first receipt starts a fresh chain at genesis, rather
      // than linking to a stranger's receipt.
      assert.equal(b.previousReceiptHash, '0'.repeat(64));
      assert.notEqual(a.auditChainHash, b.auditChainHash);
    });
  });

  describe('downloads and deletion', () => {
    it('mints a download URL that resolves to the stored bytes', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'download-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('downloadable-ciphertext');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);
      ctx.objectStore.completeUpload(c1.upload.url, sealed);

      const response = await ctx.app.inject({
        method: 'GET',
        url: `/v1/recordings/${session.recordingId}/chunks/1/download`,
        headers: auth(account.accessToken),
      });
      assert.equal(response.statusCode, 200);
      const url = response.json().download.url as string;
      // The URL is a temporary grant, not a durable location.
      assert.ok(!url.includes('https://'));
      assert.deepEqual(ctx.objectStore.completeDownload(url), sealed);
    });

    it('refuses a download URL once it has expired', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'expiry-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('expiring-ciphertext');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);
      ctx.objectStore.completeUpload(c1.upload.url, sealed);

      const response = await ctx.app.inject({
        method: 'GET',
        url: `/v1/recordings/${session.recordingId}/chunks/1/download`,
        headers: auth(account.accessToken),
      });
      const url = response.json().download.url as string;
      ctx.objectStore.expire(url);
      assert.throws(() => ctx.objectStore.completeDownload(url), /expired/i);
    });

    it('deletes stored bytes and the record together', async () => {
      const account = await registerAccount(ctx.app);
      const deviceId = 'delete-rec';
      const session = await openSession(account.accessToken, deviceId);
      const sealed = Buffer.from('doomed-ciphertext');
      const c1 = await commitChunk(account.accessToken, session.recordingId, 1, sealed);
      ctx.objectStore.completeUpload(c1.upload.url, sealed);

      const response = await ctx.app.inject({
        method: 'DELETE',
        url: `/v1/recordings/${session.recordingId}`,
        headers: auth(account.accessToken),
      });
      assert.equal(response.statusCode, 204);

      const gone = await ctx.app.inject({
        method: 'GET',
        url: `/v1/recordings/${session.recordingId}`,
        headers: auth(account.accessToken),
      });
      assert.equal(gone.statusCode, 404);
      // The bytes must be gone from storage too, not merely unlisted.
      assert.equal(await ctx.objectStore.stat(c1.chunk.objectKey ?? `x`), null);
    });
  });

  describe('capability disclosure', () => {
    it('states plainly what the server cannot verify', async () => {
      const response = await ctx.app.inject({ method: 'GET', url: '/v1/capabilities' });
      assert.equal(response.statusCode, 200);
      const body = response.json();
      assert.equal(body.serverHoldsDecryptionKey, false);
      assert.equal(body.serverVerifiesGcmTag, false);
      assert.equal(body.serverVerifiesPlaintext, false);
    });
  });
});
