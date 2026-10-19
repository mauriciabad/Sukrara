// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    // compileOnly-style on Android (platform org.json); a real dependency on the JVM
    implementation(libs.orgjson)
    testImplementation(libs.junit)
}
