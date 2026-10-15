plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.spike.cgmnostr"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.spike.cgmnostr"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-poc3"
        // libnostr_sdk_ffi.so is ~7-9 MB per ABI; ship only the phone's ABI.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")   // spike: installable without a keystore
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
      buildConfig = false
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
}
