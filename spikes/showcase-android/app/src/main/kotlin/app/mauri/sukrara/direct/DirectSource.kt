// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.GlucoseSource

import android.content.Context
import kotlinx.coroutines.CoroutineScope

fun createGlucoseSource(context: Context): GlucoseSource = DirectSource(context)

/** PoC 2: no Juggluco — the app owns the sensor link itself (and, since 08, serves the xDrip web service). */
class DirectSource(private val context: Context) : GlucoseSource {
    private var client: Libre3Client? = null
    private val pairing = runCatching { SensorPairing.load(context) }.getOrNull()
    private var libre2Client: Libre2Client? = null
    private val libre2 = runCatching { Libre2Pairing.load(context) }.getOrNull()

    override val name = (libre2?.serial ?: pairing?.serial)?.let { "sensor $it" } ?: "no sensor"
    override val servesWatches = true

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        libre2?.let { return startLibre2(it, sink) }
        val p = pairing ?: return sink.onStatus("no sensor", "Scan your sensor: Settings → Glucose source → Scan sensor")
        if (p.securityVersion != 0) return sink.onStatus("unsupported sensor",
            "security version ${p.securityVersion} (Lingo) is not implemented")
        val prefs = context.getSharedPreferences("direct", Context.MODE_PRIVATE)
        sink.onSensor(p.info)
        if (prefs.getBoolean(Libre3Client.endedKey(p.serial), false)) {
            sink.onSensorEnded(p.serial)
            return sink.onStatus("sensor ended", Libre3Client.ENDED_HINT)
        }
        client = Libre3Client(context, p, sink, prefs) { kAuth ->
            // first pairing after a scan: keep it, so later connections resume without NFC
            SensorPairing.load(context)?.takeIf { it.serial == p.serial }?.copy(kAuth = kAuth)?.save(context)
        }.also { it.connect() }
    }

    override fun stop() {
        client?.close()
        client = null
        libre2Client?.close()
        libre2Client = null
    }

    /** A Libre 2 / 2 Plus ([Libre2Nfc] wrote its pairing): its own client, same sink calls as the Libre 3. */
    private fun startLibre2(p: Libre2Pairing, sink: GlucoseSource.Sink) {
        val prefs = context.getSharedPreferences("direct", Context.MODE_PRIVATE)
        sink.onSensor(p.info)
        if (prefs.getBoolean(Libre2Client.endedKey(p.serial), false)) {
            sink.onSensorEnded(p.serial)
            return sink.onStatus("sensor ended", Libre2Client.ENDED_HINT)
        }
        libre2Client = Libre2Client(context, p, sink, prefs).also { it.connect() }
    }
}
