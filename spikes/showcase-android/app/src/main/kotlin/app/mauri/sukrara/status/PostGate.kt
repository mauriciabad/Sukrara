// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

/**
 * The one gate in front of the glucose notification's `notify` (2026-10-18: the phone hit Android's ~5 posts/s per
 * app limit, "enqueue rate is 5.02 … Shedding", during a BLE backfill: one post per history packet, plus the
 * reading + history pair of every minute).
 *
 *  - Same content key as the last post → skipped, unless `force` (screen on, SystemUI asks for a re-post).
 *  - At most one post per [minGapMs]: the first change after a quiet gap posts at once (a new value is never late);
 *    changes inside the gap are conflated into one post at its end, the latest one winning.
 *
 * Plain Kotlin with an injected clock and scheduler, so `PostGateTest` runs it on the JVM.
 */
class PostGate(
    private val minGapMs: Long,
    private val clock: () -> Long,
    private val later: (delayMs: Long, run: () -> Unit) -> Unit,
) {
    private class Pending(val key: Any?, val force: Boolean, val post: () -> Unit)

    private var lastKey: Any? = NONE
    private var lastAt = Long.MIN_VALUE / 2
    private var pending: Pending? = null

    /** Posts now, later (conflated) or not at all; [post] runs under the gate's lock, so posts never overlap. */
    @Synchronized
    fun submit(key: Any?, force: Boolean = false, post: () -> Unit) {
        pending?.let { pending = Pending(key, force || it.force, post); return }   // a post is already due: replace it
        if (!force && key == lastKey) return
        val wait = lastAt + minGapMs - clock()
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

    private fun run(key: Any?, post: () -> Unit) {
        lastKey = key
        lastAt = clock()
        post()
    }

    private companion object { val NONE = Any() }
}
