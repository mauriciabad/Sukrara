// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Home-screen widgets (Glance) drawn by :core:graph (chunk 20).
plugins {
    id("sukrara.android.library")
    id("sukrara.android.compose")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:graph"))
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.work.runtime)
}
