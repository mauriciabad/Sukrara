// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Sharing: relay transport, owner and follower engines, BLE share (chunks 13, 25). nostr-sdk (all crypto) lives here,
// not in :core:protocol (CHANGES A5).
plugins {
    id("sukrara.android.library")
    id("sukrara.contracts")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    implementation(project(":core:data"))
    implementation(libs.nostr.sdk)
    // nostr-sdk declares JNA as an optional runtime dependency: add the Android AAR flavour explicitly
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // the sharing ledger (seen gift wraps) is a :core:data Room DAO
    implementation(libs.androidx.room.runtime)
    testImplementation(project(":core:testing"))
    testImplementation(testFixtures(project(":core:protocol")))
    // Host unit tests can't load the AAR's Android .so: the JVM build of the same bindings (same classes, desktop
    // natives in its resources) runs the adapter there. Test classpath only, never in an APK.
    testImplementation(libs.nostr.sdk.jvm)
    testRuntimeOnly(libs.jna)
}
