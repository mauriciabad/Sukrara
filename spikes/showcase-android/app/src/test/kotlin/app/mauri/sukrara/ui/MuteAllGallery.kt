// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.AlertEngine
import app.mauri.sukrara.alerts.AlertKit
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.GraphStyle
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.graph.StatusBadge
import app.mauri.sukrara.settings.SettingsSpec
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
 * Rounds f30 + f31 evidence and checks, rendered on the JVM from synthetic data: the home's Mute alerts control (off,
 * for a time, until you turn it off) driving the engine, the badge and back (Unmute, TalkBack actions), the state line
 * counting down, the muted pill on a widget, the alert notification's actions (normal, remind less, muted with its
 * countdown), and the duration texts of the settings (presets "1 h / 2 h"). Out: the `f30` folder under `gallery.out`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xhdpi")
class MuteAllGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = RuntimeEnvironment.getApplication()
    private val k = AlertKit(ctx)
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "f30").also { it.mkdirs() }

    @Before fun setup() {
        RoundedFont.init(ctx)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(ctx.resources) }
        GraphSettings.init(ctx)
        WearerSettings.init(ctx)
        k.start()
    }

    @After fun tearDown() = k.stop()

    private fun save(name: String, b: Bitmap) = File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun shot(name: String) { compose.waitForIdle(); save(name, compose.onRoot().captureToImage().asAndroidBitmap()) }

    private fun home(sgv: Int = 120): Reading {
        k.reading(sgv)
        val r = Reading(k.now, sgv, 0.0)
        compose.setContent { SettingsTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(16.dp)) {
            GlucosePanel(r.sgv, r.rate, r.t, onAdd = {}, muteAlerts = true) } } }
        return r
    }

    /** Home → Mute alerts → For 2 h → the engine, the badge, the state; Unmute → off again. */
    @Test fun homeMuteAlertsForATimeAndBackOn() {
        home()
        shot("android-home-mute-alerts-off")
        compose.onNode(hasText("Mute alerts")).performClick()
        compose.waitForIdle()
        save("android-home-mute-alerts-dialog", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        // the choices as AOSP Do Not Disturb words them (spikes/alerts/copy.json `choice`), the one without an end last
        for (c in listOf("For 30 min", "For 1 h", "For 2 h", "For 4 h", "For 8 h", "Until you turn it off", "Custom")) compose.onNode(hasText(c)).assertExists()
        compose.onNode(hasText("Never")).assertDoesNotExist()
        compose.onNode(hasText("For 2 h")).performClick()
        compose.waitForIdle()
        assertEquals(k.now + 7200, Alerts.allMuted.value)
        assertEquals(AlertStatus.Muted(k.now + 7200), k.status(120))
        assertEquals("Muted · 2h", StatusBadge.look(k.status(120), k.now)?.text)
        compose.onNode(hasText("Alerts muted · 2 h left")).assertExists()
        shot("android-home-muted-until")
        // TalkBack: the card says how to leave it
        val card = compose.onNode(SemanticsMatcher("has custom actions") { it.config.getOrNull(SemanticsActions.CustomActions)?.isNotEmpty() == true })
        assertEquals(listOf("Alerts: Unmute", "Alerts: Change time"), card.fetchSemanticsNode().config[SemanticsActions.CustomActions].map { it.label })
        compose.onNode(hasText("Change time")).assertExists()
        compose.onNode(hasText("Unmute")).performClick()
        compose.waitForIdle()
        assertEquals(0L, Alerts.allMuted.value)
        assertEquals(AlertStatus.None, k.status(120))
        compose.onNode(hasText("Mute alerts")).assertExists()
    }

    @Test fun homeMutedNeverAndTalkBackTurnsBackOn() {
        Alerts.muteAll(ctx, Alerts.NEVER_MIN)
        home()
        compose.onNode(hasText("Alerts muted until you turn it off")).assertExists()
        assertEquals("Muted", StatusBadge.look(k.status(120), k.now)?.text)
        shot("android-home-muted-never")
        val card = compose.onNode(SemanticsMatcher("has custom actions") { it.config.getOrNull(SemanticsActions.CustomActions)?.isNotEmpty() == true })
        val on = card.fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Alerts: Unmute" }
        compose.runOnUiThread { on.action() }
        assertEquals(0L, Alerts.allMuted.value)
    }

    @Test fun activeNowListsMuteAll() {
        k.reading(65)
        Alerts.muteAll(ctx, 60)
        compose.setContent { SettingsTheme { AlertsScreen(follower = false) {} } }
        compose.waitForIdle()
        compose.onNode(hasText("Active now")).assertExists()
        compose.onNode(hasText("Alerts muted · 1 h left")).assertExists()
        shot("android-alerts-active-now-mute-all")
    }

    @Test fun settingsDurationsAndRelaxPresets() {
        compose.setContent { SettingsTheme { AlertsScreen(follower = false) {} } }
        compose.waitForIdle()
        compose.onNode(hasText("… for at least")).performScrollTo()
        shot("android-alerts-settings-actions")
        compose.onNode(hasText("Mute: suggested time")).assertExists()
        compose.onNode(hasText("Mute and Remind less end back in range by")).assertExists()
        compose.onNode(hasText("Remind less: every")).performClick()
        compose.waitForIdle()
        save("android-relax-presets-after", compose.onNode(isDialog()).captureToImage().asAndroidBitmap())
        compose.onNode(hasText("Every 1 h 30 min")).assertExists()
        compose.onNode(hasText("Every 2 h")).assertExists()
    }

    @Test fun widgetMutedPill() {
        val st = CardStyle(graph = GraphStyle(), sensorTime = false)
        for ((name, until) in listOf("android-widget-muted-pill" to k.now + 4800, "android-widget-muted-never" to AlertEngine.NEVER)) {
            val rs = (0 until 36).map { Reading(k.now - (35 - it) * 300L, 110 + (it % 7) * 3, 0.1) }
            val d = 2.75f
            val b = Bitmap.createBitmap((320 * d).toInt(), (180 * d).toInt(), Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            cv.drawColor(0xFF1C1B1F.toInt())
            GlucoseCard.draw(cv, b.width.toFloat(), b.height.toFloat(), rs, emptyList(), k.now, st, GraphPalette.DARK, d,
                CardSurface.WIDGET_COMBINED, CardPart.ALL, alerts = AlertSettings(), sensor = null, paused = emptyMap(), allMuted = until)
            save(name, b)
        }
    }

    /** The posted notifications drawn from their own RemoteViews (what the shade inflates). */
    private fun drawNotification(name: String, n: Notification, showWhen: Boolean = false) {
        // the JVM's clock isn't the test's; the muted one keeps its countdown chronometer (it runs from `when`)
        val b = Notification.Builder.recoverBuilder(ctx, n).setShowWhen(showWhen)
        // the chronometer counts from the framework's (Robolectric's) clock: keep the time left, not the instant
        if (showWhen) b.setWhen(org.robolectric.shadows.ShadowSystem.currentTimeMillis() + (n.`when` - k.now * 1000))
        val views = b.createBigContentView() ?: b.createContentView()
        val parent = FrameLayout(ctx)
        val v = views.apply(ctx, parent)
        val w = (400 * ctx.resources.displayMetrics.density).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth.coerceAtLeast(1), v.measuredHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0xFFF3EDF7.toInt()); v.draw(this) }
        save(name, bmp)
    }

    @Test fun notificationActions() {
        k.reading(65)
        drawNotification("android-notification-normal", k.notification(Kind.LOW)!!)
        k.tap(Kind.LOW, "Remind less")
        drawNotification("android-notification-relaxed", k.notification(Kind.LOW)!!)
        k.tap(Kind.LOW, "Mute…", typed = "1 h")
        drawNotification("android-notification-muted", k.notification(Kind.LOW)!!, showWhen = true)
        assertEquals(listOf("Unmute", "Change time"), k.buttons(Kind.LOW))
        assertTrue(SettingsSpec.MUTE_ALL.presets.contains(0.0))
    }
}
