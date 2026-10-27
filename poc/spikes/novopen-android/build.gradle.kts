// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    // the ledger (pens.json) is @Serializable, as in the app
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

// ./gradlew test   the OpenNov port (xDrip's tests + transcripts) and the dose-engine scenarios (scenarios.json)
tasks.test { workingDir = rootProject.projectDir; testLogging { events("failed"); showStandardStreams = false } }
