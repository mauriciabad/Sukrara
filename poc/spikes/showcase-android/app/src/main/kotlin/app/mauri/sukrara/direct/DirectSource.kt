// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.content.Context
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.GlucoseSource
import app.mauri.sukrara.core.ActiveSensor
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.libreview.LibreViewPayload
import kotlinx.coroutines.CoroutineScope

fun createGlucoseSource(context: Context): GlucoseSource = DirectSource(context)

/**
 * PoC 2: no Juggluco — the app owns the sensor link itself (and, since 08, serves the xDrip web service). The saved
 * sensor's profile picks a [SensorDriver] from [Drivers] and a transport ([transport]): by address ([GattLink]) for
 * the Libre 3 (the NFC tap gave it) and for a sensor found before, a short filtered scan ([SensorScanner]) for a new
 * sensor of the other families (investigation 23, steps 2–5), which then saves the address.
 */
class DirectSource(private val context: Context) : GlucoseSource {
    private var link: GattLink? = null
    private var scanner: SensorScanner? = null
    private val pairing = runCatching { SensorPairing.load(context) }.getOrNull()

    override val name = pairing?.let { "sensor ${it.serial}" } ?: "no sensor"
    override val servesWatches = true

    /** The family readings are tagged with (`owner/DirectAdapter`): the saved sensor's, the Libre 3's when none. */
    val profileId: String = pairing?.profileId ?: app.mauri.sukrara.core.SensorProfiles.LIBRE_3.id

    /** How the source reaches the saved sensor. */
    enum class Transport { ADDRESS, SCAN }

    companion object {
        /**
         * By address when pairing handed it over (the Libre 3's NFC tap: always, it never scans) or a scan found it
         * before; a scan only for a family that needs one and has no address yet.
         */
        fun transport(profile: SensorProfile, pairing: SensorPairing): Transport =
            if (profile.needsScan && pairing.address.isEmpty()) Transport.SCAN else Transport.ADDRESS
    }

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        val p = pairing ?: run { ActiveSensor.profile.value = null; return sink.onStatus("no sensor", "Tap Pair a sensor on the home screen") }
        val entry = Drivers.byId(p.profileId) ?: return sink.onStatus("unsupported sensor", "no driver for ${p.profileId}")
        ActiveSensor.profile.value = entry.profile
        entry.unsupported(p)?.let { return sink.onStatus("unsupported sensor", it) }
        val prefs = DriverPrefs.of(context.getSharedPreferences("direct", Context.MODE_PRIVATE))
        // the Libre 3's start comes from the NFC tap; the other families' once the sensor said (onSessionStart)
        if (!entry.profile.needsScan || p.startEpoch > 0) sink.onSensor(p.info)
        if (prefs.getBoolean(DriverPrefs.endedKey(p.serial), false)) {
            sink.onSensorEnded(p.serial)
            return sink.onStatus("sensor ended", DriverPrefs.ENDED_HINT)
        }
        val events = object : SensorEvents {
            override fun onStatus(state: String, detail: String?) = sink.onStatus(state, detail)
            override fun onReading(reading: Bridge.Reading) = sink.onReading(reading)
            override fun onHistory(readings: List<Bridge.Reading>) = sink.onHistory(readings)
            override fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) =
                sink.onSensorGlucose(current, history)
            override fun onSensorEnded(serial: String) = sink.onSensorEnded(serial)
            override fun onPairingState(state: ByteArray) {
                // first pairing after a scan: keep it, so later connections resume without NFC
                SensorPairing.load(context)?.takeIf { it.serial == p.serial }?.copy(kAuth = state)?.save(context)
            }
            override fun onSessionStart(startEpoch: Long) {
                val saved = current(p) ?: return
                val updated = saved.copy(startEpoch = startEpoch).also { it.save(context) }
                sink.onSensor(updated.info)
            }
        }
        val driver = entry.make(p, events, prefs)
        when (transport(entry.profile, p)) {
            Transport.ADDRESS -> link = GattLink(context, p.address, p.serial, driver, events).also { it.connect() }
            Transport.SCAN -> scanner = SensorScanner(context, driver, events) { found ->
                // keep the address: later connections go straight to it (autoConnect), no more scanning
                current(p)?.copy(address = found.address.uppercase())?.save(context)
                scanner = null
                link = GattLink(context, found.address, found.name ?: entry.profile.name, driver, events).also { it.connect() }
            }.also { it.start() }
        }
    }

    /** The saved record if it is still this source's sensor (a new pairing replaces the file and restarts the source). */
    private fun current(p: SensorPairing) =
        runCatching { SensorPairing.load(context) }.getOrNull()?.takeIf { it.serial == p.serial && it.profileId == p.profileId }

    override fun stop() {
        scanner?.stop()
        scanner = null
        link?.close()
        link = null
        ActiveSensor.profile.value = null
    }
}
