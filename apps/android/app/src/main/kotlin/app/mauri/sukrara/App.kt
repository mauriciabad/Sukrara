// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import app.mauri.sukrara.feature.treatments.TreatmentsHost
import app.mauri.sukrara.feature.treatments.TreatmentsModule
import app.mauri.sukrara.monitor.AlertsHost
import app.mauri.sukrara.monitor.MonitorHost
import app.mauri.sukrara.monitor.MonitorModule
import app.mauri.sukrara.monitor.alerts.AlertsModule
import app.mauri.sukrara.pen.PensHost
import app.mauri.sukrara.pen.PensModule
import app.mauri.sukrara.share.ShareOwnerModule
import app.mauri.sukrara.share.owner.requests.ShareOwnerHost
import app.mauri.sukrara.widgets.WidgetsHost
import app.mauri.sukrara.widgets.WidgetsModule
import kotlinx.coroutines.launch

/**
 * Builds the [AppGraph] and hands the runtime modules to their Android components (service, receivers): one `XxxHost`
 * interface per runtime module, each member one line delegating to the graph.
 */
class App :
    Application(),
    MonitorHost,
    AlertsHost,
    TreatmentsHost,
    ShareOwnerHost,
    WidgetsHost,
    PensHost {
    lateinit var graph: AppGraph
        private set

    override val monitor: MonitorModule get() = graph.monitor

    override val alerts: AlertsModule get() = graph.alerts

    override suspend fun awaitReady() = graph.ready.await()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.ready.start()
        locales = resources.configuration.locales
    }

    private var locales: LocaleList? = null

    /** A language change renames the notification channels and redraws the glucose notification at once. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.locales == locales) return
        locales = newConfig.locales
        graph.scope.launch {
            graph.ready.await()
            graph.monitor.onLocaleChanged()
        }
    }

    override val treatments: TreatmentsModule get() = graph.treatments

    override val shareOwner: ShareOwnerModule get() = graph.shareOwner

    override val widgets: WidgetsModule get() = graph.widgets

    override val pens: PensModule get() = graph.pens
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
