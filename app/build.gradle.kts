plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 200
        versionName = "2.0.0"

        // ONNX Runtime ships four ABIs (~15 MB each). Real phones are arm64-v8a;
        // x86_64 keeps the emulator working. Add "armeabi-v7a" for old 32-bit phones.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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

    buildFeatures { compose = true }

    // The model is a big, already-compact binary: store it uncompressed so it can be read straight from the APK.
    androidResources { noCompress += "onnx" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    lint { abortOnError = false }
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
}
