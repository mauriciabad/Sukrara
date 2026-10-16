// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.content.Context
import android.util.Log
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.wearer.Live
import java.io.File
import kotlinx.coroutines.flow.update

/**
 * The phone's local HTTP servers, for both roles, over one reading store ([XdripServer]):
 *  - **Garmin** (inv. 08): xDrip+/Juggluco-compatible on `127.0.0.1:17580`, only if the port is free (while Juggluco
 *    is the source it keeps the port and serves the watch itself).
 *  - **Nightscout read API** (inv. 03 tier 1): `127.0.0.1:17581`, or all interfaces with [FeatureState.nightscoutLan]
 *    (then non-loopback callers need the API secret). Works next to Juggluco, and in the follower role it serves the
 *    follower DB, so Home Assistant / xDrip+ / Nightguard / clocks can read a followed person's glucose.
 *
 * Both follow their [Features] switch live; their state goes into [Live.status] (`garminState`, `nightscoutState`).
 */
class LocalServers(ctx: Context, prefix: String, private val jugglucoExpected: () -> Boolean,
                   treatments: () -> List<Treatment>, siteName: String) {
    private val tag = "Sukrara.local"
    private val app = ctx.applicationContext
    val xdrip = XdripServer(File(app.filesDir, "$prefix-requests.log"), store = File(app.filesDir, "$prefix-readings.csv"))
    private var garminUp = false
    private var nsKey: String? = null

    init {
        xdrip.treatments = treatments
        xdrip.siteName = siteName
        xdrip.onRequest = { Live.status.update { it.copy(watchPolledAt = xdrip.lastRequest?.first ?: 0) } }
    }

    /** Apply switches; [old] == null on start. Also retried on a source change (Juggluco → direct frees 17580). */
    fun apply(old: FeatureState?, f: FeatureState) {
        if (old == null || old.garmin != f.garmin || (old.source != f.source && !garminUp)) {
            if (f.garmin && !garminUp) {
                val err = xdrip.start()
                garminUp = err == null
                val state = when {
                    err == null -> "listening on 127.0.0.1:${XdripServer.PORT}"
                    jugglucoExpected() -> "port ${XdripServer.PORT} held by Juggluco → Juggluco serves the watch"
                    else -> err
                }
                Live.status.update { it.copy(garmin = state) }
                Log.i(tag, "garmin: $state")
            } else if (!f.garmin && garminUp) {
                xdrip.stop(); garminUp = false
                Live.status.update { it.copy(garmin = "off") }
            } else if (!f.garmin) Live.status.update { it.copy(garmin = "off") }
        }
        val key = if (f.nightscout) "${f.nightscoutLan}" else null
        if (key != nsKey) {
            nsKey = key
            val state = if (!f.nightscout) {
                xdrip.stopNightscout(); "off"
            } else {
                val err = xdrip.startNightscout(Features.NIGHTSCOUT_PORT, f.nightscoutLan, Features.nightscoutSecret(app))
                when {
                    err != null -> { nsKey = null; err }
                    f.nightscoutLan -> "LAN :${Features.NIGHTSCOUT_PORT} (" +
                        Features.lanAddresses().joinToString { "http://$it:${Features.NIGHTSCOUT_PORT}" }.ifEmpty { "no LAN address" } +
                        ", API secret needed)"
                    else -> "listening on 127.0.0.1:${Features.NIGHTSCOUT_PORT}"
                }
            }
            Live.status.update { it.copy(nightscout = state) }
            Log.i(tag, "nightscout: $state")
        }
    }

    fun add(r: Bridge.Reading) = xdrip.add(r)
    fun onSensor(serial: String, startEpoch: Long) = xdrip.onSensor(serial, startEpoch)

    fun stop() {
        xdrip.stop(); garminUp = false
        xdrip.stopNightscout(); nsKey = null
        Live.status.update { it.copy(garmin = "off", nightscout = "off") }
    }
}
