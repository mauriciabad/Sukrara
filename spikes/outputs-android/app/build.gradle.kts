// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.spike.outputs"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.spike.outputs"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        // Same key as every spike (and the showcase app): ~/.android/debug.keystore.
        create("spike") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("spike") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}
