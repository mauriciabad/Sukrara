// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import app.mauri.sukrara.core.data.DataModule
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DefaultDispatchers
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SystemClock
import app.mauri.sukrara.feature.home.HomeDeps
import app.mauri.sukrara.feature.sharing.SharingDeps
import app.mauri.sukrara.monitor.MonitorModule
import app.mauri.sukrara.monitor.hub.MonitorRecord
import app.mauri.sukrara.sensor.SensorModule
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.pairing.ReceiverIdState
import app.mauri.sukrara.sensor.source.SensorSourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The app's object graph (DI, CHANGES A4). Each area is ONE module class built here by one `by lazy` line from the
 * modules below it; [ready] loads them from disk in dependency order. Feature `XxxDeps` are implemented by delegating
 * to the modules. Order: data → sensor → monitor → (alerts 11, share 13, …).
 */
class AppGraph(
    val app: Application,
    val dispatchers: DispatcherProvider = DefaultDispatchers,
    val clock: Clock = SystemClock,
) : HomeDeps, SharingDeps {
    val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    override val appVersion: String = BuildConfig.VERSION_NAME

    // ---- modules (one line each; construction does no I/O) ----

    val data by lazy { DataModule(app, appVersion, dispatchers, clock) }

    /** The phone's role, chosen in first-run setup (chunk 24 writes it); null = setup not done. */
    val role by lazy { data.typedStore("role", RoleRecord.serializer(), RoleRecord(), whenCorrupt = RoleRecord()) }

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
        )
    }

    /**
     * Loads what the modules need from disk, once, off the main thread. [App] starts it; anything that reads state
     * first does `graph.ready.await()` (cheap once done).
     */
    val ready: Deferred<Unit> =
        scope.async(dispatchers.io, CoroutineStart.LAZY) {
            data.ready()
            monitor.ready()
        }

    /**
     * The role's one reading source, or null when nothing is set up to monitor (then the service doesn't run).
     * Wearer: a debug build's synthetic source when switched on, else the sensor source once a sensor is paired (or
     * Juggluco is the source). Follower: the follower engine (chunk 25).
     */
    private suspend fun readingSource(role: Role): ReadingSource? =
        when (role) {
            Role.Wearer -> BuildVariant.wearerSource(this) ?: sensorSource()

            Role.Follower -> null
        }

    private suspend fun sensorSource(): ReadingSource? {
        val juggluco = SensorSourceKind.fromId(data.settings.flow(SensorSource).first()) == SensorSourceKind.Juggluco
        // a damaged pairing still runs the source: it shows "scan the sensor again"
        val paired = sensor.pairings.load() != PairingLoad.None
        return if (juggluco || paired) RunWhileAttached(sensor.source, sensor.source::start, sensor.source::stop) else null
    }

    private companion object {
        val SensorSource = SchemaSettings.text("source.source", SensorSourceKind.Libre3.id, SensorSourceKind.entries.map { it.id }.toSet())
    }
}
