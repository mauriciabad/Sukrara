// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Pure Kotlin types and ports every module shares (additive only).
plugins {
    id("sukrara.jvm.library")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
