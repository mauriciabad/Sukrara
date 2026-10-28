// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.data.DataModule
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DefaultDispatchers
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SystemClock
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.feature.sensor.SensorUiModule
import app.mauri.sukrara.feature.settings.AlertSettingsStore
import app.mauri.sukrara.feature.settings.SettingsModule
import app.mauri.sukrara.feature.sharing.EngineOwnerSharing
import app.mauri.sukrara.feature.sharing.SetupModule
import app.mauri.sukrara.feature.sharing.SharingDeps
import app.mauri.sukrara.feature.sharing.setup.SetupRecord
import app.mauri.sukrara.feature.sharing.setup.label
import app.mauri.sukrara.feature.treatments.TreatmentsModule
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.monitor.MonitorModule
import app.mauri.sukrara.monitor.alerts.AlertRecord
import app.mauri.sukrara.monitor.alerts.AlertsModule
import app.mauri.sukrara.monitor.hub.MonitorRecord
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.NotificationGroups
import app.mauri.sukrara.monitor.notify.NotificationIds
import app.mauri.sukrara.monitor.sensorend.SensorEndMemory
import app.mauri.sukrara.monitor.service.StartReason
import app.mauri.sukrara.sensor.SensorModule
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.pairing.ReceiverIdState
import app.mauri.sukrara.sensor.source.SensorSourceKind
import app.mauri.sukrara.share.ShareOwnerModule
import app.mauri.sukrara.share.owner.followRole
import app.mauri.sukrara.share.owner.requests.AndroidRequestNotifications
import app.mauri.sukrara.widgets.WidgetPlacements
import app.mauri.sukrara.widgets.WidgetsModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import app.mauri.sukrara.graph.Clock as ClockText

/**
 * The app's object graph (manual DI, docs/ARCHITECTURE.md §Android DI, apps/android/README.md "DI").
 *
 * - Each area owns ONE module class in its own Gradle module (e.g. `DataModule` in `:core:data`, `MonitorModule` in
 *   `:monitor`) that builds its objects from constructor parameters. `AppGraph` only composes them, in dependency
 *   order (data → role → sensor → monitor → alerts → home → …): one `val x by lazy { XModule(…) }` block per module.
 * - Built in [App.onCreate] on the main thread, so the constructor does no I/O: everything is `lazy`, and work
 *   that reads disk happens in [ready] on the IO dispatcher.
 * - [scope] lives as long as the process; a failing child doesn't cancel the others.
 */
