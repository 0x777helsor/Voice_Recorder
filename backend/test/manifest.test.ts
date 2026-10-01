import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { describe, it } from 'node:test';
import { canonicalManifestBytes, validateManifest } from '../src/domain/manifest.js';

/**
 * Manifest validation tests.
 *
 * These are unit tests for the gate that every finalized recording must pass, so
 * they cover rejection paths that the HTTP tests cannot reach cheaply — hostile
 * input, boundary sizes, and internal inconsistency.
 */
describe('manifest validation', () => {
  const validSegment = {
    sequenceNumber: 1,
    sealedBytes: 1000,
    sealedSha256: 'a'.repeat(64),
    plaintextSha256: 'b'.repeat(64),
    keyVersion: 1,
    encryptionVersion: 1,
  };

  const validManifest = (overrides: Record<string, unknown> = {}) => ({
    manifestVersion: 1,
    recordingId: 'rec-1',
    deviceRecordingId: 'dev-1',
    startedAtUtc: '2026-10-01T00:00:00.000Z',
    endedAtUtc: '2026-10-01T00:01:00.000Z',
    activationSource: 'VOICE',
    activationDetail: null,
    sampleRateHz: 48000,
    channels: 1,
    bitsPerSample: 16,
    segments: [validSegment],
    signature: 'QUJDRA==',
    signatureAlgorithm: 'SHA256withECDSA',
    signingPublicKey: 'QUJDRA==',
    ...overrides,
  });

  it('accepts a well-formed manifest and reports its hash and totals', () => {
    const result = validateManifest(validManifest());
    assert.equal(result.declaredTotalBytes, 1000);
    assert.deepEqual(result.sequenceNumbers, [1]);
    assert.match(result.manifestSha256, /^[0-9a-f]{64}$/);
  });

  it('produces a stable hash regardless of key order', () => {
    // The canonicalisation must make the signature reproducible: a client that
    // serialises its JSON in a different key order still gets the same bytes.
    const a = validateManifest(validManifest());
    const reordered = Object.fromEntries(Object.entries(validManifest()).reverse());
    const b = validateManifest(reordered);
    assert.equal(a.manifestSha256, b.manifestSha256);
  });

  it('excludes the signature from the canonical bytes', () => {
    // A signature cannot cover itself; if it did, verification could never work.
    const a = validateManifest(validManifest({ signature: 'QUJDRA==' }));
    const b = validateManifest(validManifest({ signature: 'WlpaWg==' }));
    assert.equal(a.manifestSha256, b.manifestSha256);
  });

  describe('rejections', () => {
    it('rejects a non-object manifest', () => {
      for (const bad of [null, [], 'a string', 42, true]) {
        assert.throws(() => validateManifest(bad), /manifest must be an object/);
      }
    });

    const cases: Array<[string, Record<string, unknown>, string]> = [
      ['zero manifestVersion', { manifestVersion: 0 }, 'manifestVersion'],
      ['unknown activation source', { activationSource: 'BLUETOOTH_PAIRING' }, 'activationSource'],
      ['non-timestamp start', { startedAtUtc: 'yesterday' }, 'startedAtUtc'],
      ['impossible sample rate', { sampleRateHz: 1_000_000 }, 'sampleRateHz'],
      ['too many channels', { channels: 64 }, 'channels'],
      ['unsupported bit depth', { bitsPerSample: 20 }, 'bitsPerSample'],
      ['empty segment list', { segments: [] }, 'at least one segment'],
      ['non-array segments', { segments: 'lots' }, 'segments must be an array'],
      ['unexpected signature algorithm', { signatureAlgorithm: 'HS256' }, 'signatureAlgorithm'],
      ['missing signature', { signature: '' }, 'signature'],
      ['uppercase hash', { segments: [{ ...validSegment, sealedSha256: 'A'.repeat(64) }] }, 'sealedSha256'],
      ['short hash', { segments: [{ ...validSegment, sealedSha256: 'abc' }] }, 'sealedSha256'],
      ['zero-byte segment', { segments: [{ ...validSegment, sealedBytes: 0 }] }, 'sealedBytes'],
      ['oversized segment', { segments: [{ ...validSegment, sealedBytes: 300 * 1024 * 1024 }] }, 'sealedBytes'],
      ['duplicate sequence numbers', { segments: [validSegment, { ...validSegment, sealedBytes: 5 }] }, 'duplicates'],
      ['zero sequence number', { segments: [{ ...validSegment, sequenceNumber: 0 }] }, 'sequenceNumber'],
      ['path traversal in recordingId', { recordingId: '../../etc/passwd' }, 'recordingId'],
      ['slash in deviceRecordingId', { deviceRecordingId: 'a/b' }, 'deviceRecordingId'],
      ['overlong recordingId', { recordingId: 'x'.repeat(200) }, 'recordingId'],
      ['zero key version', { segments: [{ ...validSegment, keyVersion: 0 }] }, 'keyVersion'],
    ];

    for (const [name, overrides, expectedMessage] of cases) {
      it(`rejects ${name}`, () => {
        assert.throws(
          () => validateManifest(validManifest(overrides)),
          (error: unknown) => {
            assert.ok(error instanceof Error, 'should throw an Error');
            assert.match(error.message, new RegExp(expectedMessage, 'i'));
            return true;
          },
        );
      });
    }
  });

  it('rejects a manifest declaring more segments than the ceiling', () => {
    const many = Array.from({ length: 10_001 }, (_, i) => ({ ...validSegment, sequenceNumber: i + 1 }));
    assert.throws(() => validateManifest(validManifest({ segments: many })), /at most 10000 segments/);
  });

  it('accepts a manifest at exactly the segment ceiling', () => {
    const many = Array.from({ length: 10_000 }, (_, i) => ({ ...validSegment, sequenceNumber: i + 1 }));
    const result = validateManifest(validManifest({ segments: many }));
    assert.equal(result.sequenceNumbers.length, 10_000);
  });

  it('rejects a manifest whose declared total exceeds the byte ceiling', () => {
    // 8 GiB ceiling; 40 segments of 256 MiB is 10 GiB.
    const fat = Array.from({ length: 40 }, (_, i) => ({
      ...validSegment,
      sequenceNumber: i + 1,
      sealedBytes: 256 * 1024 * 1024,
    }));
    assert.throws(() => validateManifest(validManifest({ segments: fat })), /Declared total exceeds/);
  });

  it('sorts sequence numbers so a shuffled manifest still resolves', () => {
    const result = validateManifest(
      validManifest({
        segments: [
          { ...validSegment, sequenceNumber: 3 },
          { ...validSegment, sequenceNumber: 1 },
          { ...validSegment, sequenceNumber: 2 },
        ],
      }),
    );
    assert.deepEqual(result.sequenceNumbers, [1, 2, 3]);
  });

  it('accepts a TEST activation source but keeps it distinguishable', () => {
    // Test recordings are valid — the app must be able to exercise the full
    // pipeline — but the source string is what later marks them as non-evidence.
    const result = validateManifest(validManifest({ activationSource: 'TEST' }));
    assert.equal(result.manifest.activationSource, 'TEST');
  });
});

describe('canonical manifest bytes', () => {
  it('is byte-stable across repeated calls', () => {
    const manifest = validateManifest({
      manifestVersion: 1,
      recordingId: 'rec-stable',
      deviceRecordingId: 'dev-stable',
      startedAtUtc: '2026-10-01T00:00:00.000Z',
      endedAtUtc: null,
      activationSource: 'PHYSICAL_BUTTON',
      activationDetail: null,
      sampleRateHz: 16000,
      channels: 1,
      bitsPerSample: 16,
      segments: [
        {
          sequenceNumber: 1,
          sealedBytes: 12,
          sealedSha256: 'c'.repeat(64),
          plaintextSha256: 'd'.repeat(64),
          keyVersion: 2,
          encryptionVersion: 1,
        },
      ],
      signature: 'c2ln',
      signatureAlgorithm: 'SHA256withECDSA',
      signingPublicKey: 'cGs=',
    });
    const first = canonicalManifestBytes(manifest.manifest);
    const second = canonicalManifestBytes(manifest.manifest);
    assert.equal(first, second);
    // And the hash matches a hand-computed digest of those exact bytes.
    assert.equal(
      createHash('sha256').update(first).digest('hex'),
      manifest.manifestSha256,
    );
  });
});
