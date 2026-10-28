// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.service

import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.StopReason
import app.mauri.sukrara.monitor.hub.GlucoseHubImpl

/** Why the service is being started (logs, and which paths may start it). */
enum class StartReason { App, Boot, PackageReplaced, TimeChanged, HealthCheck, AlertCheck, Resume }

enum class StartResult {
    Started,

    /** Setup isn't done or nothing is set up to monitor: no service, no "stopped" notice. */
    NotNeeded,

    /** Android refused: the hub says Stopped and the person sees "Monitoring stopped — tap to resume". */
    Denied,
}

/** Starts the foreground service; [launch] returns false when Android refused it. */
fun interface ServiceLauncher {
    fun launch(): Boolean
}

/** The "Monitoring stopped — tap to resume" notice (channel `status`). */
interface StoppedNotice {
    fun show()

    fun cancel()
}

/**
 * The only way the monitoring service is started (app open, boot, update, time change, health check, the alert check
 * of the alert check, the notice's tap). Starting a running service is harmless, so every path just calls [start].
 * A refusal is never swallowed: the hub shows Stopped and the notice offers to resume.
 */
class MonitorStarter(
    private val hub: GlucoseHubImpl,
    private val hasSomethingToMonitor: suspend () -> Boolean,
    private val launcher: ServiceLauncher,
    private val notice: StoppedNotice,
    private val log: (String) -> Unit = {},
) {
    suspend fun start(reason: StartReason): StartResult {
        if (hub.state.value.role == null || !hasSomethingToMonitor()) {
            // nothing to resume any more: an earlier "stopped" goes away
            if (hub.state.value.service is ServiceState.Stopped) hub.setService(ServiceState.NotRunning)
            notice.cancel()
            return StartResult.NotNeeded
        }
        return if (launcher.launch()) {
            notice.cancel()
            StartResult.Started
        } else {
            denied()
            StartResult.Denied
        }.also { log("start ($reason): $it") }
    }

    /** The service itself couldn't go to the foreground (or Android refused the start). */
    fun denied() {
        hub.setService(ServiceState.Stopped(StopReason.StartDenied))
        notice.show()
    }
}
