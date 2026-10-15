// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Foreground service: Juggluco (localhost) → [NostrPublisher] → relays. Keeps running with the screen off.
 *
 * Power: no polling and no alarms. Work is driven by Juggluco's SSE push; each reading takes a ≤ 3 s partial
 * wake lock so the live event (and, every 5th minute, the chunk 2 s later) leaves before the CPU sleeps again.
 * Relay sockets have no ping timer (liveness comes from the per-event OK timeout).
 */
class PublisherService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var publisher: NostrPublisher
    private lateinit var source: JugglucoSource
    private lateinit var wake: PowerManager.WakeLock
    private val http = OkHttpClient.Builder().build()   // pingInterval = 0 (default)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Publisher", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notification("starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cgmnostr:publish").apply { setReferenceCounted(false) }

        val stream = CgmStream.loadOrCreate(filesDir)
        val link = stream.followerLink()
        File(filesDir, "follower-link.txt").writeText(link + "\n")
        Status.update { it.copy(npub = stream.npub, followerLink = link) }
        Status.log("source ${stream.npub}, ${stream.relays.size} relays")
        scope.launch {
            val enc = stream.sourceNcryptsec(NIP49_TEST_PASSWORD)
            Log.i(Status.TAG, "NIP-49 ncryptsec of S (password '$NIP49_TEST_PASSWORD'): $enc")
            Status.update { it.copy(ncryptsec = enc) }
        }

        publisher = NostrPublisher(stream, http, scope)
        val url = getSharedPreferences("cfg", MODE_PRIVATE).getString("juggluco_url", DEFAULT_JUGGLUCO)!!
        source = JugglucoSource(url, http)
        source.start(scope, sink)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        source.stop()
        publisher.close()
        scope.cancel()
        if (wake.isHeld) wake.release()
        Status.update { it.copy(juggluco = "stopped") }
        super.onDestroy()
    }

    private val sink = object : JugglucoSource.Sink {
        override fun onReading(r: Reading) {
            val now = System.currentTimeMillis()
            wake.acquire(10_000)
            Status.update { it.copy(last = r, lastReceivedMs = now, jugglucoError = null) }
            runCatching { publisher.onReading(r, now) }.onFailure { Status.log("publish failed: $it") }
            // live leaves at once and its OK comes back in ~0.3 s; a due chunk follows 2 s later → 3 s is enough.
            // (10 s above is only the safety cap; holding it every minute would keep the CPU up 1/6 of the time.)
            scope.launch { delay(3_000); if (wake.isHeld) wake.release() }
            Status.log("reading ${r.sgv} mg/dL rate ${r.rate} (t-${now / 1000 - r.t}s)")
            notify("${r.sgv} mg/dL · ${Status.state.value.liveSent} live sent")
        }

        override fun onHistory(readings: List<Reading>) {
            if (readings.isEmpty()) return
            wake.acquire(60_000)   // backfill: 12 chunks × 2 s per relay
            val newest = readings.maxBy { it.t }
            Status.update { it.copy(last = it.last?.takeIf { l -> l.t >= newest.t } ?: newest) }
            runCatching { publisher.onHistory(readings) }.onFailure { Status.log("backfill failed: $it") }
        }

        override fun onStatus(state: String, error: String?) {
            Status.update { it.copy(juggluco = state, jugglucoError = error) }
            if (error != null) Status.log("juggluco: $state — $error")
            if (error != null) notify("Juggluco: $state")
        }
    }

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, notification(text))

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("CGM Nostr")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val CHANNEL = "publisher"
        const val DEFAULT_JUGGLUCO = "http://127.0.0.1:17580"
        const val NIP49_TEST_PASSWORD = "cgm-poc3"
    }
}
