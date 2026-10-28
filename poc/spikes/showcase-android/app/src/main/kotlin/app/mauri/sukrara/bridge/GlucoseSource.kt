// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import android.content.Context
import app.mauri.sukrara.libreview.LibreViewPayload
import kotlinx.coroutines.CoroutineScope

/**
 * Where glucose comes from. One implementation per product flavor:
 *   juggluco → Juggluco's web server on 127.0.0.1 (PoC 1)
 *   direct   → the Libre 3 sensor itself, over BLE (PoC 2)
 */
interface GlucoseSource {
    /** Short label for the UI, e.g. "Juggluco (localhost)" or "sensor XX0R…". */
    val name: String

    /**
     * Serve the xDrip+ web service on 127.0.0.1:17580 for Garmin watch apps (investigation 08). Only when we own
     * the sensor: in the juggluco flavor, Juggluco's own server holds that port and must keep it.
     */
    val servesWatches: Boolean get() = false

    fun start(scope: CoroutineScope, sink: Sink)
    fun stop()

    interface Sink {
        /** A new live reading. */
        fun onReading(reading: Bridge.Reading)

        /** Bulk readings (initial history or backfill after a gap); may include duplicates. */
        fun onHistory(readings: List<Bridge.Reading>)

        /** One-line state for the UI/notification; [error] is shown separately when non-null. */
        fun onStatus(state: String, error: String? = null)

        /**
         * Sensor-level detail (direct source only): serial, start, end (start + wear length) and warm-up end
         * (core/SensorLife: the badge, the countdown and warm-up), then readings by life count for the LibreView upload.
         */
        fun onSensor(info: app.mauri.sukrara.core.SensorInfo) {}
        /** The sensor has reached its end (patch state, life count): the source stops connecting to it. */
        fun onSensorEnded(serial: String) {}
        fun onSensorGlucose(current: LibreViewPayload.Glucose?, history: List<LibreViewPayload.Glucose>) {}
    }
}

/**
 * Implemented once per flavor source set (src/juggluco, src/direct), so the service below
 * is identical in both builds.
 */
// fun createGlucoseSource(context: Context): GlucoseSource
