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

    override val name = pairing?.let { "sensor ${it.serial}" } ?: "no sensor"
    override val servesWatches = true

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
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
    }
}
