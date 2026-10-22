// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.alerts.AlertNotifier
import app.mauri.sukrara.core.Notifications
import app.mauri.sukrara.status.FreshnessTicker
import app.mauri.sukrara.status.GlucoseNotification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.follower.FollowerService
import app.mauri.sukrara.owner.OwnerService
import app.mauri.sukrara.treatments.TreatmentFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Glue between investigation 12's widgets and Sukrara: readings from the wearer pipeline (`WearerExtras`) or the
 * follower DB (`FollowerExtras`) update [RecentHistory] and trigger [WidgetUpdater.refresh] (deduped; skipped while
 * the screen is off). The role's [TreatmentFeed] goes the same way ([watchTreatments]). Widget receivers make sure
 * the role's service runs.
 */
object SukraraHooks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The system's broadcasts for a change of the clock's look: Settings sends ACTION_TIME_CHANGED when the 24-hour
     * format is switched (EXTRA_TIME_PREF_24_HOUR), and the time zone. Each role's receiver (WearerExtras,
     * FollowerExtras) listens for them and redraws the notification and the widgets at once ([onClockChange]);
     * [app.mauri.sukrara.graph.Clock.signature] in the redraw keys makes those redraws real.
     */
    val CLOCK_ACTIONS = listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)

    /** A role's receiver got [i]: a clock change redraws through [render] and the widgets; true = handled. */
    fun onClockChange(c: Context, i: Intent, render: () -> Unit): Boolean {
        if (i.action !in CLOCK_ACTIONS) return false
        render()
        refresh(c, "clock", force = true)
        return true
    }

    fun onReadings(c: Context, list: List<Reading>, reason: String) {
        if (list.isEmpty()) return
        val app = c.applicationContext
        scope.launch {
            if (runCatching { RecentHistory.add(app, list) }.getOrDefault(false)) WidgetUpdater.refreshSafely(app, reason)
            FreshnessTicker.replan(app)   // the next freshness step of the newest reading
        }
    }

    /**
     * Re-read the role's store and redraw: follower start, and stop following (the DB was just wiped, so the widgets
     * show "No data" instead of the previous person's last value).
     */
    fun reload(c: Context, reason: String) {
        RecentHistory.reload()
        refresh(c, reason, force = true)
        val app = c.applicationContext
        scope.launch { FreshnessTicker.replan(app) }
    }

    /**
     * Once per process (App.onCreate): every change of the [TreatmentFeed] refreshes the widgets (same dedupe and
     * screen-off skip as readings, so no extra wake-ups). The feed's initial empty value means "not loaded yet", not
     * "no treatments", so the widgets keep the role store's treatments until the role publishes its own.
     */
    fun watchTreatments(c: Context) {
        val app = c.applicationContext
        scope.launch {
            var first = true
            TreatmentFeed.items.collect { list ->
                if (first && list.isEmpty()) { first = false; return@collect }
                first = false
                if (runCatching { RecentHistory.setTreatments(app, list) }.getOrDefault(false)) refresh(app, "treatments")
            }
        }
    }

    /**
     * Once per process: the widgets bake their colours into bitmaps, so a dark theme flip (e.g. the automatic
     * schedule) redraws them at once, including the lock screen widget's automatic appearance, instead of at the
     * next reading. A language change (per-app language) renames the notification channels and redraws the words.
     */
    fun watchConfiguration(c: Context) {
        val app = c.applicationContext
        fun night() = app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        var last = night()
        var lastLocales = app.resources.configuration.locales
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
                val now = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
                if (newConfig.locales != lastLocales) {
                    // the app's language changed: channel names and every drawn word follow
                    lastLocales = newConfig.locales
                    Notifications.channels(app)
                    AlertNotifier.channels(app)
                    GlucoseNotification.createChannel(app)
                    last = now
                    refresh(app, "language", force = true)
                } else if (now != last) { last = now; refresh(app, "night mode") }
            }
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        })
    }

    /**
     * A graph widget was placed or resized: measure its real size ([WidgetProbe]) once Glance's own update for the
     * new options has landed, then redraw. If the screen is off, the next refresh with the screen on measures it.
     */
    fun remeasure(c: Context, appWidgetId: Int) {
        val app = c.applicationContext
        WidgetProbe.forget(app, intArrayOf(appWidgetId))
        WidgetUpdater.allowProbe(appWidgetId)
        scope.launch {
            delay(1_500)
            WidgetUpdater.refreshSafely(app, "resized", force = true)
        }
    }

    /** DebugReceiver `cmd widgetprobe`: measure every graph widget again. */
    fun remeasureAll(c: Context) = WidgetProbe.placed(c).forEach { remeasure(c, it) }

    fun refresh(c: Context, reason: String, force: Boolean = false) {
        val app = c.applicationContext
        scope.launch {
            WidgetUpdater.refreshSafely(app, reason, force)
            // a mute started, changed or ended: the next redraw of its countdown (FreshnessTicker.next, round f31)
            runCatching { FreshnessTicker.replan(app) }
        }
    }

    /** Android 15+ picker previews; once per app version (the system rate-limits the call). */
    fun publishPreviewsOnce(c: Context) {
        val p = c.getSharedPreferences("widgets", Context.MODE_PRIVATE)
        val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0)
        if (p.getLong("previewsFor", -1) == v) return
        scope.launch {
            val r = runCatching { WidgetUpdater.publishPreviews(c.applicationContext) }.getOrElse { it.message ?: "?" }
            Log.i("Sukrara.widgets", "previews: $r")
            p.edit().putLong("previewsFor", v).apply()
        }
    }

    /** A widget was added/updated: the role's service feeds it, so make sure it runs. */
    fun ensureService(c: Context) {
        runCatching { startRoleService(c) }.onFailure { Log.w("Sukrara.widgets", "service not started from widget: $it") }
        refresh(c, "widget", force = true)
    }

    /** Wearer → OwnerService (if it has an identity); follower → FollowerService (if it joined someone). */
    fun startRoleService(c: Context) {
        when (App.role(c)) {
            "owner" -> if (App.owner(c).hasIdentity()) c.startForegroundService(Intent(c, OwnerService::class.java))
            "follower" -> if (App.follower(c).hasJoined()) c.startForegroundService(Intent(c, FollowerService::class.java))
        }
    }
}

/**
 * Restart the role's service (wearer or follower) after a reboot or an app update (both are allowed to start a
 * foreground service). Before inv. 10's gap audit only the wearer came back; a follower stayed silent until opened.
 */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { SukraraHooks.startRoleService(c) }.onFailure { Log.w("Sukrara.start", "service not started on ${i.action}: $it") }
    }
}
