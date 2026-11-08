// SPDX-License-Identifier: GPL-3.0-or-later

package com.bookcovermatcher.core.licensing

/**
 * The licence documents the app ships and shows.
 *
 * Two documents, both copied into `assets/licenses/` from the repository root at build time
 * (`syncLicenseAssets` in `app/build.gradle.kts`), so the APK can never disagree with the source tree:
 *
 * * [GPL] — the canonical GNU GPLv3 text, the licence of all project-owned code;
 * * [THIRD_PARTY] — the third-party inventory, which states that those components keep their own
 *   terms (PaddleOCR Apache-2.0, ONNX Runtime MIT, and the OpenCLIP weights whose terms are unresolved).
 *
 * Keeping this list in plain Kotlin lets a JVM test check it without an Android runtime.
 */
enum class LicenseDocuments(
    val assetPath: String,
    val title: String,
    val subtitle: String,
    val shortLabel: String,
) {

    GPL(
        assetPath = "licenses/GPL-3.0.txt",
        title = "GNU General Public License v3.0 or later",
        subtitle = "The licence of this app's own code",
        shortLabel = "GPLv3",
    ),

    THIRD_PARTY(
        assetPath = "licenses/THIRD_PARTY_NOTICES.md",
        title = "Third-party notices",
        subtitle = "Components that keep their own licences",
        shortLabel = "Third party",
    );

    companion object {
        /** The order the dialog shows. */
        val ordered: List<LicenseDocuments> = listOf(GPL, THIRD_PARTY)
    }
}
