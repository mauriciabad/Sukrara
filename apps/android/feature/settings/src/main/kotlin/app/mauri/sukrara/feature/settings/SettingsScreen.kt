// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.mauri.sukrara.feature.settings.advanced.AdvancedScreen
import app.mauri.sukrara.feature.settings.advanced.LogScreen
import app.mauri.sukrara.feature.settings.graph.GraphActions
import app.mauri.sukrara.feature.settings.graph.GraphContent
import app.mauri.sukrara.feature.settings.graph.GraphMarksContent
import app.mauri.sukrara.feature.settings.graph.GraphViewModel
import app.mauri.sukrara.feature.settings.lock.LockScreenContent
import app.mauri.sukrara.feature.settings.lock.LockScreenViewModel
import app.mauri.sukrara.feature.settings.pens.DosesReadPage
import app.mauri.sukrara.feature.settings.pens.PenPage
import app.mauri.sukrara.feature.settings.pens.PenViewModel
import app.mauri.sukrara.feature.settings.pens.PensContent
import app.mauri.sukrara.feature.settings.pens.PensViewModel
import app.mauri.sukrara.feature.settings.sensorend.SensorEndActions
import app.mauri.sukrara.feature.settings.sensorend.SensorEndContent
import app.mauri.sukrara.feature.settings.sensorend.SensorEndViewModel
import app.mauri.sukrara.feature.settings.statusbar.RootStatusBarSection
import app.mauri.sukrara.feature.settings.statusbar.StatusBarContent
import app.mauri.sukrara.feature.settings.statusbar.StatusBarViewModel
import app.mauri.sukrara.feature.settings.statusbar.liveUpdatesAllowed
import app.mauri.sukrara.feature.settings.statusbar.openLiveUpdateSettings
import java.time.LocalDate

/** The settings pages (the app's Nav3 entries map one key to each). */
sealed interface SettingsRoute {
    data object Overview : SettingsRoute

    data object Alerts : SettingsRoute

    data class AlertKindPage(val kind: AlertKind) : SettingsRoute

    data object Ranges : SettingsRoute

    data object Graph : SettingsRoute

    /** Settings › Graph › Treatments on the graph. */
    data object GraphMarks : SettingsRoute

    data object LockScreen : SettingsRoute

    /** Settings › Insulin pens (the wearer's phone with NFC). */
    data object Pens : SettingsRoute

    /** Settings › Insulin pens › one pen. */
    data class Pen(val serial: String) : SettingsRoute

    /** Settings › Insulin pens › one pen › Doses read. */
    data class PenDoses(val serial: String) : SettingsRoute

    data object Reliability : SettingsRoute

    data object StatusBar : SettingsRoute

    data object SensorEnd : SettingsRoute

    data object About : SettingsRoute

    /** The threshold review (also the setup's "Review alert thresholds" step). */
    data object ThresholdReview : SettingsRoute

    /** Settings › Advanced: settings file, relays, log, identity. */
    data object Advanced : SettingsRoute

