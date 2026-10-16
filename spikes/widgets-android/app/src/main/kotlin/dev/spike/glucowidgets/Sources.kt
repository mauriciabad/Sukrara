// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucowidgets

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

const val TAG = "GlucoWidgets"

/** Which source feeds the widgets, and the knobs of the synthetic one (for screenshots of every state). */
data class Config(
    val source: String = SOURCE_JUGGLUCO,
    /** Shifts the synthetic sine so the current value lands where a screenshot needs it. */
    val phaseMin: Int = 0,
    /** Synthetic source pretends its last reading was this many minutes ago and sends nothing new (stale). */
    val freezeAgeMin: Int = 0,
) {
    companion object {
        const val SOURCE_JUGGLUCO = "juggluco"
        const val SOURCE_SYNTHETIC = "synthetic"

        fun load(c: Context): Config {
            val p = c.getSharedPreferences("config", Context.MODE_PRIVATE)
            return Config(
                p.getString("source", null) ?: SOURCE_JUGGLUCO,
                p.getInt("phaseMin", 0),
                p.getInt("freezeAgeMin", 0),
            )
        }
    }

    fun save(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE).edit {
        putString("source", source); putInt("phaseMin", phaseMin); putInt("freezeAgeMin", freezeAgeMin)
    }

    fun newSource(): GlucoseSource =
        if (source == SOURCE_SYNTHETIC) SyntheticSource(phaseMin, freezeAgeMin) else JugglucoSource()
}

interface GlucoseSource {
    /** Calls [onReadings] with a backfill first (history), then with each new reading. */
    fun start(scope: CoroutineScope, onReadings: (List<Reading>) -> Unit, onStatus: (String) -> Unit): Job
}

/** Juggluco's web server on the phone: history from /api/v1/entries.json, then the /x/live SSE stream. */
class JugglucoSource : GlucoseSource {
    override fun start(scope: CoroutineScope, onReadings: (List<Reading>) -> Unit, onStatus: (String) -> Unit) =
        scope.launch {
            while (isActive) {
                try {
                    onStatus("Juggluco: fetching history")
                    onReadings(history(360))
                    onStatus("Juggluco: live")
                    followLive { onReadings(listOf(it)) }
                    onStatus("Juggluco: stream ended")
                } catch (e: Exception) {
                    Log.w(TAG, "juggluco", e)
                    onStatus("Juggluco unreachable: ${e.message}")
                }
                delay(10_000)
            }
        }

    private suspend fun followLive(onReading: (Reading) -> Unit) {
        open("/x/live", 90_000).inputStream.bufferedReader().use { reader ->
            var event = "message"
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: return
                when {
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") && event == "glucose" -> {
                        val o = JSONObject(line.substringAfter(':'))
                        onReading(Reading(o.getLong("timestamp"), o.getInt("glucoseMgDl"),
                            if (o.isNull("rateMgDlPerMinute")) null else o.getDouble("rateMgDlPerMinute")))
                        event = "message"
                    }
                }
            }
        }
    }

    companion object {
        private const val BASE = "http://127.0.0.1:17580"

        private fun open(path: String, readTimeoutMs: Int) =
            (URL(BASE + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3_000
                readTimeout = readTimeoutMs
            }

        /** Newest first on the wire; `delta` is the rate × 5; `direction` "" = rate unknown. */
        fun history(count: Int): List<Reading> {
            val arr = JSONArray(open("/api/v1/entries.json?count=$count&interval=0", 10_000)
                .inputStream.bufferedReader().use { it.readText() })
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val hasTrend = o.optString("direction").isNotEmpty()
                Reading(o.getLong("date") / 1000, o.getInt("sgv"), if (hasTrend) o.optDouble("delta") / 5 else null)
            }
        }
    }
}

/**
 * Debug source for the emulator: one reading per minute (on the minute) from a 3-hour sine 50…230 mg/dL plus a
 * small wobble, with its exact derivative as the trend — so a 3-h graph crosses low, in-range and high and the
 * arrow sweeps every angle (max |rate| ≈ 3.3 mg/dL/min).
 */
class SyntheticSource(private val phaseMin: Int, private val freezeAgeMin: Int) : GlucoseSource {
    override fun start(scope: CoroutineScope, onReadings: (List<Reading>) -> Unit, onStatus: (String) -> Unit) =
        scope.launch {
            val lastSec = (nowSec() / 60 - freezeAgeMin) * 60
            onReadings((0 until 8 * 60).map { at(lastSec - it * 60L) })
            if (freezeAgeMin > 0) {
                onStatus("Synthetic: frozen ${freezeAgeMin} min ago")
                return@launch
            }
            onStatus("Synthetic: one reading per minute")
            while (isActive) {
                delay(60_000 - System.currentTimeMillis() % 60_000 + 200)
                onReadings(listOf(at(nowSec() / 60 * 60)))
            }
        }

    fun at(sec: Long): Reading = sample(sec, phaseMin)

    companion object {
        fun sample(sec: Long, phaseMin: Int): Reading {
            val m = sec / 60.0 + phaseMin
            val w1 = 2 * PI / 180
            val w2 = 2 * PI / 23
            val v = 140 + 90 * sin(w1 * m) + 6 * sin(w2 * m)
            val rate = 90 * w1 * cos(w1 * m) + 6 * w2 * cos(w2 * m)
            return Reading(sec, v.roundToInt(), rate)
        }
    }
}
