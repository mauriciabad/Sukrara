// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.wearer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import app.mauri.sukrara.bridge.AppBroadcasts
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.HealthConnect
import app.mauri.sukrara.bridge.LocalServers
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.libreview.LibreViewPayload
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.status.RootStatusBar
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentFeed
import app.mauri.sukrara.widgets.SukraraHooks
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop
import org.json.JSONObject

/**
 * The wearer's local features around the owner engine, all driven by [Live] (no polling of their own):
 * status-bar notification (chip + icon), Garmin xDrip server, LibreView uploader, the apps' broadcasts and Health
 * Connect (investigation 22; wearer only: a follower never broadcasts).
 * Each one follows its [Features] switch live.
 */
class WearerExtras(private val ctx: Context, private val scope: CoroutineScope, private val detail: () -> String) : Live.Listener {
    private val tag = "Sukrara.wearer"
    /** Garmin (17580, if free) + Nightscout API (17581) over the wearer's readings; same files as before (`garmin-*`). */
    private val local by lazy {
        LocalServers(ctx, "garmin", { Features.value.source == "juggluco" }, { Treat.store.all() }, "Sukrara")
    }
    private var jobs = mutableListOf<Job>()
    @Volatile private var lastPostMs = 0L
    @Volatile private var screenOn = ctx.getSystemService(PowerManager::class.java).isInteractive

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (SukraraHooks.onClockChange(ctx, i) { render(force = true) }) return
            // an unlock may bring a sensor end reminder forward (USER_PRESENT can't be declared in the manifest)
            if (i.action == Intent.ACTION_USER_PRESENT) {
                runCatching { app.mauri.sukrara.sensorend.SensorEnd.unlocked(ctx) }.onFailure { Log.w(tag, "unlock: $it") }
                return
            }
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            Live.status.update { it.copy(screenOn = screenOn) }
            if (screenOn) { render(force = true); SukraraHooks.refresh(ctx, "screen on") }
        }
    }

    fun start() {
        Treat.init(ctx)
        GlucoseNotification.createChannel(ctx)
        Live.listener = this
        ctx.registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            SukraraHooks.CLOCK_ACTIONS.forEach(::addAction) })
        Live.status.update { it.copy(screenOn = screenOn) }
        apply(null, Features.value)
        jobs += scope.launch {
            var old = Features.value
            Features.state.collect { f -> if (f != old) { val o = old; old = f; apply(o, f) } }
        }
        jobs += scope.launch { Treat.uploader.status.collect { st -> Live.status.update { it.copy(libreview = st) } } }
        jobs += scope.launch { Treat.store.items.collect { TreatmentFeed.update(it) } }   // the charts' treatments
        jobs += Treat.uploader.start(scope)
        app.mauri.sukrara.tidepool.Tidepool.start(ctx, scope)   // off until the user signs in and switches it on (docs/27)
        app.mauri.sukrara.nightscout.Nightscout.start(ctx, scope)   // off until the user sets up a site (docs/25); wearer only
        // the sensor ended / a new one started: the notification and the status bar follow at once (round sensor-life)
        jobs += scope.launch { app.mauri.sukrara.core.SensorLife.current.drop(1).collect { render(force = true) } }
        // The freshness steps without a reading (struck at 5 min, "no readings" at 20 min): FreshnessTicker's alarm.
        app.mauri.sukrara.status.FreshnessTicker.onStep = { render(force = true) }
        jobs += scope.launch(kotlinx.coroutines.Dispatchers.IO) { app.mauri.sukrara.status.FreshnessTicker.replan(ctx) }
        // A safety net for the age-based parts: at most one re-post per minute, only without readings.
        jobs += scope.launch {
            while (isActive) {
                delay(60_000)
                if (System.currentTimeMillis() - lastPostMs > 90_000) render(force = false)
                if (screenOn) SukraraHooks.refresh(ctx, "minute")   // widget age text; deduped per minute
            }
        }
        render(force = true)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (Live.listener === this) Live.listener = null
        app.mauri.sukrara.status.FreshnessTicker.onStep = null
        runCatching { ctx.unregisterReceiver(screen) }
        local.stop()
        app.mauri.sukrara.tidepool.Tidepool.stop()
        app.mauri.sukrara.nightscout.Nightscout.stop()
        RootStatusBar.stop(ctx)
    }

    /** Apply switches; [old] == null on start. */
    private fun apply(old: FeatureState?, f: FeatureState) {
        local.apply(old, f)   // Garmin 17580 (retried on a source change) + Nightscout API 17581
        if (old == null || old.libreview != f.libreview || old.libreviewIntervalMin != f.libreviewIntervalMin) Treat.uploader.kick()
        if (old != null && (old.chip != f.chip || old.icon != f.icon || old.screenOffSkip != f.screenOffSkip ||
                old.systemIcons != f.systemIcons || old.hook != f.hook || old.hookPosition != f.hookPosition ||
                old.hookColor != f.hookColor || old.hideNotifIcon != f.hideNotifIcon)) render(force = true)
        // The module drops our notification icon on SystemUI's next icon-list rebuild: one more post once it knows.
        if (old != null && (old.hideNotifIcon != f.hideNotifIcon || old.hook != f.hook)) RootStatusBar.repostLater(ctx)
    }

    // ============================================================================================ Live.Listener

    override fun onReading(r: Reading) {
        val b = Bridge.Reading(r.t, r.sgv, r.rate)
        local.add(b)
        render(force = false)
        SukraraHooks.onReadings(ctx, listOf(r), "reading")
        app.mauri.sukrara.alerts.Alerts.onReading(ctx, r)
        app.mauri.sukrara.nightscout.Nightscout.kick()   // every reading goes up at once (a no-op while it's off)
        // investigation 22: the allowed apps' broadcasts + Health Connect (both off until the user turns them on). Off the
        // caller's thread: the direct source delivers on the main thread, and these read prefs, query PackageManager
        // and build Health Connect records.
        outputs.execute {
            runCatching { AppBroadcasts.onReading(ctx, r) }.onFailure { Log.w(tag, "broadcasts: $it") }
            runCatching { HealthConnect.onReading(ctx, r) }.onFailure { Log.w(tag, "health connect: $it") }
        }
    }

    override fun onHistory(list: List<Reading>) {
        val b = list.sortedBy { it.t }.map { Bridge.Reading(it.t, it.sgv, it.rate) }
        b.forEach { local.add(it) }
        render(force = false)
        SukraraHooks.onReadings(ctx, list, "history")
        app.mauri.sukrara.nightscout.Nightscout.kick()
        outputs.execute { runCatching { HealthConnect.onHistory(ctx, list) }.onFailure { Log.w(tag, "health connect: $it") } }
    }

    override fun onSensor(serial: String, startEpoch: Long) {
        local.onSensor(serial, startEpoch)
        // LibreView takes the Libre 3's sensor and glucose only (SensorProfile.libreView): never another family's
        Treat.uploader.onSensor(serial, startEpoch, libreView = app.mauri.sukrara.core.SensorOutputs.libreView(app.mauri.sukrara.core.ActiveSensor.profile.value))
    }

    override fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {
        if (!app.mauri.sukrara.core.SensorOutputs.libreView(app.mauri.sukrara.core.ActiveSensor.profile.value)) return
        if (history.isNotEmpty()) Treat.uploader.onHistory(history)
        current?.let { Treat.uploader.onCurrent(it) }
    }

    /** A treatment a follower (writer) sent over v2: into the shared log → Mac link + LibreView (if enabled). */
    override fun onFollowerTreatment(tr: JSONObject) {
        runCatching {
            val k = tr.optString("k").takeIf { it in Treatment.KINDS } ?: return
            Treat.store.merge(Treatment(id = tr.getString("id"), k = k, a = tr.optDouble("a", 0.0), t = tr.getLong("t"),
                n = Treatment.clip(tr.optString("n")), u = tr.optLong("u", System.currentTimeMillis()),
                del = tr.optBoolean("del", false), o = "follower"))
            Treat.uploader.kick()
        }.onFailure { Log.w(tag, "follower treatment not merged: $it") }
    }

    // ============================================================================================ status bar

    @Synchronized
    fun render(force: Boolean) {
        val f = Features.value
        if (!force && f.screenOffSkip && !screenOn) {
            Live.status.update { it.copy(notificationSkipped = it.notificationSkipped + 1) }
            return
        }
        val r = Live.status.value.last
        val text = detail()
        // Deduped and ≤ 1/s (a BLE backfill sends a history packet every few ms); built from the newest state.
        GlucoseNotification.post(ctx, GlucoseNotification.contentKey(ctx, r, f.chip, f.icon, text), force, build = {
            val fl = Features.value
            GlucoseNotification.build(ctx, Live.status.value.last, fl.chip, fl.icon, detail())
        }) {
            lastPostMs = System.currentTimeMillis()
            Live.status.update { it.copy(notificationPosts = it.notificationPosts + 1) }
        }
        runCatching { RootStatusBar.update(ctx, f, r) }   // system icons (root) + SystemUI hook (LSPosed)
    }

    private companion object {
        /** Investigation 22's outputs, in reading order and never on the main thread (one per process). */
        val outputs: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}
