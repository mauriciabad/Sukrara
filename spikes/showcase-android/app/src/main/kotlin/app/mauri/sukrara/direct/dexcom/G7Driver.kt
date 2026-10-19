// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.dexcom

import android.util.Log
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.core.SensorProfile
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
 * **Dexcom G7 / ONE+ / Stelo, observer mode** (investigation 23, step 2). The G7 accepts several BLE clients and
 * indicates its authentication status to all of them. So with the official Dexcom app installed and running, this
 * driver connects, watches the authentication characteristic until it sees `authenticated=1, bonded=1` (the Dexcom
 * app's session), then subscribes to the control and backfill characteristics and reads the same glucose the
 * Dexcom app gets. **It writes nothing to the sensor** (only its own notification subscriptions), exactly what LoopKit's
 * G7SensorKit does in production ("Requires use of official G7 app").
 *
 * Which sensor: a `DXCM…` / `DX01…` / `DX02…` / `DX04…` device **this phone is bonded with**, i.e. the one the Dexcom
 * app paired. A stranger's G7 nearby is never bonded to this phone, so it is never read.
 *
 * The sensor's own pairing (applicator code + a Dexcom-issued app certificate) is refused (docs/23). Untested on
 * hardware: the user owns no Dexcom sensor. Fixtures: LoopKit G7SensorKit's tests (`G7DriverTest`).
 */
internal class G7Driver(
    pairing: SensorPairing,
    events: SensorEvents,
    prefs: DriverPrefs,
    override val profile: SensorProfile = SensorProfiles.DEXCOM_G7,
    clock: () -> Long = System::currentTimeMillis,
    log: (priority: Int, message: String) -> Unit = { p, m -> Log.println(p, "Sukrara.g7", m) },
) : BrandDriver(pairing, events, prefs, clock, log) {

    /** Where the observer is. The sensor and the Dexcom app drive it; we only listen. */
    enum class Phase { IDLE, WATCHING, STREAMING }

    var phase = Phase.IDLE
        private set

    private var lastSequence = -1
    private val backfill = ArrayList<Bridge.Reading>()
    private var startEpoch: Long? = null

    override val scan = listOf(ScanSpec(service = Dexcom.ADVERTISEMENT))

    override fun matches(advert: Advert): Boolean {
        val n = advert.name ?: return false
        // G7SensorKit: the G7 advertises "DXCMxx", the ONE+ "DX02xx"; Juggluco also accepts DX01 / DX04.
        return advert.bonded && listOf("DXCM", "DX01", "DX02", "DX04").any { n.startsWith(it) }
    }

    override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
        this.link = link
        if (Dexcom.AUTHENTICATION !in characteristics || Dexcom.CONTROL !in characteristics)
            return retry("Dexcom services not found (status $status)")
        phase = Phase.WATCHING
        events.onStatus(WAITING_FOR_APP)
        link.subscribe(Dexcom.AUTHENTICATION, indicate = true)
    }

    override fun onNotification(characteristic: UUID, value: ByteArray) {
        when (characteristic) {
            Dexcom.AUTHENTICATION -> G7Messages.AuthStatus.parse(value)?.let { if (it.live) startStreaming() }
            Dexcom.CONTROL -> control(value)
            Dexcom.BACKFILL -> onBackfillRecords(value)
        }
    }

    private fun startStreaming() {
        val link = link ?: return
        if (phase == Phase.STREAMING) return
        phase = Phase.STREAMING
        events.onStatus("reading")
        link.subscribe(Dexcom.CONTROL)
        link.subscribe(Dexcom.BACKFILL)
        // No write: the Dexcom app asks for the values, and the sensor notifies every subscriber.
    }

    private fun control(value: ByteArray) {
        G7Messages.Egv.parse(value)?.let { onEgv(it); return }
        if (G7Messages.isBackfillFinished(value)) flushBackfill()
    }

    /** One 0x4E message → the session start, then a reading, a wait (warming up) or the end. */
    fun onEgv(egv: G7Messages.Egv) {
        val start = now() - egv.secondsSinceStart
        startEpoch = start
        if (Dexcom.stateEnded(egv.state)) return ended(Dexcom.stateName(egv.state))
        sessionStart(start)
        if (Dexcom.stateWarmingUp(egv.state)) return events.onStatus("warming up")
        if (!egv.usable) return events.onStatus(Dexcom.stateName(egv.state))
        if (egv.sequence == lastSequence) return
        lastSequence = egv.sequence
        reading(Bridge.Reading(start + egv.readingSeconds, egv.mgdl!!, egv.trend?.toDouble()))
    }

    /** Backfill notifications are buffered until the sensor says it is done (G7SensorKit does the same). */
    fun onBackfillRecords(value: ByteArray) {
        val start = startEpoch ?: return
        G7Messages.BackfillRecord.parseAll(value).filter { it.usable && it.mgdl!! in 39..501 }.forEach {
            backfill.add(Bridge.Reading(start + it.secondsSinceStart, it.mgdl!!, it.trend?.toDouble()))
        }
    }

    fun flushBackfill() {
        if (backfill.isEmpty()) return
        events.onHistory(backfill.toList())
        backfill.clear()
    }

    override fun onDisconnected() {
        flushBackfill()
        phase = Phase.IDLE
    }

    override fun onClosed() {
        flushBackfill()
        phase = Phase.IDLE
    }

    companion object {
        /** Status word while the Dexcom app's session hasn't shown up (NewSensorFlow: the "securing" step). */
        const val WAITING_FOR_APP = "waiting for the Dexcom app"
    }
}
