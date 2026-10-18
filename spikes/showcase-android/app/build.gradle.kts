plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.mauri.sukrara"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.mauri.sukrara.poc"
        // Credential Manager *providers* (Proton Pass, Bitwarden, KeePassDX) plug into the framework on Android 14+.
        minSdk = 36
        targetSdk = 37
        versionCode = 11014
        versionName = "0.11.14"
        // libnostr_sdk_ffi.so is ~7-9 MB per ABI; ship only arm64 (Pixel + Apple-silicon emulators).
        ndk { abiFilters += "arm64-v8a" }
    }

    // Direct-sensor source (investigation 02): the Libre 3 security core is compiled from a local Juggluco checkout
    // (GPLv3, not vendored). Looked up in this checkout's third_party/, then in the main checkout's (worktrees).
    // Without it the app still builds; the direct source then reports "native core missing".
    val juggluco = listOf("../../../third_party/Juggluco", "../../../../../../third_party/Juggluco")
        .map { file(it) }.firstOrNull { File(it, "Common/src/main/cpp/libre3/process").isDirectory }
    if (juggluco != null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
        defaultConfig.externalNativeBuild.cmake.arguments += "-DJUGGLUCO=${juggluco.absolutePath}/Common/src/main/cpp"
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
    // Per-app language (developer.android.com/guide/topics/resources/app-languages): AGP generates the LocaleConfig
    // and the manifest's android:localeConfig from the values-* folders (default locale: res/resources.properties),
    // so Settings → Apps → Sukrara → Language lists English and Spanish. localeFilters keeps libraries' other
    // translations out of the APK.
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "es")
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true   // BuildConfig.DEBUG gates the synthetic glucose source
      shaders = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("gallery.out", System.getProperty("gallery.out") ?: layout.buildDirectory.dir("gallery").get().asFile.path)
                System.getProperty("gallery.real")?.let { f -> it.systemProperty("gallery.real", f) }
            }
        }
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

// The shared alert timelines (spikes/alerts/scenarios) are inputs of AlertScenarioTest: editing one re-runs the tests.
tasks.withType<Test>().configureEach { inputs.dir("../../alerts/scenarios").withPropertyName("alertScenarios") }

// Robolectric's SDK 36 sandbox (the graph/report galleries, AlertNotifierTest) needs Java 21+: run unit tests on
// GALLERY_JAVA_HOME when set, else leave those out so `testDebugUnitTest` passes on the build's JDK 17.
if (System.getenv("GALLERY_JAVA_HOME") == null) tasks.withType<Test>().configureEach { exclude("**/*Gallery*", "**/AlertNotifierTest*") }
System.getenv("GALLERY_JAVA_HOME")?.let { home ->
    tasks.withType<Test>().configureEach { javaLauncher.set(null as JavaLauncher?); executable = "$home/bin/java"
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  // investigation 16: the graph gallery renders the real Canvas code on the JVM (native graphics)
  testImplementation("org.robolectric:robolectric:4.17")
  testImplementation("org.ow2.asm:asm-commons:9.11")
  testImplementation("org.ow2.asm:asm-tree:9.11")
  // investigation 18: the report card (Compose) rendered on the JVM for the report pictures
  testImplementation(platform(libs.androidx.compose.bom))
  testImplementation("androidx.compose.ui:ui-test-junit4")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation("org.json:json:20250517")   // real org.json for JVM tests (Android's is a stub there)
  // SystemUI module (investigation 05): provided by LSPosed/Vector at runtime
  compileOnly("io.github.libxposed:api:102.0.0")
  // the app's side: LSPosed hands an active module its XposedService (scope, running targets) → features/SystemAccess
  implementation("io.github.libxposed:service:102.0.0")

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.navigationevent.compose)
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
  // treatments + LibreView payloads (investigation 04)
  implementation(libs.kotlinx.serialization.json)
  // home-screen widgets (investigation 12)
  implementation(libs.androidx.glance.appwidget)
  implementation(libs.androidx.glance.material3)
  // Glance runs updates through WorkManager; pin the version the widgets spike shipped with (its R8 rules work)
  implementation(libs.androidx.work.runtime)
}
