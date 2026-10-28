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
     * `SYNTHETIC --ez on true|false [--ez frozen true] [--ei cycle M] [--ei endsInMin N]`: the synthetic wearer source replaces the sensor (and makes this
     * phone the wearer if setup hasn't chosen a role), or goes away again. `cycle` puts "now" at minute M of the curve's
     * 3-hour cycle (135 = its lowest point, 45 mg/dL; 45 = its highest); `endsInMin` makes the sensor end N minutes from now
     * (the sensor-end warning shows from 6 h before). The monitoring service restarts with the
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
            SyntheticSource.set(context, on, frozen = intent.getBooleanExtra("frozen", false), shift = shift, endsAt = endsAt)
            if (on) graph.role.update { if (it.role == null) RoleRecord(Role.Wearer) else it }
            context.stopService(Intent(context, MonitoringService::class.java))
            graph.monitor.starter.start(StartReason.App)
        }
    }
}
