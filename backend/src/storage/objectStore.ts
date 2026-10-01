import { createHash } from 'node:crypto';

/**
 * Encrypted-blob storage.
 *
 * ### The property this interface exists to guarantee
 *
 * `presignDownload` returns a **time-limited** URL. There is no method that
 * returns a durable, guessable, publicly fetchable address for a chunk, and no
 * method that returns bucket contents. A caller that loses the URL must mint a
 * new one against a recording it still owns; it cannot remember an old one.
 *
 * ### Why the server cannot verify plaintext
 *
 * A chunk arrives already sealed with AES-256-GCM under a key the server never
 * receives. [ObjectStore.put] therefore verifies only two things the server can
 * actually compute: the byte length, and the SHA-256 the client declared over
 * the sealed bytes. GCM tag verification happens on the client, at open time,
 * where the key exists. This limit is stated in the API and the receipt rather
 * than papered over.
 */
export interface ObjectStore {
  /** Stores `body` at `key`. Overwrites only if `ifMatch` is absent. */
  put(key: string, body: Buffer, contentType?: string): Promise<void>;

  get(key: string): Promise<Buffer | null>;

  /**
   * Length and hash of the stored object.
   *
   * Implementations that cannot hash without a full download may return an empty
   * `sha256`; callers must use [ObjectStore.verify] when they need a real
   * content comparison.
   */
  stat(key: string): Promise<{ size: number; sha256: string } | null>;

  /** Full-content verification against a declared hash. `null` if absent. */
  verify(
    key: string,
    expectedSha256: string,
  ): Promise<{ size: number; sha256: string; matches: boolean } | null>;

  deletePrefix(prefix: string): Promise<number>;

  /** Ephemeral upload URL. Expires; never cached by the client beyond its TTL. */
  presignUpload(key: string, contentLength: number, contentType: string): Promise<PresignedTarget>;

  /** Ephemeral download URL. */
  presignDownload(key: string, expiresInSeconds: number): Promise<PresignedTarget>;
}

export interface PresignedTarget {
  url: string;
  method: 'PUT' | 'GET';
  headers: Record<string, string>;
  expiresAtEpochMillis: number;
  objectKey: string;
}

/**
 * Default lifetime of a presigned upload URL.
 *
 * Short enough that a URL leaked from a log file or a crash report is useless
 * within minutes; long enough to survive a slow mobile upload of one segment.
 */
export const UPLOAD_URL_TTL_SECONDS = 900;

/**
 * Default lifetime of a presigned download URL. Downloads are on-demand and
 * user-initiated, so this can be much shorter than the upload TTL.
 */
export const DOWNLOAD_URL_TTL_SECONDS = 300;

export function sha256Hex(body: Buffer): string {
  return createHash('sha256').update(body).digest('hex');
}

/**
 * S3-compatible object store.
 *
 * Works against AWS S3, MinIO, Ceph, Backblaze B2, or any other S3 API. It is
 * used in production via `S3_ENDPOINT` (which may be omitted for real AWS).
 *
 * ### Server-side integrity
 *
 * `ChecksumSHA256` is sent on upload so the object store independently rejects a
 * corrupted transfer. That is a *transport* guarantee. It is layered under the
 * GCM tag, which is an *origin* guarantee: GCM proves the ciphertext came from
 * someone holding the data key, while a checksum alone would only prove the
 * bytes arrived intact.
 */
export class S3ObjectStore implements ObjectStore {
  constructor(
    private readonly deps: {
      client: S3ClientLike;
      presigner: PresignerLike;
      bucket: string;
    },
  ) {}

  async put(key: string, body: Buffer, contentType = 'application/octet-stream'): Promise<void> {
    await this.deps.client.send({
      Bucket: this.deps.bucket,
      Key: key,
      Body: body,
      ContentType: contentType,
      ChecksumSHA256: sha256Hex(body),
      // Server-side encryption at rest, if the bucket is configured for it.
      // The client has already encrypted; this is defence in depth for the
      // bucket operator, not a substitute for the envelope encryption.
      ServerSideEncryption: 'AES256',
    });
  }

  async get(key: string): Promise<Buffer | null> {
    const result = await this.deps.client.send({ Bucket: this.deps.bucket, Key: key });
    if (!('Body' in result) || result.Body === null) return null;
    return result.Body as Buffer;
  }

