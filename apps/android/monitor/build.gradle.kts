// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

import app.mauri.sukrara.buildlogic.repoRoot

// GlucoseHub implementation, MonitoringService, alert runtime, notifications (chunks 10, 11).
plugins {
    id("sukrara.android.library")
    id("sukrara.contracts")
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The alert sounds come straight from the contract (contracts/alerts/sounds/android/raw), never copied.
    sourceSets["main"].res.srcDir(repoRoot.dir("contracts/alerts/sounds/android"))
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:alerts"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(testFixtures(project(":core:alerts")))
}
