// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import app.mauri.sukrara.alerts.AlertEvent
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSettingsCodec
import app.mauri.sukrara.core.data.DataModule
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DefaultDispatchers
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.PenDuplicates
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SystemClock
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.feature.home.banners.BannerKind
import app.mauri.sukrara.feature.sensor.SensorUiModule
import app.mauri.sukrara.feature.settings.AlertPlayback
import app.mauri.sukrara.feature.settings.AlertSettingsStore
import app.mauri.sukrara.feature.settings.AppOutput
import app.mauri.sukrara.feature.settings.OverviewSharing
import app.mauri.sukrara.feature.settings.SettingsModule
import app.mauri.sukrara.feature.sharing.EngineOwnerSharing
import app.mauri.sukrara.feature.sharing.SetupModule
import app.mauri.sukrara.feature.sharing.SharingDeps
import app.mauri.sukrara.feature.sharing.ended
import app.mauri.sukrara.feature.sharing.setup.SetupRecord
import app.mauri.sukrara.feature.sharing.setup.label
import app.mauri.sukrara.feature.treatments.TreatmentsModule
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.integrations.CurrentSensor
import app.mauri.sukrara.integrations.IntegrationsModule
import app.mauri.sukrara.integrations.broadcasts.BcState
import app.mauri.sukrara.integrations.broadcasts.LiveReading
import app.mauri.sukrara.integrations.healthconnect.HcState
import app.mauri.sukrara.integrations.libreview.LvGlucose
import app.mauri.sukrara.integrations.libreview.LvReadings
import app.mauri.sukrara.integrations.libreview.LvState
import app.mauri.sukrara.integrations.localserver.LanPin
import app.mauri.sukrara.integrations.nightscout.NsState
import app.mauri.sukrara.integrations.tidepool.TpState
import app.mauri.sukrara.monitor.MonitorModule
import app.mauri.sukrara.monitor.alerts.AlertRecord
import app.mauri.sukrara.monitor.alerts.AlertsModule
import app.mauri.sukrara.monitor.hub.MonitorRecord
import app.mauri.sukrara.monitor.notify.Channels
import app.mauri.sukrara.monitor.notify.NotificationGroups
import app.mauri.sukrara.monitor.notify.NotificationIds
import app.mauri.sukrara.monitor.notify.SharingLine
import app.mauri.sukrara.monitor.sensorend.SensorEndMemory
import app.mauri.sukrara.monitor.service.StartReason
import app.mauri.sukrara.pen.PenLog
import app.mauri.sukrara.pen.PenNotificationPorts
import app.mauri.sukrara.pen.PensModule
import app.mauri.sukrara.pen.engine.Ledger
import app.mauri.sukrara.protocol.event.AlertCodec
import app.mauri.sukrara.sensor.SensorModule
import app.mauri.sukrara.sensor.ble.SensorGlucose
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.pairing.ReceiverIdState
import app.mauri.sukrara.sensor.source.SensorSourceKind
import app.mauri.sukrara.sensor.source.SensorStatus
import app.mauri.sukrara.share.FollowerModule
import app.mauri.sukrara.share.ShareOwnerModule
import app.mauri.sukrara.share.follower.FollowState
import app.mauri.sukrara.share.follower.FollowerNotifications
import app.mauri.sukrara.share.owner.OwnerShareState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
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

    val monitor: MonitorModule by lazy {
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
            settings = followerSettings.settings,
            treatments = recentTreatments(),
            graphConfig = graphConfig,
            // cold: the owner engine is built when the service first draws the notification
            sharing =
                flow {
                    val owner = shareOwner.ownerEngine
                    emitAll(
                        combine(owner.state, owner.nearbyLinks) { s, links ->
                            (s as? OwnerShareState.Sharing)?.let { SharingLine(it.followers.count { f -> f.active }, links.size) }
                        },
                    )
                }.distinctUntilChanged(),
        )
    }

    val alerts: AlertsModule by lazy {
        AlertsModule(
            context = app,
            hub = monitor.hub,
            alertSettings = followerSettings.settings,
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
            // a follower's runtime: the mirrored values *Settings from* chooses and the wearer's `alert` events (its own
            // mute stays here)
            wearerSettings = followerSettings.alertMirror,
            remoteEvents = flow { emitAll(follower.engine.alerts) }.mapNotNull { AlertEvent.decode(AlertCodec.encode(it)) },
        )
    }

    /**
     * A follower's *Settings from*: the settings the cards, the notification and the alerts use (the wearer's, the
     * defaults or this phone's own for the mirrored rows). Inputs are cold, so building it builds nothing else.
     */
    val followerSettings: FollowerSettingsBridge by lazy {
        FollowerSettingsBridge(
            own = data.settings,
            alerts =
                object : FollowerSettingsBridge.AlertsAccess {
                    override val settings get() = alerts.store.settings

                    override suspend fun update(transform: (AlertSettings) -> AlertSettings) = alerts.store.updateSettings(transform)
                },
            role = role.data.map { it.role },
            wearer = flow { emitAll(follower.engine.wearerSettings) },
            wearerName = flow { emitAll(monitor.hub.state) }.map { it.link?.wearerName }.distinctUntilChanged(),
        )
    }

    val settingsUi by lazy {
        SettingsModule(
            // the pages show the settings in use (a follower's mirrored rows per *Settings from*); writes go to this phone's own
            settings = followerSettings.settings,
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
            systemAccess = monitor.systemAccess,
            pensAvailable = { pens.available() },
            pens = { pens.port.takeIf { pens.available() } },
            clinicBanner =
                combine(integrations.clinicUploadSet, home.bannerMemory.data) { set, memory ->
                    set == false && !memory.dismissed(BannerKind.ShareWithClinic, clock.now())
                },
            dismissClinicBanner = { scope.launch { home.bannerMemory.update { it.dismiss(BannerKind.ShareWithClinic, clock.now()) } } },
            restoreClinicBanner = { scope.launch { home.bannerMemory.update { it.restore(BannerKind.ShareWithClinic) } } },
            closedTips = home.bannerMemory.data.map { it.closed },
            restoreClosedTips = { scope.launch { home.bannerMemory.update { it.restoreAll() } } },
            advanced = {
                AppAdvanced(
                    role.data.map { it.role },
                    shareOwner.ownerEngine,
                    follower.engine,
                    shareOwner.crypto,
                    data.diagLog,
                    // an imported alert settings value passes their codec (SPEC §3) or is refused
                    data.settingsTransfer(mapOf("alerts:alerts" to { t -> AlertSettingsCodec.decode(t)?.let(AlertSettingsCodec::encode) })),
                )
            },
            overviewSharing =
                combine(role.data, shareOwner.ownerEngine.state) { r, own ->
                    val sharing = own as? OwnerShareState.Sharing
                    OverviewSharing(r.role != Role.Follower, sharing?.profile?.name, sharing?.backedUp)
                },
            alertPlayback =
                object : AlertPlayback {
                    override fun play(
                        kind: AlertKind,
                        pack: String,
                        alarm: Boolean,
                        vibrate: Boolean,
                    ) = alerts.tryOut.play(kind, pack, alarm, vibrate)

                    override fun stop() = alerts.tryOut.stop()

                    override fun silenced(
                        kind: AlertKind,
                        alarm: Boolean,
                    ) = alerts.tryOut.silenced(kind, alarm)

                    override fun sendTest(
                        kind: AlertKind,
                        settings: AlertSettings,
                    ) = alerts.tryOut.sendTest(kind, settings)

                    override fun channelId(
                        kind: AlertKind,
                        alarm: Boolean,
                    ) = alerts.tryOut.channelId(kind, alarm)
                },
            appsShared =
                integrations.summary.map { s ->
                    setOfNotNull(
                        AppOutput.LibreView.takeIf { s.libreView },
                        AppOutput.NightscoutUpload.takeIf { s.nightscoutUpload },
                        AppOutput.XdripServer.takeIf { s.xdripServer },
                        AppOutput.NightscoutApi.takeIf { s.nightscoutApi },
                        AppOutput.Tidepool.takeIf { s.tidepool },
                        AppOutput.Broadcasts.takeIf { s.broadcastApps > 0 },
                        AppOutput.HealthConnect.takeIf { s.healthConnect },
                    )
                },
            settingsFrom = followerSettings,
        )
    }

    /** Uploads, the local xDrip+ server and export (each off until switched on in Settings › Share with apps). */
    val integrations by lazy {
        IntegrationsModule(
            context = app,
            settings = data.settings,
            secure = data.secureStore,
            // losing it only costs one comparison with the site (nothing is sent twice: the site is checked first)
            nightscoutState = data.typedStore("nightscout-upload", NsState.serializer(), NsState(), whenCorrupt = NsState()),
            // a lost state re-sends nothing old (each part starts again from now) but can't retract versions sent before
            libreViewState = data.typedStore("libreview-upload", LvState.serializer(), LvState(), whenCorrupt = LvState()),
            // a lost state re-sends what the phone keeps; Tidepool replaces each datum by its origin id (no duplicates)
            tidepoolState = data.typedStore("tidepool-upload", TpState.serializer(), TpState(), whenCorrupt = TpState()),
            // a lost state may send the current reading once more (the throttle and life count start again)
            broadcastState = data.typedStore("broadcasts", BcState.serializer(), BcState(), whenCorrupt = BcState()),
            // a lost state writes the stored history again (idempotent: one record per minute)
            healthState = data.typedStore("health-connect", HcState.serializer(), HcState(), whenCorrupt = HcState()),
            // a lost pin pauses the home-network option ("another Wi-Fi") until Allow on this Wi-Fi: never a silent move
            nightscoutLan = data.typedStore("nightscout-lan", LanPin.serializer(), LanPin(), whenCorrupt = LanPin()),
            appVersion = appVersion,
            readings = data.readings,
            treatments = data.treatments,
            sensor =
                sensor.source.sensorInfo.map { info ->
                    info?.let { CurrentSensor(it.serial, Instant.ofEpochSecond(it.startEpoch), Instant.ofEpochSecond(it.endEpoch)) }
                },
            libreReadings =
                sensor.source.sensorGlucose.map { b ->
                    fun g(x: SensorGlucose) = LvGlucose(x.lifeCount, x.mgdl, x.epochSec, x.rate)
                    LvReadings(b.serial, b.current?.let(::g), b.history.map(::g))
                },
            libreLive =
                sensor.source.sensorGlucose.mapNotNull { b ->
                    b.current?.let { LiveReading(it.mgdl, it.rate, Instant.ofEpochSecond(it.epochSec), b.serial, it.lifeCount) }
                },
            libreViewEmail = sensor.libreView.email,
            role = role.data.map { it.role },
            jugglucoIsSource = data.settings.flow(SensorSource).map { SensorSourceKind.fromId(it) == SensorSourceKind.Juggluco },
            http = data.http,
            clock = clock,
            dispatchers = dispatchers,
            log = { data.diagLog.log("integrations", it) },
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
            sharing = EngineOwnerSharing(shareOwner.ownerEngine, scope, data.settings) { clock.now().epochSecond },
            alertSettings = AppAlertSettings(alerts.store),
            settings = data.settings,
            resetAlerts = alerts::reset,
            startMonitoring = { scope.launch { startMonitoring(StartReason.App) } },
            // THE alert checklist: the same instance home's reliability row and Settings › Alert reliability read
            readiness = alerts.readiness,
            clock = clock,
            follower =
                AppFollowerSharing(
                    follower.engine,
                    resetMonitoring = ::resetMonitoring,
                    startMonitoring = { startMonitoring(StartReason.App) },
                    clearRole = { role.update { it.copy(role = null) } },
                ),
        )
    }

    /** The follower role: joins, the wearer's stream while the service runs, Stop following. */
    val follower by lazy {
        val info = NotificationGroups.Group.INFO
        val notifications =
            FollowerNotifications(app, Channels.REQUESTS, info.key) {
                NotificationGroups.post(app, NotificationIds.FOLLOWING, it, info, Channels.REQUESTS)
            }
        FollowerModule(app, data, monitor.hub, clock, dispatchers, notifications::show)
    }

    /** No alarm armed from what came before (a previous role, a follow that ended): alert state and "monitoring since" go. */
    private suspend fun resetMonitoring() {
        alerts.reset()
        monitor.hubImpl.resetMonitoring()
    }

    val sensorSettings by lazy {
        SensorSettingsBridge(sensor, data.settings, SensorSource, scope, {
            startMonitoring(StartReason.App)
        }) { monitor.hubImpl.resetMonitoring() }
    }

    /** The person's graph settings: THE [GraphConfig] every card surface draws with (home, widgets). */
    val graphConfig: Flow<GraphConfig> by lazy {
        GraphSettings.config(followerSettings.settings).shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_MS), replay = 1)
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
            alertControls = alerts.controls,
            resume = { scope.launch { startMonitoring(StartReason.Resume) } },
            nextSetupStep = setup.finishItems.map { items -> items.firstOrNull()?.label(app) },
            refreshSetup = setup::refresh,
            widgetPlaced = {
                widgets.presence.refresh()
                widgets.presence.placed.value
            },
            treatmentLog = { treatments.log },
            sharing = { shareOwner.ownerEngine.state },
            penTapsBlocked = { pens.tapsBlockedWhileOn() },
            clinicUploadSet = { integrations.clinicUploadSet },
            clinicMarks = { integrations.libreView.entryMarks.map { m -> m?.let(::LibreViewMarks) } },
            untestedSensor =
                sensor.source.detail
                    .map { d -> (d as? SensorStatus.Direct)?.profile?.takeUnless { it.verified }?.label }
                    .distinctUntilChanged(),
            systemAccess = monitor.systemAccess.state,
            build = BuildConfig.VERSION_CODE.toLong(),
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
            // what followers on *Settings from: Wearer* use (cold: the alert runtime is built when the engine reads it)
            settings = WearerSettingsSource(data.settings, flow { emitAll(alerts.store.settings) }),
        )
    }

    val treatments: TreatmentsModule by lazy {
        TreatmentsModule(
            data.treatments,
            ShareTreatmentSync(shareOwner, follower.engine, role.data.map { it.role }, scope),
            clock,
            home.glucose,
            data.settings,
            scope,
            hints = AppAlertHints(alerts.store.settings.stateIn(scope, SharingStarted.Eagerly, null), home.glucose, clock),
            // read when the dialog saves (the pens module is built on first use, after this one)
            penDuplicates = PenDuplicates { kind, at -> pens.duplicates.near(kind, at) },
        )
    }

    /**
     * Insulin pens (NFC) on the wearer's phone: the ledger (`pens.json`; damaged → empty: a known pen's next tap is
     * a first scan that continues after its newest logged entry) and its writes into the treatment log.
     */
    val pens: PensModule by lazy {
        val info = NotificationGroups.Group.INFO

        fun id(kind: PenNotificationPorts.Kind) =
            if (kind == PenNotificationPorts.Kind.Read) NotificationIds.PEN_RESULTS else NotificationIds.PEN_CHECK

        fun channel(kind: PenNotificationPorts.Kind) =
            if (kind ==
                PenNotificationPorts.Kind.Read
            ) {
                Channels.PEN_RESULTS
            } else {
                Channels.PEN_CHECK
            }
        PensModule(
            context = app,
            store = data.typedStore("pens", Ledger.serializer(), Ledger(), whenCorrupt = Ledger()),
            logChanges = data.treatments.between(Instant.EPOCH, Instant.parse("2200-01-01T00:00:00Z")),
            log =
                object : PenLog {
                    override suspend fun all() = data.treatments.changedSince(Instant.EPOCH)

                    override fun canWrite() = treatments.log.penMayWrite()

                    override suspend fun store(record: TreatmentRecord) = treatments.log.storeFromPen(record)
                },
            settings = data.settings,
            clock = clock,
            roleSource = role.data.map { it.role },
            scope = scope,
            ports =
                PenNotificationPorts(
                    readsChannel = Channels.PEN_RESULTS,
                    checkChannel = Channels.PEN_CHECK,
                    group = info.key,
                    post = { kind, n -> NotificationGroups.post(app, id(kind), n, info, channel(kind)) },
                    cancel = { kind -> NotificationGroups.cancel(app, id(kind), info) },
                ),
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
            step("integrations") {
                integrations.load()
                integrations.start(scope)
            }
            step("pens") { pens.start() }
            // a Libre tap opens the new-sensor flow only on the wearer's phone reading the sensor itself (the row
            // shows only then: never on and hidden)
            step("sensor tap") {
                scope.launch {
                    combine(role.data, data.settings.flow(SensorSource)) { r, source ->
                        r.role == Role.Wearer && SensorSourceKind.fromId(source) != SensorSourceKind.Juggluco
                    }.distinctUntilChanged()
                        .collect { allowed -> if (!allowed) sensor.tapLaunch.set(false) }
                }
            }
            // the alerts' source follows *Settings from* (the wearer's alerts, or rules run here)
            step("settingsFrom") { scope.launch { followerSettings.keepAlertSource() } }
            // sharing runs while this phone is the wearer (needs an identity: created / restored in Settings › Sharing)
            step("share") { scope.launch { shareOwner.ownerEngine.followRole(role.data.map { it.role }, scope) } }
            step("follower") {
                follower.engine.load()
                // a follow that ended (declined, invite used, removed) arms no "no data" alarm for a stream that won't come
                scope.launch {
                    follower.engine.state.map { it.ended }.distinctUntilChanged().collect { ended ->
                        if (ended && role.data.first().role == Role.Follower) resetMonitoring()
                    }
                }
            }
        }

    /**
     * The role's one reading source, or null when nothing is set up to monitor (then the service doesn't run).
     * Wearer: a debug build's synthetic source when switched on, else the sensor source once a sensor is paired (or
     * Juggluco is the source). Follower: the follower engine.
     */
    private suspend fun readingSource(role: Role): ReadingSource? =
        when (role) {
            Role.Wearer -> BuildVariant.wearerSource(this) ?: sensorSource()
            Role.Follower -> follower.engine.takeIf { it.isFollowing() }
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
        val SensorSource = SchemaSettings.text("source.source", SensorSourceKind.Direct.id, SensorSourceKind.entries.map { it.id }.toSet())
    }
}
