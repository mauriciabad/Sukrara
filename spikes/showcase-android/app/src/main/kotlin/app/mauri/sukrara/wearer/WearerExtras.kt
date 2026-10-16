// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.wearer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.MacLink
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
import org.json.JSONObject

/**
 * The wearer's local features around the owner engine, all driven by [Live] (no polling of their own):
 * status-bar notification (chip + icon), legacy Mac GATT link, Garmin xDrip server, LibreView uploader.
 * Each one follows its [Features] switch live.
 */
class WearerExtras(private val ctx: Context, private val scope: CoroutineScope, private val detail: () -> String) : Live.Listener {
    private val tag = "Sukrara.wearer"
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val mac = MacLink(ctx)
    /** Garmin (17580, if free) + Nightscout API (17581) over the wearer's readings; same files as before (`garmin-*`). */
    private val local by lazy {
        LocalServers(ctx, "garmin", { Features.value.source == "juggluco" }, { Treat.store.all() }, "Sukrara")
    }
    private var jobs = mutableListOf<Job>()
    @Volatile private var lastPostMs = 0L
    @Volatile private var screenOn = ctx.getSystemService(PowerManager::class.java).isInteractive

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            Live.status.update { it.copy(screenOn = screenOn) }
            if (screenOn) { render(force = true); SukraraHooks.refresh(ctx, "screen on") }
        }
    }

    fun start() {
        Treat.init(ctx)
        GlucoseNotification.createChannel(ctx)
        Live.listener = this
        ctx.registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        Live.status.update { it.copy(screenOn = screenOn) }
        apply(null, Features.value)
        jobs += scope.launch {
            var old = Features.value
            Features.state.collect { f -> if (f != old) { val o = old; old = f; apply(o, f) } }
        }
        jobs += scope.launch { mac.status.collect { s ->
            Live.status.update { it.copy(macRunning = s.running, macAdvertising = s.advertising, macSubscribed = s.subscribed, macError = s.error) }
        } }
        jobs += scope.launch { Treat.uploader.status.collect { st -> Live.status.update { it.copy(libreview = st) } } }
        jobs += scope.launch { Treat.store.items.collect { TreatmentFeed.update(it) } }   // the charts' treatments
        jobs += Treat.uploader.start(scope)
        // Staleness ("123?") and age text when readings stop: at most one re-post per minute, only without readings.
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
        runCatching { ctx.unregisterReceiver(screen) }
        mac.stop()
        local.stop()
        RootStatusBar.stop(ctx)
    }

    /** Apply switches; [old] == null on start. */
    private fun apply(old: FeatureState?, f: FeatureState) {
        if (old == null || old.macBle != f.macBle) { if (f.macBle) mac.start() else mac.stop() }
        local.apply(old, f)   // Garmin 17580 (retried on a source change) + Nightscout API 17581
        if (old == null || old.libreview != f.libreview || old.libreviewIntervalMin != f.libreviewIntervalMin) Treat.uploader.kick()
        if (old != null && (old.chip != f.chip || old.icon != f.icon || old.screenOffSkip != f.screenOffSkip ||
                old.systemIcons != f.systemIcons || old.hook != f.hook || old.hookPosition != f.hookPosition ||
                old.hookColor != f.hookColor)) render(force = true)
    }

    // ============================================================================================ Live.Listener

    override fun onReading(r: Reading) {
        val b = Bridge.Reading(r.t, r.sgv, r.rate)
        local.add(b)
        if (Features.value.macBle) mac.onReading(b)
        render(force = false)
        SukraraHooks.onReadings(ctx, listOf(r), "reading")
    }

    override fun onHistory(list: List<Reading>) {
        val b = list.sortedBy { it.t }.map { Bridge.Reading(it.t, it.sgv, it.rate) }
        b.forEach { local.add(it) }
        mac.onHistory(b)
        render(force = false)
        SukraraHooks.onReadings(ctx, list, "history")
    }

    override fun onSensor(serial: String, startEpoch: Long) {
        local.onSensor(serial, startEpoch)
        Treat.uploader.onSensor(serial, startEpoch)
    }

    override fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {
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
        runCatching { nm.notify(GlucoseNotification.ID, GlucoseNotification.build(ctx, r, f.chip, f.icon, detail())) }
        runCatching { RootStatusBar.update(ctx, f, r) }   // system icons (root) + SystemUI hook (LSPosed)
        lastPostMs = System.currentTimeMillis()
        Live.status.update { it.copy(notificationPosts = it.notificationPosts + 1) }
    }
}
