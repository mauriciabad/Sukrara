// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

/**
 * Android allows an app 5 scan starts per 30 s (more fail silently, counted per app, not per scanner). Every
 * Bluetooth scan of the follower takes a start from [App], so the follow link and *Find phones nearby* running at
 * once never push each other over the limit.
 */
class ScanStartBudget(
    private val limit: Int,
    private val windowMs: Long,
) {
    private val starts = ArrayDeque<Long>()

    /**
     * Counts a start at [t] (a monotonic clock) and returns 0, or returns how long to wait before one is allowed
     * (nothing counted). A start "after" [t] (a clock that went back) no longer counts.
     */
    @Synchronized
    fun take(t: Long): Long {
        starts.removeAll { t - it > windowMs || it > t }
        if (starts.size >= limit) return windowMs - (t - starts.first()) + 100
        starts += t
        return 0
    }

    companion object {
        /** One under Android's 5 per 30 s, so a start racing the window never costs a silent failure. */
        val App = ScanStartBudget(limit = 4, windowMs = 30_000L)
    }
}
