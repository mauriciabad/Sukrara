// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(21) }

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.room.gradle.plugin)
    compileOnly(libs.spotless.gradle.plugin)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "sukrara.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "sukrara.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "sukrara.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("jvmLibrary") {
            id = "sukrara.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
        }
        register("room") {
            id = "sukrara.room"
            implementationClass = "RoomConventionPlugin"
        }
        register("contracts") {
            id = "sukrara.contracts"
            implementationClass = "ContractsConventionPlugin"
        }
        register("quality") {
            id = "sukrara.quality"
            implementationClass = "QualityConventionPlugin"
        }
    }
}
