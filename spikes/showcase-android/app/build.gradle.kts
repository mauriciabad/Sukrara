import java.security.MessageDigest

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

/**
 * The fingerprint of the code System UI runs (features/SystemAccess, "does System UI need a restart?"): the LSPosed
 * hook (hook/), the card renderer it draws with (graph/) and every file a hook/ file names directly (the lock screen
 * widget, the status-bar state it reads…). The app records it per versionCode; when LSPosed says System UI runs an
 * older build, a restart is only asked for when that build's fingerprint differs.
 */
fun hookCode(): String {
    val root = file("src/main/kotlin/app/mauri/sukrara")
    val all = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    fun rel(f: File) = f.relativeTo(root).invariantSeparatorsPath
    val decl = HashMap<String, File>()
    val declRe = Regex("""^(?:[a-z]+ )*(?:class|object|interface)\s+([A-Z]\w*)""", RegexOption.MULTILINE)
    for (f in all) {
        val pkg = rel(f).substringBeforeLast('/', "").replace('/', '.')
        declRe.findAll(f.readText()).forEach { decl.putIfAbsent("$pkg.${it.groupValues[1]}", f) }
    }
    val refRe = Regex("""app\.mauri\.sukrara\.([a-z]+(?:\.[a-z]+)*\.[A-Z]\w*)""")
    val base = all.filter { rel(it).startsWith("hook/") || rel(it).startsWith("graph/") }
    val named = all.filter { rel(it).startsWith("hook/") }.flatMap { f -> refRe.findAll(f.readText()).mapNotNull { decl[it.groupValues[1]] }.toList() }
    val md = MessageDigest.getInstance("SHA-256")
    (base + named).distinct().sortedBy { rel(it) }.forEach { md.update((rel(it) + "\n").toByteArray()); md.update(it.readBytes()) }
    return md.digest().joinToString("") { "%02x".format(it) }.take(16)
}

android {
    namespace = "app.mauri.sukrara"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.mauri.sukrara.poc"
        // Credential Manager *providers* (Proton Pass, Bitwarden, KeePassDX) plug into the framework on Android 14+.
        minSdk = 36
        targetSdk = 37
        versionCode = 11037
        versionName = "0.11.37"
        buildConfigField("String", "HOOK_CODE", "\"${hookCode()}\"")
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
                // CardLayoutTest: rewrite spikes/graph-fixtures/layout/expect.json (the cross-platform answers)
                System.getProperty("layout.write")?.let { f -> it.systemProperty("layout.write", f) }
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
// The shared graph fixtures and the surface registry likewise (GraphFixtureTest, CardLayoutTest).
tasks.withType<Test>().configureEach { inputs.dir("../../graph-fixtures").withPropertyName("graphFixtures") }

// Robolectric's SDK 36 sandbox (the graph/report galleries, CardLayoutTest, ChartScrollPerfTest, FreshnessFixtureTest, AlertNotifierTest, AlertMuteTest, UserErrorsTest, ScreenHostTest, TidepoolScreenTest, WearerSettingsTest) needs Java 21+: run unit tests on
// GALLERY_JAVA_HOME when set, else leave those out so `testDebugUnitTest` passes on the build's JDK 17.
if (System.getenv("GALLERY_JAVA_HOME") == null) tasks.withType<Test>().configureEach { exclude("**/*Gallery*", "**/AlertNotifierTest*", "**/ScreenHostTest*", "**/CardLayoutTest*", "**/ChartScrollPerfTest*", "**/SensorEndNotifyTest*", "**/SettingsGroupTest*", "**/DebugFakesTest*", "**/TidepoolScreenTest*", "**/WearerSettingsTest*", "**/AppIconsTest*", "**/LibreViewUploaderTest*", "**/FreshnessFixtureTest*", "**/AlertMuteTest*", "**/UserErrorsTest*") }
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

  implementation(project(":settingslib-spa"))
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
