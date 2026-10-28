// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

/**
 * The gate in front of the glucose notification's `notify` (Android drops an app's posts above ~5/s, which a Bluetooth
 * backfill can reach):
 *  - the same key as the last post → skipped, unless forced (a re-post after the person dismissed it);
 *  - at most one post per [minGapMs]: the first change after a quiet gap posts at once (a new value is never late),
 *    changes inside the gap are folded into one post at its end, the latest winning.
 *
 * [clock] must be monotonic (`SystemClock.elapsedRealtime`): with the wall clock, setting the time back delayed every
 * later post. A wait is never longer than [minGapMs].
 */
class PostGate<K>(
    private val minGapMs: Long,
    private val clock: () -> Long,
    private val later: (delayMs: Long, run: () -> Unit) -> Unit,
) {
    private class Pending<K>(val key: K, val force: Boolean, val post: () -> Unit)

    private var lastKey: Any? = NONE
    private var lastAt = Long.MIN_VALUE / 2
    private var pending: Pending<K>? = null

    /** Drops the post waiting for the end of the gap, if any (its flush then does nothing). */
    @Synchronized
    fun cancelPending() {
        pending = null
    }

    /** Posts now, later (folded) or not at all; [post] runs under the gate's lock, so posts never overlap. */
    @Synchronized
    fun submit(
        key: K,
        force: Boolean = false,
        post: () -> Unit,
    ) {
        pending?.let {
            pending = Pending(key, force || it.force, post)
            return
        }
        if (!force && key == lastKey) return
        val wait = (lastAt + minGapMs - clock()).coerceAtMost(minGapMs)
        if (wait > 0) {
            pending = Pending(key, force, post)
            later(wait) { flush() }
            return
        }
        run(key, post)
    }

    @Synchronized
    private fun flush() {
        val p = pending ?: return
        pending = null
        if (!p.force && p.key == lastKey) return
        run(p.key, p.post)
    }

    private fun run(
        key: K,
        post: () -> Unit,
    ) {
        lastKey = key
        lastAt = clock()
        post()
    }

    private companion object {
        val NONE = Any()
    }
}
