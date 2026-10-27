// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.App
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.features.Tips
import app.mauri.sukrara.follower.FollowerUi
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.settings.WearerSettings
import app.mauri.sukrara.widgets.WidgetPresence
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Round f33 evidence + checks: a closed tip card offers Undo (snackbar), and Settings → Show closed tips brings closed
 * ones back, unavailable while none is closed. Synthetic state on the JVM. Out: `gallery.out`/tips/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h900dp-xhdpi")
class TipsGallery {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File(System.getProperty("gallery.out") ?: "build/gallery", "tips").also { it.mkdirs() }

    @Before fun setup() {
        RoundedFont.init(ctx)
        app.mauri.sukrara.core.Words.loader = { app.mauri.sukrara.core.Words.of(ctx.resources) }
        Features.init(ctx)
        GraphSettings.init(ctx)
        WearerSettings.init(ctx)
        app.mauri.sukrara.alerts.Alerts.init(ctx)
        App.setRole(ctx, "follower")
        Tips.restoreAll(ctx)
    }

    @After fun tearDown() { Tips.restoreAll(ctx) }

    private fun save(name: String) {
        compose.waitForIdle()
        File(out, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun closeThenUndoOnHome() {
        compose.setContent {
            SettingsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(16.dp)) { HomeWidgetBanner() }
                TipUndoSnackbar()
            } }
        }
        compose.waitForIdle()
        WidgetPresence.refresh(ctx)
        compose.onNodeWithText(ctx.getString(app.mauri.sukrara.R.string.home_widget_banner_title)).assertExists()
        save("android-tip-shown")
        // the close button (TalkBack: "Dismiss")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
            listOf(ctx.getString(app.mauri.sukrara.R.string.home_widget_banner_dismiss))) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertTrue(WidgetPresence.dismissed(ctx))
        compose.onNode(hasText(ctx.getString(app.mauri.sukrara.R.string.tips_closed))).assertExists()
        save("android-tip-closed-undo")
        compose.onNode(hasText(ctx.getString(app.mauri.sukrara.R.string.tips_undo)) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertFalse(WidgetPresence.dismissed(ctx))
        compose.onNodeWithText(ctx.getString(app.mauri.sukrara.R.string.home_widget_banner_title)).assertExists()
        save("android-tip-undone")
    }

    /**
     * Evidence: home as the user sees it right after closing a tip: the glucose card, the tip still open above
     * (Share your data with your clinic), and the snackbar "Tip closed · Undo" for the one just closed (Add widget).
     */
    @Test fun homeWithATipAndTheUndoSnackbar() {
        App.setRole(ctx, "owner")
        val k = app.mauri.sukrara.alerts.AlertKit(ctx)
        k.start()
        app.mauri.sukrara.treatments.Treat.init(ctx)
        try {
            k.reading(132)
            val r = app.mauri.sukrara.core.Reading(k.now, 132, 0.3)
            compose.setContent {
                SettingsTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                        ClinicUploadBanner {}
                        GlucosePanel(r.sgv, r.rate, r.t, onAdd = {}, muteAlerts = true)
                        HomeWidgetBanner()
                    }
                    TipUndoSnackbar()
                } }
            }
            compose.waitForIdle()
            WidgetPresence.refresh(ctx)
            // both cards' close buttons read "Dismiss": the Add widget card is the last one
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
                listOf(ctx.getString(app.mauri.sukrara.R.string.home_widget_banner_dismiss))) and hasClickAction()).onLast().performClick()
            compose.waitForIdle()
            assertTrue(WidgetPresence.dismissed(ctx))
            compose.onNode(hasText(ctx.getString(app.mauri.sukrara.R.string.upload_banner_title))).assertExists()
            compose.onNode(hasText(ctx.getString(app.mauri.sukrara.R.string.tips_closed))).assertExists()
            save("android-home-tip-closed-undo")
        } finally { k.stop() }
    }

    @Config(qualifiers = "w411dp-h1600dp-xhdpi")
    @Test fun showClosedTipsInSettings() {
        compose.setContent { SettingsTheme { FollowerSettings(FollowerUi()) {} } }
        val row = hasText(ctx.getString(app.mauri.sukrara.R.string.tips_show_again)) and hasClickAction()
        compose.onNode(hasText(ctx.getString(app.mauri.sukrara.R.string.tips_none_closed))).assertExists()
        compose.onNode(row).assertIsNotEnabled()
        save("android-settings-no-closed-tips")
        compose.runOnUiThread {
            Tips.close(ctx, Tips.Tip.ADD_WIDGET)
            Tips.close(ctx, Tips.Tip.CLINIC_UPLOAD)
        }
        compose.waitForIdle()
        compose.onNode(hasText("2 tips closed")).assertExists()
        compose.onNode(row).assertIsEnabled()
        save("android-settings-closed-tips")
        compose.onNode(row).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Tips.Tip>(), Tips.closed(ctx))
        compose.onNode(row).assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithText(t: String) = onNode(hasText(t))
}