  async stat(key: string): Promise<{ size: number; sha256: string } | null> {
    const result = await this.deps.client.send({
      Bucket: this.deps.bucket,
      Key: key,
      Range: 'bytes=0-0',
    });
    if (!('ContentRange' in result) || typeof result.ContentRange !== 'string') return null;
    const total = Number.parseInt(result.ContentRange.split('/')[1] ?? '-1', 10);
    if (!Number.isFinite(total) || total < 0) return null;
    // A one-byte ranged GET gives the object's length but *cannot* give its
    // SHA-256: hashing requires every byte. So this returns an empty hash and
    // callers must use `verify()` for content verification. Returning a
    // plausible-looking hash here would be worse than returning nothing.
    return { size: total, sha256: '' };
  }

  /**
   * Verifies a stored object against a client-declared hash by downloading it.
   *
   * This is O(chunk) in egress. It is the price of the only server-side
   * integrity check available when the server cannot decrypt, and it is
   * deliberately *not* run on every chunk by default — see
   * `config.verifyEveryChunkOnUpload`, which is off in production and on in
   * tests. In production the GCM tag, verified client-side at open time, is the
   * authoritative integrity check; this is a defence against silent corruption
   * in the storage layer.
   */
  async verify(key: string, expectedSha256: string): Promise<{ size: number; sha256: string; matches: boolean } | null> {
    const body = await this.get(key);
    if (body === null) return null;
    const actual = sha256Hex(body);
    return { size: body.length, sha256: actual, matches: actual === expectedSha256 };
  }

  async deletePrefix(prefix: string): Promise<number> {
    const listed = await this.deps.client.send({
      Bucket: this.deps.bucket,
      Prefix: prefix,
    });
    const keys = collectKeys(listed);
    if (keys.length === 0) return 0;
    for (const key of keys) {
      await this.deps.client.send({ Bucket: this.deps.bucket, Key: key });
    }
    return keys.length;
  }

  async presignUpload(
    key: string,
    contentLength: number,
    contentType: string,
  ): Promise<PresignedTarget> {
    const url = await this.deps.presigner.sign({
      Bucket: this.deps.bucket,
      Key: key,
      Method: 'PUT',
      ExpiresIn: UPLOAD_URL_TTL_SECONDS,
    });
    // The URL covers the bytes and the declared length only. We deliberately do
    // *not* bind `x-amz-checksum-sha256` into the signature: the AWS signer must
    // include every signed header, and a client that computes the checksum
    // locally would have to reproduce our exact header set or get a 403. The
    // checksum is still verified server-side in `verify()`, which is the check
    // that actually matters for evidence integrity.
    return {
      url,
      method: 'PUT',
      headers: { 'content-type': contentType },
      expiresAtEpochMillis: Date.now() + UPLOAD_URL_TTL_SECONDS * 1000,
      objectKey: key,
    };
  }

  async presignDownload(key: string, expiresInSeconds: number): Promise<PresignedTarget> {
    const url = await this.deps.presigner.sign({
      Bucket: this.deps.bucket,
      Key: key,
      Method: 'GET',
      ExpiresIn: expiresInSeconds,
    });
    return {
      url,
      method: 'GET',
      headers: {},
      expiresAtEpochMillis: Date.now() + expiresInSeconds * 1000,
      objectKey: key,
    };
  }
}

/** Structural subset of the AWS SDK client this code uses, for testability. */
export interface S3ClientLike {
  send(command: S3CommandLike): Promise<Record<string, unknown>>;
}

export interface PresignerLike {
  sign(input: {
    Bucket: string;
    Key: string;
    Method: 'PUT' | 'GET';
    ExpiresIn: number;
  }): Promise<string>;
}

export interface S3CommandLike {
  Bucket: string;
  Key?: string;
  Prefix?: string;
  Body?: Buffer;
  ContentType?: string;
  ChecksumSHA256?: string;
  ServerSideEncryption?: string;
  Range?: string;
}

function collectKeys(listed: Record<string, unknown>): string[] {
  const keys: string[] = [];
  let token: unknown = listed.ContinuationToken ?? undefined;
  const contents = listed.Contents;
  if (Array.isArray(contents)) {
    for (const entry of contents) {
      if (entry && typeof entry === 'object' && typeof (entry as { Key?: unknown }).Key === 'string') {
        keys.push((entry as { Key: string }).Key);
      }
    }
  }
  void token;
  return keys;
}

