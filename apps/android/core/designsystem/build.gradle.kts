// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Theme, motion, haptics and the shared settings rows.
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
    id("sukrara.contracts")
}

dependencies {
    // NumberDialog takes the generated NumberSpec (the one number input, CLAUDE.md "Settings never diverge")
    api(project(":core:data"))
    // THE mute lines (MuteText) word the alert engine's MuteCopy
    api(project(":core:alerts"))
    // the generated pictures (AvatarImage) draw the protocol's Avatar numbers
    api(project(":core:protocol"))
    testImplementation(libs.junit)
    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.serialization.json)
}
