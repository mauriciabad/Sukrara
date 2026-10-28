// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.integrations.export.ExportActions
import app.mauri.sukrara.integrations.export.ExportContent
import app.mauri.sukrara.integrations.export.ExportData
import app.mauri.sukrara.integrations.export.ExportPeriod
import app.mauri.sukrara.integrations.export.ExportUi
import app.mauri.sukrara.integrations.libreview.LibreViewActions
import app.mauri.sukrara.integrations.libreview.LibreViewContent
import app.mauri.sukrara.integrations.libreview.LvPageState
import app.mauri.sukrara.integrations.libreview.LvSettings
import app.mauri.sukrara.integrations.libreview.LvSignInUi
import app.mauri.sukrara.integrations.localserver.XdripState
import app.mauri.sukrara.integrations.nightscout.NightscoutActions
import app.mauri.sukrara.integrations.nightscout.NightscoutContent
import app.mauri.sukrara.integrations.nightscout.NsCredentialKind
import app.mauri.sukrara.integrations.nightscout.NsPageState
import app.mauri.sukrara.integrations.nightscout.NsSettings
import app.mauri.sukrara.integrations.nightscout.NsTestUi
import app.mauri.sukrara.integrations.upload.UploadStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.Instant

/**
 * The integration pages for the UX review (synthetic state only): light + dark × default + largest font, plus Spanish.
 * Images go to build/outputs/roborazzi (copied to the review folder; no goldens are committed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class IntegrationsGallery {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-27T14:00:00Z")
    private val site = NsSettings(on = true, url = "https://my-ns.example.org")
    private val configured = NsPageState(site, NsCredentialKind.Token, UploadStatus.Ok(now - Duration.ofMinutes(3), 12))
    private val readings = (0L until 300L).map { Reading(now - Duration.ofMinutes(it * 5), 120, null, SourceId.Libre3) }
    private val export = ExportData(now - Duration.ofDays(14), now, readings, emptyList(), null)

    private val lvFrom = now - Duration.ofDays(2)

    private val pages: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "apps-off" to { AppsContent(AppsUi(true, false, XdripState.Off, emptyList(), UploadStatus.Off), now, AppsActions()) },
            "apps-on" to {
                AppsContent(
                    AppsUi(
                        true,
                        true,
                        XdripState.Listening(now - Duration.ofMinutes(2)),
                        listOf("Garmin Connect"),
                        configured.status,
                        UploadStatus.Ok(now - Duration.ofMinutes(1), 1),
                    ),
                    now,
                    AppsActions(),
                )
            },
            "apps-busy-follower" to
                { AppsContent(AppsUi(false, true, XdripState.PortBusy, emptyList(), UploadStatus.Off), now, AppsActions()) },
            "nightscout-off" to {
                NightscoutContent(NsPageState(NsSettings(), null, UploadStatus.Off), true, NsTestUi.Idle, now, NightscoutActions())
            },
            "nightscout-ok" to { NightscoutContent(configured, true, NsTestUi.Idle, now, NightscoutActions()) },
            "nightscout-auth-failed" to {
                NightscoutContent(configured.copy(status = UploadStatus.AuthFailed), true, NsTestUi.Idle, now, NightscoutActions())
            },
            "nightscout-paused" to {
                NightscoutContent(
                    configured.copy(status = UploadStatus.Paused("xDrip-LibreReceiver")),
                    true,
                    NsTestUi.Idle,
                    now,
                    NightscoutActions(),
                )
            },
            "libreview-signed-out" to {
                LibreViewContent(
                    LvPageState(LvSettings(), false, "", UploadStatus.Off, null),
                    true,
                    LvSignInUi.Idle,
                    "",
                    now,
                    LibreViewActions(),
                    false,
                    {},
                )
            },
            "libreview-ok" to {
                LibreViewContent(
                    LvPageState(LvSettings(on = true), true, "person@example.com", UploadStatus.Ok(now - Duration.ofMinutes(4), 3), lvFrom),
                    true,
                    LvSignInUi.Idle,
                    "",
                    now,
                    LibreViewActions(),
                    false,
                    {},
                )
            },
            "libreview-auth-failed" to {
                LibreViewContent(
                    LvPageState(LvSettings(on = true), true, "person@example.com", UploadStatus.AuthFailed, lvFrom),
                    true,
                    LvSignInUi.Idle,
                    "",
                    now,
                    LibreViewActions(),
                    false,
                    {},
                )
            },
            "export" to { ExportContent(ExportUi(ExportPeriod.Days(14), true, export), ExportActions()) },
            "export-follower-counting" to { ExportContent(ExportUi(ExportPeriod.All, false, null, follower = true), ExportActions()) },
        )

    private fun gallery(
        dark: Boolean,
        fontScale: Float,
        prefix: String = "",
    ) {
        var current by mutableStateOf(pages.first().second)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SukraraTheme(dark = dark, dynamic = false) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState()),
                    ) {
                        current()
                    }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        val font = if (fontScale > 1f) "font2x" else "font1x"
        pages.forEach { (name, page) ->
            current = page
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/integrations-$prefix$name-$theme-$font.png")
        }
    }

    @Test fun lightDefault() = gallery(dark = false, fontScale = 1f)

    @Test fun darkDefault() = gallery(dark = true, fontScale = 1f)

    @Test fun lightLargest() = gallery(dark = false, fontScale = 2f)

    @Test fun darkLargest() = gallery(dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "es")
    fun spanish() = gallery(dark = false, fontScale = 1f, prefix = "es-")
}
