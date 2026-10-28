// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import kotlin.math.ceil
import kotlin.math.max

/**
 * The quick treatment dialog's countdown over the lock screen: a dialog opened by a stray touch in a pocket closes
 * by itself, unsaved, before a second stray touch can save. Pure, on the monotonic clock (`elapsedRealtime`, ms):
 * - the duration is the setting, raised to Android's *Time to take action* and to 60 s with TalkBack ([duration]);
 * - touches and typing never add time (in a pocket they type too);
 * - the unlock prompt pauses it; a cancelled or failed unlock resumes with at least [RESUME_FLOOR_MS] left;
 * - unlocking stops it for good ([stop]).
 */
class LockCountdown(
    val durationMs: Long,
    startedAt: Long,
) {
    private var deadline = startedAt + durationMs
    private var pausedLeft: Long? = null
    private var shown = false

    /** Unlocked: no countdown any more. */
    var stopped = false
        private set

    val paused: Boolean get() = pausedLeft != null

    /** Milliseconds left at [now] (the full duration once stopped). */
    fun remaining(now: Long): Long =
        when {
            stopped -> durationMs
            else -> pausedLeft ?: max(0L, deadline - now)
        }

    /** The ring: 1 = full, 0 = empty; [stepped] (reduced motion) moves once per whole second. */
    fun fraction(
        now: Long,
        stepped: Boolean = false,
    ): Float {
        val left = remaining(now)
        val drawn = if (stepped) ceil(left / 1000.0).toLong() * 1000 else left
        return (drawn.toFloat() / durationMs).coerceIn(0f, 1f)
    }

    fun expired(now: Long): Boolean = !stopped && pausedLeft == null && now >= deadline

    /**
     * The dialog's first frame is on screen: the full time counts from here (a slow first frame mustn't eat into
     * it). Only once.
     */
    fun firstFrame(now: Long) {
        if (shown) return
        shown = true
        if (!stopped && pausedLeft == null) deadline = now + durationMs
    }

    /** The unlock prompt is up: time stands still. */
    fun pause(now: Long) {
        if (!stopped && pausedLeft == null) pausedLeft = remaining(now)
    }

    /** The prompt was cancelled or failed: the person is there, so at least [RESUME_FLOOR_MS] to see the dialog. */
    fun resume(now: Long) {
        val left = pausedLeft ?: return
        deadline = now + max(left, RESUME_FLOOR_MS)
        pausedLeft = null
    }

    fun stop() {
        stopped = true
        pausedLeft = null
    }

    /** Few enough seconds left to warn (one haptic tick when something was typed). */
    fun warning(now: Long): Boolean = !stopped && remaining(now) <= WARN_MS

    /**
     * TalkBack's one polite *Closes in 10 seconds*: due once [ANNOUNCE_MS] or less is left while it runs (not while the
     * unlock prompt pauses it, never after an unlock). The caller says it once; the seconds are never read out each.
     */
    fun announceDue(now: Long): Boolean = !stopped && pausedLeft == null && remaining(now) <= ANNOUNCE_MS

    /** The number inside the ring: whole seconds left, rounded up. */
    fun wholeSeconds(now: Long): Int = ceil(remaining(now) / 1000.0).toInt()

    /** What TalkBack hears: the seconds left rounded up to 5 s steps (not chatty). */
    fun spokenSeconds(now: Long): Int = (ceil(remaining(now) / 5000.0).toInt() * 5).coerceAtLeast(0)

    companion object {
        const val RESUME_FLOOR_MS = 5_000L
        const val WARN_MS = 5_000L
        const val ANNOUNCE_MS = 10_000L
        const val TALKBACK_MS = 60_000L

        /**
         * How long the dialog stays: the setting ([settingS]), or Android's recommended timeout ([recommendedMs],
         * `AccessibilityManager.getRecommendedTimeoutMillis`) if longer, and at least 60 s with TalkBack on.
         */
        fun duration(
            settingS: Int,
            recommendedMs: Long,
            touchExploration: Boolean,
        ): Long = maxOf(settingS * 1000L, recommendedMs, if (touchExploration) TALKBACK_MS else 0L)
    }
}
