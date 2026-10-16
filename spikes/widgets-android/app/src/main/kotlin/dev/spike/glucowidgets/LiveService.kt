// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucowidgets

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pushes widget updates as readings arrive (the system's own periodic update is ≥ 30 min).
 *
 * Follows the source (Juggluco SSE or synthetic) and calls [WidgetUpdater.refresh] on each new reading, plus a
 * ticker on the reading's age-minute boundaries so "3 min ago"/stale keeps moving when readings stop. The ticker
 * only runs while the screen is on; widget renders are skipped while it is off and caught up on SCREEN_ON.
 * In the showcase app this service already exists (its glucose FGS): only the two hooks are needed.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sourceJob: Job? = null
    private var tickJob: Job? = null

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_ON) {
                refresh("screen on")
                startTicker()
            } else {
                tickJob?.cancel()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(screen, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        running.value = true
        startTicker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sourceJob == null || intent?.action == ACTION_RESTART) startSource(clear = intent?.action == ACTION_RESTART)
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refresh("configuration") // dark mode: the graph bitmap has baked colours
    }

    override fun onDestroy() {
        running.value = false
        unregisterReceiver(screen)
        scope.cancel()
        super.onDestroy()
    }

    /** [clear]: source changed — drop the old history only after the old source has fully stopped. */
    private fun startSource(clear: Boolean = false) {
        val old = sourceJob
        sourceJob = scope.launch {
            old?.cancelAndJoin()
            if (clear) GlucoseStore.replace(this@LiveService, emptyList())
            Config.load(this@LiveService).newSource().start(this,
                onReadings = { list -> if (GlucoseStore.add(this@LiveService, list)) refresh("reading") },
                onStatus = { status.value = it; Log.i(TAG, it) })
        }
    }

    private fun refresh(reason: String) {
        scope.launch { WidgetUpdater.refresh(this@LiveService, reason) }
    }

    /** Wakes at latest.epoch + n·60 s (+1 s) — the instants where the age text changes. CPU sleep pauses it. */
    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                val last = GlucoseStore.history.value.lastOrNull()?.epochSec ?: nowSec()
                val nowMs = System.currentTimeMillis()
                val sinceMs = nowMs - last * 1000
                delay(60_000 - Math.floorMod(sinceMs, 60_000L) + 1_000)
                WidgetUpdater.refresh(this@LiveService, "tick")
            }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Widget updates", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.arrow_18)
            .setContentTitle("Keeping glucose widgets up to date")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "widgets"
        const val ACTION_RESTART = "dev.spike.glucowidgets.RESTART"
        val status = MutableStateFlow("stopped")
        val running = MutableStateFlow(false)

        /** Background starts (widget broadcasts) may be refused by the FGS rules; RefreshWorker covers that. */
        fun tryStart(c: Context, reason: String, restart: Boolean = false) {
            try {
                c.startForegroundService(Intent(c, LiveService::class.java).apply {
                    if (restart) action = ACTION_RESTART
                })
            } catch (e: Exception) {
                Log.w(TAG, "can't start LiveService ($reason): $e")
                status.value = "not started ($reason): ${e.javaClass.simpleName}"
            }
        }

        fun stop(c: Context) = c.stopService(Intent(c, LiveService::class.java))

        fun widgetCount(c: Context): Int {
            val mgr = AppWidgetManager.getInstance(c)
            return listOf(ValueWidgetReceiver::class.java, GraphWidgetReceiver::class.java,
                CombinedWidgetReceiver::class.java).sumOf { mgr.getAppWidgetIds(ComponentName(c, it)).size }
        }

        fun stopIfNoWidgets(c: Context) {
            if (widgetCount(c) == 0) {
                stop(c)
                RefreshWorker.cancel(c)
            }
        }
    }
}
