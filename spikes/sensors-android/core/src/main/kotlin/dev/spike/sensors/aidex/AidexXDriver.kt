// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.aidex

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
 * **Aidex X** driver: derive the keys from the serial, take the session key from the sensor, then poll it.
 * The transport reads [AidexX.DATA] after the key notification (the sensor answers a read, not a notification),
 * which this spike models with [onDataRead]. Untested on hardware.
 */
class AidexXDriver(
    private val serial: String,
    private val events: SensorEvents,
    private val now: () -> Long = System::currentTimeMillis,
) : SensorDriver {
    override val profile = PROFILE

    private val iv = AidexX.iv(serial)
    private var notifiedKey: ByteArray? = null
    private var session: ByteArray? = null
    private var startMs: Long? = null
    private var link: BleLink? = null

    override fun matches(advert: Advert) = advert.name == AidexX.deviceName(serial)

    override fun onConnected(link: BleLink) {
        this.link = link
        events.onStatus(DriverStatus.Progress("exchanging keys"))
        val characteristic = if (link.isBonded) AidexX.KEY_BONDED else AidexX.KEY_UNBONDED
        link.subscribe(characteristic)
        link.subscribe(AidexX.DATA)
        link.write(characteristic, AidexX.askKey(serial), withResponse = false)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            AidexX.KEY_UNBONDED, AidexX.KEY_BONDED -> if (value.size == 16) notifiedKey = value
            AidexX.DATA -> reply(value)
        }
    }

    /** The 17-byte read from [AidexX.DATA] that follows the key notification. */
    fun onDataRead(value: ByteArray) {
        val key = notifiedKey ?: return
        val s = AidexX.sessionKey(key, iv, value)
        if (s == null) {
            events.onStatus(DriverStatus.Failed("wrong serial number for this sensor"))
            link?.disconnect()
            return
        }
        session = s
        events.onPairingState(s)
        link?.write(AidexX.DATA, AidexX.command(s, iv, AidexX.OP_START_TIME), withResponse = false)
    }

    private fun reply(encrypted: ByteArray) {
        val s = session ?: return
        val plain = AidexX.reply(s, iv, encrypted) ?: return
        when (plain.firstOrNull()?.toInt()?.and(0xFF)) {
            AidexX.OP_START_TIME -> {
                // The glucose records count minutes from the start, so only the start time is needed.
                link?.write(AidexX.DATA, AidexX.command(s, iv, AidexX.OP_GLUCOSE), withResponse = false)
            }
            AidexX.OP_GLUCOSE -> AidexX.Glucose.parseReply(plain)?.let { onGlucose(it) }
        }
    }

    /** A reading from a reply or from an advertisement — the same 9 bytes either way. */
    fun onGlucose(g: AidexX.Glucose): Reading? {
        val start = startMs ?: (now() - g.minutesSinceStart * 60_000L).also { startMs = it }
        events.onSession(Session(start, start + profile.warmup.inWholeMilliseconds,
            start + profile.lifetime.inWholeMilliseconds, if (g.warmingUp) "warming up" else null))
        if (g.warmingUp) { events.onStatus(DriverStatus.Progress("warming up")); return null }
        if (!g.usable) { events.onStatus(DriverStatus.Progress("no value")); return null }
        val reading = Reading(start + g.minutesSinceStart * 60_000L, g.mgdl, g.trend, index = g.minutesSinceStart)
        events.onReading(reading)
        events.onStatus(DriverStatus.Streaming)
        return reading
    }

    /** A scan result's manufacturer data: a reading without connecting at all. */
    fun onAdvertisement(bytes: ByteArray): Reading? {
        val advert = AidexX.Advertisement.parse(bytes) ?: return null
        if (!advert.crcOk) return null
        return onGlucose(advert.latest)
    }

    override fun onDisconnected() { link = null }

    companion object {
        val PROFILE = SensorProfile(
            id = "aidex-x", brand = "Microtech", models = listOf("Aidex X", "LinX", "LumiFlex", "GlucoRx", "Wellion"),
            pairing = Pairing.TransmitterSerial(10, "box data-matrix code"),
            // Juggluco SensorGlucoseData.hpp:1361 — warm-up 60 min (15 min "manual"), 15 days (16 for LumiFlex).
            warmup = 60.minutes, lifetime = 15.days, calibration = Calibration.NONE, needsScan = true,
        )
    }
}
