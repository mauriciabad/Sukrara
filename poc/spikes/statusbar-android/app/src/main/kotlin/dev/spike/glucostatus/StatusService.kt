// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground service: follows a glucose source and pushes every reading to all enabled status-bar surfaces. */
class StatusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sourceJob: Job? = null
    private lateinit var cfg: Config
    private val slot by lazy { SlotOutput(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        GlucoseNotification.createChannel(this)
        cfg = Config.load(this)
        startForeground(GlucoseNotification.ID, GlucoseNotification.build(this, cfg, null, false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // Another app's Live Update appeared/disappeared → re-post with/without the promotion request.
        scope.launch {
            Hub.state.map { it.otherLiveUpdates.isEmpty() }.distinctUntilChanged().collect { render() }
        }
        // Age text and staleness move on even without new readings.
        scope.launch { while (isActive) { delay(60_000); render() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val old = cfg
        cfg = Config.load(this)
        if (sourceJob == null || old.demo != cfg.demo) startSource()
        if (old.slot && !cfg.slot) scope.launch { slot.stop() }
        render()
        return START_STICKY
    }

    private fun startSource() {
        sourceJob?.cancel()
        val source = if (cfg.demo) DemoSource() else JugglucoSource()
        sourceJob = source.start(scope,
            onReading = { r ->
                if (System.currentTimeMillis() > Hub.state.value.pinnedUntilMs) {
                    Hub.state.update { it.copy(reading = r) }; render()
                }
            },
            onStatus = { s -> Hub.state.update { it.copy(source = s) } })
    }

    @Synchronized
    private fun render() {
        val r = Hub.state.value.reading
        val yielding = cfg.yieldChip && Hub.state.value.otherLiveUpdates.isNotEmpty()
        getSystemService(NotificationManager::class.java)
            .notify(GlucoseNotification.ID, GlucoseNotification.build(this, cfg, r, promote = cfg.chip && !yielding))
        // The hook gets the config even when disabled, so turning it off hides the view.
        HookBridge.send(this, cfg, r)
        if (cfg.slot && r != null) scope.launch { slot.show(r) }
    }

    override fun onDestroy() {
        slot.stop()
        HookBridge.send(this, cfg.copy(hook = false), null)
        scope.cancel()
        Hub.state.update { it.copy(source = "stopped") }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, StatusService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, StatusService::class.java))
    }
}
