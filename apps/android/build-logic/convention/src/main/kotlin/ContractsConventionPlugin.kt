// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.repoRoot
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/**
 * Tests that read the cross-platform truth in `<repo>/contracts` (alert scenarios, graph fixtures, settings schema…):
 * the path reaches the tests as system property `sukrara.contracts` (read it with `Contracts.file` from
 * `:core:testing`), and the folder is a task input, so editing a fixture re-runs the tests instead of hitting the
 * build cache.
 */
class ContractsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val contracts = repoRoot.dir("contracts")
        tasks.withType<Test>().configureEach {
            systemProperty("sukrara.contracts", contracts.asFile.absolutePath)
            inputs.dir(contracts).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contracts")
        }
    }
}
