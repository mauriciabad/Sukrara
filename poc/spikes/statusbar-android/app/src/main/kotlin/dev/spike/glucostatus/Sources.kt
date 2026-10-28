// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.util.Log
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

/**
 * The status bar is the subject of this spike, not the data, so glucose comes from the simplest place:
 * Juggluco's web server on localhost (as in GlucoBridge's juggluco flavor), or a synthetic curve.
 */
interface GlucoseSource {
    fun start(scope: CoroutineScope, onReading: (Reading) -> Unit, onStatus: (String) -> Unit): Job
}

/** Latest value via /api/v1/entries.json, then the /x/live SSE stream (one event per minute). */
class JugglucoSource : GlucoseSource {
    private val base = "http://127.0.0.1:17580"

    override fun start(scope: CoroutineScope, onReading: (Reading) -> Unit, onStatus: (String) -> Unit) = scope.launch {
        while (isActive) {
            try {
                onStatus("Juggluco: fetching")
                latest()?.let(onReading)
                onStatus("Juggluco: live")
                followLive(onReading)
                onStatus("Juggluco: stream ended")
            } catch (e: Exception) {
                Log.w(TAG, "juggluco", e)
                onStatus("Juggluco unreachable: ${e.message}")
            }
            delay(5_000)
        }
    }

    private fun open(path: String, readTimeoutMs: Int) =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = readTimeoutMs
        }

    private fun latest(): Reading? {
        val arr = JSONArray(open("/api/v1/entries.json?count=1&interval=0", 10_000)
            .inputStream.bufferedReader().use { it.readText() })
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val hasTrend = o.optString("direction").isNotEmpty()
        // Juggluco's Nightscout "delta" is the rate × 5 (per 5 minutes).
        return Reading(o.getLong("date") / 1000, o.getInt("sgv"), if (hasTrend) o.optDouble("delta") / 5 else null)
    }

    private suspend fun followLive(onReading: (Reading) -> Unit) {
        open("/x/live", 60_000).inputStream.bufferedReader().use { reader ->
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
}

/**
 * A slow sine wave through the whole range (LO…HI) with its exact derivative as the trend, sped up so a
 * full cycle takes [periodSec]. Exercises 2- and 3-digit values, colours and every arrow angle.
 */
class DemoSource(private val stepSec: Int = 5, private val periodSec: Int = 600) : GlucoseSource {
    override fun start(scope: CoroutineScope, onReading: (Reading) -> Unit, onStatus: (String) -> Unit) = scope.launch {
        onStatus("Demo: sine 30…420 mg/dL, new value every ${stepSec}s")
        val minutesPerCycle = 360.0 // pretend one cycle is 6 h of glucose, so rates stay physiological-ish
        while (isActive) {
            val t = (System.currentTimeMillis() / 1000 % periodSec) / periodSec.toDouble() * 2 * PI
            val mgdl = 225 + 195 * sin(t)
            val rate = 195 * cos(t) * 2 * PI / minutesPerCycle
            onReading(Reading(System.currentTimeMillis() / 1000, mgdl.roundToInt(), rate))
            delay(stepSec * 1000L)
        }
    }
}

const val TAG = "GlucoStatus"
