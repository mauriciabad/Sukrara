// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import java.util.TreeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Turns readings into protocol events and hands them to the relays.
 *
 *  - every new reading → one live event (kind 21078) to every relay, immediately (piggybacks on the wake-up
 *    that delivered the reading);
 *  - the reading's hour becomes "dirty"; the current hour's chunk (kind 30078) is republished when it was last
 *    sent ≥ 5 min ago, a finished hour is published as soon as the next hour starts;
 *  - bulk history (startup / reconnect after an outage) marks every hour whose content changed as dirty and
 *    publishes those chunks right away — startup therefore backfills the last [BACKFILL_HOURS] hours, paced
 *    by each relay's writer (2 s apart);
 *  - a 5-minute coroutine tick flushes leftovers (it does not wake the device; it runs when the CPU is up).
 */
class NostrPublisher(private val stream: CgmStream, http: OkHttpClient, private val scope: CoroutineScope) {
    private val relays = stream.relays.map { RelayConnection(it, http, scope) }
    private val readings = TreeMap<Long, Reading>()          // t → reading, last ~13 h
    private val dirty = sortedSetOf<Long>()                  // hour starts with unpublished changes
    private val lastChunkSentAt = HashMap<Long, Long>()      // hour → unix s of last publish
    private val lastChunkCreatedAt = HashMap<Long, Long>()   // hour → created_at used (must strictly increase)
    private var newestLiveT = 0L

    var liveSent = 0; private set
    var chunksSent = 0; private set

    init {
        scope.launch {
            while (isActive) {
                delay(CHUNK_EVERY_S * 1000)
                synchronized(this@NostrPublisher) { flush(force = false) }
            }
        }
    }

    @Synchronized
    fun onHistory(list: List<Reading>) {
        val now = nowS()
        val minT = hourOf(now) - (BACKFILL_HOURS - 1) * 3600
        var changed = 0
        list.filter { it.t >= minT && it.t <= now + 60 }.forEach { if (merge(it)) changed++ }
        newestLiveT = maxOf(newestLiveT, readings.lastEntry()?.key ?: 0L)
        prune(now)
        Status.log("history: ${list.size} entries, $changed new/changed, ${dirty.size} hour chunk(s) to publish")
        flush(force = true)
    }

    @Synchronized
    fun onReading(r: Reading, receivedMs: Long) {
        val now = nowS()
        merge(r)
        if (r.t > newestLiveT && now - r.t < LIVE_MAX_AGE_S) {
            newestLiveT = r.t
            val ev = stream.liveEvent(r, now)
            val out = Outgoing(ev.id().toHex(), ev.asJson(), null, now, receivedMs)
            relays.forEach { it.enqueue(out) }
            liveSent++
        }
        prune(now)
        flush(force = false)
    }

    /** Insert/replace; returns true if the hour's content changed (keeps the copy that has a rate). */
    private fun merge(r: Reading): Boolean {
        val old = readings[r.t]
        if (old == r || (old != null && old.sgv == r.sgv && r.rate == null)) return false
        readings[r.t] = r
        dirty += hourOf(r.t)
        return true
    }

    private fun prune(now: Long) {
        val cutoff = hourOf(now) - BACKFILL_HOURS * 3600
        readings.headMap(cutoff).clear()
        dirty.removeIf { it < cutoff }
        lastChunkSentAt.keys.removeIf { it < cutoff - 3600 }
        lastChunkCreatedAt.keys.removeIf { it < cutoff - 3600 }
    }

    /** Publish dirty chunks; newest hour first so followers get the most useful data soonest. */
    private fun flush(force: Boolean) {
        val now = nowS()
        val current = hourOf(now)
        for (h in dirty.descendingSet().toList()) {
            val due = force || h < current || now - (lastChunkSentAt[h] ?: 0) >= CHUNK_EVERY_S
            if (!due) continue
            val rows = readings.subMap(h, h + 3600).values
            if (rows.isEmpty()) { dirty -= h; continue }
            val createdAt = maxOf(now, (lastChunkCreatedAt[h] ?: 0) + 1)
            val ev = stream.chunkEvent(h, rows, createdAt)
            val out = Outgoing(ev.id().toHex(), ev.asJson(), stream.chunkId(h), createdAt, System.currentTimeMillis())
            relays.forEach { it.enqueue(out) }
            lastChunkSentAt[h] = now
            lastChunkCreatedAt[h] = createdAt
            dirty -= h
            chunksSent++
        }
        Status.update {
            it.copy(readingsKnown = readings.size, liveSent = liveSent, chunksSent = chunksSent)
        }
    }

    fun close() = relays.forEach { it.close() }

    companion object {
        const val BACKFILL_HOURS = 12L
        const val CHUNK_EVERY_S = 300L
        const val LIVE_MAX_AGE_S = 600L
        fun hourOf(t: Long) = t - Math.floorMod(t, 3600L)
        fun nowS() = System.currentTimeMillis() / 1000
    }
}
