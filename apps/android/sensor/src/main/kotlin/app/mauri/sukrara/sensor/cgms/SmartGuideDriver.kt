// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.cgms

import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.FailReason
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandDriver
import app.mauri.sukrara.sensor.brand.ScanSpec
import app.mauri.sukrara.sensor.pairing.Pairing
import java.util.UUID

/**
 * Accu-Chek SmartGuide (Roche): the standard CGM Service with calibrated values. The sensor sends finished mg/dL, so no
 * algorithm and no vendor code; the only secret is the 6-digit PIN on the cap, which the person types into Android's
 * own pairing dialog ([BleLink.bond]). A stranger's SmartGuide would need its PIN, so the bond fails and nothing is read.
 *
 * Sequence (Juggluco `AccuGattCallback`): bond → subscribe measurement / RACP / SOCP → SOCP "get interval" (with the
 * E2E-CRC) → read the CGM Status (how long the session has run: the start every value is timed from) → RACP "records
 * since the last one we have". Every record must carry a correct E2E-CRC. A "session stopped" flag is confirmed with a
 * CGM Status read and is then shown as "no session", never as the sensor's end (its end comes from its start + 14
 * days). Untested on a real sensor.
 */
internal class SmartGuideDriver(
    pairing: Pairing,
    events: SensorEvents,
    clockMs: () -> Long = System::currentTimeMillis,
    log: (String) -> Unit = {},
) : BrandDriver(pairing, events, clockMs, log) {
    private var lastOffset = -1
    private var statusRead = false

    override val validMgdl = 40..400

    override val scan = listOf(ScanSpec(service = CgmService.SERVICE))

    override fun matches(advert: Advert) = advert.name?.startsWith("AC-") == true && CgmService.SERVICE in advert.serviceUuids

    override fun onConnected(
        link: BleLink,
        characteristics: Set<UUID>,
        status: Int,
    ) {
        this.link = link
        statusRead = false
        if (CgmService.MEASUREMENT !in characteristics || CgmService.STATUS !in characteristics) {
            return retry("CGM service not found (status $status)")
        }
        // bond first: the SmartGuide refuses its control points to an unbonded phone (the PIN on the cap)
        if (!link.isBonded) {
            events.onStatus(LinkStatus.EnterPin)
            return link.bond()
        }
        subscribe(link)
    }

    override fun onBondChanged(bonded: Boolean) {
        val link = link ?: return
        if (bonded) subscribe(link) else fail(FailReason.BondFailed, "Bluetooth pairing was cancelled or the PIN didn't match")
    }

    private fun subscribe(link: BleLink) {
        events.onStatus(LinkStatus.Authorized)
        link.subscribe(CgmService.MEASUREMENT)
        link.indicate(CgmService.RACP)
        link.indicate(CgmService.SOCP)
        link.write(CgmService.SOCP, CgmService.getCommunicationInterval())
        link.read(CgmService.STATUS)
    }

    override fun onRead(
        characteristic: UUID,
        value: ByteArray,
    ) {
        if (characteristic == CgmService.STATUS) onStatusRead(value)
    }

    /** The CGM Status read: no session, or the session start and then everything not seen yet (the last day at most). */
    fun onStatusRead(value: ByteArray) {
        val status = CgmService.Status.parse(value) ?: return retry("CGM status unreadable")
        if (status.annunciation.sessionStopped) return events.onStatus(LinkStatus.NoSession)
        sessionStart(now() - status.offsetMinutes * 60L)
        val first = statusRead.not()
        statusRead = true
        if (first) {
            val from = maxOf(lastOffset + 1, status.offsetMinutes - BACKFILL_MIN, 0)
            link?.write(CgmService.RACP, CgmService.reportRecordsFrom(from))
        }
    }

    override fun onNotification(
        characteristic: UUID,
        value: ByteArray,
    ) {
        if (characteristic == CgmService.MEASUREMENT) CgmService.Measurement.parseAll(value).forEach { measurement(it) }
        // RACP / SOCP: operation results, nothing to act on
    }

    /** One measurement record → a reading, a state, or nothing (no CRC, no start yet). */
    fun measurement(m: CgmService.Measurement) {
        if (m.crcOk != true) return log("measurement without a correct E2E-CRC dropped")
        val a = m.annunciation
        if (a.sessionStopped) {
            // confirm with the status before saying so (one flipped flag must not stop anything)
            link?.read(CgmService.STATUS)
            return
        }
        val start = knownStart ?: return log("measurement before the session start is known: not used")
        val mgdl =
            m.mgdl ?: return events.onStatus(
                LinkStatus.NoValue(
                    when {
                        a.belowRange -> NoValueReason.BelowRange
                        a.aboveRange -> NoValueReason.AboveRange
                        a.sensorMalfunction -> NoValueReason.SensorError
                        else -> NoValueReason.Missing
                    },
                ),
            )
        lastOffset = maxOf(lastOffset, m.offsetMinutes)
        reading(start + m.offsetMinutes * 60L, mgdl, m.trendMgdlPerMin)
    }

    override fun onDisconnected() {}

    override fun onClosed() {}

    private companion object {
        /** Stored records asked for after a (re)connection: the last day at most. */
        const val BACKFILL_MIN = 24 * 60
    }
}
