// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.upload

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * The shape every "my data to a service I chose" upload shares (Tidepool, docs/27; Nightscout, docs/25):
 *
 * - **No outbox.** The phone's own stores (31 days of readings, the treatment log) are the queue. Each uploader keeps a
 *   small state of what the service already has (sent time ranges, the treatment versions it got) and every cycle
 *   plans `store − state`. An outage, a reconnect backfill or late sensor history is simply "not sent yet".
 * - **One loop per uploader** ([UploadLoop]): a cycle every few minutes, or at once on [CloudUploader.kick] (a new
 *   reading, an edited entry, a changed setting).
 * - **Backoff** after a failed cycle ([UploadBackoff]); a refused sign-in stops until the user fixes it.
 * - **One status** ([UploadStatus]) drawn the same way on every uploader's page (`ui/UploadUi.kt`).
 */
interface CloudUploader {
    /** Shown in Share with apps' summary while [uploading]. */
    val title: String
    val status: StateFlow<UploadStatus>
    /** Switched on and able to upload (signed in / configured, and allowed for the role). */
    val uploading: Boolean
    fun kick()
}

/**
 * [kind]: `off` · `setup` (needs an account / site) · `sign_in` (the service refused the credential) · `not_allowed`
 * (waiting for the user's choice) · `paused` (waiting for the user's confirmation, [error] says why) · `waiting` · `ok`
 * · `error` (will retry, [error] says why).
 */
data class UploadStatus(val kind: String = "off", val lastOkMs: Long = 0, val lastCount: Int = 0, val error: String? = null)

/** A cycle every [intervalMs], or sooner on [kick]; a failing cycle is logged and the loop goes on. */
class UploadLoop(private val tag: String, private val intervalMs: Long, private val cycle: () -> Unit) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun kick() { wake.trySend(Unit) }

    @Synchronized
    fun start(scope: CoroutineScope): Boolean {
        if (job?.isActive == true) return false
        job = scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { cycle() }.onFailure { Log.w(tag, "cycle: ${it.javaClass.simpleName} ${it.message}") }
                withTimeoutOrNull(intervalMs) { wake.receive() }
            }
        }
        return true
    }

    @Synchronized
    fun stop() { job?.cancel(); job = null }
}

object UploadBackoff {
    /** [baseMs] doubling per failure up to [capMs]; [jitter] spreads it by ±20 % (null: exact). */
    fun ms(failures: Int, baseMs: Long, capMs: Long, jitter: Random? = null): Long {
        if (failures <= 0) return 0
        val exact = minOf(capMs, baseMs shl minOf(failures - 1, 20))
        return jitter?.let { (exact * (0.8 + 0.4 * it.nextDouble())).toLong() } ?: exact
    }
}

/** A set of whole numbers (time slots, minutes) kept as sorted `[first, last]` ranges: a day is one pair. */
object SentRanges {
    fun ranges(values: Collection<Long>): List<LongArray> {
        val out = ArrayList<LongArray>()
        for (s in values.sorted()) {
            val last = out.lastOrNull()
            if (last != null && s <= last[1] + 1) last[1] = maxOf(last[1], s) else out += longArrayOf(s, s)
        }
        return out
    }

    fun expand(ranges: List<LongArray>): java.util.TreeSet<Long> = java.util.TreeSet<Long>().also { s -> for (r in ranges) for (x in r[0]..r[1]) s += x }
}
