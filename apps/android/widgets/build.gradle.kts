// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Home-screen widgets (Glance) drawn by :core:graph.
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("sukrara.contracts")
    alias(libs.plugins.roborazzi)
}

android {
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:graph"))
    implementation(project(":core:designsystem"))
    // the quick Add treatment dialog a tap opens (QuickTreatmentIntents)
    implementation(project(":feature:treatments"))
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.roborazzi)
}

// Goldens in src/test/snapshots: `./gradlew :widgets:recordRoborazziDebug` rewrites them, `verifyRoborazziDebug` (CI)
// compares. A plain test run neither writes nor compares.
tasks.withType<Test>().configureEach {
    // Robolectric on SDK 36 reaches into FileDescriptor internals
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
