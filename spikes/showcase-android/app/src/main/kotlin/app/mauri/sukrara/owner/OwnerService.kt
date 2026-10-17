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
 * features ([WearerExtras]: status bar, Garmin server, LibreView). Its notification IS the glucose
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
        // Investigation 15: alerts on the wearer's readings; rapid insulin and carbs from the shared log; fired alerts → followers
        app.mauri.sukrara.alerts.Alerts.attach(this, "wearer",
            doses = { app.mauri.sukrara.alerts.AlertEngine.doses(app.mauri.sukrara.treatments.Treat.store.all()) },
            publish = { engine.publishAlert(it) }, newest = engine.history(app.mauri.sukrara.core.Proto.nowS() - 86_400).lastOrNull())
        app.mauri.sukrara.treatments.Treat.store.onChange += alertsOnTreatment
        engine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    private val alertsOnTreatment: (app.mauri.sukrara.treatments.Treatment, Long) -> Unit =
        { _, _ -> app.mauri.sukrara.alerts.Alerts.check(this) }

    override fun onDestroy() {
        app.mauri.sukrara.treatments.Treat.store.onChange -= alertsOnTreatment
        app.mauri.sukrara.alerts.Alerts.detach()
        App.owner(this).stop()
        extras?.stop()
        scope.cancel()
        super.onDestroy()
    }
}
