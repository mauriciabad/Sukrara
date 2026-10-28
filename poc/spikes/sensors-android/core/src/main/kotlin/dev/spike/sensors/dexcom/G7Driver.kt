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
import dev.spike.sensors.SensorProfile
import dev.spike.sensors.SensorProfiles
import dev.spike.sensors.Session
import java.security.SecureRandom
import java.util.UUID

/**
 * **Dexcom G7 / ONE+ / Stelo.** The sensor sends finished mg/dL every 5 minutes and backfills on request, so
 * decoding ([G7Messages]) is the easy half. Getting a session is the hard half, and this driver offers the two
 * honest ways to have one:
 *
 * - **[Mode.OBSERVE] (the default).** The G7 accepts several BLE clients and indicates its authentication status to
 *   all of them. So with the official Dexcom app installed and running, this driver connects, watches the
 *   authentication characteristic until it sees `authenticated=1, bonded=1`, and then subscribes to the control
 *   characteristic and reads the same glucose the official app gets. **It never writes anything to the sensor.**
 *   This is exactly what LoopKit's G7SensorKit does in production ("Requires use of official G7 app").
 * - **[Mode.PAIR].** The sensor's own pairing: EC-J-PAKE with the 4-digit code on the applicator ([G7Jpake]) — the
 *   user's own secret, implemented here — and then an **app certificate chain issued by Dexcom plus a signature
 *   from its private key**. Sukrara has no such identity, and lifting one out of Dexcom's app (what Juggluco embeds
 *   and what xDrip+'s "keks" plugin asks the user to paste in) is the same borrowing the Libre 2 decision already
 *   rejected (docs/02). So this mode runs the J-PAKE, then stops with a clear reason at
 *   [Phase.CERTIFICATE_BLOCKED]. A sensor that **was** paired with Sukrara before resumes from the saved key.
 *
 * Untested on hardware: the user owns no Dexcom sensor (investigation 23).
 */
