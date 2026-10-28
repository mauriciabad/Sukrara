// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor

import android.content.Context
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseHub
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.monitor.hub.GlucoseHubImpl
import app.mauri.sukrara.monitor.hub.MonitorRecord
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.service.AndroidAlarms
import app.mauri.sukrara.monitor.service.AndroidServiceLauncher
import app.mauri.sukrara.monitor.service.AndroidStoppedNotice
import app.mauri.sukrara.monitor.service.FreshnessTicker
import app.mauri.sukrara.monitor.service.HealthCheck
import app.mauri.sukrara.monitor.service.MonitorStarter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

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
     * What the glucose notification draws besides the hub state: the status-bar settings, the treatments and the graph
     * settings (the custom card), as one value that changes when any of them does.
     */
    val notificationInputs: Flow<NotificationInputs> =
        combine(
            settings.flow(SurfaceSettings.StatusChip),
            settings.flow(SurfaceSettings.NumberIcon),
            treatments,
            graphConfig,
        ) { chip, icon, list, config -> NotificationInputs(chip, icon, list, config) }.distinctUntilChanged()

    /**
     * Post the glucose notification again even if unchanged: the screen came on (its card isn't drawn while it's off),
     * or the person swiped it away.
     */
    val repostTicks: SharedFlow<Unit> = screenOn

    internal fun repost() {
        screenOn.tryEmit(Unit)
    }

    /** Channels and the hub's inputs; call from `AppGraph.ready()` (after storage is ready, off the main thread). */
    suspend fun ready() {
        Channels.create(context)
        hubImpl.start()
        // starters must see the real role from disk, not the initial "setup not done"
        hubImpl.awaitInputs()
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
)

/** The `Application` gives the monitor's Android components (service, receivers) their module. */
interface MonitorHost {
    val monitor: MonitorModule

    /** Completes when the app's storage and modules are ready ([MonitorModule.ready] has run). */
    suspend fun awaitReady()
}

internal val Context.monitorHost: MonitorHost get() = applicationContext as MonitorHost
