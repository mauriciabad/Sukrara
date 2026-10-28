// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.content.Context
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.monitor.hub.GlucoseHistory
import app.mauri.sukrara.monitor.hub.GlucoseHubImpl
import app.mauri.sukrara.monitor.hub.MonitorRecord
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.service.AndroidAlarms
import app.mauri.sukrara.monitor.service.AndroidServiceLauncher
import app.mauri.sukrara.monitor.service.AndroidStoppedNotice
import app.mauri.sukrara.monitor.service.FreshnessTicker
import app.mauri.sukrara.monitor.service.HealthCheck
import app.mauri.sukrara.monitor.service.MonitorStarter
import app.mauri.sukrara.monitor.systemui.RootStatusBar
import app.mauri.sukrara.monitor.systemui.SystemAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn

/**
 * The monitor's objects (DI module): the hub, the service's starter, the health check, the freshness ticker and
 * the glucose notification. `AppGraph` builds one and the `Application` exposes it through [MonitorHost].
 *
 * [source] is the role's one reading source: wearer → the sensor module's source, follower → the follower engine;
 * null = nothing set up to monitor (no sensor paired, not following anyone), so the service doesn't run.
 */
class MonitorModule(
    private val context: Context,
    scope: CoroutineScope,
    dispatchers: DispatcherProvider,
    val clock: Clock,
    readings: ReadingsRepository,
    record: StateStore<MonitorRecord>,
    role: Flow<Role?>,
    val source: suspend (Role) -> ReadingSource?,
    settings: SettingsRepository,
    treatments: Flow<List<Treatment>>,
    graphConfig: Flow<GraphConfig>,
) {
    val hubImpl = GlucoseHubImpl(scope, dispatchers, clock, readings, record, role)

    /** What every other module reads. */
    val hub: GlucoseHub get() = hubImpl

    private val glucoseHistory = GlucoseHistory(clock, readings, hubImpl.state.map { it.recent })

    /**
     * The last 31 days before the live window (home's scroll and zoom, the multi-day slots), re-read hourly: a surface
     * that follows the hub joins them with `GlucoseState.recent` itself (`ReadingHistory.join`), so a new reading
     * redraws it once, not twice.
     */
    val storedHistory: Flow<List<Reading>> =
        glucoseHistory.stored.shareIn(scope, SharingStarted.WhileSubscribed(HISTORY_STOP_MS), replay = 1)

    val notification = LiveNotification(context)

    private val alarms = AndroidAlarms(context)

    val starter =
        MonitorStarter(
            hub = hubImpl,
            hasSomethingToMonitor = {
                hubImpl.state.value.role
                    ?.let { source(it) } != null
            },
            launcher = AndroidServiceLauncher(context),
            notice = AndroidStoppedNotice(context),
            log = { android.util.Log.i("Sukrara.monitor", it) },
        )

    val healthCheck = HealthCheck(alarms, starter)
    val freshnessTicker = FreshnessTicker(alarms)

    private val ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A freshness step passed without a new reading: surfaces that show the value redraw (notification, widgets). */
    val freshnessTicks: SharedFlow<Unit> = ticks

    private val screenOn = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * What the glucose notification draws besides the hub state: the status-bar settings, the treatments, the graph
     * settings and the stored days (the custom card and its 7–30-day slots, the same windows as home and the widgets),
     * as one value that changes when any of them does. The stored days start empty so a slow read never holds the post.
     */
    val notificationInputs: Flow<NotificationInputs> =
        combine(
            settings.flow(SurfaceSettings.StatusChip),
            settings.flow(SurfaceSettings.NumberIcon),
            treatments,
            graphConfig,
            storedHistory.onStart { emit(emptyList()) },
        ) { chip, icon, list, config, stored -> NotificationInputs(chip, icon, list, config, stored) }
            .distinctUntilChanged()
            // a failing store must never stop the foreground notification: it goes on with the defaults
            .catch {
                android.util.Log.w("Sukrara.monitor", "notification inputs failed: ${it.javaClass.simpleName}")
                emit(NotificationInputs(chip = true, numberIcon = true, treatments = emptyList(), config = GraphConfig()))
            }

    /**
     * Post the glucose notification again even if unchanged: the screen came on (its card isn't drawn while it's off),
     * or the person swiped it away.
     */
    val repostTicks: SharedFlow<Unit> = screenOn

    internal fun repost() {
        screenOn.tryEmit(Unit)
    }

    /** Root for Sukrara and the System UI module (Settings › Status bar, the home restart card). */
    val systemAccess = SystemAccess(dispatchers.io)

    /** The value in the status bar with root: system icons and the System UI module's state. */
    val rootStatusBar =
        RootStatusBar(
            context = context,
            scope = scope,
            clock = clock,
            access = systemAccess,
            hub = hubImpl.state,
            settings = settings,
            graphConfig = graphConfig,
            freshnessTicks = freshnessTicks,
            repostNotification = ::repost,
        )

    /** Channels and the hub's inputs; call from `AppGraph.ready()` (after storage is ready, off the main thread). */
    suspend fun ready() {
        Channels.create(context)
        hubImpl.start()
        // starters must see the real role from disk, not the initial "setup not done"
        hubImpl.awaitInputs()
        systemAccess.start()
        rootStatusBar.start()
    }

    internal fun onFreshnessStep() {
        freshnessTicker.fired()
        ticks.tryEmit(Unit)
    }
}

/** The glucose notification's inputs besides the hub state. */
data class NotificationInputs(
    val chip: Boolean,
    val numberIcon: Boolean,
    val treatments: List<Treatment>,
    val config: GraphConfig,
    /** The stored days before the live window (the multi-day slots count them). */
    val stored: List<Reading> = emptyList(),
)

/** The `Application` gives the monitor's Android components (service, receivers) their module. */
interface MonitorHost {
    val monitor: MonitorModule

    /** Completes when the app's storage and modules are ready ([MonitorModule.ready] has run). */
    suspend fun awaitReady()
}

/** Home left for this long (rotation, a quick switch) keeps the month in memory. */
private const val HISTORY_STOP_MS = 5_000L

internal val Context.monitorHost: MonitorHost get() = applicationContext as MonitorHost
