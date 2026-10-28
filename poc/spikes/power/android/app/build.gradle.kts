plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.spike.powerlab"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.spike.powerlab"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = false }
}

kotlin {
    jvmToolchain(17)
}
