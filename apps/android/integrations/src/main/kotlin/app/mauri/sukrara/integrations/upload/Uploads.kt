// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.upload

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.random.Random

/*
 * The shape every "my data to a service I chose" upload shares:
 *
 * - No outbox. The phone's own stores (readings, the treatment log) are the queue. Each uploader keeps a small state of
 *   what the service already has (sent time ranges, the treatment versions it got) and every cycle plans
 *   `stores − state`. An outage, a reconnect backfill or late sensor history is simply "not sent yet".
 * - One loop per uploader ([UploadLoop]): a cycle every few minutes, or at once on a kick (a new reading, an edited
 *   entry, a changed setting).
 * - Backoff after a failed cycle ([UploadBackoff]); a refused credential stops until the person changes it.
 * - One status ([UploadStatus]) drawn the same way on every uploader's page.
 */

/** What an upload is doing, as its page and the Share with apps summary show it. */
sealed interface UploadStatus {
    /** Switched off (or this phone isn't the wearer). */
    data object Off : UploadStatus

    /** Switched on but not set up (no site / account yet). */
    data object Setup : UploadStatus

    /** The service refused the credential: nothing is sent until it's changed. */
    data object AuthFailed : UploadStatus

    /** Waiting for the person's confirmation: [other] (another uploader) writes to the same place. */
    data class Paused(val other: String) : UploadStatus

    /** On and set up, nothing has gone up yet. */
    data object Waiting : UploadStatus

    data class Ok(val at: Instant, val count: Int) : UploadStatus

    /** The last cycle failed; it is tried again by itself ([why] is for the page, never holds a secret). */
    data class Failing(val why: String, val lastOk: Instant?) : UploadStatus
}

/** A cycle every [intervalMs], or sooner on [kick]; a failing cycle is reported and the loop goes on. */
class UploadLoop(
    private val intervalMs: Long,
    private val dispatcher: CoroutineDispatcher,
    private val onFailure: (Throwable) -> Unit,
    private val cycle: suspend () -> Unit,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun kick() {
        wake.trySend(Unit)
    }

    /** Starts the loop once; later calls do nothing while it runs. */
    @Synchronized
    fun start(scope: CoroutineScope): Boolean {
        if (job?.isActive == true) return false
        job =
            scope.launch(dispatcher) {
                while (true) {
                    try {
                        cycle()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onFailure(e)
                    }
                    withTimeoutOrNull(intervalMs) { wake.receive() }
                }
            }
        return true
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}

object UploadBackoff {
    /** [baseMs] doubling per failure up to [capMs]; [jitter] spreads it by ±20 % (null: exact). */
    fun ms(
        failures: Int,
        baseMs: Long,
        capMs: Long,
        jitter: Random? = null,
    ): Long {
        if (failures <= 0) return 0
        val exact = minOf(capMs, baseMs shl minOf(failures - 1, 20))
        return jitter?.let { (exact * (0.8 + 0.4 * it.nextDouble())).toLong() } ?: exact
    }
}

/** A closed range of whole numbers (minutes), one item of [SentRanges]. */
@Serializable
data class Span(
    val first: Long,
    val last: Long,
)

/** A set of whole numbers (minutes) kept as sorted [Span]s: a day of minutes is one pair. */
object SentRanges {
    fun ranges(values: Collection<Long>): List<Span> {
        val out = ArrayList<Span>()
        for (s in values.sorted()) {
            val last = out.lastOrNull()
            if (last != null && s <= last.last + 1) out[out.size - 1] = last.copy(last = maxOf(last.last, s)) else out += Span(s, s)
        }
        return out
    }

    fun expand(ranges: List<Span>): java.util.TreeSet<Long> =
        java.util.TreeSet<Long>().also { s ->
            for (r in ranges) for (x in r.first..r.last) s += x
        }
}
