// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.cgms

import dev.spike.sensors.Advert
import dev.spike.sensors.BleLink
import dev.spike.sensors.Calibration
import dev.spike.sensors.DriverStatus
import dev.spike.sensors.Pairing
import dev.spike.sensors.Reading
import dev.spike.sensors.SensorDriver
import dev.spike.sensors.SensorEvents
import dev.spike.sensors.SensorProfile
import dev.spike.sensors.Session
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * **Accu-Chek SmartGuide** (Roche) — and any other sensor that speaks the standard CGM Service with calibrated
 * values. The sensor sends finished mg/dL, so no algorithm and no vendor code is involved; the only secret is the
 * 6-digit PIN printed on the blue cap, which goes into Android's own pairing dialog.
 *
 * Untested on hardware (investigation 23): the decoding is pinned by real frames in [CgmsTest], the connection
 * sequence follows Juggluco's `AccuGattCallback.java`.
 */
class SmartGuideDriver(
    private val events: SensorEvents,
    /** Name advertised by this sensor, from the data-matrix code on the cap (last 16 characters). */
    private val sensorName: String? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : SensorDriver {
    override val profile = PROFILE

    private var startMs: Long? = null
    private var lastOffset = -1

    override fun matches(advert: Advert) =
        (sensorName == null || advert.name == sensorName) && CgmService.SERVICE in advert.serviceUuids

    override fun onConnected(link: BleLink) {
        this.link = link
        events.onStatus(DriverStatus.Progress("connecting"))
        // Bonding first: the SmartGuide refuses the control points to an unbonded phone (PIN from the cap).
        if (!link.isBonded) {
            events.onStatus(DriverStatus.Progress("pairing — enter the PIN from the sensor's cap"))
            link.bond()
        }
        link.subscribe(CgmService.MEASUREMENT)
        link.subscribe(CgmService.RACP, indicate = true)
        link.subscribe(CgmService.SOCP, indicate = true)
        link.write(CgmService.SOCP, CgmService.getCommunicationInterval())
    }

    private var link: BleLink? = null

    /** Called by the transport with the Status characteristic it read (2AA9). */
    fun onStatusRead(value: ByteArray) {
        val status = CgmService.Status.parse(value) ?: return
        startMs = now() - status.offsetMinutes * 60_000L
        events.onSession(Session(startMs, startMs?.plus(profile.warmup.inWholeMilliseconds),
            startMs?.plus(profile.lifetime.inWholeMilliseconds), null))
        // Everything we have not seen yet, from the session start.
        link?.write(CgmService.RACP, CgmService.reportRecordsFrom(if (lastOffset < 0) 0 else lastOffset + 1))
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            CgmService.MEASUREMENT -> measurement(value)?.let { events.onReading(it); events.onStatus(DriverStatus.Streaming) }
            CgmService.RACP, CgmService.SOCP -> Unit   // operation results; nothing to do in the PoC
        }
    }

    /** One measurement record → a reading, or null when it carries no usable number. */
    fun measurement(frame: ByteArray): Reading? {
        val m = CgmService.Measurement.parse(frame) ?: return null
        if (m.crcOk == false) return null
        val start = startMs ?: now() - m.offsetMinutes * 60_000L
        if (m.sessionStopped) {
            events.onStatus(DriverStatus.Ended("session stopped"))
            return null
        }
        // A below-range frame carries the SFLOAT "-INFINITY" instead of a number (and, on the SmartGuide, the
        // status octet's malfunction bit as well): report the floor value, as Juggluco does for the same frames.
        val mgdl = m.mgdl ?: if (m.belowRange) 39 else {
            events.onStatus(DriverStatus.Progress(if (m.sensorMalfunction) "sensor error" else "no value"))
            return null
        }
        lastOffset = maxOf(lastOffset, m.offsetMinutes)
        return Reading(
            timeMs = start + m.offsetMinutes * 60_000L, mgdl = mgdl, trendMgdlPerMin = m.trendMgdlPerMin,
            sensorState = if (m.calibrationRequired) "needs calibration" else null, index = m.offsetMinutes,
        )
    }

    companion object {
        val PROFILE = SensorProfile(
            id = "accu-chek-smartguide", brand = "Roche", models = listOf("Accu-Chek SmartGuide"),
            pairing = Pairing.BlePasskey(6, "blue cap data-matrix code"),
            // Juggluco SensorGlucoseData.hpp:1387 — warm-up 60 min, wear 14 days.
            warmup = 60.minutes, lifetime = 14.days, calibration = Calibration.OPTIONAL_ON_SENSOR, needsScan = true,
        )
    }
}
