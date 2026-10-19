// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.follower

import app.mauri.sukrara.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.LocalServers
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.widgets.SukraraHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.drop

/**
 * The follower role's local features, fed by the follower DB (gap audit 2026-10-16: before this they existed only
 * for the wearer):
 *  - **home-screen widgets** (inv. 12): read `follower.db` on start, then every stored batch;
 *  - **status bar** (inv. 05 approaches 1 + 2): the service's only notification is the same Live Update chip /
 *    number icon the wearer gets, showing the followed person's value;
 *  - **local servers** ([LocalServers]): Garmin on 127.0.0.1:17580 (a follower phone has no Juggluco) and the
 *    Nightscout read API on :17581 (entries + the treatments received from the wearer), so a parent's watch / Home
 *    Assistant can read the followed person.
 * Started and stopped by [FollowerService]; nothing polls (screen-on + one minute ticker while the screen is on).
 */
class FollowerExtras(private val ctx: Context, private val scope: CoroutineScope) {
    private val engine = app.mauri.sukrara.App.follower(ctx)
    private val local = LocalServers(ctx, "follower-garmin", { false }, { engine.treatments() }, "Sukrara (following)")
    private val jobs = mutableListOf<Job>()
    @Volatile private var screenOn = ctx.getSystemService(PowerManager::class.java).isInteractive

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (screenOn) { render(force = true); SukraraHooks.refresh(ctx, "screen on") }
        }
    }

    fun start() {
        GlucoseNotification.createChannel(ctx)
        jobs += scope.launch(Dispatchers.IO) {
            seed()
            engine.onReadings = { list, reason -> onReadings(list, reason) }
        }
        ctx.registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        local.apply(null, Features.value)
        jobs += scope.launch {
            var old = Features.value
            Features.state.collect { f -> if (f != old) { val o = old; old = f; local.apply(o, f); render() } }
        }
        // the wearer's sensor ended / a new one started (live ss/se/sw): the notification and the status bar follow
        jobs += scope.launch { app.mauri.sukrara.core.SensorLife.current.drop(1).collect { render() } }
        // Re-render the notification when what it shows changes (status, newest reading, transport).
        jobs += scope.launch {
            engine.ui.map { Triple(it.status, it.last?.t, it.lastVia) }.distinctUntilChanged().collect { render() }
        }
        // Age / staleness ("123?") and widget age text: once a minute, only while the screen is on.
        jobs += scope.launch {
            while (isActive) {
                delay(60_000)
                if (screenOn) { render(); SukraraHooks.refresh(ctx, "minute") }
            }
        }
        SukraraHooks.publishPreviewsOnce(ctx)
        // uploads the followed person only after the user's own choice on the Tidepool page (docs/27)
        app.mauri.sukrara.tidepool.Tidepool.start(ctx, scope)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        engine.onReadings = null
        runCatching { ctx.unregisterReceiver(screen) }
        local.stop()
        app.mauri.sukrara.tidepool.Tidepool.stop()
    }

    /** The follower DB is the source of truth: the local servers get its last 24 h, the widgets re-read it. */
    private fun seed() {
        val rows = engine.history(Proto.nowS() - 24 * 3600)
        local.xdrip.clear()
        rows.forEach { local.xdrip.add(Bridge.Reading(it.t, it.sgv, it.rate), persist = false) }
        SukraraHooks.reload(ctx, "follower start")
    }

    private fun onReadings(list: List<Reading>, reason: String) {
        list.forEach { local.add(Bridge.Reading(it.t, it.sgv, it.rate)) }
        SukraraHooks.onReadings(ctx, list, "follower $reason")
    }

    /** Deduped and ≤ 1/s ([GlucoseNotification.post]); [force] on screen on (the card's pictures wait for it). */
    fun render(force: Boolean = false) {
        runCatching {
            val (r, chip, icon, text) = content()
            GlucoseNotification.post(ctx, GlucoseNotification.contentKey(ctx, r, chip, icon, text) + engine.ui.value.status, force,
                build = { content().let { (r2, c2, i2, t2) -> GlucoseNotification.build(ctx, r2, c2, i2, t2) } })
        }.onFailure { android.util.Log.w("Sukrara.follower", "notification: $it") }
    }

    private data class Content(val r: Reading?, val chip: Boolean, val icon: Boolean, val text: String)

    private fun content(): Content {
        val u = engine.ui.value
        val f = Features.value
        return when (u.status) {
            FollowerState.ACTIVE -> Content(u.last?.let { Reading(it.t, it.sgv, it.rate) }, f.chip, f.icon,
                u.lastVia?.let { ctx.getString(R.string.notif_following_via, it.removePrefix("relay:")) } ?: ctx.getString(R.string.notif_following))
            FollowerState.REQUESTED -> Content(null, false, false, ctx.getString(R.string.notif_waiting_approval, u.code))
            FollowerState.REVOKED -> Content(null, false, false, ctx.getString(R.string.notif_access_removed))
            else -> Content(null, false, false, ctx.getString(R.string.notif_not_following))
        }
    }
}
