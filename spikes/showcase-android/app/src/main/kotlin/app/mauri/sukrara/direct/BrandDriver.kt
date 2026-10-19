// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.util.Log
import app.mauri.sukrara.bridge.Bridge

/**
 * What the untested families (investigation 23, steps 2–5: Dexcom G7 / G6, Accu-Chek SmartGuide, Aidex X) share on
 * top of [SensorDriver]: the status words `NewSensorFlow` reads, the session start, the reading rules and the two ways
 * a session stops. The Libre 3 doesn't use it ([Libre3Driver] keeps its own, unchanged).
 *
 * Status words: "live" (a reading came), "warming up", "reconnecting" + why (transient: the transport reconnects),
 * "failed" + why (needs the user: wrong id, refused PIN; the link is given up until the source restarts), "sensor
 * ended" (the sensor said so: no more connections, like the Libre 3).
 */
internal abstract class BrandDriver(
    protected val pairing: SensorPairing,
    protected val events: SensorEvents,
    private val prefs: DriverPrefs,
    private val clock: () -> Long,
    protected val log: (priority: Int, message: String) -> Unit,
) : SensorDriver {
    protected var link: BleLink? = null
    private var reportedStart: Long? = null

    /** Epoch seconds. */
    protected fun now() = clock() / 1000

    /** The sensor's session start, reported when it is new or moved by more than a minute (clock jitter). */
    protected fun sessionStart(startEpoch: Long) {
        val last = reportedStart
        if (last != null && kotlin.math.abs(last - startEpoch) <= 60) return
        reportedStart = startEpoch
        events.onSessionStart(startEpoch)
    }

    /**
     * A calibrated value from the sensor: a live reading when it is recent, else history (a record the sensor stored
     * while the phone was away must not look like the current value to the alerts). Out-of-range values are dropped,
     * like the Libre 3's.
     */
    protected fun reading(r: Bridge.Reading) {
        if (r.mgdl !in 39..501) return log(Log.WARN, "ignoring out-of-range glucose ${r.mgdl}")
        if (now() - r.epochSec <= LIVE_S) {
            events.onStatus("live")
            events.onReading(r)
        } else events.onHistory(listOf(r))
    }

    /** The sensor said its session is over: no more connections until a new sensor is paired. */
    protected fun ended(why: String) {
        log(Log.WARN, "sensor ${pairing.serial} ended ($why): not reconnecting")
        events.onSensorEnded(pairing.serial)
        prefs.putBoolean(DriverPrefs.endedKey(pairing.serial), true)
        link?.close()
        events.onStatus("sensor ended", DriverPrefs.ENDED_HINT)
    }

    /** Something the user has to fix (wrong id, refused PIN): give the device up, say why. */
    protected fun fail(why: String) {
        log(Log.WARN, "sensor: $why")
        link?.close()
        events.onStatus(FAILED, why)
    }

    /** A transient problem: drop the connection, the transport reconnects. */
    protected fun retry(why: String) {
        log(Log.WARN, "sensor: $why")
        events.onStatus("reconnecting", why)
        link?.disconnect()
    }

    override fun onLinkFailed(why: String) = retry(why)

    companion object {
        /** A reading at most this old (s) is the current value; older ones are history. */
        const val LIVE_S = 180L
        const val FAILED = "failed"
    }
}
