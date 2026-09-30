package com.safesignal.data.local

import com.safesignal.core.crypto.testing.InMemoryKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Crash-recovery and reconciliation tests (SPEC §87).
 *
 * The property under test is that reconciliation never destroys data it cannot
 * prove is worthless, and that an interrupted commit is distinguishable from a
 * committed one.
 */
class EvidenceStoreReconcilerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyProvider = InMemoryKeyProvider()
    private val recordingId = "rec-recovery-1"

    private fun rootWithRecording(): File {
        val root = temporaryFolder.newFolder("evidence-root")
        val dir = File(root, recordingId)
        dir.mkdirs()
        return dir
    }

    private fun commit(directory: File, sequence: Int, marker: Byte): String {
        val material = com.safesignal.core.crypto.RecordingKeyManager(keyProvider)
            .createRecordingKeyMaterial(recordingId)
        val writer = SegmentedEvidenceWriter(directory, recordingId, material)
        return writer.commitSegment(ByteArray(2048) { ((it + marker) % 251).toByte() }, sequence).fileName
    }

    @Test
    fun `committed segments are recognised as recoverable`() {
        val dir = rootWithRecording()
        val first = commit(dir, 0, 1)
        val second = commit(dir, 1, 2)

        val report = EvidenceStoreReconciler(dir.parentFile).reconcile(dir)

        assertEquals(listOf(first, second).sorted(), report.committedSegmentFiles)
        assertTrue(report.hasRecoverableEvidence)
        assertEquals(listOf(0, 1), EvidenceStoreReconciler(dir.parentFile).committedSequenceNumbers(report))
    }

    @Test
    fun `an interrupted commit is deleted and never counted as evidence`() {
        val dir = rootWithRecording()
        commit(dir, 0, 3)
        // A crash between "write tmp" and "atomic rename".
        val partial = File(dir, "segment-000001.ssg.tmp")
        partial.writeBytes(ByteArray(1024) { 7 })

        val report = EvidenceStoreReconciler(dir.parentFile).reconcile(dir)

        assertEquals(listOf("segment-000001.ssg.tmp"), report.partialFiles)
        assertFalse("partial file must not be evidence", partial.exists())
        assertEquals(1, report.committedSegmentFiles.size)
    }

    @Test
    fun `a corrupt segment is quarantined, never deleted`() {
        val dir = rootWithRecording()
        commit(dir, 0, 4)
        // A file with the right extension that is not a valid container.
        val corrupt = File(dir, "segment-000001.ssg")
        corrupt.writeBytes(ByteArray(64) { 0x41 })

        val report = EvidenceStoreReconciler(dir.parentFile).reconcile(dir)

        assertEquals(listOf("segment-000001.ssg"), report.quarantinedFiles)
        assertTrue(
            "corrupt data must be preserved for inspection, not destroyed",
            File(dir, "quarantine/segment-000001.ssg").exists(),
        )
        assertEquals(1, report.committedSegmentFiles.size)
    }

    @Test
    fun `an unexpected file is reported and left untouched`() {
        val dir = rootWithRecording()
        commit(dir, 0, 5)
        val stray = File(dir, "notes.txt")
        stray.writeText("operator note")

        val report = EvidenceStoreReconciler(dir.parentFile).reconcile(dir)

        assertEquals(listOf("notes.txt"), report.unrecognisedFiles)
        assertTrue("unknown files must not be deleted", stray.exists())
    }

    @Test
    fun `quarantine directory itself is not treated as a recording`() {
        val dir = rootWithRecording()
        commit(dir, 0, 6)
        File(dir, "quarantine").mkdirs()

        val root = dir.parentFile
        val reconciler = EvidenceStoreReconciler(root)
        val recordingDirs = reconciler.recordingDirectories().map { it.name }

        assertEquals(listOf(recordingId), recordingDirs)
    }

    @Test
    fun `a sequence gap after a crash is detectable`() {
        val dir = rootWithRecording()
        commit(dir, 0, 7)
        commit(dir, 2, 9) // segment 1 never made it

        val reconciler = EvidenceStoreReconciler(dir.parentFile)
        val sequences = reconciler.committedSequenceNumbers(reconciler.reconcile(dir))

        assertEquals(listOf(0, 2), sequences)
        assertFalse(
            "a gap must be visible rather than silently compacted",
            sequences == (0 until sequences.size).toList(),
        )
    }

    @Test
    fun `reconciling an empty evidence root is a no-op`() {
        val root = temporaryFolder.newFolder("empty-root")
        val reports = EvidenceStoreReconciler(root).reconcileAll()
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `reconciling twice is idempotent`() {
        val dir = rootWithRecording()
        commit(dir, 0, 10)
        File(dir, "segment-000001.ssg.tmp").writeBytes(ByteArray(32) { 1 })

        val reconciler = EvidenceStoreReconciler(dir.parentFile)
        val first = reconciler.reconcile(dir)
        val second = reconciler.reconcile(dir)

        assertEquals(first.committedSegmentFiles, second.committedSegmentFiles)
        assertTrue(second.partialFiles.isEmpty())
    }
}