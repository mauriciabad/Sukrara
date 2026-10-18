// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.content.Context
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.GlucoseSource
import app.mauri.sukrara.libreview.LibreViewPayload
import kotlinx.coroutines.CoroutineScope

fun createGlucoseSource(context: Context): GlucoseSource = DirectSource(context)

/**
 * PoC 2: no Juggluco — the app owns the sensor link itself (and, since 08, serves the xDrip web service). The saved
 * sensor's profile picks a [SensorDriver] from [Drivers] (investigation 23, step 1: the Libre 3 is the only one) and a
 * transport: by address ([GattLink]) for families that get it from pairing, a scan for the rest (not here yet).
 */
class DirectSource(private val context: Context) : GlucoseSource {
    private var link: GattLink? = null
    private val pairing = runCatching { SensorPairing.load(context) }.getOrNull()

    override val name = pairing?.let { "sensor ${it.serial}" } ?: "no sensor"
    override val servesWatches = true

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        val p = pairing ?: return sink.onStatus("no sensor", "Tap Pair a sensor on the home screen")
        val entry = Drivers.byId(p.profileId) ?: return sink.onStatus("unsupported sensor", "no driver for ${p.profileId}")
        entry.unsupported(p)?.let { return sink.onStatus("unsupported sensor", it) }
        val prefs = DriverPrefs.of(context.getSharedPreferences("direct", Context.MODE_PRIVATE))
        sink.onSensor(p.info)
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
        }
        val driver = entry.make(p, events, prefs)
        if (entry.profile.needsScan) return sink.onStatus("unsupported sensor", "${entry.profile.id}: scanning sources aren't implemented")
        link = GattLink(context, p.address, p.serial, driver, events).also { it.connect() }
    }

    override fun stop() {
        link?.close()
        link = null
    }
}