    /** Settings › Advanced › Log. */
    data object Log : SettingsRoute
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
    openApps: () -> Unit = {},
    openClinicUpload: () -> Unit = {},
    openProfile: () -> Unit = {},
    openBackup: () -> Unit = {},
    openExport: () -> Unit = {},
    onReviewFinish: (ThresholdReviewResult) -> Unit = { onBack() },
    /** The live card the Ranges and Graph pages show on top (the app's own panel; nothing in tests). */
    preview: @Composable () -> Unit = {},
) {
    val clock = deps.clock
    when (route) {
        SettingsRoute.Overview -> {
            val vm = alertsViewModel(deps)
            val ui by vm.ui.collectAsStateWithLifecycle()
            val readiness by deps.readiness.readiness.collectAsStateWithLifecycle()
            val apps by deps.appsShared.collectAsStateWithLifecycle(emptySet())
            val clinic by deps.clinicBanner.collectAsStateWithLifecycle(false)
            val closedTips by deps.closedTips.collectAsStateWithLifecycle(0)
            val sharing by deps.overviewSharing.collectAsStateWithLifecycle(OverviewSharing())
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { deps.readiness.refresh() }
            SettingsOverviewContent(
                ui?.settings?.on ?: true,
                ui?.alerts ?: AlertUiState(),
                readiness,
                clock,
                OverviewActions(
                    open = navigate,
                    openSensor = openSensor,
                    openSharing = openSharing,
                    openApps = openApps,
                    unmuteAll = vm::unmuteAll,
                    openClinicUpload = openClinicUpload,
                    dismissClinicBanner = deps::dismissClinicBanner,
                    restoreClinicBanner = deps::restoreClinicBanner,
                    restoreClosedTips = deps::restoreClosedTips,
                    pens = deps.pensAvailable(),
                    openProfile = openProfile,
                    openBackup = openBackup,
                    openExport = openExport,
                ),
                apps,
                clinic,
                closedTips,
                sharing,
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
                    }, deps.alertPlayback),
                    onBack,
                )
            }
        }

        is SettingsRoute.AlertKindPage -> {
            val vm = alertsViewModel(deps)
            val ui by vm.ui.collectAsStateWithLifecycle()
            ui?.let {
                AlertKindContent(
                    route.kind,
                    it,
                    clock,
                    AlertKindActions(vm::edit, vm::unmute, vm::remindAsUsual, deps.alertPlayback),
                    onBack,
                )
            }
        }

        SettingsRoute.Ranges -> {
            val vm = viewModel { GraphPrefsViewModel(deps.settings) }
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            prefs?.let { RangesContent(it, vm::set, onBack, vm::reset, preview) }
        }

        SettingsRoute.Graph, SettingsRoute.GraphMarks -> {
            val vm = viewModel { GraphViewModel(deps.settings) }
            val config by vm.config.collectAsStateWithLifecycle()
            val actions =
                GraphActions(
                    number = vm::set,
                    bool = vm::set,
                    lines = vm::lines,
                    slots = vm::slots,
                    lastPlace = vm::lastPlace,
                    lastElse = vm::lastElse,
                    ringRange = vm::ringRange,
                    ringOrder = vm::ringOrder,
                    marks = vm::marks,
                    placeHours = vm::placeHours,
                    sensorTime = vm::sensorTime,
                    reset = vm::reset,
                    openMarks = { navigate(SettingsRoute.GraphMarks) },
                )
            config?.let {
                if (route == SettingsRoute.Graph) {
                    GraphContent(it, actions, onBack, preview)
                } else {
                    GraphMarksContent(it, actions, onBack, preview)
                }
            }
        }

        SettingsRoute.Pens -> {
            val vm = viewModel { PensViewModel(deps.settings, deps.pens) }
            val ui by vm.ui.collectAsStateWithLifecycle()
            ui?.let { PensContent(it, vm, onBack) { s -> navigate(SettingsRoute.Pen(s)) } }
        }

        is SettingsRoute.Pen -> {
            val port = deps.pens
            if (port == null) {
                val back by rememberUpdatedState(onBack)
                LaunchedEffect(Unit) { back() }
            } else {
                val vm = viewModel(key = "pen-${route.serial}") { PenViewModel(port, route.serial, deps.settings) }
                PenPage(vm, onBack, openDoses = { navigate(SettingsRoute.PenDoses(route.serial)) })
            }
        }

        is SettingsRoute.PenDoses -> {
            val port = deps.pens
            if (port == null) {
                val back by rememberUpdatedState(onBack)
                LaunchedEffect(Unit) { back() }
            } else {
                val vm = viewModel(key = "pen-${route.serial}") { PenViewModel(port, route.serial, deps.settings) }
                DosesReadPage(vm, onBack)
            }
        }

        SettingsRoute.LockScreen -> {
            val vm = viewModel { LockScreenViewModel(deps.settings) }
            val seconds by vm.seconds.collectAsStateWithLifecycle()
            seconds?.let { LockScreenContent(it, vm::set, onBack) }
        }

        SettingsRoute.Reliability -> {
            val readiness by deps.readiness.readiness.collectAsStateWithLifecycle()
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { deps.readiness.refresh() }
            ReliabilityContent(readiness, deps.readiness::fix, onBack)
        }

        SettingsRoute.StatusBar -> {
            val vm = viewModel { StatusBarViewModel(deps.settings, deps.systemAccess) }
            val prefs by vm.prefs.collectAsStateWithLifecycle()
            val root by vm.root.collectAsStateWithLifecycle()
            val access by vm.access.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val problem by vm.problem.collectAsStateWithLifecycle()
            var choosing by rememberSaveable { mutableStateOf(false) }
            val context = LocalContext.current
            // the person may come back from Android's Live Updates switch, KernelSU or LSPosed: read them again on resume
            var allowed by remember { mutableStateOf(context.liveUpdatesAllowed()) }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                allowed = context.liveUpdatesAllowed()
                vm.refresh()
            }
            prefs?.let {
                StatusBarContent(it, allowed, vm::setChip, vm::setNumberIcon, context::openLiveUpdateSettings, onBack) {
                    root?.let { r -> RootStatusBarSection(r, access, busy, problem, choosing, { c -> choosing = c }, vm.actions) }
                }
            }
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

        SettingsRoute.Advanced -> {
            AdvancedScreen(
                deps.advanced,
                { LocalDate.now(clock.zone) },
                { navigate(SettingsRoute.Log) },
                { navigate(SettingsRoute.ThresholdReview) },
                onBack,
            )
        }

        SettingsRoute.Log -> {
            LogScreen(deps.advanced, onBack)
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
