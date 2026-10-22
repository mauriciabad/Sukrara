// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.AlertEngine
import app.mauri.sukrara.alerts.AlertKit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.graph.StatusBadge
import app.mauri.sukrara.settings.WearerSettings
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
 * Round f33 (the user: "tapping Eat carbs does nothing"): the status pill is a button. *Eat carbs* opens Add treatment
 * with Carbs focused, *Take insulin* with Rapid focused; Cancel leaves home as it was; a mute's pill opens its ways out.
 * Guard: every pill the card can show has a click action (with the Button role and an action label for TalkBack)
 * wherever the device may add entries. Out: `gallery.out`/pill/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xhdpi")
class PillGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = RuntimeEnvironment.getApplication()
    private val k = AlertKit(ctx)
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "pill").also { it.mkdirs() }

    @Before fun setup() {
        RoundedFont.init(ctx)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(ctx.resources) }
        GraphSettings.init(ctx)
        WearerSettings.init(ctx)
        k.start()
    }

    @After fun tearDown() = k.stop()

    private fun shot(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val n = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        File(out, "$name.png").outputStream().use { n.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Home's panel with a working Add treatment: the dialog the pill opens, with the field it asked for. */
    private var focus: String? = "unset"
    private var adding = false
    private fun home(sgv: Int, canAdd: Boolean = true) {
        k.reading(sgv)
        val r = Reading(k.now, sgv, 0.0)
        compose.setContent { SettingsTheme { Column(Modifier.padding(16.dp)) {
            var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            GlucosePanel(r.sgv, r.rate, r.t, onAdd = if (canAdd) ({ f -> focus = f; adding = true; open = true }) else null, muteAlerts = true)
            if (open) TreatmentDialog(onDismiss = { open = false; adding = false }, onSave = {}, focus = focus)
        } } }
        drawn()
    }

    /** The pill's button follows where the renderer drew it: draw once (Robolectric draws only on capture). */
    private fun drawn() {
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        compose.waitForIdle()
    }

    private val pill = hasTestTag("status_pill")

    private fun assertPillIsAButton(description: String, label: String) {
        val node = compose.onNode(pill).fetchSemanticsNode()
        assertEquals(Role.Button, node.config[SemanticsProperties.Role])
        assertEquals(listOf(description), node.config[SemanticsProperties.ContentDescription])
        assertEquals(label, node.config[SemanticsActions.OnClick].label)
    }

    @Test fun eatCarbsOpensAddTreatmentWithCarbsFocused() {
        home(62)
        shot("android-home-eat-carbs")
        assertPillIsAButton("Eat carbs", "Add treatment")
        compose.onNode(pill).performClick()
        compose.waitForIdle()
        assertEquals("carbs", focus)
        compose.onNode(hasTestTag("treatment_carbs")).assertExists()
        shot("android-eat-carbs-dialog", dialog = true)
        // Cancel: back to home as it was, nothing saved
        compose.onNode(hasText("Cancel")).performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNode(pill).assertExists()
    }

    @Test fun takeInsulinOpensAddTreatmentWithRapidFocused() {
        home(280)
        assertPillIsAButton("Take insulin", "Add treatment")
        compose.onNode(pill).performClick()
        compose.waitForIdle()
        assertEquals("rapid", focus)
        shot("android-take-insulin-dialog", dialog = true)
    }

    /**
     * The field the pill asks for takes the focus (and with it the keyboard), even where the low rule would pick
     * another: Take insulin while low-ish readings are stored still focuses Rapid. Drawn in the window (as the quick
     * dialog does) so the JVM's window has the focus.
     */
    @Test fun theAskedFieldTakesTheFocus() {
        k.reading(62)
        var f by androidx.compose.runtime.mutableStateOf("carbs")
        compose.setContent { SettingsTheme { androidx.compose.runtime.key(f) { TreatmentDialog(onDismiss = {}, onSave = {}, inWindow = true, focus = f) } } }
        compose.waitForIdle()
        compose.onNode(hasTestTag("treatment_carbs")).assertIsFocused()
        // the dialog as the Eat carbs pill opens it (Carbs has the focus; the JVM draws no focus outline)
        File(out, "android-eat-carbs-dialog-focused.png").outputStream().use {
            compose.onNode(hasTestTag("treatment_card")).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        f = "rapid"
        compose.waitForIdle()
        compose.onNode(hasTestTag("treatment_rapid")).assertIsFocused()
    }

    @Test fun aMutedPillOpensItsWaysOut() {
        home(62)
        Alerts.mute(ctx, Kind.LOW, 30)
        drawn()
        assertPillIsAButton("Low alerts muted · 30 min left", "Show alert options")
        compose.onNode(pill).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("Unmute")).assertExists()
        assertEquals(false, adding)
    }

    @Test fun aFollowerWhoCantAddEntriesHasNoTreatmentAction() {
        home(62, canAdd = false)
        compose.onNode(pill).assertDoesNotExist()
        assertNull(focus.takeIf { it != "unset" })
    }

    /** The guard: every status that shows a pill has an action wherever entries can be added. */
    @Test fun everyPillHasAnAction() {
        val statuses = listOf(AlertStatus.Needed(Kind.LOW), AlertStatus.Needed(Kind.VERY_LOW), AlertStatus.Needed(Kind.HIGH),
            AlertStatus.Needed(Kind.VERY_HIGH), AlertStatus.Paused(Kind.LOW, 100), AlertStatus.Paused(Kind.HIGH, AlertEngine.NEVER),
            AlertStatus.Muted(100), AlertStatus.Muted(AlertEngine.NEVER))
        for (s in statuses) {
            assertNotEquals("$s", null, StatusBadge.look(s, 0))
            assertNotEquals("$s", PillAction.None, PillAction.of(s, canAdd = true))
        }
        assertEquals(PillAction.Treat("carbs"), PillAction.of(AlertStatus.Needed(Kind.VERY_LOW), true))
        assertEquals(PillAction.Treat("rapid"), PillAction.of(AlertStatus.Needed(Kind.HIGH), true))
        assertEquals(PillAction.None, PillAction.of(AlertStatus.None, true))
    }
}
