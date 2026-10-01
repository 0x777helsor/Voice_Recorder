-- SafeSignal reference backend — initial schema.
--
-- Design rules encoded here:
--
--   1. No column can hold audio. There is no `data BYTEA`, no `payload`, no
--      `recording` blob. The largest bytea column is a SHA-256 digest. Ciphertext
--      lives in object storage, addressed by key.
--   2. Every row that belongs to a user carries `user_id`, so a query that
--      forgets a scope predicate is visible in review rather than silently
--      returning another tenant's rows.
--   3. Foreign keys cascade on recording deletion, because a chunk row without
--      its recording is meaningless and would only corrupt a count.

BEGIN;

CREATE TABLE IF NOT EXISTS users (
    id             TEXT PRIMARY KEY,
    email          TEXT NOT NULL,
    password_hash  TEXT NOT NULL,
    password_salt  TEXT NOT NULL,
    token_version  INTEGER NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_unique UNIQUE (email),
    CONSTRAINT users_email_format CHECK (email = lower(email))
);

CREATE TABLE IF NOT EXISTS recordings (
    id                    TEXT PRIMARY KEY,
    user_id               TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    state                 TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    storage_prefix        TEXT NOT NULL,
    client_reference      TEXT NOT NULL,
    finalized_at          TIMESTAMPTZ,
    manifest_json         TEXT,
    receipt_json          TEXT,
    manifest_sha256       TEXT,
    declared_chunk_count  INTEGER,
    verified_chunk_count  INTEGER,
    CONSTRAINT recordings_state_valid CHECK (
        state IN ('UPLOAD_SESSION', 'INCOMPLETE', 'COMPLETE', 'FINALIZED')
    ),
    CONSTRAINT recordings_client_reference_unique UNIQUE (user_id, client_reference),
    -- A finalized recording must have its manifest and receipt; an unfinalized
    -- one must not. Encoding this in the schema means the invariant cannot be
    -- violated by a partially-completed request.
    CONSTRAINT recordings_finalized_requires_manifest CHECK (
        state <> 'FINALIZED' OR (manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL AND receipt_json IS NOT NULL)
    ),
    CONSTRAINT recordings_chunk_counts_consistent CHECK (
        verified_chunk_count IS NULL
        OR declared_chunk_count IS NULL
        OR verified_chunk_count <= declared_chunk_count
    )
);

CREATE INDEX IF NOT EXISTS recordings_user_created_idx
    ON recordings (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS recordings_state_idx
    ON recordings (state);
CREATE INDEX IF NOT EXISTS recordings_upload_state_idx
    ON recordings (state, updated_at);

CREATE TABLE IF NOT EXISTS chunks (
    id                   TEXT PRIMARY KEY,
    recording_id         TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
    sequence_number      INTEGER NOT NULL,
    object_key           TEXT NOT NULL,
    sealed_bytes         BIGINT NOT NULL,
    sealed_sha256        CHAR(64) NOT NULL,
    plaintext_sha256     CHAR(64) NOT NULL,
    key_version          INTEGER NOT NULL,
    encryption_version   INTEGER NOT NULL,
    received_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chunks_sequence_unique UNIQUE (recording_id, sequence_number),
    CONSTRAINT chunks_sequence_positive CHECK (sequence_number > 0),
    CONSTRAINT chunks_sealed_bytes_positive CHECK (sealed_bytes > 0),
    CONSTRAINT chunks_sealed_bytes_bounded CHECK (sealed_bytes <= 268435456),
    -- Sequence numbers start at 1, never 0. A zero sequence usually means an
    -- off-by-one in the client, and allowing it would make two clients disagree
    -- about chunk 0's identity.
    CONSTRAINT chunks_sequence_gte_one CHECK (sequence_number >= 1)
);

CREATE INDEX IF NOT EXISTS chunks_recording_sequence_idx
    ON chunks (recording_id, sequence_number);

CREATE TABLE IF NOT EXISTS chunk_idempotency (
    recording_id    TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
    idempotency_key TEXT NOT NULL,
    chunk_id        TEXT NOT NULL REFERENCES chunks(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (recording_id, idempotency_key)
);

CREATE TABLE IF NOT EXISTS receipts (
    receipt_id                   TEXT PRIMARY KEY,
    recording_id                 TEXT NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
    user_id                      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    issued_at                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    server_issued_at_epoch_millis BIGINT NOT NULL,
    chunk_count                  INTEGER NOT NULL,
    total_sealed_bytes           BIGINT NOT NULL,
    manifest_sha256              CHAR(64) NOT NULL,
    signature_algorithm          TEXT NOT NULL,
    manifest_signing_public_key  TEXT NOT NULL,
    previous_receipt_hash        CHAR(64) NOT NULL,
    audit_chain_hash             CHAR(64) NOT NULL,
    disclaimer                   TEXT NOT NULL,
    CONSTRAINT receipts_chunk_count_positive CHECK (chunk_count > 0),
    CONSTRAINT receipts_total_bytes_positive CHECK (total_sealed_bytes > 0)
);

-- The chain head lookup is `(user_id, issued_at DESC)`, and appendReceipt runs
-- in a SERIALIZABLE transaction that reads exactly this index. Serving it from
-- an index is what keeps the chain append from degrading as receipts accumulate.
CREATE INDEX IF NOT EXISTS receipts_user_issued_idx
    ON receipts (user_id, issued_at DESC);

COMMIT;