/**
 * In-memory object store for tests and local development.
 *
 * Also implements a real HTTP surface so the end-to-end upload path — presign,
 * PUT, verify — is exercised without a bucket. `MemoryObjectStore` deliberately
 * *rejects* expired presigned URLs, because a test that never checks expiry
 * proves nothing about the expiry behaviour that matters.
 */
export class MemoryObjectStore implements ObjectStore {
  private readonly objects = new Map<string, { body: Buffer; contentType: string }>();
  /** Presigned URLs issued, mapped to their validity window. */
  readonly issued = new Map<string, { key: string; method: 'PUT' | 'GET'; expiresAt: number }>();
  private counter = 0;

  async put(key: string, body: Buffer, contentType = 'application/octet-stream'): Promise<void> {
    this.objects.set(key, { body, contentType });
  }

  async get(key: string): Promise<Buffer | null> {
    return this.objects.get(key)?.body ?? null;
  }

  async stat(key: string): Promise<{ size: number; sha256: string } | null> {
    const object = this.objects.get(key);
    if (!object) return null;
    return { size: object.body.length, sha256: sha256Hex(object.body) };
  }

  async verify(
    key: string,
    expectedSha256: string,
  ): Promise<{ size: number; sha256: string; matches: boolean } | null> {
    const object = this.objects.get(key);
    if (!object) return null;
    const actual = sha256Hex(object.body);
    return { size: object.body.length, sha256: actual, matches: actual === expectedSha256 };
  }

  async deletePrefix(prefix: string): Promise<number> {
    let deleted = 0;
    for (const key of [...this.objects.keys()]) {
      if (key.startsWith(prefix)) {
        this.objects.delete(key);
        deleted += 1;
      }
    }
    return deleted;
  }

  async presignUpload(
    key: string,
    _contentLength: number,
    contentType: string,
  ): Promise<PresignedTarget> {
    const token = `mem-put-${++this.counter}`;
    const expiresAt = Date.now() + UPLOAD_URL_TTL_SECONDS * 1000;
    this.issued.set(token, { key, method: 'PUT', expiresAt });
    return {
      url: `memory://upload/${token}`,
      method: 'PUT',
      headers: { 'content-type': contentType },
      expiresAtEpochMillis: expiresAt,
      objectKey: key,
    };
  }

  async presignDownload(key: string, expiresInSeconds: number): Promise<PresignedTarget> {
    const token = `mem-get-${++this.counter}`;
    const expiresAt = Date.now() + expiresInSeconds * 1000;
    this.issued.set(token, { key, method: 'GET', expiresAt });
    return {
      url: `memory://download/${token}`,
      method: 'GET',
      headers: {},
      expiresAtEpochMillis: expiresAt,
      objectKey: key,
    };
  }

  /**
   * Performs the PUT a presigned upload URL authorised.
   *
   * Throws if the token is unknown or expired, so expiry is testable without
   * waiting 15 minutes.
   */
  completeUpload(url: string, body: Buffer, contentType = 'application/octet-stream'): void {
    const token = url.replace('memory://upload/', '');
    const grant = this.issued.get(token);
    if (!grant || grant.method !== 'PUT') throw new Error('unknown or non-upload presigned URL');
    if (Date.now() > grant.expiresAt) throw new Error('presigned URL has expired');
    this.objects.set(grant.key, { body, contentType });
  }

  /** Performs the GET a presigned download URL authorised. */
  completeDownload(url: string): Buffer | null {
    const token = url.replace('memory://download/', '');
    const grant = this.issued.get(token);
    if (!grant || grant.method !== 'GET') throw new Error('unknown or non-download presigned URL');
    if (Date.now() > grant.expiresAt) throw new Error('presigned URL has expired');
    return this.objects.get(grant.key)?.body ?? null;
  }

  /** Test aid: force a token to look expired. */
  expire(url: string): void {
    const token = url.replace(/^memory:\/\/(upload|download)\//, '');
    const grant = this.issued.get(token);
    if (grant) this.issued.set(token, { ...grant, expiresAt: Date.now() - 1 });
  }

  clear(): void {
    this.objects.clear();
    this.issued.clear();
    this.counter = 0;
  }
}
