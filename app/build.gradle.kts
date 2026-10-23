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
        versionCode = 204
        versionName = "2.2.2"

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
