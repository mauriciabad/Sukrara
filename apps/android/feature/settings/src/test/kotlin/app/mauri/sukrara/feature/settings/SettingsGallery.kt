// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.data.settings.HookPosition
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.ModuleState
import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorReminder
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SystemAccessState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.feature.settings.graph.GraphActions
import app.mauri.sukrara.feature.settings.graph.GraphContent
import app.mauri.sukrara.feature.settings.graph.GraphMarksContent
import app.mauri.sukrara.feature.settings.graph.Reorder
import app.mauri.sukrara.feature.settings.lock.LockScreenContent
import app.mauri.sukrara.feature.settings.sensor.LibreViewSummary
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsActions
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsContent
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsState
import app.mauri.sukrara.feature.settings.sensor.SensorSummary
import app.mauri.sukrara.feature.settings.sensor.SourceChoice
import app.mauri.sukrara.feature.settings.sensor.SourceLink
import app.mauri.sukrara.feature.settings.sensorend.SensorEndActions
import app.mauri.sukrara.feature.settings.sensorend.SensorEndContent
import app.mauri.sukrara.feature.settings.sensorend.SensorEndPrefs
import app.mauri.sukrara.feature.settings.statusbar.RootActions
import app.mauri.sukrara.feature.settings.statusbar.RootSettings
import app.mauri.sukrara.feature.settings.statusbar.RootStatusBarSection
import app.mauri.sukrara.feature.settings.statusbar.StatusBarContent
import app.mauri.sukrara.feature.settings.statusbar.StatusBarPrefs
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.RingSpec
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.ZoneOffset

