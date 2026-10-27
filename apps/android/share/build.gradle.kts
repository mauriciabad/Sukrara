// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Sharing: relay transport, owner and follower engines, BLE share (chunks 13, 25). nostr-sdk (all crypto) lives here,
// not in :core:protocol (CHANGES A5).
plugins {
    id("sukrara.android.library")
    id("sukrara.contracts")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// PocWireCompatTest runs the showcase 0.11.53 protocol code itself, compiled from poc/ unchanged (copied at build time,
// never committed, so it can't drift): its builders make the 0.11.53 wearer fixture and its parsing reads V1's wearer
// output. SensorLife.kt (Android + the whole settings stack) is replaced by the test shim PocShims.kt.
abstract class PocWireSources @Inject constructor(private val fs: FileSystemOperations) : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val root: DirectoryProperty

    @get:OutputDirectory
    abstract val out: DirectoryProperty

    @TaskAction fun copy() {
        fs.sync {
            from(root) { include(sources.map { it.relativeTo(root.get().asFile).invariantSeparatorsPath }) }
            into(out)
        }
    }
}

val pocKotlin = rootProject.layout.projectDirectory.dir("../../poc/spikes/showcase-android/app/src/main/kotlin")
val pocWireSources = tasks.register<PocWireSources>("pocWireSources") {
    root.set(pocKotlin)
    sources.from(
        listOf("core/Proto.kt", "core/Derive.kt", "core/Avatar.kt", "owner/OwnerState.kt").map { pocKotlin.file("app/mauri/sukrara/$it") },
    )
    out.set(layout.buildDirectory.dir("generated/poc-wire/kotlin"))
}

androidComponents {
    onVariants { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.sources?.kotlin
            ?.addGeneratedSourceDirectory(pocWireSources, PocWireSources::out)
    }
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

// `./gradlew :share:testDebugUnitTest -Psukrara.regen=true` rewrites contracts/protocol/sync/poc-0.11.53-wearer.json
tasks.withType<Test>().configureEach {
    systemProperty("sukrara.regen", providers.gradleProperty("sukrara.regen").getOrElse("false"))
}

dependencies {
    api(project(":core:model"))
    api(project(":core:protocol"))
    implementation(project(":core:data"))
    implementation(libs.nostr.sdk)
    // nostr-sdk declares JNA as an optional runtime dependency: add the Android AAR flavour explicitly
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // the sharing ledger (seen gift wraps) is a :core:data Room DAO
    implementation(libs.androidx.room.runtime)
    testImplementation(project(":core:testing"))
    testImplementation(testFixtures(project(":core:protocol")))
    // Host unit tests can't load the AAR's Android .so: the JVM build of the same bindings (same classes, desktop
    // natives in its resources) runs the adapter there. Test classpath only, never in an APK.
    testImplementation(libs.nostr.sdk.jvm)
    testRuntimeOnly(libs.jna)
    testImplementation(libs.org.json)
}
