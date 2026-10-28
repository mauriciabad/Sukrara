// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Modules apply plugins through the convention plugins in build-logic/; declaring them here once keeps them on one
// classloader.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.roborazzi) apply false
}

// `./gradlew check` also runs the convention plugins' own tests (AppVersionTest…).
tasks.register("check") {
    dependsOn(gradle.includedBuild("build-logic").task(":convention:test"))
}

// The verified goldens (:core:graph, :widgets) are recorded only on CI (.github/workflows/goldens.yml): Robolectric
// draws text and blends a little differently on macOS and Linux, so a Mac recording fails CI's verify. Locally,
// `compareRoborazziDebug` shows the diff. (Other modules' screenshot galleries still record locally.)
val recordsGoldens = Regex("^(:core:graph:|:widgets:)?recordRoborazzi")
if (System.getenv("CI") == null && gradle.startParameter.taskNames.any { recordsGoldens.containsMatchIn(it) }) {
    throw GradleException(
        "Roborazzi goldens are recorded on CI only: gh workflow run goldens.yml --ref <branch> " +
            "(locally, compareRoborazziDebug shows the diff)",
    )
}
