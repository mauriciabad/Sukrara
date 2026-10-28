// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Insulin pens: NFC read, PenEngine, dose ledger.
plugins {
    id("sukrara.android.library")
    id("sukrara.contracts")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.androidx.work.runtime)
    testImplementation(project(":core:testing"))
}
