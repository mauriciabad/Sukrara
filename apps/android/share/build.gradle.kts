// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Sharing: relay transport (nostr-sdk), owner and follower engines, BLE share (chunks 13, 25).
plugins {
    id("sukrara.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:protocol"))
    implementation(project(":core:data"))
    implementation(libs.nostr.sdk)
    // nostr-sdk declares JNA as an optional runtime dependency: add the Android AAR flavour explicitly
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":core:testing"))
}
