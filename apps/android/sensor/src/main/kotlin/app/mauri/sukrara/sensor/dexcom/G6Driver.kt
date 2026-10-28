// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.dexcom

import app.mauri.sukrara.sensor.ble.BleLink
import app.mauri.sukrara.sensor.ble.FailReason
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.ble.SensorEvents
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandDriver
import app.mauri.sukrara.sensor.brand.ScanSpec
import app.mauri.sukrara.sensor.pairing.Pairing
import java.security.SecureRandom
import java.util.UUID

/**
 * Dexcom G6 / ONE (and G5): the whole handshake is keyed by the transmitter id the person reads off the device
 * ([Pairing.serial]), so Sukrara pairs with it on its own, with no Dexcom-issued certificate.
 *
 * Flow (xDrip+ `Ob1G5StateMachine`): indicate authentication → 0x01 token → 0x03 challenge → check the transmitter
 * knows the same key → 0x04 hash → 0x05 authenticated → bond on the first session → 0x24 time (the transmitter's
 * clock and session start) → 0x4E glucose → 0x50 backfill for the gap. The transmitter needs a session started by the
 * Dexcom app or receiver; Sukrara starts none. Untested on a real transmitter.
 *
 * Times: every value is timed in the transmitter's own clock, anchored by the 0x25 reply (phone time − transmitter
 * time); a value that arrives before that anchor is not used.
 */
