plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.bcprov)
    testImplementation(libs.junit)
}

// Decoder harness: ./gradlew :core:run --args="g7 core/src/main/resources/traces/g7-session.txt"
application { mainClass.set("dev.spike.sensors.harness.HarnessKt") }

// Nothing here reads a vendor checkout: every fixture is committed under core/src/main/resources/traces
// (docs/23 — the drivers borrow no vendor code or credentials).
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
