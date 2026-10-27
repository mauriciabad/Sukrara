// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Storage: Preferences DataStore (settings, keys from the schema), typed DataStore, the Keystore-backed SecureStore, Room (`SukraraDb`).
plugins {
    id("sukrara.android.library")
    id("sukrara.room")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":core:testing"))
    // RoomToolchainTest: proves Room + KSP work under AGP 9 built-in Kotlin
    kspTest(libs.androidx.room.compiler)
}
