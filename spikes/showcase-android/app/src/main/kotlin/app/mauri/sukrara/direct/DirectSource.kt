// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.bridge.GlucoseSource

import android.content.Context
import app.mauri.sukrara.direct.Libre3Client
import app.mauri.sukrara.direct.SensorPairing
import kotlinx.coroutines.CoroutineScope

fun createGlucoseSource(context: Context): GlucoseSource = DirectSource(context)

/** PoC 2: no Juggluco — the app owns the sensor link itself (and, since 08, serves the xDrip web service). */
class DirectSource(private val context: Context) : GlucoseSource {
    private var client: Libre3Client? = null
    private val pairing = runCatching { SensorPairing.load(context) }.getOrNull()

    override val name = pairing?.let { "sensor ${it.serial}" } ?: "sensor (not paired)"
    override val servesWatches = true

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        val p = pairing ?: return sink.onStatus("no pairing data",
            "Copy ${SensorPairing.FILE} into the app's files directory (see tools/import-pairing.sh)")
        if (p.securityVersion != 0) return sink.onStatus("unsupported sensor",
            "security version ${p.securityVersion} (Lingo) is not implemented")
        val prefs = context.getSharedPreferences("direct", Context.MODE_PRIVATE)
        sink.onSensor(p.serial, p.startEpoch)
        client = Libre3Client(context, p, sink, prefs).also { it.connect() }
    }

    override fun stop() {
        client?.close()
        client = null
    }
}
