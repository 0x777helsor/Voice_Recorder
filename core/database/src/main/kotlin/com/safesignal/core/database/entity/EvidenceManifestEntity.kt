package com.safesignal.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The signed integrity manifest for one recording (SPEC §25).
 *
 * Stored as canonical JSON rather than spread across columns because the signed
 * bytes must be reproducible exactly. Re-serialising from columns risks a field
 * ordering or float-formatting drift that would invalidate the signature after an
 * app upgrade; storing the signed bytes verbatim cannot drift.
 */
@Entity(tableName = "evidence_manifests")
data class EvidenceManifestEntity(
    @PrimaryKey
    @ColumnInfo(name = "recording_id")
    val recordingId: String,

    /** Canonical JSON exactly as signed. Do not re-serialise to "fix" this. */
    @ColumnInfo(name = "canonical_json")
    val canonicalJson: String,

    @ColumnInfo(name = "signature_base64")
    val signatureBase64: String,

    @ColumnInfo(name = "verification_key_base64")
    val verificationKeyBase64: String,

    @ColumnInfo(name = "manifest_digest")
    val manifestDigest: String,

    @ColumnInfo(name = "sealed_at")
    val sealedAt: Long,
)

/**
 * The activation timeline for one recording (SPEC §27).
 *
 * Every row is a discrete, timestamped event with a monotonic timestamp, so the
 * ordering of events is provable even if the device wall clock moved.
 *
 * @property phaseLabel human-readable phase, drawn from the normative state names.
 * @property detail secret-free context, e.g. "segment 4 committed".
 */
@Entity(
    tableName = "activation_timeline",
    indices = [androidx.room.Index(value = ["recording_id", "elapsed_at"])],
)
data class TimelineEventEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "recording_id")
    val recordingId: String,

    @ColumnInfo(name = "phase_label")
    val phaseLabel: String,

    @ColumnInfo(name = "detail")
    val detail: String?,

    @ColumnInfo(name = "elapsed_at")
    val elapsedAt: Long,

    @ColumnInfo(name = "wall_clock_at")
    val wallClockAt: Long,
)