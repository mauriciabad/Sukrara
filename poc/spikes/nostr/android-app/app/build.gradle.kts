plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.spike.cgmshare"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.spike.cgmshare"
        // Credential Manager *providers* (Proton Pass, Bitwarden, KeePassDX) plug into the framework on Android 14+.
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-inv07"
        // libnostr_sdk_ffi.so is ~7-9 MB per ABI; ship only arm64 (Pixel + Apple-silicon emulators).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        // Debug AND release are signed with the same key (~/.android/debug.keystore): password managers such as
        // Proton Pass bind a saved password to the app's signing certificate, so a different key would make the
        // identity backup invisible to the other build.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true   // BuildConfig.DEBUG gates the synthetic glucose source
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.kotlinx.coroutines.android)

  implementation(libs.okhttp)
  implementation(libs.nostr.sdk)
  // nostr-sdk declares JNA as an *optional* runtime dep, so it must be added explicitly (the Android AAR flavor).
  implementation(libs.jna) { artifact { type = "aar" } }
  implementation(libs.zxing.core)
  implementation(libs.zxing.cpp)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  // Framework Credential Manager only (API 34+): no credentials-play-services-auth, so no Play Services dependency.
  implementation(libs.credentials)
}
