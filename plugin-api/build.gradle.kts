// SPDX-License-Identifier: GPL-3.0-or-later
plugins { id("org.jetbrains.kotlin.jvm") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
