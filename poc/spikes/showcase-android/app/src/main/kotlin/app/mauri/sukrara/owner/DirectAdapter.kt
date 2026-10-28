// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import android.content.Context
import app.mauri.sukrara.bridge.Bridge
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.DirectSource
import app.mauri.sukrara.libreview.LibreViewPayload
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Investigation 02's direct sensor reader (the Libre 3, and since investigation 23 the untested families) behind the owner's [GlucoseSource] seam. Opt-in only (settings, with a
 * warning): while it runs, this app holds the sensor and Juggluco (and its alarms) gets nothing.
 */
class DirectAdapter(private val ctx: Context) : GlucoseSource {
    private var inner: DirectSource? = null

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        scope.launch(Dispatchers.Main) {
            // GattLink uses a main-thread Handler for its timers and reconnects.
            runCatching {
                DirectSource(ctx).also { inner = it }.start(scope, object : app.mauri.sukrara.bridge.GlucoseSource.Sink {
                    override fun onReading(reading: Bridge.Reading) = sink.onReading(reading.toCore())
                    override fun onHistory(readings: List<Bridge.Reading>) = sink.onHistory(readings.map { it.toCore() })
                    override fun onStatus(state: String, error: String?) = sink.onStatus("direct: $state", error)
                    override fun onSensor(info: SensorInfo) {
                        SensorLife.update(ctx, info)
                        Live.sensor(info.serial, info.startEpoch)
                    }
                    override fun onSensorEnded(serial: String) = SensorLife.markEnded(ctx, serial, Proto.nowS())
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

    /** Tagged with the sensor's family (`SensorProfile.id`: "libre3", "dexcom-g7", …), the `dev` of every reading. */
    private fun Bridge.Reading.toCore() = Reading(epochSec, mgdl, rate, inner?.profileId ?: SensorProfiles.LIBRE_3.id)
}
