// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.dexcom

import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandDriver
import app.mauri.sukrara.sensor.brand.ScanSpec
import app.mauri.sukrara.sensor.pairing.Pairing
import java.util.UUID

/**
 * Dexcom G7 / ONE+ / Stelo, reading along with the Dexcom app. The G7 accepts several Bluetooth clients and indicates
 * its authentication status to all of them: this driver connects, watches the authentication characteristic until it
 * sees `authenticated=1, bonded=1` (the Dexcom app's session), then subscribes to the control and backfill
 * characteristics and reads the same glucose the Dexcom app gets. It writes nothing to the sensor (only its own
 * subscriptions), like LoopKit's G7SensorKit ("Requires use of official G7 app").
 *
 * Which sensor: a `DXCM…` / `DX01…` / `DX02…` / `DX04…` device this phone is bonded with, i.e. the one the Dexcom app
 * paired. A stranger's G7 nearby is never bonded to this phone, so it is never read. Untested on a real sensor.
 */
internal class G7Driver(
    pairing: Pairing,
    events: SensorEvents,
    clockMs: () -> Long = System::currentTimeMillis,
    log: (String) -> Unit = {},
) : BrandDriver(pairing, events, clockMs, log) {
    /** Where the reader is. The sensor and the Dexcom app drive it; Sukrara only listens. */
    enum class Phase { Idle, Watching, Streaming }

    var phase = Phase.Idle
        private set

    private var lastSequence = -1
    private val backfill = ArrayList<Triple<Long, Int, Double?>>()

    /** The start this connection's messages were timed against (epoch s). */
    private var start: Long? = null

    override val validMgdl = Dexcom.VALID_MGDL
    override val plausibleMgdl = Dexcom.PLAUSIBLE_MGDL

    override val scan = listOf(ScanSpec(service = Dexcom.ADVERTISEMENT))

    override fun matches(advert: Advert): Boolean {
        val n = advert.name ?: return false
        return advert.bonded && PREFIXES.any { n.startsWith(it) }
    }

    override fun onConnected(
        link: BleLink,
        characteristics: Set<UUID>,
        status: Int,
    ) {
        this.link = link
        if (Dexcom.AUTHENTICATION !in characteristics || Dexcom.CONTROL !in characteristics) {
            return retry("Dexcom services not found (status $status)")
        }
        phase = Phase.Watching
        events.onStatus(LinkStatus.WaitingForVendorApp)
        link.indicate(Dexcom.AUTHENTICATION)
    }

    override fun onNotification(
        characteristic: UUID,
        value: ByteArray,
    ) {
        when (characteristic) {
            Dexcom.AUTHENTICATION -> G7Messages.AuthStatus.parse(value)?.let { if (it.live) startStreaming() }
            Dexcom.CONTROL -> control(value)
            Dexcom.BACKFILL -> onBackfillRecords(value)
        }
    }

    private fun startStreaming() {
        val link = link ?: return
        if (phase == Phase.Streaming) return
        phase = Phase.Streaming
        events.onStatus(LinkStatus.Authorized)
        link.subscribe(Dexcom.CONTROL)
        link.subscribe(Dexcom.BACKFILL)
        // no write: the Dexcom app asks for the values, and the sensor notifies every subscriber
    }

    private fun control(value: ByteArray) {
        if (phase != Phase.Streaming) return
        G7Messages.Egv.parse(value)?.let {
            onEgv(it)
            return
        }
        if (G7Messages.isBackfillFinished(value)) flushBackfill()
    }

    /** One 0x4E message → the session start, then a reading, a wait (warming up), no value or the end. */
    fun onEgv(egv: G7Messages.Egv) {
        // A sensor already past its wear before this pairing ever saw it is an old one still bonded to this phone
        // (ended, or in its 12 h grace period): never adopted, never allowed to end the new pairing.
        val old = knownStart == null && egv.secondsSinceStart >= profile.wearMin * 60L
        when (Dexcom.state(egv.state)) {
            Dexcom.State.Ended -> {
                return if (old) wrongDevice("ended, ${egv.secondsSinceStart / 3600} h old") else ended("algorithm state ${egv.state}")
            }

            // before the Dexcom app starts the session: its clock means nothing yet
            Dexcom.State.Stopped -> {
                return events.onStatus(LinkStatus.NoSession)
            }

            else -> {
                if (old) return wrongDevice("${egv.secondsSinceStart / 3600} h old, past its wear")
            }
        }
        val s = sessionStart(now() - egv.secondsSinceStart)
        start = s
        when (Dexcom.state(egv.state)) {
            Dexcom.State.WarmingUp -> {
                events.onStatus(LinkStatus.WarmingUp(minutesLeft(egv.secondsSinceStart)))
            }

            Dexcom.State.Ok -> {
                val mgdl = egv.mgdl ?: return events.onStatus(LinkStatus.NoValue(NoValueReason.Missing))
                if (egv.sequence == lastSequence) return // another subscriber's copy of the same message
                lastSequence = egv.sequence
                reading(s + egv.readingSeconds, mgdl, egv.trend)
            }

            Dexcom.State.NoValue -> {
                events.onStatus(LinkStatus.NoValue(Dexcom.noValueReason(egv.state)))
            }

            else -> {
                events.onStatus(LinkStatus.NoValue(NoValueReason.SensorError))
            }
        }
    }

    private fun minutesLeft(secondsSinceStart: Long) = ((profile.warmupMin * 60L - secondsSinceStart) / 60).toInt().coerceAtLeast(0)

    /** Backfill notifications are buffered until the sensor says it is done (G7SensorKit does the same). */
    fun onBackfillRecords(value: ByteArray) {
        val s = start ?: return
        if (backfill.size >= MAX_BACKFILL) return
        G7Messages.BackfillRecord
            .parseAll(value)
            .filter { Dexcom.state(it.state) == Dexcom.State.Ok && it.mgdl != null }
            .forEach { backfill.add(Triple(s + it.secondsSinceStart, it.mgdl!!, it.trend)) }
    }

    fun flushBackfill() {
        if (backfill.isEmpty()) return
        history(backfill.toList())
        backfill.clear()
    }

    override fun onDisconnected() {
        flushBackfill()
        phase = Phase.Idle
    }

    override fun onClosed() {
        flushBackfill()
        phase = Phase.Idle
    }

    private companion object {
        /** G7SensorKit: the G7 advertises "DXCMxx", the ONE+ "DX02xx"; Juggluco also accepts DX01 / DX04. */
        val PREFIXES = listOf("DXCM", "DX01", "DX02", "DX04")

        /** 24 h of 5-minute records: a bound on what one backfill can hold. */
        const val MAX_BACKFILL = 288
    }
}
