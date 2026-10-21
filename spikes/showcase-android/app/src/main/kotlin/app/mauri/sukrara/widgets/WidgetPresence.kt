// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
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

/**
 * Is one of Sukrara's graph widgets on the home screen? Sukrara is meant to be used through the big widget, so the
 * home screen offers to add it ([WidgetBanner]) while none is placed.
 *
 * - Placed = the launcher has bound an id to the provider: AppWidgetManager.getAppWidgetIds(provider), "the list of
 *   appWidgetIds that have been bound to the given AppWidget provider" (developer.android.com/reference/android/appwidget/AppWidgetManager#getAppWidgetIds(android.content.ComponentName)).
 *   Only the graph widgets count (glucose + graph, and the graph alone); the small value widget and the lock screen
 *   widget (SystemUI hosts it) aren't the main surface.
 * - Added from the app with requestPinAppWidget (developer.android.com/develop/ui/views/appwidgets/discoverability#pin).
 *   No custom preview extra: the launcher's dialog then shows the widget picker's preview, on Android 15+ the generated
 *   one (setWidgetPreview, "used ... for previewing the widget in the widget picker and pin app widget flow"; Glance
 *   providePreview, published by [WidgetUpdater.publishPreviews]), drawn at the widget's real size. A live
 *   EXTRA_APPWIDGET_PREVIEW (Glance requestPinGlanceAppWidget) was tried and dropped: one fixed-size RemoteViews that
 *   Pixel Launcher stretched into the 4×3 frame. It returns false when the launcher can't pin
 *   (isRequestPinAppWidgetSupported, or a profile restricted on the home screen); the success callback
 *   ([PinnedReceiver]) fires only when the widget is created, never on a refusal.
 * - Checked again whenever the home screen resumes (the launcher's dialog sits on top of it) and on that callback.
 */
object WidgetPresence {
    private const val TAG = "Sukrara.widgets"

    /** The widgets that make the banner unnecessary: the big one first (the one the banner pins). */
    val BIG: List<Class<*>> = listOf(CombinedWidgetReceiver::class.java, GraphWidgetReceiver::class.java)

    data class State(val placed: Boolean = true, val pinSupported: Boolean = true, val dismissed: Boolean = true, val pinFailed: Boolean = false)

    // optimistic defaults: nothing flashes before the first check
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun prefs(c: Context) = c.getSharedPreferences("widget-banner", Context.MODE_PRIVATE)

    /** Placed if any [BIG] provider has a bound id. */
    fun placed(idsPerProvider: List<IntArray>): Boolean = idsPerProvider.any { it.isNotEmpty() }

    fun refresh(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = BIG.map { runCatching { mgr.getAppWidgetIds(ComponentName(c, it)) }.getOrDefault(IntArray(0)) }
        val placed = placed(ids)
        val supported = runCatching { mgr.isRequestPinAppWidgetSupported }.getOrDefault(false)
        _state.value = _state.value.copy(placed = placed, pinSupported = supported,
            dismissed = prefs(c).getBoolean("dismissed", false), pinFailed = _state.value.pinFailed && !placed)
    }

    fun dismiss(c: Context) {
        prefs(c).edit().putBoolean("dismissed", true).apply()
        _state.value = _state.value.copy(dismissed = true)
    }

    /** Debug builds: show the banner again (DebugReceiver `widgetBanner`). */
    fun undismiss(c: Context) {
        prefs(c).edit().remove("dismissed").apply()
        refresh(c)
    }

    /** Asks the launcher to place the big widget. Needs a foreground activity (else IllegalStateException). */
    fun pin(a: Activity): Boolean {
        val callback = PendingIntent.getBroadcast(a, 0, Intent(a, PinnedReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ok = runCatching {
            AppWidgetManager.getInstance(a).requestPinAppWidget(ComponentName(a, BIG.first()), null, callback)
        }.onFailure { Log.w(TAG, "pin: $it") }.getOrDefault(false)
        if (!ok) _state.value = _state.value.copy(pinFailed = true)
        return ok
    }

    /** requestPinAppWidget's success callback: the widget was created. */
    class PinnedReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "widget pinned")
            refresh(context)
        }
    }
}

/** What the home screen shows about the widget. */
enum class WidgetBanner {
    HIDDEN,
    /** The pill that pins the big widget. */
    PIN,
    /** The launcher can't pin: how to add it by hand. */
    MANUAL;

    companion object {
        /**
         * Shown on the wearer's and the follower's home (both have widgets) while no graph widget is placed, until
         * dismissed. The dismissal is for good: the user said no. Placing a widget isn't a dismissal, so if it's
         * removed later (or the launcher is reset, or a new phone), the banner is back.
         */
        fun of(role: String?, s: WidgetPresence.State): WidgetBanner = when {
            role == null || s.placed || s.dismissed -> HIDDEN
            !s.pinSupported || s.pinFailed -> MANUAL
            else -> PIN
        }
    }
}
