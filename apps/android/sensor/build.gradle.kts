// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.NativeCore

// The sensor: Bluetooth link, Libre 3 driver and its native security core, pairing, Juggluco source (chunk 12).
plugins {
    id("sukrara.android.library")
}

// The Libre 3 core is compiled only when a Juggluco checkout is there (NativeCore). Without it a debug build still
// works (the app shows "Libre 3 core missing"); a release build of :app refuses (verifyNativeCore).
val juggluco = NativeCore.locate(project)
if (juggluco == null) {
    logger.warn("WARNING: no Juggluco checkout: this build has NO Libre 3 core (release builds will fail). See docs/DEVELOPMENT.md.")
}

android {
    defaultConfig.ndk.abiFilters += "arm64-v8a"
    if (juggluco != null) {
        externalNativeBuild.cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
        defaultConfig.externalNativeBuild.cmake.arguments += "-DJUGGLUCO=${juggluco.path}/Common/src/main/cpp"
    }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":core:testing"))
}
