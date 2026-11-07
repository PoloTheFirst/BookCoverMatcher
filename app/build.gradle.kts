// SPDX-License-Identifier: GPL-3.0-or-later

import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquo.python)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.bookcovermatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bookcovermatcher"
        minSdk = 26
        targetSdk = 35
        // 220 is dev.5: 219 was dev.4, 218 dev.3, 217 2.5.0-dev.2, 216 dev.1, 215 the 2.4.0 stable release.
        versionCode = 220
        versionName = "2.5.0-dev.5"

        // ONNX Runtime ships four ABIs (~15 MB each). Real phones are arm64-v8a;
        // x86_64 keeps the emulator working. Add "armeabi-v7a" for old 32-bit phones.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Shrinking is left off on purpose: ONNX Runtime uses JNI and the app is dominated by the model.
            isMinifyEnabled = false
            // So `assembleRelease` gives an installable APK out of the box. Replace with your own keystore to publish.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        // Turned on so the More screen can show `BuildConfig.VERSION_NAME` instead of a hand-copied
        // string that silently drifts from this file on every release.
        buildConfig = true
    }

    // The model is a big, already-compact binary: store it uncompressed so it can be read straight from the APK.
    androidResources { noCompress += "onnx" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    lint { abortOnError = false }
}

// ---------------------------------------------------------------------------------------------
// Embedded Python (Chaquopy) — the in-process runtime that runs the original `chrome-lens-py`
// package. Everything happens inside this app's own process: no companion device, no server.
//
// Build-host requirement: a CPython 3.12 interpreter. Chaquopy needs the build machine's Python
// major.minor to match the app's, and 3.12 is the newest target Chaquopy publishes a *prebuilt*
// Pillow wheel for (chrome-lens-py needs Pillow, which has C extensions we cannot compile here).
// Point at it from the git-ignored local.properties:
//
//     chaquopy.buildPython=C:/path/to/Python/3.12/python.exe
//
// or with -PchaquopyBuildPython=<path>. Without either, Chaquopy looks for `py -3.12` on PATH.
// ---------------------------------------------------------------------------------------------
val buildPythonPath: String? =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }.getProperty("chaquopy.buildPython")
        ?: providers.gradleProperty("chaquopyBuildPython").orNull

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPythonPath?.let { buildPython(it) }

        pip {
            // The upstream library, unmodified and version-pinned (MIT licensed, pure Python).
            install("chrome-lens-py==3.8.2")
            // chrome-lens-py asks for protobuf>=6.33.5,<8. Only the 6.33.x line still publishes a
            // pure-Python (py3-none-any) wheel; 7.x ships platform wheels we could not install.
            install("protobuf==6.33.6")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// PP-OCRv6 Medium assets — required by the PaddleOCR engine and packaged into every APK.
//
// The three files are ~139 MB, so they are *not* committed: this task fetches them during an
// ordinary Gradle build, verifies each SHA-256 against the pinned value, and fails the build if a
// file is missing, truncated or different. Verified local copies are reused, so only the first
// build (or CI) needs network access.
//
// Provenance (see `historical_prompt/v2.5.0-dev.5-ag.md`):
//   detector    PaddlePaddle/PP-OCRv6_medium_det_onnx  @ 61323801669c338b7891481ec7bac61ce31b576a
//   recogniser  PaddlePaddle/PP-OCRv6_medium_rec_onnx   @ 50c7eacafc52fa7bcf4194e8cd08e46f8558504b
//   dictionary  ppocr/utils/dict/ppocrv6_dict.txt       (Apache-2.0, already tracked in this repo)
// All three artifacts are Apache-2.0.
// ---------------------------------------------------------------------------------------------

data class PaddleOcrAsset(
    val name: String,
    val sha256: String,
    val size: Long,
    /** Remote source; used when no verified local copy exists. */
    val url: String? = null,
    /** Local source that is already tracked in git (the dictionary). */
    val localSource: File? = null,
)

fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val paddleOcrAssets = listOf(
    PaddleOcrAsset(
        name = "det.onnx",
        sha256 = "eb13b44b25bb36f89528b68720af8a61d9cf381176107f465db1757b65d086e1",
        size = 62_032_837L,
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/" +
            "61323801669c338b7891481ec7bac61ce31b576a/inference.onnx",
    ),
    PaddleOcrAsset(
        name = "rec.onnx",
        sha256 = "9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba",
        size = 76_554_979L,
        url = "https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_rec_onnx/resolve/" +
            "50c7eacafc52fa7bcf4194e8cd08e46f8558504b/inference.onnx",
    ),
    PaddleOcrAsset(
        name = "ppocrv6_dict.txt",
        sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
        size = 74_947L,
        localSource = rootProject.file("native/ocr/assets/ppocrv6_dict.txt"),
    ),
)

val paddleOcrAssetDir = layout.projectDirectory.dir("src/main/assets/ocr/ppocrv6").asFile

val preparePaddleOcrAssets = tasks.register("preparePaddleOcrAssets") {
    group = "build"
    description = "Fetches and SHA-256-verifies the PP-OCRv6 Medium assets that every APK must contain."
    outputs.dir(paddleOcrAssetDir)
    doLast {
        paddleOcrAssetDir.mkdirs()
        val problems = mutableListOf<String>()
        for (asset in paddleOcrAssets) {
            val target = File(paddleOcrAssetDir, asset.name)
            if (target.isFile && target.length() == asset.size && sha256OfFile(target) == asset.sha256) {
                logger.lifecycle("PP-OCRv6 asset present: ${asset.name} (${asset.size} bytes, sha256 verified)")
                continue
            }
            target.delete()
            val part = File(paddleOcrAssetDir, "${asset.name}.part")
            part.delete()
            try {
                when {
                    asset.localSource != null -> {
                        if (!asset.localSource.isFile) {
                            throw GradleException("${asset.localSource} is missing - it is tracked in git, restore it")
                        }
                        asset.localSource.copyTo(part, overwrite = true)
                    }
                    asset.url != null -> {
                        logger.lifecycle("Downloading ${asset.name} from ${asset.url}")
                        URI(asset.url).toURL().openStream().use { input ->
                            part.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    else -> throw GradleException("no source configured for ${asset.name}")
                }
                val gotSize = part.length()
                val gotSha = sha256OfFile(part)
                if (gotSize != asset.size || gotSha != asset.sha256) {
                    throw GradleException(
                        "hash/size mismatch: expected ${asset.size} bytes sha256 ${asset.sha256}, " +
                            "got $gotSize bytes sha256 $gotSha",
                    )
                }
                if (!part.renameTo(target)) throw GradleException("could not move the verified file into place")
                logger.lifecycle("Prepared ${asset.name} ($gotSize bytes, sha256 verified)")
            } catch (e: Exception) {
                part.delete()
                problems += "${asset.name}: ${e.message}"
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "PP-OCRv6 assets are missing or invalid, so the APK would ship a PaddleOCR that cannot run:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nEvery APK must contain the pinned detector, recogniser and dictionary. " +
                    "Check network access for the first build, then re-run.",
            )
        }
    }
}

// Any packaging task (and therefore every ordinary build) needs the assets in place first.
tasks.named("preBuild") { dependsOn(preparePaddleOcrAssets) }

// The live catalogue checks are opt-in: `-DonlineCheck=1` forwards the flag to the test JVM.
tasks.withType<Test>().configureEach {
    systemProperty("onlineCheck", System.getProperty("onlineCheck") ?: "")
}


dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.exifinterface)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
