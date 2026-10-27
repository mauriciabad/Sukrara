// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import app.mauri.sukrara.alerts.AlertEngine
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.settings.WearerSettings
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * Round f29 evidence + checks: the ways out of a quiet alert in the app, without the notification (user, 2026-10-21:
 * "There's no way to unmute!"). Rendered on the JVM from synthetic state: the home card with the muted pill and the
 * TalkBack actions on it, the sheet the pill opens, the Alerts page's Active now. Out: the `quiet` folder under `gallery.out`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xhdpi")
class QuietGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "quiet").also { it.mkdirs() }
    private var t = Proto.nowS() - 90

    @Before fun setup() {
        RoundedFont.init(ctx)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(ctx.resources) }
        GraphSettings.init(ctx)
        WearerSettings.init(ctx)
        Alerts.resetForTests()
        Alerts.init(ctx)
        Alerts.update(ctx) { AlertSettings() }
        Alerts.attach(ctx, "wearer", doses = { AlertEngine.Doses() }, publish = {})
        Alerts.history.value = emptyList()
    }

    @After fun tearDown() { Alerts.detach(); Alerts.resetForTests() }

    /** Carbs logged [agoMin] minutes ago: the after-carbs rule holds lows back (AlertSettings' default 15 min). */
    private fun carbs(agoMin: Int) {
        val carbsAt = Proto.nowS() - agoMin * 60L
        Alerts.detach()
        Alerts.attach(ctx, "wearer", doses = { AlertEngine.Doses(carbs = listOf(carbsAt)) }, publish = {})
    }

    /** Round f33: quiet after carbs, its line worded like the mutes and counting down (the sheet). */
    @Test fun sheetQuietAfterCarbs() {
        carbs(3)
        low()
        compose.setContent { SettingsTheme { AlertQuietSheet(Kind.LOW, onDismiss = {}, onSettings = {}) } }
        compose.waitForIdle()
        save("android-sheet-quiet-carbs", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        // 15 min after the carbs, 3 min ago: 12 min left (the old "Low quiet after carbs until 10:42" is gone)
        compose.onNode(androidx.compose.ui.test.hasText("Low alerts muted after carbs · 12 min left")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("quiet after carbs", substring = true)).assertDoesNotExist()
        compose.onNode(androidx.compose.ui.test.hasText("Alert me anyway")).assertExists()
    }

    /** Round f33, in Spanish: "queda 1 h" (one unit), "quedan 12 min" after carbs, on the sheet and in Active now. */
    @Test @Config(qualifiers = "es-w411dp-h900dp-xhdpi") fun spanishTimeLeftAgrees() {
        carbs(3)
        low()
        Alerts.mute(ctx, Kind.FALLING_FAST, 60)
        compose.setContent { SettingsTheme { AlertsScreen(follower = false) {} } }
        compose.waitForIdle()
        save("android-alerts-active-now-es", compose.onRoot().captureToImage().asAndroidBitmap())
        compose.onNode(androidx.compose.ui.test.hasText("Alertas de glucosa baja silenciadas tras los hidratos · quedan 12 min")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("Alertas de bajada rápida silenciadas · queda 1 h")).assertExists()
    }

    /** Round f33: the same line in Active now, and on the home card as a chip (the pill shows the low's own mute). */
    @Test fun activeNowQuietAfterCarbs() {
        carbs(3)
        low()
        compose.setContent { SettingsTheme { AlertsScreen(follower = false) {} } }
        compose.waitForIdle()
        save("android-alerts-active-now-carbs", compose.onRoot().captureToImage().asAndroidBitmap())
        compose.onNode(androidx.compose.ui.test.hasText("Low alerts muted after carbs · 12 min left")).assertExists()
    }

    private fun low(sgv: Int = 62) = Reading(t++, sgv, -0.4, "test").also { Alerts.onReading(ctx, it) }
    private fun save(name: String, b: Bitmap) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun homeCardMutedWithTalkBackActions() {
        val r = low()
        Alerts.mute(ctx, Kind.LOW, 30)
        compose.setContent { SettingsTheme { GlucosePanel(r.sgv, r.rate, r.t) } }
        compose.waitForIdle()
        save("android-home-muted", compose.onRoot().captureToImage().asAndroidBitmap())
        // every way out is a TalkBack action on the card: no notification needed
        val card = compose.onNode(SemanticsMatcher("has custom actions") { it.config.getOrNull(SemanticsActions.CustomActions)?.isNotEmpty() == true })
        val actions = card.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Low: Unmute"), actions.map { it.label })
        compose.runOnUiThread { actions.single().action() }
        assertTrue(Alerts.active.value.isEmpty())
    }

    @Test fun homeCardRelaxedChip() {
        val r = low()
        Alerts.relax(ctx, Kind.LOW)
        compose.setContent { SettingsTheme { GlucosePanel(r.sgv, r.rate, r.t) } }
        compose.waitForIdle()
        save("android-home-relaxed", compose.onRoot().captureToImage().asAndroidBitmap())
        compose.onNode(androidx.compose.ui.test.hasText("Low reminders every 30 min")).assertExists()
    }

    @Test fun sheetFromThePill() {
        low()
        Alerts.relax(ctx, Kind.LOW)
        Alerts.mute(ctx, Kind.LOW, 30)
        compose.setContent { SettingsTheme { AlertQuietSheet(Kind.LOW, onDismiss = {}, onSettings = {}) } }
        compose.waitForIdle()
        save("android-sheet-muted-relaxed", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        compose.onNode(androidx.compose.ui.test.hasText("Low alerts muted · 30 min left")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("Low reminders every 30 min")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("Remind as usual")).assertExists()
    }

    /** Round f31: a side's mute alone, its line counting down in the sheet. */
    @Test fun sheetSideMuted() {
        low()
        Alerts.mute(ctx, Kind.LOW, 90)
        compose.setContent { SettingsTheme { AlertQuietSheet(Kind.LOW, onDismiss = {}, onSettings = {}) } }
        compose.waitForIdle()
        save("android-sheet-muted", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        compose.onNode(androidx.compose.ui.test.hasText("Low alerts muted · 1 h 30 min left")).assertExists()
        compose.onNode(androidx.compose.ui.test.hasText("Remind less")).assertExists()
    }

    @Test fun alertsPageActiveNow() {
        low()
        Alerts.mute(ctx, Kind.LOW, 30)
        compose.setContent { SettingsTheme { AlertsScreen(follower = false) {} } }
        compose.waitForIdle()
        save("android-alerts-active-now", compose.onRoot().captureToImage().asAndroidBitmap())
        compose.onNode(androidx.compose.ui.test.hasText("Active now")).assertExists()
    }
}
