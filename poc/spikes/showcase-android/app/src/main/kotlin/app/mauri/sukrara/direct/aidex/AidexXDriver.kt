// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.aidex

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
 * **Aidex X** driver (investigation 23 step 4): derive the keys from the serial ([SensorPairing.serial], typed from
 * the box), take the session key from the sensor, then poll it once a minute. Readings also arrive in the sensor's
 * advertisement ([onAdvertisement]), so a scan alone can show a value before any connection. Untested on hardware.
 *
 * Sequence (Juggluco `aidexx/`): subscribe the key characteristic (F001 unbonded / F003 bonded) and F002 → write the
 * ask-key → the sensor notifies a 16-byte key → read F002 (17 bytes: the session key + CRC-8, encrypted with it) →
 * 0x21 start time → 0x11 glucose → every [POLL_MS].
 */
internal class AidexXDriver(
    pairing: SensorPairing,
    events: SensorEvents,
    prefs: DriverPrefs,
    clock: () -> Long = System::currentTimeMillis,
    log: (priority: Int, message: String) -> Unit = { p, m -> Log.println(p, "Sukrara.aidex", m) },
) : BrandDriver(pairing, events, prefs, clock, log) {
    override val profile = SensorProfiles.AIDEX_X

    private val serial = pairing.serial.uppercase()
    private val iv = AidexX.iv(serial)
    private var notifiedKey: ByteArray? = null
    private var session: ByteArray? = null
    private var startEpoch: Long? = null
    private var lastMinute = -1
    private val poll = Runnable { ask(AidexX.OP_GLUCOSE) }

    override val scan = listOf(ScanSpec(name = AidexX.deviceName(serial)))

    override fun matches(advert: Advert) = advert.name == AidexX.deviceName(serial)

    override fun onAdvertisement(advert: Advert, record: ByteArray) {
        if (advert.name != AidexX.deviceName(serial)) return
        val a = AidexX.Advertisement.parse(record) ?: return
        if (a.crcOk && a.name == AidexX.deviceName(serial)) onGlucose(a.latest)
    }

    override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
        this.link = link
        session = null
        notifiedKey = null
        if (AidexX.DATA !in characteristics) return retry("Aidex service not found (status $status)")
        events.onStatus(KEYS)
        val key = if (link.isBonded) AidexX.KEY_BONDED else AidexX.KEY_UNBONDED
        link.subscribe(key)
        link.subscribe(AidexX.DATA)
        link.write(key, AidexX.askKey(serial), withResponse = false)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            AidexX.KEY_UNBONDED, AidexX.KEY_BONDED -> if (value.size == 16) {
                notifiedKey = value
                link?.read(AidexX.DATA)   // the sensor answers a read with the session key
            }
            AidexX.DATA -> reply(value)
        }
    }

    override fun onRead(characteristic: UUID, value: ByteArray) {
        if (characteristic == AidexX.DATA) onDataRead(value)
    }

    /** The 17-byte read from [AidexX.DATA] that follows the key notification. */
    fun onDataRead(value: ByteArray) {
        val key = notifiedKey ?: return
        val s = AidexX.sessionKey(key, iv, value) ?: return fail("wrong serial number for this sensor")
        session = s
        ask(AidexX.OP_START_TIME)
    }

    private fun ask(opcode: Int) {
        val s = session ?: return
        link?.write(AidexX.DATA, AidexX.command(s, iv, opcode), withResponse = false)
    }

    private fun reply(encrypted: ByteArray) {
        val s = session ?: return
        val plain = AidexX.reply(s, iv, encrypted) ?: return
        when (plain.firstOrNull()?.toInt()?.and(0xFF)) {
            // the glucose records count minutes from the start, so the start time is only the cue to ask
            AidexX.OP_START_TIME -> ask(AidexX.OP_GLUCOSE)
            AidexX.OP_GLUCOSE -> {
                AidexX.Glucose.parseReply(plain)?.let { onGlucose(it) }
                link?.cancel(poll)
                link?.schedule(POLL_MS, poll)
            }
        }
    }

    /** A record from a reply or an advertisement — the same 8 bytes either way. */
    fun onGlucose(g: AidexX.Glucose) {
        val start = now() - g.minutesSinceStart * 60L
        startEpoch = start
        sessionStart(start)
        if (g.warmingUp) return events.onStatus("warming up")
        if (!g.usable) return events.onStatus("no value")
        if (g.minutesSinceStart == lastMinute) return
        lastMinute = g.minutesSinceStart
        reading(Bridge.Reading(start + g.minutesSinceStart * 60L, g.mgdl, g.trend.toDouble()))
    }

    override fun onDisconnected() { link?.cancel(poll) }
    override fun onClosed() { link?.cancel(poll) }

    companion object {
        /** How often a connected sensor is asked for its value (it measures every minute). */
        const val POLL_MS = 60_000L
        const val KEYS = "exchanging keys"
    }
}
