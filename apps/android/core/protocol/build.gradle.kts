// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

// Nostr protocol v2 wire formats, pure JVM, no nostr-sdk. Crypto is the NostrCrypto interface: `:share`
// implements it with the Android AAR; the tests here use the JVM build of the same library (test classpath only), and
// `testFixtures` ships NostrCryptoContractTest for every implementation.
plugins {
    id("sukrara.jvm.library")
    id("sukrara.contracts")
    id("org.jetbrains.kotlin.plugin.serialization")
    `java-test-fixtures`
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testFixturesApi(project(":core:testing"))
    testFixturesApi(libs.junit)
    testFixturesApi(libs.kotlinx.serialization.json)
    testImplementation(project(":core:testing"))
    testImplementation(libs.nostr.sdk.jvm)
    testRuntimeOnly(libs.jna)
}

// `./gradlew :core:protocol:test -Psukrara.regen=true` rewrites contracts/protocol/sync (expectations must not change).
tasks.withType<Test>().configureEach {
    systemProperty("sukrara.regen", providers.gradleProperty("sukrara.regen").getOrElse("false"))
}
