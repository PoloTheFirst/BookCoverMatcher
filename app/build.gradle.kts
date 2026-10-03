plugins {
    alias(libs.plugins.android.application)
    // Compose compiler plugin for Kotlin 2.0+
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.polo.bookcovermatcher"
    compileSdk {
        version = release(37)
    }

    // Enable Jetpack Compose
    buildFeatures { compose = true }

    defaultConfig {
        applicationId = "com.polo.bookcovermatcher"
        minSdk = 24
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0-dev.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("androidx.webkit:webkit:1.17.1")   // WebView fallback (kept for reference)

    // Compose BOM – pulls matching versions of compose libraries
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.ktx) // already present, but keep ordering
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
}