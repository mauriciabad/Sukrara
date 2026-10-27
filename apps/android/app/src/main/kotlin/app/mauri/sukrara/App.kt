// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import android.content.Context
import app.mauri.sukrara.feature.treatments.TreatmentsHost
import app.mauri.sukrara.feature.treatments.TreatmentsModule
import app.mauri.sukrara.monitor.AlertsHost
import app.mauri.sukrara.monitor.MonitorHost
import app.mauri.sukrara.monitor.MonitorModule
import app.mauri.sukrara.monitor.alerts.AlertsModule
import app.mauri.sukrara.share.ShareOwnerModule
import app.mauri.sukrara.share.owner.requests.ShareOwnerHost

/**
 * Builds the [AppGraph] and hands the runtime modules to their Android components (service, receivers): one `XxxHost`
 * interface per runtime module, each member one line delegating to the graph.
 */
class App :
    Application(),
    MonitorHost,
    AlertsHost,
    TreatmentsHost,
    ShareOwnerHost {
    lateinit var graph: AppGraph
        private set

    override val monitor: MonitorModule get() = graph.monitor

    override val alerts: AlertsModule get() = graph.alerts

    override suspend fun awaitReady() = graph.ready.await()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.ready.start()
    }

    override val treatments: TreatmentsModule get() = graph.treatments

    override val shareOwner: ShareOwnerModule get() = graph.shareOwner
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
