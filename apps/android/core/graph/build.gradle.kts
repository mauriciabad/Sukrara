// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// The one card renderer: GlucoseCard, graph, layout, freshness (chunk 08).
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
    id("sukrara.contracts")
    alias(libs.plugins.roborazzi)
}

android {
    // Robolectric's resource loader reads FileDescriptor internals on JDK 21
    testOptions.unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
}

dependencies {
    api(project(":core:model"))
    // the generated SettingsSpec (band limits, graph defaults)
    implementation(project(":core:data"))
    // ColorUtils, PathParser for the Canvas drawing
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.serialization.json)
    // Robolectric resources (CardWords en + es) and Roborazzi card screenshots
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}
