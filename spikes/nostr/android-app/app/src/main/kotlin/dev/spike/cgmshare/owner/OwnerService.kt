// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.owner

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import dev.spike.cgmshare.App
import dev.spike.cgmshare.core.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the owner engine alive with the screen off. connectedDevice (not dataSync: capped at 6 h/day on 15+);
 * its prerequisite is CHANGE_NETWORK_STATE, a normal permission. No polling: work is driven by the glucose
 * source (SSE push) and a 5-minute chunk tick that only runs when the CPU is awake anyway.
 */
class OwnerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_SERVICE,
            Notifications.build(this, Notifications.SERVICE, "CGM Share", "Sharing your glucose…", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val engine = App.owner(this)
        engine.start()
        scope.launch {
            engine.ui.map { u -> "${u.last?.sgv ?: "–"} mg/dL · epoch ${u.epoch} · ${u.followers.count { it.revoked == null }} follower(s)" }
                .distinctUntilChanged()
                .collect { Notifications.post(this@OwnerService, Notifications.ID_SERVICE, Notifications.SERVICE, "CGM Share · owner", it, ongoing = true) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        App.owner(this).stop()
        scope.cancel()
        super.onDestroy()
    }
}
