// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.cgms

import android.util.Log
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.Advert
import app.mauri.sukrara.direct.BleLink
import app.mauri.sukrara.direct.BrandDriver
import app.mauri.sukrara.direct.DriverPrefs
import app.mauri.sukrara.direct.ScanSpec
import app.mauri.sukrara.direct.SensorEvents
import app.mauri.sukrara.direct.SensorPairing
import java.util.UUID

/**
 * **Accu-Chek SmartGuide** (Roche), investigation 23 step 3 — the standard CGM Service with calibrated values. The
 * sensor sends finished mg/dL, so no algorithm and no vendor code; the only secret is the 6-digit PIN on the cap,
 * which the user types into **Android's own pairing dialog** ([BleLink.bond]; the result comes back through
 * [onBondChanged]).
 *
 * Which sensor: an `AC-…` device advertising 0x181F (Juggluco `AccuGattCallback.matchDeviceName`); a stranger's
 * SmartGuide would need its PIN, so the bond fails and nothing is read. Sequence after Juggluco's
 * `AccuGattCallback.java`: bond → subscribe measurement / RACP / SOCP → SOCP "get interval" (with the E2E-CRC) →
 * read the CGM Status (how long the session has run) → RACP "records since the last one we have". Untested on
 * hardware: the decoding is pinned by real frames.
 */
internal class SmartGuideDriver(
    pairing: SensorPairing,
    events: SensorEvents,
    prefs: DriverPrefs,
    clock: () -> Long = System::currentTimeMillis,
    log: (priority: Int, message: String) -> Unit = { p, m -> Log.println(p, "Sukrara.smartguide", m) },
) : BrandDriver(pairing, events, prefs, clock, log) {
    override val profile = SensorProfiles.SMARTGUIDE

    private var startEpoch: Long? = null
    private var lastOffset = -1

    override val scan = listOf(ScanSpec(service = CgmService.SERVICE))

    override fun matches(advert: Advert) = advert.name?.startsWith("AC-") == true && CgmService.SERVICE in advert.serviceUuids

    override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
        this.link = link
        if (CgmService.MEASUREMENT !in characteristics || CgmService.STATUS !in characteristics)
            return retry("CGM service not found (status $status)")
        // Bond first: the SmartGuide refuses its control points to an unbonded phone (the PIN on the cap).
        if (!link.isBonded) {
            events.onStatus(PAIRING)
            return link.bond()
        }
        subscribe(link)
    }

    override fun onBondChanged(bonded: Boolean) {
        val link = link ?: return
        if (bonded) subscribe(link) else fail("Bluetooth pairing was cancelled or the PIN didn't match")
    }

    private fun subscribe(link: BleLink) {
        events.onStatus("reading")
        link.subscribe(CgmService.MEASUREMENT)
        link.subscribe(CgmService.RACP, indicate = true)
        link.subscribe(CgmService.SOCP, indicate = true)
        link.write(CgmService.SOCP, CgmService.getCommunicationInterval())
        link.read(CgmService.STATUS)
    }

    override fun onRead(characteristic: UUID, value: ByteArray) {
        if (characteristic == CgmService.STATUS) onStatusRead(value)
    }

    /** The CGM Status read: the session start, then everything we have not seen yet. */
    fun onStatusRead(value: ByteArray) {
        val status = CgmService.Status.parse(value) ?: return
        val start = now() - status.offsetMinutes * 60L
        startEpoch = start
        sessionStart(start)
        link?.write(CgmService.RACP, CgmService.reportRecordsFrom(if (lastOffset < 0) 0 else lastOffset + 1))
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        if (characteristic == CgmService.MEASUREMENT) measurement(value)?.let { reading(it) }
        // RACP / SOCP: operation results, nothing to act on
    }

    /** One measurement record → a reading, or null when it carries no usable number. */
    fun measurement(frame: ByteArray): Bridge.Reading? {
        val m = CgmService.Measurement.parse(frame) ?: return null
        if (m.crcOk == false) return null
        if (m.sessionStopped) { ended("session stopped"); return null }
        val start = startEpoch ?: (now() - m.offsetMinutes * 60L).also { startEpoch = it; sessionStart(it) }
        // A below-range frame carries the SFLOAT "-INFINITY" instead of a number: the floor value, as Juggluco posts.
        val mgdl = m.mgdl ?: if (m.belowRange) 39 else {
            events.onStatus(if (m.sensorMalfunction) "sensor error" else "no value")
            return null
        }
        lastOffset = maxOf(lastOffset, m.offsetMinutes)
        return Bridge.Reading(start + m.offsetMinutes * 60L, mgdl, m.trendMgdlPerMin?.toDouble())
    }

    override fun onDisconnected() {}
    override fun onClosed() {}

    companion object {
        /** Status word while Android's pairing dialog (the cap's PIN) is up. */
        const val PAIRING = "pairing — enter the PIN from the sensor's cap"
    }
}
