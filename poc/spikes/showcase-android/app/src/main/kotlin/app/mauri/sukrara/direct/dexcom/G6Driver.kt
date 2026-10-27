// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.dexcom

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
import java.security.SecureRandom
import java.util.UUID

/**
 * **Dexcom G6 / ONE** (and G5), investigation 23 step 5: the whole handshake is keyed by the transmitter id the user
 * reads off the device ([SensorPairing.serial]), so Sukrara pairs with it on its own — no Dexcom-issued certificate.
 *
 * Flow (xDrip+ `Ob1G5CollectionService` / `Ob1G5StateMachine`): subscribe authentication → 0x01 token → 0x03 challenge
 * → verify the transmitter → 0x04 hash → 0x05 authenticated → bond on the first session → 0x24 time (session start)
 * → 0x4E glucose → 0x50 backfill for the gap. The transmitter needs a session started by the Dexcom app or receiver;
 * Sukrara starts none. Untested on hardware.
 */
internal class G6Driver(
    pairing: SensorPairing,
    events: SensorEvents,
    prefs: DriverPrefs,
    /** G6 firmware answers 0x4E with a prediction; a G5 or an old G6 only answers 0x30. */
    private val extended: Boolean = true,
    private val random: (ByteArray) -> Unit = { SecureRandom().nextBytes(it) },
    clock: () -> Long = System::currentTimeMillis,
    log: (priority: Int, message: String) -> Unit = { p, m -> Log.println(p, "Sukrara.g6", m) },
) : BrandDriver(pairing, events, prefs, clock, log) {
    override val profile = SensorProfiles.DEXCOM_G6

    private val transmitterId = pairing.serial.uppercase()
    private val key = G6Messages.key(transmitterId)
    private var token = ByteArray(8)
    /** The transmitter's clock as an epoch: epoch = [txEpoch] + transmitter seconds. */
    private var txEpoch: Long? = null
    private var lastBackfillTo = 0
    private val backfill = G6Messages.BackfillStream()

    override val scan = listOf(ScanSpec(name = "Dexcom${transmitterId.takeLast(2)}"))

    /** A Dexcom transmitter advertises "Dexcom" + the last two characters of its id. */
    override fun matches(advert: Advert) = advert.name == "Dexcom${transmitterId.takeLast(2)}"

    override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
        this.link = link
        if (Dexcom.AUTHENTICATION !in characteristics || Dexcom.CONTROL !in characteristics)
            return retry("Dexcom services not found (status $status)")
        events.onStatus("authenticating")
        backfill.clear()
        link.subscribe(Dexcom.AUTHENTICATION, indicate = true)
        random(token)
        link.write(Dexcom.AUTHENTICATION, G6Messages.authRequest(token))
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            Dexcom.AUTHENTICATION -> authentication(value)
            Dexcom.CONTROL -> control(value)
            Dexcom.BACKFILL -> if (!backfill.push(value)) log(Log.WARN, "backfill piece out of order")
        }
    }

    private fun authentication(value: ByteArray) {
        val link = link ?: return
        G6Messages.AuthChallenge.parse(value)?.let { challenge ->
            // The transmitter proves it holds the same key before we answer: its tokenHash must match our token.
            if (!Dexcom.challengeHash(key, token).contentEquals(challenge.tokenHash))
                return fail("wrong transmitter id for this transmitter")
            link.write(Dexcom.AUTHENTICATION, G6Messages.challengeReply(Dexcom.challengeHash(key, challenge.challenge)))
            return
        }
        G6Messages.AuthStatus.parse(value)?.let { status ->
            if (!status.ok) return fail("the transmitter refused the authentication")
            if (status.bonded != 1 && !link.isBonded) {
                events.onStatus("pairing")
                link.write(Dexcom.AUTHENTICATION, G6Messages.bondRequest())
                link.bond()
                return
            }
            startStreaming()
        }
    }

    override fun onBondChanged(bonded: Boolean) {
        if (bonded) startStreaming() else fail("Android's Bluetooth pairing with the transmitter didn't complete")
    }

    private fun startStreaming() {
        val link = link ?: return
        events.onStatus("reading")
        link.subscribe(Dexcom.CONTROL, indicate = true)
        link.subscribe(Dexcom.BACKFILL)
        link.write(Dexcom.CONTROL, G6Messages.timeRequest())
        link.write(Dexcom.CONTROL, if (extended) G6Messages.eGlucoseRequest() else G6Messages.glucoseRequest())
    }

    private fun control(value: ByteArray) {
        G6Messages.Time.parse(value)?.let { t ->
            if (t.sessionInProgress) sessionStart(t.sessionStartEpoch(now()))
            else events.onStatus(NO_SESSION)
            return
        }
        G6Messages.Glucose.parse(value)?.let { onGlucose(it); return }
        G6Messages.BackfillReply.parse(value)?.let {
            val epoch = txEpoch ?: return
            val readings = backfill.records().filter { r -> r.usable }
                .map { r -> Bridge.Reading(epoch + r.timeSeconds, r.mgdl, r.trend?.toDouble()) }
            backfill.clear()
            if (readings.isNotEmpty()) events.onHistory(readings)
        }
    }

    fun onGlucose(g: G6Messages.Glucose) {
        val epoch = now() - g.timeSeconds
        txEpoch = epoch
        if (Dexcom.stateEnded(g.state)) return ended(Dexcom.stateName(g.state))
        if (Dexcom.stateWarmingUp(g.state)) return events.onStatus("warming up")
        if (!g.usable) return events.onStatus(Dexcom.stateName(g.state))
        reading(Bridge.Reading(epoch + g.timeSeconds, g.mgdl, g.trend?.toDouble()))
        // Fill the gap since the last value we have, in the transmitter's own time base (the last 24 h at most).
        val from = maxOf(lastBackfillTo + 300, g.timeSeconds - 24 * 3600)
        if (g.timeSeconds - from >= 300) {
            link?.write(Dexcom.CONTROL, G6Messages.backfillRequest(from, g.timeSeconds))
            lastBackfillTo = g.timeSeconds
        }
    }

    override fun onDisconnected() = backfill.clear()
    override fun onClosed() = backfill.clear()

    companion object {
        /** The transmitter runs but no sensor session is started (the Dexcom app or receiver starts it). */
        const val NO_SESSION = "no sensor session"
    }
}
