// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import android.content.Context
import app.mauri.sukrara.widgets.WidgetsHost
import app.mauri.sukrara.widgets.WidgetsModule

class App :
    Application(),
    WidgetsHost {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.ready.start()
    }

    override val widgets: WidgetsModule get() = graph.widgets

    override suspend fun awaitReady() = graph.ready.await()
}

/** The graph from any Android component of the app (activities, services, receivers). */
val Context.graph: AppGraph get() = (applicationContext as App).graph
