// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import kotlin.math.ceil
import kotlin.math.max

/**
 * The treatment dialog's countdown over the lock screen (round 52): a dialog opened by a stray touch in a pocket
 * closes by itself before a second stray touch can save. Pure and on the monotonic clock (`elapsedRealtime`, ms), so
 * the rules are unit tested (LockCountdownTest):
 * - the duration is the setting, raised to Android's *Time to take action* ([duration]) and to 60 s with TalkBack;
 * - touches and typing never add time (in a pocket they type too);
 * - the unlock prompt pauses it; a cancelled or failed unlock resumes with at least [RESUME_FLOOR_MS] left;
 * - unlocking stops it for good ([stop]).
 */
class LockCountdown(val durationMs: Long, startedAt: Long) {
    private var deadline = startedAt + durationMs
    private var pausedLeft: Long? = null

    /** Unlocked: no countdown any more. */
    var stopped = false
        private set

    val paused: Boolean get() = pausedLeft != null

    /** Milliseconds left at [now] (the full duration once stopped). */
    fun remaining(now: Long): Long = when {
        stopped -> durationMs
        else -> pausedLeft ?: max(0L, deadline - now)
    }

    /** The ring: 1 = full, 0 = empty; [stepped] (reduced motion) moves once per whole second. */
    fun fraction(now: Long, stepped: Boolean = false): Float {
        val left = remaining(now)
        val shown = if (stepped) ceil(left / 1000.0).toLong() * 1000 else left
        return (shown.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    fun expired(now: Long): Boolean = !stopped && pausedLeft == null && now >= deadline

    /** The unlock prompt is up: time stands still. */
    fun pause(now: Long) {
        if (!stopped && pausedLeft == null) pausedLeft = remaining(now)
    }

    /** The prompt was cancelled or failed: the person is there, give them at least [RESUME_FLOOR_MS] to see the dialog. */
    fun resume(now: Long) {
        val left = pausedLeft ?: return
        deadline = now + max(left, RESUME_FLOOR_MS)
        pausedLeft = null
    }

    fun stop() { stopped = true; pausedLeft = null }

    private var shown = false

    /**
     * The dialog's first frame is on screen: the full time counts from here, not from when the activity resumed (a
     * slow first frame mustn't eat into it). Only once; later calls change nothing.
     */
    fun firstFrame(now: Long) {
        if (shown) return
        shown = true
        if (!stopped && pausedLeft == null) deadline = now + durationMs
    }

    /** Few enough seconds left to warn (the pulse and, with input, one haptic tick). */
    fun warning(now: Long): Boolean = !stopped && remaining(now) <= WARN_MS

    /** The number inside the ring: whole seconds left, rounded up (20 at the start, 1 in the last second). */
    fun wholeSeconds(now: Long): Int = ceil(remaining(now) / 1000.0).toInt()

    /** What TalkBack hears on Cancel: the seconds left rounded up to 5 s steps (not chatty). */
    fun spokenSeconds(now: Long): Int = (ceil(remaining(now) / 5000.0).toInt() * 5).coerceAtLeast(0)

    companion object {
        const val RESUME_FLOOR_MS = 5_000L
        const val WARN_MS = 5_000L
        const val ANNOUNCE_MS = 10_000L
        const val TALKBACK_MS = 60_000L

        /**
         * How long the dialog stays: the setting ([settingS] seconds), or Android's recommended timeout for it
         * ([recommendedMs], `AccessibilityManager.getRecommendedTimeoutMillis`: *Time to take action*) if longer, and
         * at least 60 s with touch exploration (TalkBack) on: 3 fields and Save to explore.
         */
        fun duration(settingS: Int, recommendedMs: Long, touchExploration: Boolean): Long =
            maxOf(settingS * 1000L, recommendedMs, if (touchExploration) TALKBACK_MS else 0L)
    }
}
