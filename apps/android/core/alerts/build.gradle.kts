// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// The pure alert engine: no Android, no clock, no :core:model (the runtime maps its Reading to AlertReading).
// The scenario driver is a test fixture: `:monitor` runs the same timelines through the Android runtime.
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
