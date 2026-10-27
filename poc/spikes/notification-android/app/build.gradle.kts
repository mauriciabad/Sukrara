plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.spike.notiflab"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.spike.notiflab"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        // Same key as every spike (and the showcase app): ~/.android/debug.keystore.
        create("spike") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("spike") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}
