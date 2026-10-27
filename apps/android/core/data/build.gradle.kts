// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Storage: Preferences DataStore (settings, keys from the schema), typed DataStore, the Keystore-backed SecureStore, Room (`SukraraDatabase`), OkHttp, the diagnostic log.
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
    api(libs.okhttp)
    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
