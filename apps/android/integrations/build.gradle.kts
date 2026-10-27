// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Uploads (Nightscout, Tidepool, LibreView), Health Connect, broadcasts, local servers, CSV export (chunk 26).
plugins {
    id("sukrara.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    testImplementation(project(":core:testing"))
}