internal class G6Driver(
    pairing: Pairing,
    events: SensorEvents,
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    clockMs: () -> Long = System::currentTimeMillis,
    log: (String) -> Unit = {},
) : BrandDriver(pairing, events, clockMs, log) {
    private val transmitterId = pairing.serial.uppercase()
    private val key = G6Messages.key(transmitterId)
    private val token = ByteArray(8)

    /** Phone epoch of transmitter time 0, from this connection's 0x25 reply. */
    private var txEpoch: Long? = null
    private var lastSequence = -1
    private var backfilledTo = 0
    private var backfillPending = false
    private var backfillBroken = false
    private val backfill = G6Messages.BackfillStream()

    override val validMgdl = Dexcom.VALID_MGDL
    override val plausibleMgdl = Dexcom.PLAUSIBLE_MGDL

    override val scan = listOf(ScanSpec(name = advertisedName()))

    /** A Dexcom transmitter advertises "Dexcom" + the last two characters of its id. */
    override fun matches(advert: Advert) = advert.name == advertisedName()

    private fun advertisedName() = "Dexcom${transmitterId.takeLast(2)}"

    override fun onConnected(
        link: BleLink,
        characteristics: Set<UUID>,
        status: Int,
    ) {
        this.link = link
        txEpoch = null
        backfill.clear()
        backfillPending = false
        backfillBroken = false
        if (Dexcom.AUTHENTICATION !in characteristics || Dexcom.CONTROL !in characteristics) {
            return retry("Dexcom services not found (status $status)")
        }
        events.onStatus(LinkStatus.Authorizing)
        link.indicate(Dexcom.AUTHENTICATION)
        random(token)
        link.write(Dexcom.AUTHENTICATION, G6Messages.authRequest(token))
    }

    override fun onNotification(
        characteristic: UUID,
        value: ByteArray,
    ) {
        when (characteristic) {
            Dexcom.AUTHENTICATION -> authentication(value)
            Dexcom.CONTROL -> control(value)
            Dexcom.BACKFILL -> if (!backfill.push(value)) backfillBroken = true
        }
    }

    private fun authentication(value: ByteArray) {
        val link = link ?: return
        G6Messages.AuthChallenge.parse(value)?.let { challenge ->
            // the transmitter proves it holds the same key before we answer: its tokenHash must match our token
            if (!Dexcom.challengeHash(key, token).contentEquals(challenge.tokenHash)) {
                return fail(FailReason.WrongId, "wrong transmitter id for this transmitter")
            }
            link.write(Dexcom.AUTHENTICATION, G6Messages.challengeReply(Dexcom.challengeHash(key, challenge.challenge)))
            return
        }
        G6Messages.AuthStatus.parse(value)?.let { status ->
            if (!status.ok) return fail(FailReason.Refused, "the transmitter refused the authentication")
            if (status.bonded != 1 && !link.isBonded) {
                events.onStatus(LinkStatus.Pairing)
                link.write(Dexcom.AUTHENTICATION, G6Messages.bondRequest())
                link.bond()
                return
            }
            startStreaming()
        }
    }

    override fun onBondChanged(bonded: Boolean) {
        if (bonded) startStreaming() else fail(FailReason.BondFailed, "Android's Bluetooth pairing with the transmitter didn't complete")
    }

    private fun startStreaming() {
        val link = link ?: return
        events.onStatus(LinkStatus.Authorized)
        link.indicate(Dexcom.CONTROL)
        link.subscribe(Dexcom.BACKFILL)
        link.write(Dexcom.CONTROL, G6Messages.timeRequest())
        link.write(Dexcom.CONTROL, G6Messages.eGlucoseRequest())
    }

    private fun control(value: ByteArray) {
        G6Messages.Time.parse(value)?.let { t ->
            val epoch = now() - t.currentSeconds
            txEpoch = epoch
            if (t.sessionInProgress) sessionStart(epoch + t.sessionStartSeconds) else events.onStatus(LinkStatus.NoSession)
            return
        }
        G6Messages.Glucose.parse(value)?.let {
            onGlucose(it)
            return
        }
        G6Messages.BackfillReply.parse(value)?.let { onBackfillDone(it) }
    }

    fun onGlucose(g: G6Messages.Glucose) {
        val epoch = txEpoch ?: return log("glucose before the transmitter's clock: not used")
        when (Dexcom.state(g.state)) {
            // the session is over, not the transmitter: it lives on for the next session the Dexcom app starts
            Dexcom.State.Ended -> {
                events.onSensorEnded(pairing.label)
                return events.onStatus(LinkStatus.NoSession)
            }

            Dexcom.State.Stopped -> {
                return events.onStatus(LinkStatus.NoSession)
            }

            Dexcom.State.WarmingUp -> {
                val start = knownStart
                val left = if (start == null) profile.warmupMin else ((start + profile.warmupMin * 60L - now()) / 60).toInt()
                return events.onStatus(LinkStatus.WarmingUp(left.coerceAtLeast(0)))
            }

            Dexcom.State.NoValue -> {
                return events.onStatus(LinkStatus.NoValue(Dexcom.noValueReason(g.state)))
            }

            Dexcom.State.Unknown -> {
                return events.onStatus(LinkStatus.NoValue(NoValueReason.SensorError))
            }

            Dexcom.State.Ok -> {}
        }
        if (g.sequence == lastSequence) return
        lastSequence = g.sequence
        reading(epoch + g.timeSeconds, g.mgdl, g.trend)
        // fill the gap since the last backfill, in the transmitter's own time (the last 24 h at most)
        val from = maxOf(backfilledTo + 300, g.timeSeconds - 24 * 3600)
        if (!backfillPending && g.timeSeconds - from >= 300) {
            backfillPending = true
            backfill.clear()
            backfillBroken = false
            link?.write(Dexcom.CONTROL, G6Messages.backfillRequest(from, g.timeSeconds))
        }
    }

    /** The 0x51 reply closes a backfill: its records become history (never live), only if every piece arrived in order. */
    private fun onBackfillDone(reply: G6Messages.BackfillReply) {
        backfillPending = false
        val epoch = txEpoch
        if (epoch == null || backfillBroken) {
            backfill.clear()
            return log("backfill dropped (pieces out of order or no clock)")
        }
        val values =
            backfill
                .records()
                .filter { Dexcom.state(it.state) == Dexcom.State.Ok && it.timeSeconds in reply.startSeconds..reply.endSeconds }
                .map { Triple(epoch + it.timeSeconds, it.mgdl, it.trend) }
        backfill.clear()
        backfilledTo = reply.endSeconds
        history(values)
    }

    override fun onDisconnected() = backfill.clear()

    override fun onClosed() = backfill.clear()
}
