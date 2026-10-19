// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Guard for the user's report "app icons have inconsistent sizes" (icons round-cropped at 24 dp, legacy icons cut):
 * every app or service icon in settings goes through [AppIcons] (AOSP Settings' IconDrawableFactory path) and is
 * drawn at SPA's app-list size, and every bundled service icon is a real app icon (adaptive, or a full-size legacy
 * bitmap), so no row can drift back to a hand-sized picture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class AppIconsTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ui = File("src/main/kotlin/app/mauri/sukrara/ui")

    @Test fun bundledIconsAreAppIcons() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (s in Service.entries) {
            val d = ctx.getDrawable(s.bundled)
            val ok = d is AdaptiveIconDrawable || (d is BitmapDrawable && d.intrinsicWidth == d.intrinsicHeight &&
                d.intrinsicWidth * 160 / ctx.resources.displayMetrics.densityDpi >= 48)
            assertTrue("$s: bundled icon must be an <adaptive-icon> or a 48 dp legacy launcher bitmap, got $d", ok)
        }
    }

    @Test fun serviceIconsAreDrawnAtTheAppListSize() {
        compose.setContent { SettingsTheme { Box(Modifier.testTag("icon")) { ServiceIcon(Service.NIGHTSCOUT) } } }
        compose.onNodeWithTag("icon").onChild().assertWidthIsEqualTo(SettingsDimension.appIconItemSize)
            .assertHeightIsEqualTo(SettingsDimension.appIconItemSize)
        assertEquals(32.dp, SettingsDimension.appIconItemSize)
    }

    /** Package icons only through the shared loader; no hand-made round crops of brand pictures. */
    @Test fun noOtherIconLoaders() {
        val banned = Regex("""getApplicationIcon\(|\.loadIcon\(|getDrawable\(R\.drawable\.ic_app_|painterResource\(R\.drawable\.ic_app_|ic_brand_(libreview|xdrip|juggluco)|ic_nightscout\b""")
        val hits = ui.walkTopDown().filter { it.extension == "kt" && it.name != "AppIcons.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (banned.containsMatchIn(l) && !l.contains("ctx.packageName")) "${f.name}:${i + 1}: ${l.trim()}" else null }
        }.toList()
        assertTrue("load app/service icons with ServiceIcon / PackageIcon (ui/AppIcons.kt):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    /** Every row that stands for an outside service or app shows its icon. */
    @Test fun serviceRowsHaveTheirIcon() {
        val src = File(ui, "SettingsScreens.kt").readLines() + File(ui, "SourceScreen.kt").readLines()
        val rows = mapOf(
            "radio(stringResource(R.string.source_juggluco)" to "ServiceIcon(Service.JUGGLUCO)",
            "\"LibreView\", when" to "ServiceIcon(Service.LIBREVIEW)",
            "R.string.apps_xdrip)" to "ServiceIcon(Service.XDRIP)",
            "R.string.apps_nightscout)" to "ServiceIcon(Service.NIGHTSCOUT)",
            "R.string.ns_upload_title), nightscoutUploadSummary()" to "ServiceIcon(Service.NIGHTSCOUT)",
            "R.string.apps_health_connect)" to "ServiceIcon(Service.HEALTH_CONNECT)",
            "switch(r.label" to "PackageIcon(r.pkg)",
            "switch(a.label" to "PackageIcon(a.pkg)",
            "link(\"Tidepool\"" to "ServiceIcon(Service.TIDEPOOL)",
        )
        for ((row, icon) in rows) {
            val at = src.indexOfFirst { row in it }
            assertTrue("row $row not found", at >= 0)
            assertTrue("row $row must use $icon", src.subList(at, minOf(at + 6, src.size)).any { icon in it })
        }
    }
}
