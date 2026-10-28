// Brand preview: every icon candidate as its own launcher entry, plus themed + status-bar renderings (investigation 09).
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.spike.brandpreview"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.spike.brandpreview"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = false
    }
}

kotlin {
    jvmToolchain(17)
}
