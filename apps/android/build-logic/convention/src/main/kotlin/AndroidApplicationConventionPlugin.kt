// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.AppVersion
import app.mauri.sukrara.buildlogic.Sdk
import app.mauri.sukrara.buildlogic.configureAndroid
import app.mauri.sukrara.buildlogic.repoRoot
import app.mauri.sukrara.buildlogic.testDependencies
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.io.File

/**
 * The app module: id `app.mauri.sukrara` for debug and release (one install per phone), version from `<repo>/VERSION`,
 * arm64 only, R8 + resource shrinking on release, release signed with the dedicated Sukrara key.
 *
 * Release signing (docs/DEVELOPMENT.md): keystore `SUKRARA_KEYSTORE` (default `~/.android/sukrara-release.jks`,
 * alias `sukrara`), password from env `SUKRARA_KEYSTORE_PASSWORD` only — Gradle never reads the Keychain; the release
 * scripts export it. A release build without them stops in `verifyReleaseSigning` with the instructions.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("sukrara.quality")
        val version = AppVersion.parse(repoRoot.file("VERSION").asFile.readText())
        val keystore = providers.environmentVariable("SUKRARA_KEYSTORE").orNull?.let(::File)
            ?: File(System.getProperty("user.home"), ".android/sukrara-release.jks")
        val password = providers.environmentVariable("SUKRARA_KEYSTORE_PASSWORD").orNull
        val signable = keystore.isFile && !password.isNullOrEmpty()

        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            defaultConfig {
                applicationId = "app.mauri.sukrara"
                targetSdk = Sdk.TARGET
                versionCode = version.code
                versionName = version.name
                buildConfigField("boolean", "PRERELEASE", version.prerelease.toString())
                ndk { abiFilters += "arm64-v8a" }
            }
            buildFeatures.buildConfig = true
            signingConfigs.create("release") {
                if (signable) {
                    storeFile = keystore
                    storePassword = password
                    keyAlias = "sukrara"
                    keyPassword = password
                }
            }
            buildTypes.getByName("release") {
                isMinifyEnabled = true
                isShrinkResources = true
                if (signable) signingConfig = signingConfigs.getByName("release")
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
            androidResources {
                generateLocaleConfig = true
                localeFilters += listOf("en", "es")
            }
        }
        testDependencies()

        val keystorePath = keystore.path
        val verifySigning = tasks.register("verifyReleaseSigning") {
            group = "verification"
            description = "Fails a release build that would not be signed with the Sukrara release key."
            doLast {
                if (!signable) {
                    throw GradleException(
                        "Release signing key not available (keystore $keystorePath, env SUKRARA_KEYSTORE_PASSWORD). " +
                            "Create the key once with tools/release/make-keystore.sh, then build with\n" +
                            "  SUKRARA_KEYSTORE_PASSWORD=\"\$(security find-generic-password -s sukrara-android-release " +
                            "-a sukrara -w)\" ./gradlew :app:assembleRelease",
                    )
                }
            }
        }
        // pre<Variant>Build is the root of every release output (assemble, bundle, install).
        tasks.named { it == "preReleaseBuild" }.configureEach { dependsOn(verifySigning) }
    }
}
