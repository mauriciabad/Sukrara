// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.settings.WearerSettings
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Round f28 evidence: the Graph and Alerts settings pages (wearer, follower in Wearer and Custom) and the one number
 * input, rendered on the JVM with synthetic state (no device). Out: `gallery.out`/settings/android-*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h3000dp-xhdpi")
class SettingsGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "settings").also { it.mkdirs() }

    @Before fun setup() {
        val a = RuntimeEnvironment.getApplication()
        RoundedFont.init(a)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(a.resources) }
        GraphSettings.init(a)
        Alerts.init(a)
        WearerSettings.init(a)
    }

    private fun save(name: String, b: Bitmap) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun page(name: String) { compose.waitForIdle(); save(name, compose.onRoot().captureToImage().asAndroidBitmap()) }
    private fun dialog(name: String) { compose.waitForIdle(); save(name, compose.onNode(isDialog()).captureToImage().asAndroidBitmap()) }

    @Test fun graphWearer() {
        compose.setContent { SettingsTheme { GraphScreen(follower = false) {} } }
        page("android-after-graph-wearer")
    }

    @Test fun graphFollowerWearer() {
        WearerSettings.setMode(WearerSettings.WEARER)
        compose.setContent { SettingsTheme { GraphScreen(follower = true) {} } }
        page("android-after-graph-follower-wearer")
    }

    @Test fun graphFollowerCustom() {
        WearerSettings.setMode(WearerSettings.CUSTOM)
        compose.setContent { SettingsTheme { GraphScreen(follower = true) {} } }
        page("android-after-graph-follower-custom")
        WearerSettings.setMode(WearerSettings.WEARER)
    }

    @Test fun alertsFollowerWearer() {
        WearerSettings.setMode(WearerSettings.WEARER)
        compose.setContent { SettingsTheme { AlertsScreen(follower = true) {} } }
        page("android-after-alerts-follower-wearer")
    }

    @Test fun numberDialogPresets() {
        compose.setContent { SettingsTheme { NumberDialog("Time shown", SettingsSpec.GRAPH_HOURS, 12.0, { "${it.toInt()} h" }, {}) {} } }
        dialog("android-after-number-presets")
    }
}

/** The number input's Custom states (a plain window: in the tall page window a text field never lets Compose idle). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class NumberDialogGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "settings").also { it.mkdirs() }
    private fun dialog(name: String) {
        compose.waitForIdle()
        File(out, "$name.png").outputStream().use { compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun numberDialogCustomError() {
        // a value outside the presets opens on Custom with its text; 60 h is out of range, so the range shows as the error
        compose.setContent { SettingsTheme { NumberDialog("Time shown", SettingsSpec.GRAPH_HOURS, 60.0, { "${it.toInt()} h" }, {}, focusField = false) {} } }
        dialog("android-after-number-custom-error")
    }

    @Test fun numberDialogRate() {
        compose.setContent { SettingsTheme { NumberDialog("Rate", SettingsSpec.THRESHOLD_RATE, 1.7, { "%.1f mg/dL/min".format(it) }, {}, focusField = false) {} } }
        dialog("android-after-number-custom-rate")
    }
}
