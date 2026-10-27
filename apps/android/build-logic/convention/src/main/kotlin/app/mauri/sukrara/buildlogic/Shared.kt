// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

/** SDK levels of every Android module: newest API generation only. */
object Sdk {
    const val COMPILE = 37
    const val TARGET = 37
    const val MIN = 36
}

/** Every module compiles and tests on JDK 21 (Robolectric needs it); no auto-download (gradle.properties). */
const val JDK = 21

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String) = findLibrary(alias).orElseThrow { IllegalArgumentException("libs.$alias missing") }

/** `:app` → `app.mauri.sukrara`; `:core:model` → `app.mauri.sukrara.core.model`. */
fun Project.sukraraNamespace(): String =
    if (path == ":app") "app.mauri.sukrara" else "app.mauri.sukrara" + path.replace(':', '.').replace('-', '_')

/** The repo root (apps/android/../..): contracts/, VERSION, third_party/. */
val Project.repoRoot get() = rootProject.layout.projectDirectory.dir("../..")

/** Shared Android settings for the application and library plugins. */
fun Project.configureAndroid(android: CommonExtension) {
    android.apply {
        namespace = sukraraNamespace()
        compileSdk = Sdk.COMPILE
        defaultConfig.minSdk = Sdk.MIN
        compileOptions.sourceCompatibility = JavaVersion.VERSION_21
        compileOptions.targetCompatibility = JavaVersion.VERSION_21
        testOptions.unitTests.isIncludeAndroidResources = true
    }
    configureKotlin()
}

/** JDK 21 toolchain for compilation and for every test task (the build fails loudly when JDK 21 is missing). */
fun Project.configureKotlin() {
    extensions.getByType<KotlinBaseExtension>().jvmToolchain(JDK)
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(JDK))
    }
    tasks.withType<Test>().configureEach { javaLauncher.set(launcher) }
}

fun Project.testDependencies() {
    dependencies.add("testImplementation", libs.lib("junit"))
    dependencies.add("testImplementation", libs.lib("kotlinx-coroutines-test"))
}
