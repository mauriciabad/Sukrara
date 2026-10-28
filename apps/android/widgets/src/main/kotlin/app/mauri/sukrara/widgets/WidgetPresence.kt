// Sukrara — https://github.com/mauriciabad/sukrara
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Is one of the graph widgets on the home screen? The home screen offers "Add the widget" while none is placed
 * (FEATURES.md "Add-the-widget banner"). Placed = the launcher bound an id to the combined or the graph widget
 * (AppWidgetManager.getAppWidgetIds); the small value widget isn't the main surface. Checked when the home screen
 * resumes ([refresh]), when a widget is added or removed, and on the pin request's success callback.
 *
 * Placing it from the app is `requestPinAppWidget` with the widget's generated preview ([pinPreview]), so the
 * launcher's dialog shows the real card instead of a blank one. When the launcher can't pin (not supported, or it
 * refused the request) [canPin] is false and the banner says how to add the widget by hand.
 */
class WidgetPresence(
    private val context: Context,
    /** The app's scope: the preview is composed before the request goes. */
    private val scope: CoroutineScope,
) {
    private val placedState = MutableStateFlow<Boolean?>(null)

    /** Null until the first [refresh] (nothing flashes before it). */
    val placed: StateFlow<Boolean?> = placedState.asStateFlow()

    /** The launcher refused a pin request in this process (a profile restricted on the home screen, …). */
    @Volatile private var refused = false

    fun refresh() {
        val mgr = AppWidgetManager.getInstance(context)
        placedState.value =
            BIG.any { receiver ->
                runCatching { mgr.getAppWidgetIds(ComponentName(context, receiver)) }.getOrNull()?.isNotEmpty() == true
            }
    }

    /** Whether the launcher can place a widget from the app ([pin]); else the banner explains how to add it by hand. */
    fun canPin(): Boolean =
        !refused && runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /**
     * Asks the launcher to place the combined widget, its preview in the dialog. Needs [activity] in the foreground
     * (the request goes once the preview is composed, a few ms). [onRefused] runs when the launcher refused at once
     * ([canPin] is false from then on); success arrives later on [PinnedReceiver].
     */
    fun pin(
        activity: Activity,
        onRefused: () -> Unit,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            val extras = pinPreview(activity, WidgetKind.COMBINED)
            val callback =
                PendingIntent.getBroadcast(
                    activity,
                    0,
                    Intent(activity, PinnedReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            // only the launcher's own "no" counts as refused (a request that failed, e.g. the screen was left
            // meanwhile, keeps the Add button for the next try)
            val ok =
                runCatching {
                    AppWidgetManager.getInstance(activity).requestPinAppWidget(ComponentName(activity, BIG.first()), extras, callback)
                }.onFailure { Log.w(TAG, "pin: $it") }
                    .getOrNull() ?: return@launch
            if (!ok) {
                refused = true
                onRefused()
            }
        }
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
