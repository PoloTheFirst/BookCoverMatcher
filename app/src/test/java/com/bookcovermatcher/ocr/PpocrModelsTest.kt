// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The store's job is to make "the right three files, or nothing" a checkable property, so these
 * tests use small fixtures and injected hashes rather than the real 139 MB of weights.
 */
class PpocrModelsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fixture(dir: File, name: String, content: String): File =
        File(dir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun sourceFixture(): File {
        val dir = temp.newFolder("source")
        fixture(dir, PpocrModels.DETECTOR, "detector-bytes")
        fixture(dir, PpocrModels.RECOGNIZER, "recognizer-bytes")
        fixture(dir, PpocrModels.DICTIONARY, "dictionary-bytes")
        return dir
    }

    private fun expectedFor(dir: File): Map<String, String> =
        PpocrModels.NAMES.associateWith { sha256(File(dir, it)) }

    @Test
    fun anEmptyDirectoryIsReportedAsAbsent() {
        assertEquals(ModelState.Absent, PpocrModels(temp.newFolder("empty")).state())
    }

    @Test
    fun installingCopiesHashesAndRecordsTheVerification() {
        val source = sourceFixture()
        val root = temp.newFolder("root")
        val models = PpocrModels(root, expectedFor(source))

        val state = models.installFromDirectory(source)

        assertTrue("expected Ready, got $state", state is ModelState.Ready)
        for (name in PpocrModels.NAMES) {
            assertTrue("$name was not promoted", File(root, name).isFile)
        }
        assertTrue("the marker was not written", File(root, PpocrModels.MARKER).isFile)
        // The second call must use the marker rather than re-hashing (same answer either way here).
        assertTrue(models.state() is ModelState.Ready)
    }

    @Test
    fun aFileWithTheWrongHashIsRefusedAndNothingIsPromoted() {
        val source = sourceFixture()
        val pins = expectedFor(source).toMutableMap()
        pins[PpocrModels.DETECTOR] = "0".repeat(64)
        val root = temp.newFolder("root")
        val models = PpocrModels(root, pins)

        val state = models.installFromDirectory(source)

        assertTrue("expected Invalid, got $state", state is ModelState.Invalid)
        assertTrue((state as ModelState.Invalid).reason.contains("does not match"))
        assertTrue("a rejected file must not be promoted", !File(root, PpocrModels.DETECTOR).exists())
        assertTrue("no partial file may be left behind", root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun aFileThatChangedAfterVerificationIsDetected() {
        val source = sourceFixture()
        val root = temp.newFolder("root")
        val models = PpocrModels(root, expectedFor(source))
        assertTrue(models.installFromDirectory(source) is ModelState.Ready)

        // Same name, different length: the marker no longer applies, so the hash is recomputed.
        File(root, PpocrModels.RECOGNIZER).appendText("tampered")

        val state = models.state()
        assertTrue("expected Invalid, got $state", state is ModelState.Invalid)
        assertTrue((state as ModelState.Invalid).reason.contains(PpocrModels.RECOGNIZER))
    }

    @Test
    fun aSourceMissingOneFileIsReportedByName() {
        val source = sourceFixture()
        val pins = expectedFor(source)
        File(source, PpocrModels.DICTIONARY).delete()
        val models = PpocrModels(temp.newFolder("root"), pins)

        val state = models.installFromDirectory(source)

        assertTrue(state is ModelState.Invalid)
        assertTrue((state as ModelState.Invalid).reason.contains(PpocrModels.DICTIONARY))
    }

    @Test
    fun anUnexpectedFileNameIsRefused() {
        val models = PpocrModels(temp.newFolder("root"), emptyMap())
        val state = models.install(mapOf("other.onnx" to "x".byteInputStream()))
        assertTrue(state is ModelState.Invalid)
    }

    @Test
    fun thePinnedHashesAreTheThreeVerifiedArtifacts() {
        assertEquals(PpocrModels.NAMES.toSet(), PpocrModels.PINNED_SHA256.keys)
        for ((name, sha) in PpocrModels.PINNED_SHA256) {
            assertEquals("$name is not a sha256", 64, sha.length)
            assertTrue("$name is not lowercase hex", sha.all { it in "0123456789abcdef" })
        }
        // The dictionary is the one committed to this repository (v2.5.0-dev.5-ag.md).
        assertEquals(
            "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
            PpocrModels.PINNED_SHA256[PpocrModels.DICTIONARY],
        )
    }
}