class AppGraph(
    val app: Application,
    val dispatchers: DispatcherProvider = DefaultDispatchers,
    val clock: Clock = SystemClock,
) : SharingDeps {
    val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val appVersion: String = BuildConfig.VERSION_NAME

    // ---- modules (one line each; construction does no I/O) ----

    val data by lazy { DataModule(app, appVersion, dispatchers, clock) }

    /**
     * The phone's role, chosen in first-run setup (written with `role.update`); null = setup not done. Two
     * copies, so one damaged file (reported on home) never stops monitoring ([RoleStore]).
     */
    val role by lazy {
        RoleStore(
            primary = data.typedStore("role", RoleRecord.serializer(), RoleRecord(), whenCorrupt = RoleRecord()),
            backup = data.typedStore("role-backup", RoleRecord.serializer(), RoleRecord(), whenCorrupt = RoleRecord()),
        )
    }

    val sensor by lazy {
        SensorModule(
            context = app,
            secure = data.secureStore,
            // losing it only asks for the LibreView sign-in again
            receiverIds = data.typedStore("receiver-ids", ReceiverIdState.serializer(), ReceiverIdState(), ReceiverIdState()),
            sourceKind = data.settings.flow(SensorSource).map(SensorSourceKind::fromId),
            http = data.http,
            clock = clock,
            dispatchers = dispatchers,
        )
    }

    val monitor by lazy {
        MonitorModule(
            context = app,
            scope = scope,
            dispatchers = dispatchers,
            clock = clock,
            readings = data.readings,
            // a damaged record keeps the no-data alarm armed (from now) instead of silently disarming it
            record =
                data.typedStore(
                    "monitor-record",
                    MonitorRecord.serializer(),
                    MonitorRecord(),
                    whenCorrupt = MonitorRecord(startedAtEpochMs = clock.now().toEpochMilli()),
                ),
            role = role.data.map { it.role },
            source = ::readingSource,
            settings = data.settings,
            treatments = recentTreatments(),
            graphConfig = graphConfig,
        )
    }

    val alerts by lazy {
        AlertsModule(
            context = app,
            hub = monitor.hub,
            alertSettings = data.settings,
            // a damaged record starts the machine over; the hub re-arms it as soon as the service runs
            alertRecord = data.typedStore("alert_state", AlertRecord.serializer(), AlertRecord(), whenCorrupt = AlertRecord()),
            // a damaged memory can only repeat a sensor-end warning
            sensorEndRecord = data.typedStore("sensor_end", SensorEndMemory.serializer(), SensorEndMemory(), SensorEndMemory()),
            treatments = recentTreatments(),
            // the alert alarm found the service dead: bring it back (a refusal shows "Monitoring stopped")
            restarter = { startMonitoring(StartReason.AlertCheck) },
            clock = clock,
            log = { data.diagLog.log("alerts", it) },
            scope = scope,
            dispatchers = dispatchers,
            needsBluetooth = needsBluetooth(),
        )
    }

    val settingsUi by lazy {
        SettingsModule(
            settings = data.settings,
            alertSettings =
                object : AlertSettingsStore {
                    override val settings = alerts.store.settings

                    override suspend fun update(transform: (AlertSettings) -> AlertSettings) = alerts.store.updateSettings(transform)
                },
            alertControls = alerts.controls,
            alertState = monitor.hub.state.map { it.alerts },
            readiness = alerts.readiness,
            appVersion = appVersion,
            prerelease = BuildConfig.PRERELEASE,
            http = data.http,
            dispatchers = dispatchers,
            clock = clock,
            sensor = monitor.hub.state.map { it.sensor },
        )
    }

    val sensorUi by lazy {
        SensorUiModule(
            app,
            sensor,
            data.settings,
            clock,
            scope,
            startMonitoring = { startMonitoring(StartReason.App) },
        )
    }

    override val setup by lazy {
        SetupModule(
            context = app,
            setupStore = data.typedStore("setup", SetupRecord.serializer(), SetupRecord(), whenCorrupt = SetupRecord()),
            roles = AppRoles(role),
            hub = monitor.hub,
            sharing = EngineOwnerSharing(shareOwner.ownerEngine, scope) { clock.now().epochSecond },
            alertSettings = AppAlertSettings(alerts.store),
            settings = data.settings,
            resetAlerts = alerts::reset,
            startMonitoring = { scope.launch { startMonitoring(StartReason.App) } },
            // THE alert checklist: the same instance home's reliability row and Settings › Alert reliability read
            readiness = alerts.readiness,
            clock = clock,
        )
    }

    val sensorSettings by lazy { SensorSettingsBridge(sensor, data.settings, SensorSource, scope) { startMonitoring(StartReason.App) } }

    /** The person's graph settings: THE [GraphConfig] every card surface draws with (home, widgets). */
    val graphConfig: Flow<GraphConfig> by lazy {
        GraphSettings.config(data.settings).shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_MS), replay = 1)
    }

    val home by lazy {
        HomeModule(
            data = data,
            clock = clock,
            scope = scope,
            glucose = monitor.hub.state,
            graphConfig = graphConfig,
            storedHistory = monitor.storedHistory,
            readinessSource = alerts.readiness,
            unmuteAlerts = { scope.launch { alerts.controls.unmuteAll() } },
            endQuietAlerts = { kind -> scope.launch { alerts.controls.unmute(kind) } },
            resume = { scope.launch { startMonitoring(StartReason.Resume) } },
            nextSetupStep = setup.finishItems.map { items -> items.firstOrNull()?.label(app) },
            refreshSetup = setup::refresh,
            widgetPlaced = {
                widgets.presence.refresh()
                widgets.presence.placed.value
            },
            treatmentLog = { treatments.log },
        )
    }

    val widgets by lazy {
        WidgetsModule(
            context = app,
            scope = scope,
            clock = clock,
            glucose = monitor.hub.state,
            treatments = recentTreatments(),
            graphConfig = graphConfig,
            storedHistory = monitor.storedHistory,
            freshnessTicks = monitor.freshnessTicks,
            store = data.typedStore("widgets", WidgetPlacements.serializer(), WidgetPlacements(), whenCorrupt = WidgetPlacements()),
        )
    }

    /**
     * Starts the monitoring service once the graph is loaded (app open, the home's Resume, the alert alarm). A graph
     * that failed to load is logged and the start is skipped; the next trigger tries again in a new process.
     */
    suspend fun startMonitoring(reason: StartReason) {
        runCatching { ready.await() }.onFailure {
            android.util.Log.e("Sukrara.app", "graph not ready: ${it.javaClass.simpleName}")
            return
        }
        monitor.starter.start(reason)
    }

    /** The wearer's sharing (relays, followers, treatments and settings to them); started by the sharing UI / service. */
    val shareOwner by lazy {
        val info = NotificationGroups.Group.INFO
        ShareOwnerModule(
            context = app,
            data = data,
            hub = monitor.hub,
            clock = clock,
            dispatchers = dispatchers,
            scope = scope,
            requestNotifications =
                AndroidRequestNotifications(
                    context = app,
                    channel = Channels.REQUESTS,
                    group = info.key,
                    time = { ClockText.time(it) },
                    post = { NotificationGroups.post(app, NotificationIds.REQUESTS, it, info, Channels.REQUESTS) },
                    remove = { NotificationGroups.cancel(app, NotificationIds.REQUESTS, info) },
                ),
        )
    }

    val treatments: TreatmentsModule by lazy {
        TreatmentsModule(
            data.treatments,
            ShareTreatmentSync(shareOwner),
            clock,
            home.glucose,
            data.settings,
            scope,
            hints = AppAlertHints(alerts.store.settings.stateIn(scope, SharingStarted.Eagerly, null), home.glucose, clock),
        )
    }

    /**
     * Loads what the modules need from disk, once, off the main thread. [App] starts it; anything that reads state
     * first does `graph.ready.await()` (cheap once done).
     */
    val ready: Deferred<Unit> =
        scope.async(dispatchers.io, CoroutineStart.LAZY) {
            // a step that fails is logged and the next one still runs: a damaged database must not keep the hub
            // (and so the service, the notification and the alerts) from starting
            step("data") { data.ready() }
            step("role") { role.heal() }
            step("monitor") { monitor.ready() }
            step("widgets") { widgets.start() }
            // sharing runs while this phone is the wearer (needs an identity: created / restored in Settings › Sharing)
            step("share") { scope.launch { shareOwner.ownerEngine.followRole(role.data.map { it.role }, scope) } }
        }

    /**
     * The role's one reading source, or null when nothing is set up to monitor (then the service doesn't run).
     * Wearer: a debug build's synthetic source when switched on, else the sensor source once a sensor is paired (or
     * Juggluco is the source). Follower: the follower engine.
     */
    private suspend fun readingSource(role: Role): ReadingSource? =
        when (role) {
            Role.Wearer -> BuildVariant.wearerSource(this) ?: sensorSource()
            Role.Follower -> null
        }

    /** Whether the role's source talks to the sensor over Bluetooth: the wearer's own sensor (not Juggluco). */
    private fun needsBluetooth(): Flow<Boolean> =
        combine(role.data, data.settings.flow(SensorSource)) { r, source ->
            r.role == Role.Wearer && SensorSourceKind.fromId(source) != SensorSourceKind.Juggluco
        }

    /** Treatments of the last [TREATMENTS_WINDOW] (and any later), the window moving forward every hour. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun recentTreatments(): Flow<List<Treatment>> =
        flow {
            while (true) {
                emit(clock.now() - TREATMENTS_WINDOW)
                delay(Duration.ofHours(1).toMillis())
            }
        }.flatMapLatest { from -> data.treatments.between(from, from + Duration.ofDays(3650)) }

    private suspend fun step(
        name: String,
        load: suspend () -> Unit,
    ) {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("Sukrara.app", "$name not loaded: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun sensorSource(): ReadingSource? {
        val juggluco = SensorSourceKind.fromId(data.settings.flow(SensorSource).first()) == SensorSourceKind.Juggluco
        // a damaged pairing still runs the source: it shows "scan the sensor again"
        val paired = sensor.pairings.load() != PairingLoad.None
        return if (juggluco || paired) RunWhileAttached(sensor.source, sensor.source::start, sensor.source::stop) else null
    }

    private companion object {
        val TREATMENTS_WINDOW: Duration = Duration.ofHours(25)

        /** A config nobody draws with stops being read after this (a screen rotation keeps it). */
        const val SHARE_STOP_MS = 5_000L
        val SensorSource = SchemaSettings.text("source.source", SensorSourceKind.Libre3.id, SensorSourceKind.entries.map { it.id }.toSet())
    }
}