/**
 * The settings pages for the UX review (synthetic state only): light + dark × default + largest font, one phone
 * size, plus Spanish. Images go to build/outputs/roborazzi (copied to the review folder; no goldens are committed).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsGallery {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.parse("2026-10-27T14:00:00Z")
    private val zone = ZoneOffset.UTC
    private val clock = FakeClock(now, zone)
    private val muted = AlertUiState(mutedUntil = now.plusSeconds(90 * 60))
    private val lowMuted =
        AlertUiState(
            quiet =
                listOf(
                    AlertQuietSide(
                        "low",
                        matching = true,
                        reason = AlertQuietReason.Muted,
                        until = now.plusSeconds(1800),
                        relaxMin = 0,
                        canRemindLess = false,
                    ),
                ),
        )
    private val release =
        Release(
            "v0.12.1",
            "https://example.invalid/r",
            prerelease = true,
            draft = false,
            assets =
                listOf(
                    ReleaseAsset("Sukrara-0.12.1.dmg", "https://example.invalid/d"),
                    ReleaseAsset("Sukrara-0.12.1.apk", "https://example.invalid/a"),
                ),
        )

    private val sensorWorking =
        SensorSettingsState(
            SourceChoice.Libre3Direct,
            SensorState.Active(now.plusSeconds(9 * 86_400 + 4 * 3600)),
            SensorSummary("3MH00SYNTH1", now.minusSeconds(5 * 86_400 + 20 * 3600), now.plusSeconds(9 * 86_400 + 4 * 3600)),
            LibreViewSummary("wearer@example.invalid", 2_864_434_397),
        )

    private val pages: List<Pair<String, @Composable () -> Unit>> =
        listOf(
            "overview" to
                {
                    SettingsOverviewContent(
                        true,
                        AlertUiState(),
                        AlertReadiness(setOf(AlertRequirement.ExactAlarm)),
                        clock,
                        OverviewActions(),
                    ) {}
                },
            "overview-clinic" to {
                SettingsOverviewContent(
                    true,
                    AlertUiState(),
                    AlertReadiness.Ready,
                    clock,
                    OverviewActions(),
                    clinicBanner = true,
                    closedTips = 2,
                ) {}
            },
            "overview-muted" to { SettingsOverviewContent(true, muted, AlertReadiness.Ready, clock, OverviewActions()) {} },
            "alerts" to { AlertsSettingsContent(AlertsUi(AlertSettings(), AlertUiState()), null, clock, AlertsActions()) {} },
            "alerts-muted-forever" to {
                AlertsSettingsContent(
                    AlertsUi(AlertSettings(), AlertUiState(mutedUntil = AlertUiState.UNTIL_TURNED_OFF)),
                    now,
                    clock,
                    AlertsActions(),
                ) {}
            },
            "alert-kind-low-muted" to {
                AlertKindContent(
                    AlertKind.LOW,
                    AlertsUi(AlertSettings(), lowMuted),
                    clock,
                    AlertKindActions(),
                ) {}
            },
            "alert-kind-very-low" to
                { AlertKindContent(AlertKind.VERY_LOW, AlertsUi(AlertSettings(), AlertUiState()), clock, AlertKindActions()) {} },
            "threshold-review" to { AlertThresholdReviewContent(AlertSettings(), { _, _ -> }, {}, {}) },
            "ranges" to { RangesContent(GraphPrefs(), { _, _ -> }, {}) },
            "ranges-changed" to { RangesContent(GraphPrefs(listOf(54.0, 70.0, 120.0, 180.0, 250.0, 300.0)), { _, _ -> }, {}) },
            "graph" to { GraphContent(GraphConfig(), GraphActions(), {}) },
            "graph-custom" to {
                GraphContent(
                    GraphConfig(
                        lines = listOf(80, 100, 160),
                        placeHours = mapOf(GraphPlace.NOTIFICATION to 3),
                        ringCount = 3,
                        lastElse = null,
                        ringOrder = Reorder.moved(RingSpec.DEFAULT_RING_ORDER, 5, 0),
                        sensorHidden = setOf(GraphPlace.WIDGETS),
                    ),
                    GraphActions(),
                    {},
                )
            },
            "graph-marks" to { GraphMarksContent(GraphConfig(), GraphActions(), {}) },
            "lock" to { LockScreenContent(20.0, {}, {}) },
            "reliability" to
                { ReliabilityContent(AlertReadiness(setOf(AlertRequirement.FullScreenIntent, AlertRequirement.BatteryExemption)), {}) {} },
            "about-update" to { AboutContent("0.12.0", UpdateState.Available(release), {}) {} },
            "about-offline" to { AboutContent("0.12.0", UpdateState.Offline, {}) {} },
            "sensor" to { SensorSettingsContent(sensorWorking, now, zone, SensorSettingsActions()) {} },
            "sensor-ended" to
                { SensorSettingsContent(sensorWorking.copy(state = SensorState.Ended), now, zone, SensorSettingsActions()) {} },
            "sensor-juggluco-signed-out" to {
                SensorSettingsContent(
                    sensorWorking.copy(source = SourceChoice.Juggluco, sensor = null, account = null),
                    now,
                    zone,
                    SensorSettingsActions(),
                ) {
                }
            },
            "sensor-juggluco-unreachable" to {
                SensorSettingsContent(
                    sensorWorking.copy(source = SourceChoice.Juggluco, link = SourceLink.JugglucoUnreachable),
                    now,
                    zone,
                    SensorSettingsActions(),
                ) {
                }
            },
            "statusbar" to
                { StatusBarContent(StatusBarPrefs(chip = true, numberIcon = true), true, {}, {}, {}, {}) { root(rootOn, noRoot) } },
            "statusbar-live-updates-off" to { StatusBarContent(StatusBarPrefs(chip = true, numberIcon = false), false, {}, {}, {}, {}) },
            "statusbar-chip-off" to { StatusBarContent(StatusBarPrefs(chip = false, numberIcon = true), true, {}, {}, {}, {}) },
            "statusbar-root-icons" to {
                StatusBarContent(StatusBarPrefs(chip = false, numberIcon = true), true, {}, {}, {}, {}) {
                    root(rootOn, SystemAccessState(root = true, module = ModuleState.Missing, framework = null))
                }
            },
            "statusbar-root-restart" to {
                StatusBarContent(StatusBarPrefs(chip = false, numberIcon = true), true, {}, {}, {}, {}) {
                    root(rootOn.copy(position = HookPosition.AFTER_CLOCK), SystemAccessState(root = true, module = ModuleState.Update))
                }
            },
            "statusbar-root-active" to {
                StatusBarContent(StatusBarPrefs(chip = false, numberIcon = true), true, {}, {}, {}, {}) {
                    root(rootOn, SystemAccessState(root = true, module = ModuleState.Active, framework = "LSPosed 2.0"))
                }
            },
            "statusbar-root-placement" to {
                StatusBarContent(StatusBarPrefs(chip = false, numberIcon = true), true, {}, {}, {}, {}) {
                    root(rootOn, SystemAccessState(root = true, module = ModuleState.Missing), choosing = true)
                }
            },
            "sensorend" to {
                SensorEndContent(
                    SensorEndPrefs(SensorEndConfig(), 8, SensorState.Active(now.plusSeconds(86_400 + 3600))),
                    now,
                    SensorEndActions(),
                    onBack = {},
                )
            },
            "sensorend-custom" to {
                SensorEndContent(
                    SensorEndPrefs(
                        SensorEndConfig(
                            reminders = listOf(SensorReminder(3, 20 * 60), SensorReminder(1, 9 * 60), SensorReminder(0, 7 * 60)),
                            countdown = false,
                            evening = false,
                        ),
                        4,
                        SensorState.WarmingUp(now.plusSeconds(1800)),
                    ),
                    now,
                    SensorEndActions(),
                    onBack = {},
                )
            },
            "sensor-none" to {
                SensorSettingsContent(
                    SensorSettingsState(SourceChoice.Libre3Direct, SensorState.None, null, null),
                    now,
                    zone,
                    SensorSettingsActions(),
                ) {}
            },
        )

    private fun gallery(
        dark: Boolean,
        fontScale: Float,
        prefix: String = "",
        only: Set<String>? = null,
    ) {
        var current by mutableStateOf(pages.first().second)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SukraraTheme(dark = dark, dynamic = false) { current() }
            }
        }
        val theme = if (dark) "dark" else "light"
        val font = if (fontScale > 1f) "font2x" else "font1x"
        pages.filter { only == null || it.first in only }.forEach { (name, page) ->
            current = page
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/settings-$prefix$name-$theme-$font.png")
        }
    }

    @Test fun lightDefault() = gallery(dark = false, fontScale = 1f)

    @Test fun darkDefault() = gallery(dark = true, fontScale = 1f)

    @Test fun lightLargest() = gallery(dark = false, fontScale = 2f)

    @Test fun darkLargest() = gallery(dark = true, fontScale = 2f)

    @Test
    @Config(qualifiers = "es")
    fun spanish() =
        gallery(
            dark = true,
            fontScale = 1f,
            prefix = "es-",
            only =
                setOf(
                    "alerts",
                    "alert-kind-low-muted",
                    "threshold-review",
                    "about-update",
                    "sensor",
                    "sensor-juggluco-signed-out",
                    "sensor-juggluco-unreachable",
                ),
        )
}

private val rootOn = RootSettings(on = true, position = HookPosition.DEFAULT, color = true, hideIcon = true)
private val noRoot = SystemAccessState(root = false, module = ModuleState.Missing)

/** The status bar page's root section with made-up access states (no root, no LSPosed: pictures only). */
@Composable
private fun root(
    s: RootSettings,
    access: SystemAccessState,
    choosing: Boolean = false,
) = RootStatusBarSection(s, access, busy = false, problem = null, choosing = choosing, onChoosing = {
}, actions = RootActions({}, {}, {}, {}, {}, {}, {}))
