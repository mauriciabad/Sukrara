// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.lib
import app.mauri.sukrara.buildlogic.libs
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

/** Jetpack Compose on top of an Android application or library module (apply after it). */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true
        val bom = dependencies.platform(libs.lib("androidx-compose-bom"))
        dependencies.add("implementation", bom)
        dependencies.add("testImplementation", bom)
        dependencies.add("implementation", libs.lib("androidx-compose-ui"))
        dependencies.add("implementation", libs.lib("androidx-compose-material3"))
        dependencies.add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        dependencies.add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
    }
}
