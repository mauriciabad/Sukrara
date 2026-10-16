// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import android.content.Context
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.direct.DirectSource
import app.mauri.sukrara.libreview.LibreViewPayload
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Investigation 02's direct Libre 3 reader behind the owner's [GlucoseSource] seam. Opt-in only (settings, with a
 * warning): while it runs, this app holds the sensor and Juggluco (and its alarms) gets nothing.
 */
class DirectAdapter(private val ctx: Context) : GlucoseSource {
    private var inner: DirectSource? = null

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        scope.launch(Dispatchers.Main) {
            // Libre3Client uses a main-thread Handler for its GATT queue.
            runCatching {
                DirectSource(ctx).also { inner = it }.start(scope, object : app.mauri.sukrara.bridge.GlucoseSource.Sink {
                    override fun onReading(reading: Bridge.Reading) = sink.onReading(reading.toCore())
                    override fun onHistory(readings: List<Bridge.Reading>) = sink.onHistory(readings.map { it.toCore() })
                    override fun onStatus(state: String, error: String?) = sink.onStatus("direct: $state", error)
                    override fun onSensor(serial: String, startEpoch: Long) = Live.sensor(serial, startEpoch)
                    override fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {
                        Live.sensorGlucose(current, history)
                    }
                })
            }.onFailure { sink.onStatus("direct: failed", "${it.javaClass.simpleName}: ${it.message} (native core missing?)") }
        }
    }

    override fun stop() {
        inner?.stop()
        inner = null
    }

    private fun Bridge.Reading.toCore() = Reading(epochSec, mgdl, rate, "libre3")
}
