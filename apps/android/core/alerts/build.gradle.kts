// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// The pure alert engine (chunk 04): no Android, no clock, no :core:model (the runtime maps its Reading to AlertReading).
// The scenario driver is a test fixture: `:monitor` runs the same timelines through the Android runtime (chunk 11).
plugins {
    id("sukrara.jvm.library")
    id("sukrara.contracts")
    `java-test-fixtures`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testFixturesImplementation(project(":core:testing"))
}
