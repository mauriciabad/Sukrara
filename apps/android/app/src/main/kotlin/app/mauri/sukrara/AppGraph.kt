// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import app.mauri.sukrara.core.data.DataModule
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DefaultDispatchers
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SystemClock
import app.mauri.sukrara.feature.sharing.SharingDeps
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.widgets.WidgetPlacements
import app.mauri.sukrara.widgets.WidgetsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Duration

/**
 * The app's object graph (manual DI, docs/ARCHITECTURE.md §Android DI).
 *
 * - Each area owns ONE module class in its own Gradle module (e.g. `DataModule` in `:core:data`, `MonitorModule` in
 *   `:monitor`) that builds its objects from constructor parameters. `AppGraph` only composes them: one
 *   `val x by lazy { XModule(…) }` line per module, then the feature `XxxDeps` it implements delegate to them.
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

    val data by lazy { DataModule(app, appVersion, dispatchers, clock) }

    // the hub's state and the alert checklist come from :monitor / the alert runtime once they are composed here
    val home by lazy {
        HomeModule(app, data, clock, scope, MutableStateFlow(GlucoseState.Initial), readinessSource = null, unmuteAlerts = {}, resume = {})
    }

    // the hub's state, the graph settings and monitor's freshness ticks join when :monitor / settings are composed here
    val widgets by lazy {
        WidgetsModule(
            context = app,
            scope = scope,
            clock = clock,
            glucose = MutableStateFlow(GlucoseState.Initial),
            treatments = data.treatments.between(clock.now().minus(Duration.ofDays(2)), clock.now().plus(Duration.ofDays(365))),
            graphConfig = flowOf(GraphConfig()),
            freshnessTicks = emptyFlow(),
            store = data.typedStore("widgets", WidgetPlacements.serializer(), WidgetPlacements(), WidgetPlacements()),
        )
    }

    /**
     * Loads what the modules need from disk, once, off the main thread. [App] starts it; anything that reads state
     * first does `graph.ready.await()` (cheap once done).
     */
    val ready: Deferred<Unit> =
        scope.async(dispatchers.io, CoroutineStart.LAZY) {
            // modules' load calls go here, in dependency order
            data.ready()
            widgets.start()
        }
}
