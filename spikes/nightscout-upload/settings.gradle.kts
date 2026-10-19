// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nightscout-upload-spike"
// core: the uploader library, the shape Sukrara would embed (plain JVM + org.json + HttpURLConnection, both on Android)
include(":core")
// cli: drives core against a Nightscout server (synthetic data only) and checks what followers would read
include(":cli")
