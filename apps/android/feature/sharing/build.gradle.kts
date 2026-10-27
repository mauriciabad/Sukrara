// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// First-run setup flow, sharing, invites, following (chunk 24).
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    // Robolectric's resource loader reads FileDescriptor internals on JDK 21
    testOptions.unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:designsystem"))
    // the setup record's typed store and the generated threshold formats
    implementation(project(":core:data"))
    // the alert settings the alert step confirms
    implementation(project(":core:alerts"))
    // the wearer's sharing engine (chunk 13)
    implementation(project(":share"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.serialization.json)
    // invite QR code
    implementation(libs.zxing.core)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.androidx.test.core)
}
