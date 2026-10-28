// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.aidex

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
 * Aidex X: derive the keys from the serial ([Pairing.serial], typed from the box), take the session key from the
 * sensor, then ask it for its value once a minute. Untested on a real sensor.
 *
 * Sequence (Juggluco `aidexx/`): subscribe the key characteristic (F001 unbonded / F003 bonded) and F002 → write the
 * ask-key → the sensor notifies a 16-byte key → read F002 (17 bytes: the session key + CRC-8, encrypted with it) →
 * 0x21 start time → 0x11 glucose → every [POLL_MS].
 *
 * Times: a record counts minutes from the sensor's start. The start is derived once (phone time − minutes) and kept
 * with the pairing, so a record the sensor repeats keeps its old time (history, not the current value). Values in the
 * sensor's advertisement are used only once that start is known.
 */
internal class AidexXDriver(
    pairing: Pairing,
    events: SensorEvents,
    clockMs: () -> Long = System::currentTimeMillis,
    log: (String) -> Unit = {},
) : BrandDriver(pairing, events, clockMs, log) {
    private val serial = pairing.serial.uppercase()
    private val iv = AidexX.iv(serial)
    private var notifiedKey: ByteArray? = null
    private var session: ByteArray? = null
    private var lastMinute = -1
    private val poll = Runnable { ask(AidexX.OP_GLUCOSE) }

    override val scan = listOf(ScanSpec(name = AidexX.deviceName(serial)))

    override fun matches(advert: Advert) = advert.name == AidexX.deviceName(serial)

    override fun onAdvertisement(
        advert: Advert,
        record: ByteArray,
    ) {
        if (advert.name != AidexX.deviceName(serial) || knownStart == null) return
        val a = AidexX.Advertisement.parse(record) ?: return
        if (a.crcOk && a.name == AidexX.deviceName(serial)) onGlucose(a.latest)
    }

    override fun onConnected(
        link: BleLink,
        characteristics: Set<UUID>,
        status: Int,
    ) {
        this.link = link
        session = null
        notifiedKey = null
        if (AidexX.DATA !in characteristics) return retry("Aidex service not found (status $status)")
        events.onStatus(LinkStatus.Authorizing)
        val key = if (link.isBonded) AidexX.KEY_BONDED else AidexX.KEY_UNBONDED
        if (key !in characteristics) return retry("Aidex key characteristic not found")
        link.subscribe(key)
        link.subscribe(AidexX.DATA)
        link.writeNoResponse(key, AidexX.askKey(serial))
    }

    override fun onNotification(
        characteristic: UUID,
        value: ByteArray,
    ) {
        when (characteristic) {
            AidexX.KEY_UNBONDED, AidexX.KEY_BONDED -> {
                if (value.size == 16 && session == null) {
                    notifiedKey = value
                    link?.read(AidexX.DATA) // the sensor answers a read with the session key
                }
            }

            AidexX.DATA -> {
                reply(value)
            }
        }
    }

    override fun onRead(
        characteristic: UUID,
        value: ByteArray,
    ) {
        if (characteristic == AidexX.DATA) onDataRead(value)
    }

    /** The 17-byte read from [AidexX.DATA] that follows the key notification. */
    fun onDataRead(value: ByteArray) {
        val key = notifiedKey ?: return
        session = AidexX.sessionKey(key, iv, value) ?: return fail(FailReason.WrongId, "wrong serial number for this sensor")
        events.onStatus(LinkStatus.Authorized)
        ask(AidexX.OP_START_TIME)
    }

    private fun ask(opcode: Int) {
        val s = session ?: return
        link?.writeNoResponse(AidexX.DATA, AidexX.command(s, iv, opcode))
        // the next ask is armed now: a lost write or reply never stops the polling
        if (opcode == AidexX.OP_GLUCOSE) {
            link?.cancel(poll)
            link?.schedule(POLL_MS, poll)
        }
    }

    private fun reply(encrypted: ByteArray) {
        val s = session ?: return
        val plain = AidexX.reply(s, iv, encrypted) ?: return
        when (plain.firstOrNull()?.toInt()?.and(0xFF)) {
            // the records count minutes from the start, so the start-time reply is only the cue to ask for the value
            AidexX.OP_START_TIME -> {
                ask(AidexX.OP_GLUCOSE)
            }

            AidexX.OP_GLUCOSE -> {
                AidexX.Glucose.parseReply(plain)?.let { onGlucose(it) }
            }
        }
    }

    /** A record from a reply or an advertisement: the same 8 bytes either way. */
    fun onGlucose(g: AidexX.Glucose) {
        val start = sessionStart(now() - g.minutesSinceStart * 60L, authoritative = false)
        if (g.warmingUp) return events.onStatus(LinkStatus.WarmingUp((profile.warmupMin - g.minutesSinceStart).coerceAtLeast(0)))
        if (!g.valid) return events.onStatus(LinkStatus.NoValue(NoValueReason.Missing))
        if (g.minutesSinceStart == lastMinute) return
        lastMinute = g.minutesSinceStart
        reading(start + g.minutesSinceStart * 60L, g.mgdl, g.trend.toDouble())
    }

    override fun onDisconnected() {
        link?.cancel(poll)
    }

    override fun onClosed() {
        link?.cancel(poll)
    }

    companion object {
        /** How often a connected sensor is asked for its value (it measures every minute). */
        const val POLL_MS = 60_000L
    }
}
