// SPDX-License-Identifier: GPL-3.0-or-later

import java.io.ByteArrayOutputStream
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

// One source of truth for the application id: the manifest merge uses it, and the Azure configuration
// task below checks the redirect URI against the package name the app is actually installed under.
val appPackageName = "com.bookcovermatcher"

// ---- Azure app-registration values (consumed by the manifest and the generator task) ----------
//
// The three values are machine-local: they live in the repository root's git-ignored `.env` and are
// never written into a tracked file, a log line or a report. They are read here so the MSAL intent
// filter and the generated configuration always agree, and read again by `generateAzureAppConfig`,
// which is where they are validated and written out.
//
// The client id and redirect URI are **public application identifiers** — MSAL cannot work without
// them in the APK — while the `.env` file itself is never packaged. See the generator task below.
val azureEnvFile = rootProject.file(".env")
val azurePlaceholderValues = linkedMapOf(
    "Application_ID" to "00000000-0000-0000-0000-000000000000",
    "Directory_ID" to "00000000-0000-0000-0000-000000000000",
    "Redirect_URL" to "msal00000000-0000-0000-0000-000000000000://auth",
)

fun azureEnvValues(): Map<String, String> {
    val parsed = linkedMapOf<String, String>()
    if (!azureEnvFile.isFile) return parsed
    azureEnvFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains('=')) {
            val key = trimmed.substringBefore('=').trim()
            if (key.isNotEmpty()) parsed[key] = trimmed.substringAfter('=').trim().trim('"', '\'')
        }
    }
    return parsed
}

/**
 * Percent-decodes a URI path the way `android.net.Uri.getPath()` does — and deliberately **not** the way
 * `java.net.URLDecoder` does, because URLDecoder treats `+` as a space and a base64 signature hash
 * contains real `+` characters.
 *
 * This exists because of v2.6.0-dev.1 crash-fix-3: Android matches an intent filter's literal
 * `android:path` against the *decoded* `Uri.getPath()` of the incoming `msauth://` redirect, so a filter
 * carrying Azure's percent-encoded hash (`…%2B…%3D`) can never match. The browser then finds no activity
 * to hand the response to and shows a blank page while the app never resumes. Only the manifest
 * placeholder is decoded; the `redirect_uri` handed to MSAL stays exactly as Azure registered it.
 */
