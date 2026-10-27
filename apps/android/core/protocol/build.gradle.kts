// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Nostr protocol v2 wire formats, pure JVM, no nostr-sdk (chunk 06).
plugins {
    id("sukrara.jvm.library")
    id("sukrara.contracts")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
}
