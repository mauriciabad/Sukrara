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
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.status.GlucoseNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the follower's relay subscriptions with the screen off (an FGS keeps network access in Doze). Push only:
 * relays deliver ~1 live event/min; liveness and the "no data" alarm come from [WatchdogReceiver].
 */
class FollowerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?) = null

    private var extras: FollowerExtras? = null

    override fun onCreate() {
        super.onCreate()
        // Same notification as the wearer's (status-bar chip + number icon), showing the followed person's value.
        GlucoseNotification.createChannel(this)
        startForeground(GlucoseNotification.ID, GlucoseNotification.build(this, null, false, false, "following…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        Features.init(this)
        val engine = App.follower(this)
        engine.start()
        extras = FollowerExtras(this, scope).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        extras?.stop(); extras = null
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
