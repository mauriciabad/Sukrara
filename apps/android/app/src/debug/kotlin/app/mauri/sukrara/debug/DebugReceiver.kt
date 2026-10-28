// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.mauri.sukrara.RoleRecord
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.graph
import app.mauri.sukrara.monitor.MonitoringService
import app.mauri.sukrara.monitor.service.StartReason
import app.mauri.sukrara.pen.PenReadActivity
import app.mauri.sukrara.pen.opennov.PenDose
import app.mauri.sukrara.pen.opennov.PenRead
import kotlinx.coroutines.launch

class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action ?: return
        val handled = DebugFakes.run(context, action, intent)
        if (!handled) Log.w(TAG, "unknown debug action $action")
    }

    private companion object {
        const val TAG = "SukraraDebug"
    }
}

/**
 * Every debug command that replaces the sensor or shows / writes made-up data (CLAUDE.md: debug builds only, this
 * whole source set is compiled out of release).
 * `adb shell am broadcast -n app.mauri.sukrara/.debug.DebugReceiver -a app.mauri.sukrara.debug.<NAME> …`.
 */
object DebugFakes {
    private const val PREFIX = "app.mauri.sukrara.debug."

    private val commands =
        mapOf<String, (Context, Intent) -> Unit>(
            PREFIX + "SYNTHETIC" to ::synthetic,
            PREFIX + "PEN_READ" to ::penRead,
            PREFIX + "PEN_TAGS" to ::penTags,
        )

    /** Runs [action] when it is a known fake; false otherwise. */
    fun run(
        context: Context,
        action: String,
        intent: Intent,
    ): Boolean {
        val command = commands[action] ?: return false
        command(context, intent)
        return true
    }

    /**
     * `SYNTHETIC --ez on true|false [--ez frozen true] [--ei cycle M] [--ei endsInMin N] [--ei warmupMin W]`: the synthetic wearer source replaces the sensor (and makes this
     * phone the wearer if setup hasn't chosen a role), or goes away again. `cycle` puts "now" at minute M of the curve's
     * 3-hour cycle (135 = its lowest point, 45 mg/dL; 45 = its highest); `endsInMin` makes the sensor end N minutes from now
     * (the sensor-end warning shows from 6 h before); `warmupMin` makes it a new sensor warming up for W more minutes (no
     * readings, the warm-up countdown on the card and the notification; ready → readings start). The monitoring service restarts with the
     * new source; from the background Android may refuse that start (then "Monitoring stopped" shows: open the app).
     */
    private fun synthetic(
        context: Context,
        intent: Intent,
    ) {
        val on = intent.getBooleanExtra("on", true)
        val graph = context.graph
        graph.scope.launch {
            graph.ready.await()
            val cycleMinute = intent.getIntExtra("cycle", -1)
            val shift = if (cycleMinute >= 0) SyntheticSource.shiftFor(graph.clock.now(), cycleMinute) else 0L
            val endsInMin = intent.getIntExtra("endsInMin", -1)
            val endsAt = if (endsInMin >= 0) graph.clock.now() + java.time.Duration.ofMinutes(endsInMin.toLong()) else null
            val warmupMin = intent.getIntExtra("warmupMin", -1)
            val readyAt = if (warmupMin > 0) graph.clock.now() + java.time.Duration.ofMinutes(warmupMin.toLong()) else null
            SyntheticSource.set(
                context,
                on,
                frozen = intent.getBooleanExtra("frozen", false),
                shift = shift,
                endsAt = endsAt,
                readyAt = readyAt,
            )
            if (on) graph.role.update { if (it.role == null) RoleRecord(Role.Wearer) else it }
            context.stopService(Intent(context, MonitoringService::class.java))
            graph.monitor.starter.start(StartReason.App)
        }
    }

    /**
     * `PEN_READ [--es doses "-9:8.5,-8:1"] [--es serial AB1234567] [--ez first true]`: a made-up NovoPen read (minutes
     * from now : units) goes through the real pen path (ledger, engine, treatment log) and its card shows over the
     * current app. Emulators have no NFC: this also shows the pen surfaces there. `first` = a first read 10 min ago
     * (nothing older is logged), so the doses that follow are new.
     */
    private fun penRead(
        context: Context,
        intent: Intent,
    ) {
        val graph = context.graph
        graph.scope.launch {
            graph.ready.await()
            graph.pens.fakeNfc.value = true
            val now = graph.clock.now().epochSecond
            val penNow = 400 * 86400L
            val serial = intent.getStringExtra("serial") ?: "AB1234567"

            fun read(at: Long, doses: List<Pair<Long, Double>>) =
                PenRead(serial, "NovoPen", at, penNow + (at - now), doses.map { (t, u) -> PenDose(penNow - (now - t), t, u, 0x08000000) })
            // an earlier half-unit dose identifies the pen as an Echo Plus (an empty read would be the untested NovoPen 6)
            if (intent.getBooleanExtra("first", false)) graph.pens.pens.onRead(read(now - 600, listOf(now - 86400L to 4.5)))
            val doses =
                (intent.getStringExtra("doses") ?: "-1:1,-1:8.5").split(',').mapIndexed { i, d ->
                    val (min, u) = d.split(':')
                    (now + min.toLong() * 60 + i) to u.toDouble()
                }
            graph.pens.pens.onRead(read(now, doses))
            // the read card is enabled a moment after fakeNfc (or never while reading is Off): a refused start is only logged
            kotlinx.coroutines.delay(500)
            runCatching {
                context.startActivity(
                    Intent(context, PenReadActivity::class.java)
                        .putExtra(PenReadActivity.PREVIEW, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Log.w("SukraraDebug", "pen card not shown", it) }
        }
    }

    /**
     * `PEN_TAGS --ez blocked true|false`: shows the *Pen taps are blocked* state (home banner, Settings › Insulin pens)
     * as if Android blocked Sukrara's tag intents; real NFC is untouched. Also shows the pen surfaces on an emulator
     * without NFC. The screens read it on resume.
     */
    private fun penTags(
        context: Context,
        intent: Intent,
    ) {
        val graph = context.graph
        graph.pens.fakeNfc.value = true
        graph.pens.fakeTagsBlocked.value = intent.getBooleanExtra("blocked", true)
        graph.home.refresh()
    }
}
