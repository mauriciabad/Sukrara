// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.orgjson)
}

// ./gradlew -q :cli:run --args="help"
application { mainClass.set("dev.spike.nsupload.cli.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir; standardInput = System.`in` }
