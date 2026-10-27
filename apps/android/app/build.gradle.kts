// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import app.mauri.sukrara.buildlogic.NativeCore
import com.android.build.api.artifact.SingleArtifact
import java.util.zip.ZipFile

// The app: Application, AppGraph, MainActivity + navigation, manifest aggregation, src/debug tools.
plugins {
    id("sukrara.android.application")
    id("sukrara.android.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:alerts"))
    implementation(project(":core:protocol"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:graph"))
    implementation(project(":monitor"))
    implementation(project(":sensor"))
    implementation(project(":share"))
    implementation(project(":integrations"))
    implementation(project(":pen"))
    implementation(project(":widgets"))
    implementation(project(":feature:home"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:sharing"))
    implementation(project(":feature:sensor"))
    implementation(project(":feature:treatments"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(project(":core:testing"))
}

/*
 * A release APK must carry the Libre 3 core from the pinned Juggluco revision: the owner's sensor depends on it.
 * The checkout is checked before anything is built (pre<Variant>Build roots assemble, bundle and install); the
 * finished APK is checked for the library before assemble/install complete.
 */
val jugglucoProblem = NativeCore.problem(project, NativeCore.locate(project))
val verifyJuggluco =
    tasks.register("verifyJuggluco") {
        group = "verification"
        description = "Fails a release build without the pinned, clean Juggluco checkout."
        doLast { jugglucoProblem?.let { throw GradleException("Release build without the Libre 3 core: $it") } }
    }
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyJuggluco) }

androidComponents {
    // ManifestTest reads the merged debug manifest (every module's components) through the public artifact API.
    onVariants(selector().withBuildType("debug")) { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        tasks.withType<Test>().configureEach {
            inputs.file(manifest).withPropertyName("mergedManifest")
            jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dsukrara.mergedManifest=${manifest.get().asFile}") }
        }
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val apkDir = variant.artifacts.get(SingleArtifact.APK)
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val verify =
            tasks.register("verify${name}NativeCore") {
                group = "verification"
                description = "Fails when the ${variant.name} APK lacks ${NativeCore.LIBRARY}."
                inputs.files(apkDir)
                doLast {
                    val apks =
                        apkDir
                            .get()
                            .asFile
                            .walk()
                            .filter { it.extension == "apk" }
                            .toList()
                    if (apks.isEmpty()) throw GradleException("no APK in ${apkDir.get()}")
                    apks.forEach { apk ->
                        ZipFile(apk).use { zip ->
                            if (zip.getEntry(NativeCore.LIBRARY) == null) throw GradleException("$apk has no ${NativeCore.LIBRARY}")
                        }
                    }
                }
            }
        tasks.matching { it.name == "assemble$name" || it.name == "install$name" }.configureEach { dependsOn(verify) }
    }
    // Release unit tests would compile the release variant (and its guards) in `./gradlew check`; the debug unit
    // tests cover the same code.
    beforeVariants(selector().withBuildType("release")) { it.hostTests.values.forEach { t -> t.enable = false } }
}
