// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.Sdk
import app.mauri.sukrara.buildlogic.configureAndroid
import app.mauri.sukrara.buildlogic.testDependencies
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** An Android library module: namespace from its path, SDK levels, JDK 21, quality checks, JUnit. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("sukrara.quality")
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            lint.targetSdk = Sdk.TARGET
            testOptions.targetSdk = Sdk.TARGET
        }
        testDependencies()
    }
}
