package com.bookcovermatcher

import com.bookcovermatcher.core.licensing.LicenseDocuments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The repository's licensing invariants, run with the ordinary unit tests so a future change cannot
 * quietly drop them. `tools/check-licenses.py` checks the same rules from the command line (and can
 * additionally inspect tags and branch tips); this class is the in-build guard.
 */
class LicensingPolicyTest {

    private val identifier = "SPDX-License-Identifier: GPL-3.0-or-later"

    /** Gradle runs unit tests from the module directory, so the repository root is one level up. */
    private fun root(): File = File("..").canonicalFile

    @Test
    fun theLicenceIsTheCompleteOfficialGplv3Text() {
        val license = File(root(), "LICENSE")
        assertTrue("LICENSE must exist at the repository root", license.isFile)
        val bytes = license.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        // Pinned to https://www.gnu.org/licenses/gpl-3.0.txt — 35,149 bytes of unmodified text.
        assertEquals("3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986", digest)
        val text = license.readText()
        for (marker in listOf("GNU GENERAL PUBLIC LICENSE", "Version 3, 29 June 2007", "END OF TERMS AND CONDITIONS")) {
            assertTrue("LICENSE must contain $marker", text.contains(marker))
        }
    }

    @Test
    fun projectOwnedSourcesSayOrLaterAndNeverTheOldIdentifier() {
        val root = root()
        // Same exclusions as tools/check-licenses.py: IDE and Gradle caches, build outputs, and the
        // user's untracked QA artefacts are not project source.
        val skippedDirs = setOf(".git", "build", ".gradle", ".kotlin", ".idea", "BookCoverMatcher-QA")
        val code = root.walkTopDown()
            .onEnter { it.name !in skippedDirs }
            .filter { it.isFile && it.extension in setOf("kt", "kts", "rs", "py", "ps1", "xml", "pro") }
            .filterNot { it.path.contains("src${File.separator}test${File.separator}resources") }
            .filterNot { it.path.replace('\\', '/').contains("assets/licenses") }
            .toList()
        assertTrue("expected project sources to be found", code.size > 200)
        val missing = code.filterNot { it.readText().contains(identifier) }.map { it.name }
        assertEquals("files without $identifier: $missing", emptyList<String>(), missing)
        // These three name the identifier they hunt for, so they cannot count themselves as matches.
        val old = code
            .filter { it.name !in setOf("LicensingPolicyTest.kt", "apply-license-headers.py", "check-licenses.py") }
            .filter { it.readText().contains("SPDX-License-Identifier: GPL-3.0-only") }
            .map { it.name }
        assertEquals("files still carrying the previous identifier: $old", emptyList<String>(), old)
    }

    @Test
    fun thirdPartyFilesAreNotRelicensed() {
        val root = root()
        val wrapper = File(root, "gradlew").readText()
        assertTrue(wrapper.contains("SPDX-License-Identifier: Apache-2.0"))
        assertTrue(!wrapper.contains(identifier))
        assertTrue(!File(root, "native/ocr/assets/ppocrv6_dict.txt").readText().contains("SPDX"))
        assertTrue(!File(root, "app/src/main/assets/models/openclip_image.json").readText().contains("SPDX"))
        assertTrue(File(root, "native/ocr/Cargo.toml").readText().contains("license = \"GPL-3.0-or-later\""))
    }

    @Test
    fun theDocumentsStateOrLaterAndKeepTheThirdPartyInventory() {
        val root = root()
        for (name in listOf("README.md", "NOTICE", "THIRD_PARTY_NOTICES.md")) {
            val text = File(root, name).readText()
            assertTrue("$name must state GPL-3.0-or-later", text.contains("GPL-3.0-or-later"))
            assertTrue("$name must not state GPL-3.0-only", !text.contains("GPL-3.0-only"))
        }
        val notices = File(root, "THIRD_PARTY_NOTICES.md").readText()
        for (required in listOf("PaddleOCR", "ONNX Runtime", "OpenCLIP", "Unresolved", "Apache-2.0", "MIT")) {
            assertTrue("THIRD_PARTY_NOTICES.md must document $required", notices.contains(required))
        }
    }

    @Test
    fun theAppShipsBothDocumentsAndOffersThemInTheUi() {
        assertEquals(
            listOf("licenses/GPL-3.0.txt", "licenses/THIRD_PARTY_NOTICES.md"),
            LicenseDocuments.ordered.map { it.assetPath },
        )
        assertTrue(LicenseDocuments.GPL.title.contains("v3.0 or later"))
        // The More tab must actually offer them: a document nobody can open is not compliance.
        val more = File(root(), "app/src/main/java/com/bookcovermatcher/ui/MoreScreen.kt").readText()
        assertTrue(more.contains("LicenseDocuments"))
        assertTrue(more.contains("Open-source licences"))
        // And the APK must actually carry them: the Gradle task that copies them has to be wired in.
        val gradle = File(root(), "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("syncLicenseAssets"))
        assertTrue(gradle.contains("THIRD_PARTY_NOTICES.md"))
    }
}
