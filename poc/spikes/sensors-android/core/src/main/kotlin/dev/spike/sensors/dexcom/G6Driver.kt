// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import dev.spike.sensors.Advert
import dev.spike.sensors.BleLink
import dev.spike.sensors.DriverStatus
import dev.spike.sensors.Reading
import dev.spike.sensors.SensorDriver
import dev.spike.sensors.SensorEvents
import dev.spike.sensors.SensorProfiles
import dev.spike.sensors.Session
import java.security.SecureRandom
import java.util.UUID

/**
 * **Dexcom G6 / ONE** (and G5): the whole handshake is keyed by the transmitter id the user reads off the device,
 * so Sukrara can pair with it on its own — no Dexcom-issued app certificate, unlike the G7 ([G7Driver]).
 *
 * Flow (xDrip+ `Ob1G5CollectionService` / `Ob1G5StateMachine`, Juggluco has no G6):
 *   subscribe authentication → 0x01 token slot → 0x03 challenge → verify the transmitter → 0x04 hash →
 *   0x05 authenticated → (bond on the first session) → 0x30/0x4E glucose → 0x50 backfill for the gap.
 *
 * Untested on hardware: the user owns no Dexcom sensor (investigation 23).
 */
class G6Driver(
    private val transmitterId: String,
    private val events: SensorEvents,
    /** G6 firmware answers 0x4E with a prediction; a G5 or an old G6 only answers 0x30. */
    private val extended: Boolean = true,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) : SensorDriver {
    override val profile = SensorProfiles.DEXCOM_G6

    private val key = G6Messages.key(transmitterId)
    private var token = ByteArray(8)
    private var link: BleLink? = null
    private var sessionStartMs: Long? = null
    private var lastBackfillTo = 0
    private val backfill = G6Messages.BackfillStream()

    /** The advertised name of a Dexcom transmitter is "Dexcom" + the last two characters of its id. */
    override fun matches(advert: Advert): Boolean {
        val tail = transmitterId.takeLast(2)
        return advert.name == "Dexcom$tail" || (advert.name?.startsWith("DX") == true && Dexcom.ADVERTISEMENT in advert.serviceUuids)
    }

    override fun onConnected(link: BleLink) {
        this.link = link
        events.onStatus(DriverStatus.Progress("authenticating"))
        link.subscribe(Dexcom.AUTHENTICATION, indicate = true)
        random.nextBytes(token)
        link.write(Dexcom.AUTHENTICATION, G6Messages.authRequest(token))
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            Dexcom.AUTHENTICATION -> authentication(value)
            Dexcom.CONTROL -> control(value)
            Dexcom.BACKFILL -> if (!backfill.push(value)) events.onStatus(DriverStatus.Progress("backfill out of order"))
        }
    }

    private fun authentication(value: ByteArray) {
        val link = link ?: return
        G6Messages.AuthChallenge.parse(value)?.let { challenge ->
            // The transmitter proves it holds the same key before we answer: its tokenHash must match our token.
            if (!Dexcom.challengeHash(key, token).contentEquals(challenge.tokenHash)) {
                events.onStatus(DriverStatus.Failed("wrong transmitter id for this device"))
                link.disconnect()
                return
            }
            link.write(Dexcom.AUTHENTICATION, G6Messages.challengeReply(Dexcom.challengeHash(key, challenge.challenge)))
            return
        }
        G6Messages.AuthStatus.parse(value)?.let { status ->
            if (!status.ok) {
                events.onStatus(DriverStatus.Failed("transmitter refused the authentication"))
                link.disconnect()
                return
            }
            if (status.bonded != 1) {
                events.onStatus(DriverStatus.Progress("pairing"))
                link.write(Dexcom.AUTHENTICATION, G6Messages.bondRequest())
                link.bond()
                return
            }
            startStreaming()
        }
    }

    private fun startStreaming() {
        val link = link ?: return
        events.onStatus(DriverStatus.Progress("reading"))
        link.subscribe(Dexcom.CONTROL, indicate = true)
        link.subscribe(Dexcom.BACKFILL)
        link.write(Dexcom.CONTROL, if (extended) G6Messages.eGlucoseRequest() else G6Messages.glucoseRequest())
    }

    override fun onBondChanged(bonded: Boolean) {
        if (bonded) startStreaming()
    }

    private fun control(value: ByteArray) {
        G6Messages.Glucose.parse(value)?.let { g ->
            val start = now() - g.timeSeconds * 1000L
            sessionStartMs = start
            events.onSession(Session(start, start + profile.warmup.inWholeMilliseconds,
                start + profile.lifetime.inWholeMilliseconds, Dexcom.stateName(g.state)))
            if (Dexcom.stateEnded(g.state)) {
                events.onStatus(DriverStatus.Ended(Dexcom.stateName(g.state)))
                return
            }
            if (!g.usable) {
                events.onStatus(DriverStatus.Progress(Dexcom.stateName(g.state)))
                return
            }
            events.onReading(Reading(now(), g.mgdl, g.trend, Dexcom.stateName(g.state), g.timeSeconds / 300))
            events.onStatus(DriverStatus.Streaming)
            // Fill the gap since the last value we have, in the transmitter's own time base.
            val from = (lastBackfillTo + 300).coerceAtMost(g.timeSeconds)
            if (g.timeSeconds - from >= 300) {
                link?.write(Dexcom.CONTROL, G6Messages.backfillRequest(from, g.timeSeconds))
                lastBackfillTo = g.timeSeconds
            }
            return
        }
        G6Messages.BackfillReply.parse(value)?.let {
            val start = sessionStartMs ?: return
            val readings = backfill.records().filter { r -> r.usable }
                .map { r -> Reading(start + r.timeSeconds * 1000L, r.mgdl, r.trend, index = r.timeSeconds / 300) }
            if (readings.isNotEmpty()) events.onHistory(readings)
        }
    }

    override fun onDisconnected() {
        link = null
    }
}