fun decodeManifestPath(path: String): String {
    if (!path.contains('%')) return path
    val bytes = ByteArrayOutputStream(path.length)
    var index = 0
    while (index < path.length) {
        val char = path[index]
        if (char == '%' && index + 2 < path.length) {
            val code = path.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                bytes.write(code)
                index += 3
                continue
            }
        }
        bytes.write(char.toString().toByteArray(Charsets.UTF_8))
        index += 1
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

/** Splits a redirect URI into the scheme / host / path an intent filter needs. */
fun redirectParts(redirect: String): Triple<String, String, String> {
    val scheme = redirect.substringBefore("://")
    val rest = redirect.substringAfter("://", "")
    val host = rest.substringBefore('/')
    val rawPath = if (rest.contains('/')) "/" + rest.substringAfter('/') else "/"
    val path = decodeManifestPath(rawPath)
    if (rawPath.contains('%') && path == rawPath) {
        throw GradleException(
            "The redirect path still carries percent escapes after decoding, so the intent filter " +
                "could never match the redirect the browser returns. Check decodeManifestPath().",
        )
    }
    return Triple(scheme, host, path)
}

val azureRedirectForManifest = azureEnvValues()["Redirect_URL"]?.takeIf { it.isNotBlank() }
    ?: azurePlaceholderValues.getValue("Redirect_URL")
val azureRedirectParts = redirectParts(azureRedirectForManifest)

android {
    namespace = "com.bookcovermatcher"
    compileSdk = 35

    defaultConfig {
        applicationId = appPackageName
        minSdk = 26
        targetSdk = 35
        // 220 is dev.5: 219 was dev.4, 218 dev.3, 217 2.5.0-dev.2, 216 dev.1, 215 the 2.4.0 stable release.
        versionCode = 221
        versionName = "2.5.0"

        // The custom-tab redirect is matched by MSAL's BrowserTabActivity intent filter. Its scheme,
        // host and path are taken from the registered Redirect_URL at build time, so no value from
        // `.env` is hardcoded in a tracked file and the filter cannot drift from the configuration
        // MSAL is given. Without a `.env` the placeholders below are used and sign-in is simply not
        // configured yet.
        manifestPlaceholders["azureRedirectScheme"] = azureRedirectParts.first
        manifestPlaceholders["azureRedirectHost"] = azureRedirectParts.second
        manifestPlaceholders["azureRedirectPath"] = azureRedirectParts.third

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
// ---------------------------------------------------------------------------------------------
// Licence assets: the root LICENSE and THIRD_PARTY_NOTICES.md travel inside the APK.
//
// A GPLv3 binary distribution has to carry the licence text and the third-party statements. Copying
// the two root documents into assets at build time means the APK cannot drift from the source tree,
// and the app never ships a second, edited copy of either document.
// ---------------------------------------------------------------------------------------------

val licenseAssetDir = layout.projectDirectory.dir("src/main/assets/licenses").asFile

val syncLicenseAssets = tasks.register("syncLicenseAssets") {
    group = "build"
    description = "Copies LICENSE and THIRD_PARTY_NOTICES.md into the APK assets."
    val root = rootProject.projectDir
    val sources = listOf(
        root.resolve("LICENSE") to "GPL-3.0.txt",
        root.resolve("THIRD_PARTY_NOTICES.md") to "THIRD_PARTY_NOTICES.md",
    )
    inputs.files(sources.map { it.first })
    outputs.dir(licenseAssetDir)
    doLast {
        licenseAssetDir.mkdirs()
        val missing = sources.map { it.first }.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "the licensing documents are missing, so the APK would ship without them: " +
                    missing.joinToString(", ") { it.path },
            )
        }
        sources.forEach { (from, name) ->
            from.copyTo(File(licenseAssetDir, name), overwrite = true)
        }
        logger.lifecycle("licence assets: " + sources.joinToString(", ") { it.second })
    }
}

tasks.named("preBuild") { dependsOn(syncLicenseAssets) }


// ---------------------------------------------------------------------------------------------
// Azure app-registration configuration for the OneDrive sign-in (v2.6.0).
//
// Two artefacts come out of the values read above, and they are deliberately different in status:
//
//  * `app/src/main/res/raw/msal_config_default.json` — **packaged**, because MSAL needs the public
//    client id and redirect URI at runtime. Both are public application identifiers, not secrets; a
//    public client cannot work without them in the app. The file is generated (git-ignored), so no
//    value from `.env` is ever written into a tracked source file.
//  * `app/build/generated/azure/msal_config_default.json` — a build-directory copy for inspection and
//    for the report's verification steps. Never packaged.
//
// The `.env` file itself is never packaged and never tracked. `Directory_ID` is **not** written into
// the runtime configuration: the intended authority is the personal-accounts endpoint
// `https://login.microsoftonline.com/consumers`, which MSAL derives from the
// `PersonalMicrosoftAccount` audience. Putting a tenant id beside that audience would be wrong — see
// `historical_prompt/v2.6.0-dev.1-ag.md` §20.4.
//
// A missing `.env` (fresh clone, CI, another developer) is normal: the placeholders from
// `.env.example` are used instead, so every build still works and no value is invented. A `.env` that
// exists but is incomplete, or that carries a redirect this app cannot answer, fails the build with a
// message naming the variable — never its value.
// ---------------------------------------------------------------------------------------------

val azureConfigDir = layout.buildDirectory.dir("generated/azure")
val azureResRawDir = layout.projectDirectory.dir("src/main/res/raw").asFile
val azureResRawFile = File(azureResRawDir, "msal_config_default.json")
val azurePackageName = appPackageName

val generateAzureAppConfig = tasks.register("generateAzureAppConfig") {
    group = "build"
    description = "Generates the MSAL app configuration from the git-ignored root .env (or placeholders)."
    // Not `inputs.file(...).optional()`: Gradle 9 rejects a missing `@InputFile` outright ("input file
    // does not exist"), and an optional file that is absent contributes nothing to the fingerprint — so
    // adding or deleting `.env` would leave this task UP-TO-DATE with a stale configuration. A digest of
    // the file (or the literal "absent") makes presence *and* content a proper input, and it is the
    // digest — never a value — that Gradle records.
    inputs.property("dotEnv", if (azureEnvFile.isFile) sha256OfFile(azureEnvFile) else "absent")
    outputs.dir(azureConfigDir)
    outputs.file(azureResRawFile)

    doLast {
        val fromEnv = azureEnvFile.isFile
        val values = linkedMapOf<String, String>()
        if (fromEnv) {
            azureEnvFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains('=')) {
                    val key = trimmed.substringBefore('=').trim()
                    if (key.isNotEmpty()) values[key] = trimmed.substringAfter('=').trim().trim('"', '\'')
                }
            }
            val missing = azurePlaceholderValues.keys.filter { values[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "the root .env does not define ${missing.joinToString(", ")}. Copy .env.example and " +
                        "fill in the three values; the names must match exactly. The values themselves are " +
                        "never printed by this build, which is why none appear here.",
                )
            }
            val unsafe = values.filterValues { v -> v.any { it == '"' || it == '\\' || it.isISOControl() } }.keys
            if (unsafe.isNotEmpty()) {
                throw GradleException(
                    ".env contains characters a JSON configuration cannot carry safely in: " +
                        "${unsafe.joinToString(", ")}. Check for a stray quote or line break.",
                )
            }
        }

        // Directory_ID is read only to prove the file is complete; it is deliberately NOT written into
        // the runtime configuration (see the header comment and §20.4 of the agent report).
        val clientId = values["Application_ID"] ?: azurePlaceholderValues.getValue("Application_ID")
        val redirect = values["Redirect_URL"] ?: azurePlaceholderValues.getValue("Redirect_URL")

        // The redirect must be one this application can actually answer: either MSAL's derived scheme
        // for this client id, or the Android-platform form the Azure portal generates from the package
        // name and the signing certificate. A mismatch is a silent sign-in failure later, so it fails
        // here instead — and the message names the shape, never the value.
        val androidForm = Regex("^msauth://([^/]+)/(?:[A-Za-z0-9+/=_.-]|%[0-9A-Fa-f]{2})+$").find(redirect)
        if (redirect != "msal$clientId://auth" &&
            !(androidForm != null && androidForm.groupValues[1] == azurePackageName)
        ) {
            throw GradleException(
                "Redirect_URL is not a redirect this application can answer. Expected " +
                    "`msal<Application_ID>://auth` or `msauth://$azurePackageName/<signature-hash>` " +
                    "matching the signing certificate. Fix the .env value or the app registration; the " +
                    "value itself is not printed here on purpose.",
            )
        }

        val config = buildString {
            appendLine("{")
            appendLine("  \"client_id\": \"$clientId\",")
            appendLine("  \"authorization_user_agent\": \"DEFAULT\",")
            appendLine("  \"account_mode\": \"MULTIPLE\",")
            // No broker redirect URI is registered for this application, so asking MSAL to try a broker
            // would fail and fall back; the browser flow with the registered custom-scheme redirect is
            // the supported path here.
            appendLine("  \"broker_redirect_uri_registered\": false,")
            appendLine("  \"redirect_uri\": \"$redirect\",")
            appendLine("  \"authorities\": [")
            appendLine("    {")
            appendLine("      \"type\": \"AAD\",")
            appendLine("      \"audience\": {")
            // Personal Microsoft accounts only. MSAL maps this audience to
            // https://login.microsoftonline.com/consumers, so no tenant id belongs here.
            appendLine("        \"type\": \"PersonalMicrosoftAccount\"")
            appendLine("      }")
            appendLine("    }")
            appendLine("  ]")
            append("}")
            appendLine()
        }
        // The packaged resource MSAL loads (R.raw.msal_config_default) …
        azureResRawDir.mkdirs()
        azureResRawFile.writeText(config, Charsets.UTF_8)
        // … and a build-directory copy used for inspection; never packaged.
        val out = azureConfigDir.get().file("msal_config_default.json").asFile
        out.parentFile.mkdirs()
        out.writeText(config, Charsets.UTF_8)
        logger.lifecycle(
            "azure config: " + if (fromEnv) {
                "generated from the root .env (values not printed)"
            } else {
                "generated from .env.example placeholders (no .env present)"
            },
        )
    }
}

tasks.named("preBuild") { dependsOn(generateAzureAppConfig) }

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
    // Microsoft sign-in for the OneDrive import (v2.6.0). MIT-licensed, minSdk 24; see
    // THIRD_PARTY_NOTICES.md and historical_prompt/v2.6.0-dev.1-ag.md §20.2.
    implementation(libs.msal.android)

    testImplementation(libs.junit)

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
