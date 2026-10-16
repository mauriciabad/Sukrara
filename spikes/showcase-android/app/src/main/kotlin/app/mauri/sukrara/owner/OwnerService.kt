// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import app.mauri.sukrara.App
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.wearer.WearerExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The wearer's one foreground service (one process): owner engine (Nostr + Bluetooth followers) and the local
 * features ([WearerExtras]: status bar, legacy Mac link, Garmin server, LibreView). Its notification IS the glucose
 * notification (Live Update chip + number icon), so the status bar costs no extra post.
 *
 * connectedDevice (not dataSync: capped at 6 h/day on 15+); no polling: work is driven by the glucose source.
 */
class OwnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var extras: WearerExtras? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Features.init(this)
        GlucoseNotification.createChannel(this)
        startForeground(GlucoseNotification.ID, GlucoseNotification.build(this, null, false, false, "starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val engine = App.owner(this)
        extras = WearerExtras(this, scope) {
            val u = engine.ui.value
            val f = Features.value
            val n = u.followers.count { it.revoked == null }
            "$n follower${if (n == 1) "" else "s"}" + (if (!f.nostr) " · Nostr off" else "") +
                (if (u.bleCentrals > 0) " · ${u.bleCentrals} nearby" else "")
        }.also { it.start() }
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        App.owner(this).stop()
        extras?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
