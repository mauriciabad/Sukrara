// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.feature.settings.sensorend.SensorEndActions
import app.mauri.sukrara.feature.settings.sensorend.SensorEndContent
import app.mauri.sukrara.feature.settings.sensorend.SensorEndViewModel
import app.mauri.sukrara.feature.settings.statusbar.StatusBarContent
import app.mauri.sukrara.feature.settings.statusbar.StatusBarViewModel
import app.mauri.sukrara.feature.settings.statusbar.liveUpdatesAllowed
import app.mauri.sukrara.feature.settings.statusbar.openLiveUpdateSettings

/** The settings pages (the app's Nav3 entries map one key to each). */
sealed interface SettingsRoute {
    data object Overview : SettingsRoute

    data object Alerts : SettingsRoute

    data class AlertKindPage(val kind: AlertKind) : SettingsRoute

    data object Ranges : SettingsRoute

    data object Graph : SettingsRoute

    data object Reliability : SettingsRoute

    data object StatusBar : SettingsRoute

    data object SensorEnd : SettingsRoute

    data object About : SettingsRoute

    /** The threshold review (also the setup's "Review alert thresholds" step). */
    data object ThresholdReview : SettingsRoute
}

/**
 * Renders [route] with its ViewModel from [deps]. [navigate] opens another settings page, [onBack] leaves this one;
 * Sensor and Sharing are other features' screens. [onReviewFinish] gets the review's result (setup continues on it;
 * from settings it just goes back).
 */
@Composable
fun SettingsScreen(
    deps: SettingsDeps,
    route: SettingsRoute,
    navigate: (SettingsRoute) -> Unit,
    onBack: () -> Unit,
    openSensor: () -> Unit,
    openSharing: () -> Unit,
    onReviewFinish: (ThresholdReviewResult) -> Unit = { onBack() },
) {
    val clock = deps.clock
    when (route) {
        SettingsRoute.Overview -> {
            val vm = alertsViewModel(deps)
            val ui by vm.ui.collectAsStateWithLifecycle()
            val readiness by deps.readiness.readiness.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { deps.readiness.refresh() }
            SettingsOverviewContent(
                ui?.settings?.on ?: true,
                ui?.alerts ?: AlertUiState(),
                readiness,
                clock,
                OverviewActions(navigate, openSensor, openSharing, vm::unmuteAll),
                onBack,
            )
        }

        SettingsRoute.Alerts -> {
            val vm = alertsViewModel(deps)
            val ui by vm.ui.collectAsStateWithLifecycle()
            val confirmedAt by SchemaPrefs.thresholdsConfirmedAt(deps.settings).collectAsStateWithLifecycle(null)
            ui?.let {
                AlertsSettingsContent(
                    it,
                    confirmedAt,
                    clock,
                    AlertsActions(vm::edit, vm::muteAll, vm::unmuteAll, { k -> navigate(SettingsRoute.AlertKindPage(k)) }, {
                        navigate(SettingsRoute.ThresholdReview)
                    }),
                    onBack,
                )
            }
        }

        is SettingsRoute.AlertKindPage -> {
            val vm = alertsViewModel(deps)
            val ui by vm.ui.collectAsStateWithLifecycle()
            ui?.let { AlertKindContent(route.kind, it, clock, AlertKindActions(vm::edit, vm::unmute, vm::remindAsUsual), onBack) }
        }

        SettingsRoute.Ranges -> {
            val vm = viewModel { GraphPrefsViewModel(deps.settings) }
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            prefs?.let { RangesContent(it, { s, v -> vm.set(s, v) }, onBack) }
        }

        SettingsRoute.Graph -> {
            val vm = viewModel { GraphPrefsViewModel(deps.settings) }
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            prefs?.let { GraphBasicsContent(it, { s, v -> vm.set(s, v) }, { s, v -> vm.set(s, v) }, onBack) }
        }

        SettingsRoute.Reliability -> {
            val readiness by deps.readiness.readiness.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { deps.readiness.refresh() }
            ReliabilityContent(readiness, deps.readiness::fix, onBack)
        }

        SettingsRoute.StatusBar -> {
            val vm = viewModel { StatusBarViewModel(deps.settings) }
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val context = LocalContext.current
            // the person may come back from Android's Live Updates switch: read it again on resume
            var allowed by remember { mutableStateOf(context.liveUpdatesAllowed()) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = context.liveUpdatesAllowed() }
            prefs?.let { StatusBarContent(it, allowed, vm::setChip, vm::setNumberIcon, context::openLiveUpdateSettings, onBack) }
        }

        SettingsRoute.SensorEnd -> {
            val vm = viewModel { SensorEndViewModel(deps.settings, deps.sensor) }
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            prefs?.let { SensorEndContent(it, clock.now(), SensorEndActions(vm::change, vm::setCountdownHours), onBack) }
        }

        SettingsRoute.About -> {
            val vm = viewModel { AboutViewModel(deps.releases, deps.appVersion, deps.prerelease) }
            val state by vm.state.collectAsStateWithLifecycle()
            AboutContent(vm.installed, state, vm::check, onBack)
        }

        SettingsRoute.ThresholdReview -> {
            val vm = viewModel { ThresholdReviewViewModel(deps.alertSettings, deps.settings, deps.clock) }
            val draft by vm.draft.collectAsStateWithLifecycle()
            draft?.let { d: AlertSettings ->
                AlertThresholdReviewContent(
                    d,
                    vm::setThreshold,
                    { vm.confirm(onReviewFinish) },
                    { onReviewFinish(ThresholdReviewResult.Cancelled) },
                )
            }
        }
    }
}

@Composable
private fun alertsViewModel(
    deps: SettingsDeps,
): AlertsViewModel = viewModel(factory = viewModelFactory { initializer { AlertsViewModel(deps) } })
