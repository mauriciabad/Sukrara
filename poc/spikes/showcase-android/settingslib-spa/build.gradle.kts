/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted for Sukrara (README.md): AGP's built-in Kotlin instead of kotlin-android, our Compose BOM and material3,
 * minSdk/compileSdk of the app, no chart / Lottie / jacoco / androidTest.
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.android.settingslib.spa"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    sourceSets.getByName("main") {
        kotlin.directories.add("src")
        res.directories.add("res")
        manifest.srcFile("AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint { abortOnError = false }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api("androidx.appcompat:appcompat:1.7.1")
    api(libs.androidx.compose.material3)
    api("androidx.compose.material:material-icons-extended:1.7.8")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.graphics:graphics-shapes-android:1.1.0")
    api(libs.androidx.lifecycle.runtime.compose)
    api("androidx.navigation:navigation-compose:2.9.7")
    api("androidx.window:window:1.5.1")
    api("com.google.android.material:material:1.14.0-alpha02")
    debugApi("androidx.compose.ui:ui-tooling")
}
