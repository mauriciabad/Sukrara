// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.owner

import dev.spike.cgmshare.core.Reading
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

interface GlucoseSource {
    interface Sink {
        fun onReading(r: Reading)
        fun onHistory(readings: List<Reading>)
        fun onStatus(state: String, error: String? = null)
    }

    fun start(scope: CoroutineScope, sink: Sink)
    fun stop()
}

/**
 * Glucose from Juggluco's web server on the same phone ("Local only" mode, cleartext loopback). Copied from the
 * PoC 3 publisher: GET /api/v1/entries.json for history, then hold GET /x/live (SSE) open — push, no polling.
 */
class JugglucoSource(private val baseUrl: String, http: OkHttpClient) : GlucoseSource {
    private val http = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // > 20 s SSE keep-alive
        .build()
    private var job: Job? = null
    @Volatile private var call: Call? = null

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        job = scope.launch(Dispatchers.IO) { run(sink) }
    }

    override fun stop() { job?.cancel(); call?.cancel() }

    private suspend fun CoroutineScope.run(sink: GlucoseSource.Sink) {
        var first = true
        var backoff = 5_000L
        while (isActive) {
            try {
                sink.onStatus("fetching history")
                sink.onHistory(history(if (first) 1600 else 180))
                first = false
                live(sink) { backoff = 5_000L }
                sink.onStatus("live stream ended", "SSE closed by Juggluco")
            } catch (e: Exception) {
                if (!isActive) break
                sink.onStatus("Juggluco unreachable", "${e.javaClass.simpleName}: ${e.message?.take(80)}")
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun history(count: Int): List<Reading> {
        val req = Request.Builder().url("$baseUrl/api/v1/entries.json?count=$count&interval=0").build()
        http.newCall(req).also { call = it }.execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val arr = JSONArray(resp.body!!.string())
            return (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val rate = if (e.optString("direction").isEmpty() || e.isNull("delta")) null else e.getDouble("delta") / 5.0
                Reading(e.getLong("date") / 1000, e.getInt("sgv"), rate, "juggluco")
            }
        }
    }

    private fun live(sink: GlucoseSource.Sink, onData: () -> Unit) {
        val req = Request.Builder().url("$baseUrl/x/live").header("Accept", "text/event-stream").build()
        http.newCall(req).also { call = it }.execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            sink.onStatus("live (Juggluco SSE)")
            val src = resp.body!!.source()
            var event = ""
            while (true) {
                val line = src.readUtf8Line() ?: return
                when {
                    line.isEmpty() -> event = ""
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") && event == "glucose" -> {
                        val j = JSONObject(line.substring(5).trim())
                        val rate = if (j.isNull("rateMgDlPerMinute")) null else j.getDouble("rateMgDlPerMinute")
                        onData()
                        sink.onReading(Reading(j.getLong("timestamp"), j.getInt("glucoseMgDl"), rate, "juggluco"))
                    }
                }
            }
        }
    }
}

/**
 * DEBUG-ONLY fake sensor so the owner role runs on an emulator: one reading per minute (on the minute) plus 24 h of
 * history at start. Same function as the Python reference (`v2/nodes.py: synthetic`), so values can be compared
 * across implementations and survive a reinstall unchanged.
 */
class SyntheticSource : GlucoseSource {
    private var job: Job? = null

    override fun start(scope: CoroutineScope, sink: GlucoseSource.Sink) {
        job = scope.launch {
            val now = System.currentTimeMillis() / 1000
            val last = now - now % 60
            sink.onStatus("synthetic (debug)")
            sink.onHistory((0 until 24 * 60).map { at(last - it * 60L) })
            var t = last
            while (isActive) {
                t += 60
                delay((t * 1000 - System.currentTimeMillis()).coerceAtLeast(0))
                sink.onReading(at(t))
            }
        }
    }

    override fun stop() { job?.cancel() }

    companion object {
        fun at(t: Long) = Reading(
            t,
            (130 + 60 * sin(t / 5400.0)).toInt(),
            Math.round(60 * cos(t / 5400.0) / 90 * 100) / 100.0,
            "synthetic",
        )
    }
}
