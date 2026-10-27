// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Is one of the graph widgets on the home screen? The home screen offers "Add the widget" while none is placed
 * (FEATURES.md "Add-the-widget banner"). Placed = the launcher bound an id to the combined or the graph widget
 * (AppWidgetManager.getAppWidgetIds); the small value widget isn't the main surface. Checked when the home screen
 * resumes ([refresh]), when a widget is added or removed, and on the pin request's success callback.
 */
class WidgetPresence(
    private val context: Context,
) {
    private val placedState = MutableStateFlow<Boolean?>(null)

    /** Null until the first [refresh] (nothing flashes before it). */
    val placed: StateFlow<Boolean?> = placedState.asStateFlow()

    fun refresh() {
        val mgr = AppWidgetManager.getInstance(context)
        placedState.value =
            BIG.any { receiver ->
                runCatching { mgr.getAppWidgetIds(ComponentName(context, receiver)) }.getOrNull()?.isNotEmpty() == true
            }
    }

    /** Whether the launcher can place a widget from the app ([pin]); else the banner explains how to add it by hand. */
    fun canPin(): Boolean = runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /**
     * Asks the launcher to place the combined widget (its picker preview shows in the dialog). Needs a foreground
     * [activity]. Returns false when the launcher refused at once; success arrives later on [PinnedReceiver].
     */
    fun pin(activity: Activity): Boolean {
        val callback =
            PendingIntent.getBroadcast(
                activity,
                0,
                Intent(activity, PinnedReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return runCatching {
            AppWidgetManager.getInstance(activity).requestPinAppWidget(ComponentName(activity, BIG.first()), null, callback)
        }.onFailure { Log.w(TAG, "pin: $it") }.getOrDefault(false)
    }

    /** requestPinAppWidget's success callback: the widget was placed. */
    class PinnedReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            context.widgetsHost.widgets.presence
                .refresh()
        }
    }

    companion object {
        private const val TAG = "Sukrara.widgets"

        /** The widgets that make the banner unnecessary, the one the banner places first. */
        val BIG: List<Class<*>> = listOf(CombinedWidgetReceiver::class.java, GraphWidgetReceiver::class.java)
    }
}
