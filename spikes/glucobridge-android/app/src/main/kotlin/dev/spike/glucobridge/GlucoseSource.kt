package dev.spike.glucobridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Where glucose comes from. One implementation per product flavor:
 *   juggluco → Juggluco's web server on 127.0.0.1 (PoC 1)
 *   direct   → the Libre 3 sensor itself, over BLE (PoC 2)
 */
interface GlucoseSource {
    /** Short label for the UI, e.g. "Juggluco (localhost)" or "sensor XX0R…". */
    val name: String

    fun start(scope: CoroutineScope, sink: Sink)
    fun stop()

    interface Sink {
        /** A new live reading. */
        fun onReading(reading: Bridge.Reading)

        /** Bulk readings (initial history or backfill after a gap); may include duplicates. */
        fun onHistory(readings: List<Bridge.Reading>)

        /** One-line state for the UI/notification; [error] is shown separately when non-null. */
        fun onStatus(state: String, error: String? = null)
    }
}

/**
 * Implemented once per flavor source set (src/juggluco, src/direct), so the service below
 * is identical in both builds.
 */
// fun createGlucoseSource(context: Context): GlucoseSource
