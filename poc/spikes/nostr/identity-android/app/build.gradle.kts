plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.spike.nostrid"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.spike.nostrid"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-inv14"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
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
      buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    // identity/vectors.json from the Python reference is the shared test fixture
    sourceSets["test"].resources.srcDir("../../identity")
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.material3)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.okhttp)
  implementation(libs.nostr.sdk)
  implementation(libs.jna) { artifact { type = "aar" } }
  implementation(libs.zxing.core)
  implementation("androidx.exifinterface:exifinterface:1.4.1")
  testImplementation(libs.junit)
  testImplementation(libs.json)
}
