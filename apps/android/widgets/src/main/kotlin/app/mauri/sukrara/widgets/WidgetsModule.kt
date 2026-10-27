// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.PowerManager
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.graph.GraphConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The home-screen widgets' objects (apps/android/README.md "DI"): built by `AppGraph` from what the widgets read.
 * [glucose] = the hub's state; [treatments] = recent treatments; [graphConfig] = the graph settings;
 * [freshnessTicks] = monitor's freshness steps; [store] = the typed state file "widgets". [start] once storage is
 * ready.
 */
class WidgetsModule(
    private val context: Context,
    private val scope: CoroutineScope,
    clock: Clock,
    glucose: Flow<GlucoseState>,
    treatments: Flow<List<Treatment>>,
    graphConfig: Flow<GraphConfig>,
    freshnessTicks: Flow<Unit>,
    store: StateStore<WidgetPlacements>,
) {
    val settings = WidgetSettingsStore(store)

    val presence = WidgetPresence(context)

    val updater =
        WidgetUpdater(
            scope = scope,
            clock = clock,
            glucose = glucose,
            treatments = treatments,
            config = graphConfig,
            placements = settings.placements,
            freshnessTicks = freshnessTicks,
            screenOn = screenOn(context),
            dark = { context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES },
            redraw = ::redrawPlaced,
        )

    fun start() {
        presence.refresh()
        updater.start()
    }

    /** Placements were removed: once storage is [ready], forget their settings; recheck the banner. */
    fun forget(
        appWidgetIds: IntArray,
        ready: suspend () -> Unit,
    ): Job {
        presence.refresh()
        return scope.launch {
            ready()
            settings.forget(appWidgetIds)
        }
    }

    /** One placement's settings changed (Reconfigure): save them, then the updater redraws it. */
    suspend fun saveOverrides(
        appWidgetId: Int,
        overrides: WidgetOverrides,
    ) = settings.set(appWidgetId, overrides)

    /** Redraws every placed widget, one update per placement (never a blanket updateAll per reading). */
    private suspend fun redrawPlaced() {
        val manager = GlanceAppWidgetManager(context)
        for (kind in WidgetKind.entries) {
            val widget = kind.widget()
            for (id in manager.getGlanceIds(widget.javaClass)) {
                runCatching { widget.update(context, id) }.onFailure { Log.w(TAG, "redraw ${kind.name}: $it") }
            }
        }
    }

    private companion object {
        const val TAG = "Sukrara.widgets"

        /** Screen on / off from the system broadcasts (registered while the app runs), starting from now. */
        fun screenOn(context: Context): Flow<Boolean> =
            callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(
                            c: Context,
                            intent: Intent,
                        ) {
                            trySend(intent.action == Intent.ACTION_SCREEN_ON)
                        }
                    }
                val filter = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                trySend(context.getSystemService(PowerManager::class.java).isInteractive)
                awaitClose { context.unregisterReceiver(receiver) }
            }.distinctUntilChanged()
    }
}

/** The `Application` gives the widgets' Android components (receivers, the Reconfigure activity) their module. */
interface WidgetsHost {
    val widgets: WidgetsModule

    /** Completes when the app's storage and modules are ready. */
    suspend fun awaitReady()
}

internal val Context.widgetsHost: WidgetsHost get() = applicationContext as WidgetsHost
