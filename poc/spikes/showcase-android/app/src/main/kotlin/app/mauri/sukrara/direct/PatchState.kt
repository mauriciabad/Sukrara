// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

/**
 * The Libre 3 patch state (BLE patch status byte 7, NFC patch info byte 14) and when to give up on a sensor.
 * Values as in DiaBLE / Juggluco (`appcurve.cpp`: state > 4 = "stopped working"): 1 storage (never started),
 * 2 insertion detection, 3 insertion failed, 4 paired / running, 5 expired, 6 terminated, 7 error, 8 error terminated.
 *
 * Seen 2026-10-18 on the user's Libre 3 Plus at its end: `state=5 currentLifeCount=21635`, then no glucose — the
 * client kept reconnecting every 2 minutes for ever.
 */
object PatchState {
    const val RUNNING = 4
    const val EXPIRED = 5

    /** The sensor has reached its end: an expired/terminated/error state, or the life count past its wear length. */
    fun ended(state: Int, lifeCount: Int, wearMin: Int): Boolean = state >= EXPIRED || (wearMin > 0 && lifeCount >= wearMin)

    /**
     * Stop connecting at once: terminated or error states never send glucose again. An expired sensor (5) or one past
     * its wear length may still be in a grace period, so it gets one glucose wait first; if no glucose comes, the
     * client stops for good (until a new sensor is scanned).
     */
    fun stopNow(state: Int): Boolean = state > EXPIRED
}
