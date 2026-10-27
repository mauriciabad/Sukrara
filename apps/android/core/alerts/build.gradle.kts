// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// The pure alert engine (chunk 04).
plugins {
    id("sukrara.jvm.library")
    id("sukrara.contracts")
}

dependencies {
    api(project(":core:model"))
    testImplementation(project(":core:testing"))
}
