// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

fun createGlucoseSource(context: Context): GlucoseSource = JugglucoSource()

/**
 * PoC 1: follows Juggluco's web server on localhost, so "Local only" can stay enabled.
 * History via /api/v1/entries.json (interval=0 → every minute), live via the /x/live SSE stream.
 */
class JugglucoSource : GlucoseSource {
    private val tag = "GlucoBridge"
    private val base = "http://127.0.0.1:17580"
    private var job: Job? = null

    override val name = "Juggluco (localhost)"

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        job = scope.launch {
            while (isActive) {
                try {
                    sink.onStatus("fetching history")
                    sink.onHistory(fetchHistory())
                    sink.onStatus("live")
                    followLive(sink)
                    sink.onStatus("stream ended")
                } catch (e: Exception) {
                    Log.w(tag, "juggluco", e)
                    sink.onStatus("unreachable", e.message)
                }
                delay(5_000)
            }
        }
    }

    override fun stop() {
        job?.cancel()
    }

    private fun open(path: String, readTimeoutMs: Int) =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = readTimeoutMs
        }

    private fun fetchHistory(): List<Bridge.Reading> {
        val c = open("/api/v1/entries.json?count=${Bridge.HISTORY_MINUTES}&interval=0", 10_000)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val dir = o.optString("direction")
            Bridge.Reading(o.getLong("date") / 1000, o.getInt("sgv"),
                if (dir.isEmpty()) null else o.optDouble("delta") / 5) // Juggluco delta = rate × 5
        }.sortedBy { it.epochSec }
    }

    /** Server-Sent Events: one `glucose` event per new 1-minute reading. Heartbeat every 20 s. */
    private suspend fun followLive(sink: GlucoseSource.Sink) {
        val c = open("/x/live", 60_000)
        c.inputStream.bufferedReader().use { reader ->
            var event = "message"
            while (currentCoroutineIsActive()) {
                val line = reader.readLine() ?: return
                when {
                    line.startsWith("event:") -> event = line.substringAfter(':').trim()
                    line.startsWith("data:") && event == "glucose" -> {
                        val o = JSONObject(line.substringAfter(':'))
                        sink.onReading(Bridge.Reading(o.getLong("timestamp"), o.getInt("glucoseMgDl"),
                            if (o.isNull("rateMgDlPerMinute")) null else o.getDouble("rateMgDlPerMinute")))
                        event = "message"
                    }
                }
            }
        }
    }

    private suspend fun currentCoroutineIsActive() = kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive != false
}