class G7Driver(
    private val events: SensorEvents,
    private val mode: Mode = Mode.OBSERVE,
    /** The 4-digit code on the applicator (6 digits on some boxes). Only used by [Mode.PAIR]. */
    pairingCode: String? = null,
    /** The key kept from an earlier pairing ([SensorEvents.onPairingState]); null = a fresh pairing. */
    savedKey: ByteArray? = null,
    /** "DXCM…" / "DX02…" sensor name to stick to, or null to take the first G7 seen. */
    private val sensorName: String? = null,
    override val profile: SensorProfile = SensorProfiles.DEXCOM_G7,
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) : SensorDriver {

    enum class Mode { OBSERVE, PAIR }

    /** Where the handshake is. The sensor drives it; we only answer. */
    enum class Phase { IDLE, WATCHING, JPAKE_0, JPAKE_1, JPAKE_2, AUTH, CHALLENGE, CERTIFICATE_BLOCKED, STREAMING }

    var phase = Phase.IDLE
        private set

    private val jpake = pairingCode?.let { G7Jpake(it, random) }
    private var key: ByteArray? = savedKey
    private var token = ByteArray(8)
    private var link: BleLink? = null
    private var sessionStartMs: Long? = null
    private var inbound = ByteArray(0)   // J-PAKE packets arrive in 20-byte pieces
    private var lastBackfillTo = 0
    private val backfill = ArrayList<Reading>()

    override fun matches(advert: Advert): Boolean {
        val n = advert.name ?: return false
        // G7SensorKit: the G7 advertises "DXCMxx", the ONE+ "DX02xx"; Juggluco also accepts DX01 / DX04.
        if (!(n.startsWith("DXCM") || n.startsWith("DX01") || n.startsWith("DX02") || n.startsWith("DX04"))) return false
        return sensorName == null || n.takeLast(2) == sensorName.takeLast(2)
    }

    override fun onConnected(link: BleLink) {
        this.link = link
        inbound = ByteArray(0)
        link.subscribe(Dexcom.AUTHENTICATION, indicate = true)
        when {
            mode == Mode.OBSERVE -> {
                phase = Phase.WATCHING
                events.onStatus(DriverStatus.Progress("waiting for the Dexcom app's session"))
            }
            key != null -> {
                phase = Phase.AUTH
                events.onStatus(DriverStatus.Progress("authenticating"))
                link.subscribe(Dexcom.EXCHANGE)
                sendAuthRequest()
            }
            jpake != null -> {
                phase = Phase.JPAKE_0
                events.onStatus(DriverStatus.Progress("pairing with the applicator code"))
                link.subscribe(Dexcom.EXCHANGE)
                link.write(Dexcom.AUTHENTICATION, G7Messages.jpakeRound(0))
            }
            else -> {
                events.onStatus(DriverStatus.Failed("no pairing code and no saved key"))
                link.disconnect()
            }
        }
    }

    private fun sendAuthRequest() {
        random.nextBytes(token)
        link?.write(Dexcom.AUTHENTICATION, G7Messages.authRequest(token))
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            Dexcom.EXCHANGE -> exchange(value)
            Dexcom.AUTHENTICATION -> authentication(value)
            Dexcom.CONTROL -> control(value)
            Dexcom.BACKFILL -> onBackfillRecords(value)
        }
    }

    /** The sensor's J-PAKE packets, 20 bytes at a time until 160 ([Mode.PAIR] only). */
    private fun exchange(piece: ByteArray) {
        val link = link ?: return
        val jpake = jpake ?: return
        inbound += piece
        if (inbound.size < G7Jpake.PACKET) return
        val packet = inbound.copyOf(G7Jpake.PACKET)
        inbound = ByteArray(0)
        val round = when (phase) { Phase.JPAKE_0 -> 0; Phase.JPAKE_1 -> 1; Phase.JPAKE_2 -> 2; else -> return }
        if (!jpake.acceptRound(round, packet)) {
            events.onStatus(DriverStatus.Failed(
                if (round == 2) "wrong pairing code" else "the sensor's key exchange did not verify"))
            link.disconnect()
            return
        }
        writePieces(Dexcom.EXCHANGE, jpake.round(round))
        when (round) {
            0, 1 -> {
                phase = if (round == 0) Phase.JPAKE_1 else Phase.JPAKE_2
                link.write(Dexcom.AUTHENTICATION, G7Messages.jpakeRound(round + 1))
            }
            2 -> {
                key = jpake.key
                key?.let { events.onPairingState(it) }
                phase = Phase.AUTH
                sendAuthRequest()
            }
        }
    }

    private fun writePieces(characteristic: UUID, data: ByteArray) {
        var at = 0
        while (at < data.size) {
            val end = minOf(at + 20, data.size)
            link?.write(characteristic, data.copyOfRange(at, end), withResponse = false)
            at = end
        }
    }

    private fun authentication(value: ByteArray) {
        val link = link ?: return
        if (mode == Mode.OBSERVE) {
            // Listen only: the status belongs to the official app's session.
            G7Messages.AuthStatus.parse(value)?.let { if (it.live) startStreaming() }
            return
        }
        val k = key
        G7Messages.AuthChallenge.parse(value)?.let { challenge ->
            if (k == null) return
            if (!Dexcom.challengeHash(k, token).contentEquals(challenge.tokenHash)) {
                events.onStatus(DriverStatus.Failed("the sensor answered with a different key: pair it again"))
                link.disconnect()
                return
            }
            phase = Phase.CHALLENGE
            link.write(Dexcom.AUTHENTICATION, G7Messages.challengeReply(Dexcom.challengeHash(k, challenge.challenge)))
            return
        }
        G7Messages.AuthStatus.parse(value)?.let { status ->
            when {
                status.bonded == 3 -> {
                    events.onStatus(DriverStatus.Failed("another app or device still holds this sensor"))
                    link.disconnect()
                }
                status.live -> startStreaming()
                status.authenticated == 1 -> { link.write(Dexcom.AUTHENTICATION, G7Messages.KEEP_ALIVE_BOND); link.bond() }
                else -> blockedOnCertificate()
            }
            return
        }
        if (G7Messages.CertificateSize.parse(value) != null) blockedOnCertificate()
    }

    /** The one step this spike does not take. See the class comment and docs/23. */
    private fun blockedOnCertificate() {
        phase = Phase.CERTIFICATE_BLOCKED
        events.onStatus(DriverStatus.Failed(
            "this sensor has never been paired with Sukrara and asks for a Dexcom app certificate, which Sukrara " +
            "does not have (docs/23). Start the sensor with the Dexcom app and let Sukrara read along, or use a " +
            "G6 / ONE transmitter."))
        link?.disconnect()
    }

    override fun onBondChanged(bonded: Boolean) {
        if (bonded && phase == Phase.CHALLENGE) startStreaming()
    }

    private fun startStreaming() {
        val link = link ?: return
        if (phase == Phase.STREAMING) return
        phase = Phase.STREAMING
        events.onStatus(DriverStatus.Progress("reading"))
        link.subscribe(Dexcom.CONTROL)
        link.subscribe(Dexcom.BACKFILL)
        // Observer mode never writes: the official app asks for the values, and the sensor notifies everyone.
        if (mode == Mode.PAIR) link.write(Dexcom.CONTROL, byteArrayOf(G7Messages.EGV.toByte()))
    }

    private fun control(value: ByteArray) {
        G7Messages.Egv.parse(value)?.let { onEgv(it); return }
        if (G7Messages.isBackfillFinished(value)) flushBackfill()
    }

    /** One 0x4E message → the current reading, the session timing, and maybe a backfill request. */
    fun onEgv(egv: G7Messages.Egv): Reading? {
        val start = now() - egv.secondsSinceStart * 1000L
        sessionStartMs = start
        events.onSession(Session(start, start + profile.warmup.inWholeMilliseconds,
            start + profile.lifetime.inWholeMilliseconds, Dexcom.stateName(egv.state)))
        if (Dexcom.stateEnded(egv.state)) { events.onStatus(DriverStatus.Ended(Dexcom.stateName(egv.state))); return null }
        if (!egv.usable) { events.onStatus(DriverStatus.Progress(Dexcom.stateName(egv.state))); return null }
        val reading = Reading(start + egv.readingSeconds * 1000L, egv.mgdl!!, egv.trend,
            Dexcom.stateName(egv.state), (egv.readingSeconds / 300).toInt())
        events.onReading(reading)
        events.onStatus(DriverStatus.Streaming)
        if (mode == Mode.PAIR) {
            val from = lastBackfillTo + 300
            val to = (egv.readingSeconds - 60).toInt()
            if (to - from >= 300) {
                link?.write(Dexcom.CONTROL, G7Messages.backfillRequest(from, to))
                lastBackfillTo = to
            }
        }
        return reading
    }

    /** Backfill notifications are buffered until the sensor says it is done (G7SensorKit does the same). */
    fun onBackfillRecords(value: ByteArray) {
        val start = sessionStartMs ?: return
        G7Messages.BackfillRecord.parseAll(value).filter { it.usable }.forEach {
            backfill.add(Reading(start + it.secondsSinceStart * 1000L, it.mgdl!!, it.trend, index = it.secondsSinceStart / 300))
        }
    }

    fun flushBackfill() {
        if (backfill.isEmpty()) return
        events.onHistory(backfill.toList())
        backfill.clear()
    }

    override fun onDisconnected() {
        flushBackfill()
        link = null
        if (phase != Phase.CERTIFICATE_BLOCKED) phase = Phase.IDLE
        inbound = ByteArray(0)
    }
}
