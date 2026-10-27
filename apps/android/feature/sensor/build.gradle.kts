// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// New-sensor flow and sensor end UI (chunk 18).
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":sensor"))
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(project(":core:testing"))
}
