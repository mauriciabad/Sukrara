// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.configureKotlin
import app.mauri.sukrara.buildlogic.testDependencies
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A pure Kotlin/JVM module (no Android): the logic the Mac app mirrors (model, alerts, protocol) stays testable on
 * the JVM and can't reach Android APIs by accident.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("sukrara.quality")
        configureKotlin()
        testDependencies()
    }
}
