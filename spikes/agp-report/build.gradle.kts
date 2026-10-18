plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    // JsonElement API only (no compiler plugin): fixtures and the page's display list
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

// ./gradlew run --args="fixtures"          regenerate fixtures/*.json (synthetic data + the engine's expected numbers)
// ./gradlew run --args="render <out dir>"  page PNGs (Java2D) + display-list JSON for the Mac painter
application { mainClass.set("app.mauri.agp.MainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir; systemProperty("java.awt.headless", "true") }
tasks.test { workingDir = rootProject.projectDir; systemProperty("java.awt.headless", "true") }
