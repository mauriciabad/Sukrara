// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// The one card renderer: GlucoseCard, graph, layout, freshness (chunk 08).
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
    id("sukrara.contracts")
}

dependencies {
    api(project(":core:model"))
    testImplementation(project(":core:testing"))
}
