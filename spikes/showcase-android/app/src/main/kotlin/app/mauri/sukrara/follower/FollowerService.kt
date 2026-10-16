// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.follower

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Holds the follower's relay subscriptions with the screen off (an FGS keeps network access in Doze). Push only:
 * relays deliver ~1 live event/min; liveness and the "no data" alarm come from [WatchdogReceiver].
 */
class FollowerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_SERVICE,
            Notifications.build(this, Notifications.SERVICE, "Sukrara", "Following…", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val engine = App.follower(this)
        engine.start()
        scope.launch {
            engine.ui.map { u ->
                when (u.status) {
                    "active" -> "${u.last?.sgv ?: "–"} mg/dL · ${u.last?.via?.removePrefix("relay:") ?: "waiting for data"}"
                    "requested" -> "Waiting for approval · code ${u.code}"
                    "revoked" -> "Access removed"
                    else -> "Not following anyone"
                }
            }.distinctUntilChanged().collect {
                Notifications.post(this@FollowerService, Notifications.ID_SERVICE, Notifications.SERVICE, "Sukrara · follower", it, ongoing = true)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        App.follower(this).stop()
        scope.cancel()
        super.onDestroy()
    }
}

/** AlarmManager target: staleness check + "no data for N min" alarm. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { App.follower(context).watchdog() } finally { pending.finish() }
        }
    }
}
