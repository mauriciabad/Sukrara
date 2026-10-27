// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmnostr

import java.util.concurrent.TimeUnit
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

/**
 * Glucose from Juggluco's web server on the same phone ("Local only" mode, cleartext loopback).
 * Same shape as GlucoBridge's GlucoseSource.Sink so the publisher can be plugged into either.
 *
 *  - (re)connect: GET /api/v1/entries.json?count=N&interval=0 → [Sink.onHistory] (startup: ~13 h, later: 3 h);
 *  - then hold GET /x/live (SSE) open → [Sink.onReading] per `event: glucose`.
 * No polling: the SSE stream pushes; Juggluco's 20 s keep-alive comments keep it fresh. If Juggluco is down we
 * retry with backoff (5 s … 60 s) and report "Juggluco unreachable".
 */
class JugglucoSource(private val baseUrl: String, http: OkHttpClient) {
    interface Sink {
        fun onReading(r: Reading)
        fun onHistory(readings: List<Reading>)
        fun onStatus(state: String, error: String? = null)
    }

    private val http = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)   // > 20 s keep-alive; Okio uses the monotonic clock, so deep sleep doesn't trip it
        .build()
    private var job: Job? = null
    @Volatile private var call: Call? = null

    fun start(scope: CoroutineScope, sink: Sink) {
        job = scope.launch(Dispatchers.IO) { run(sink) }
    }

    fun stop() { job?.cancel(); call?.cancel() }

    private suspend fun CoroutineScope.run(sink: Sink) {
        var first = true
        var backoff = 5_000L
        while (isActive) {
            try {
                sink.onStatus("fetching history")
                sink.onHistory(history(if (first) 800 else 180))
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
                // rate = delta / 5 (mg/dL per 5 min → per min); empty direction means Juggluco has no trend yet
                val rate = if (e.optString("direction").isEmpty() || e.isNull("delta")) null else e.getDouble("delta") / 5.0
                Reading(e.getLong("date") / 1000, e.getInt("sgv"), rate)
            }
        }
    }

    private fun live(sink: Sink, onData: () -> Unit) {
        val req = Request.Builder().url("$baseUrl/x/live").header("Accept", "text/event-stream").build()
        http.newCall(req).also { call = it }.execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            sink.onStatus("live (SSE)")
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
                        sink.onReading(Reading(j.getLong("timestamp"), j.getInt("glucoseMgDl"), rate))
                    }
                }
            }
        }
    }
}
