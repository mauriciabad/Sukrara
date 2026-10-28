// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Settings pages.
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
}

dependencies {
    api(project(":core:model"))
    // the alert settings model (SPEC §3) the alert pages edit
    api(project(":core:alerts"))
    implementation(project(":core:designsystem"))
    // THE clock formatter (24-hour setting) for "muted until" times
    implementation(project(":core:graph"))
    // the settings file pickers (Settings › Advanced)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The screenshot gallery records into build/outputs/roborazzi (synthetic data, for the UX review; no goldens are
// committed: images live only under docs/img or evidence folders).
tasks.withType<Test>().configureEach {
    systemProperty("roborazzi.test.record", "true")
    // Robolectric on SDK 36 reaches into FileDescriptor internals
    jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}
