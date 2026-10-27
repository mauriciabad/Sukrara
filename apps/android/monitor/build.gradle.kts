// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// GlucoseHub implementation, MonitoringService, alert runtime, notifications (chunks 10, 11).
plugins {
    id("sukrara.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:alerts"))
    // Freshness (the face rule) for the glucose notification's text and the freshness ticker
    implementation(project(":core:graph"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
}
