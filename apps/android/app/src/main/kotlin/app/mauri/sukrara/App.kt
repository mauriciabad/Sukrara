// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import android.content.Context
import app.mauri.sukrara.feature.treatments.TreatmentsHost
import app.mauri.sukrara.feature.treatments.TreatmentsModule

class App :
    Application(),
    TreatmentsHost {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.ready.start()
    }

    override val treatments: TreatmentsModule get() = graph.treatments
}

/** The graph from any Android component of the app (activities, services, receivers). */
val Context.graph: AppGraph get() = (applicationContext as App).graph
