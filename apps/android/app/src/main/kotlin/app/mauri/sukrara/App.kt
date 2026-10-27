// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import android.content.Context
import app.mauri.sukrara.monitor.MonitorHost
import app.mauri.sukrara.monitor.MonitorModule

/** Builds the [AppGraph] and hands the runtime modules to their Android components (service, receivers). */
class App :
    Application(),
    MonitorHost {
    lateinit var graph: AppGraph
        private set

    override val monitor: MonitorModule get() = graph.monitor

    override suspend fun awaitReady() = graph.ready.await()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.ready.start()
    }
}

val Context.graph: AppGraph get() = (applicationContext as App).graph
