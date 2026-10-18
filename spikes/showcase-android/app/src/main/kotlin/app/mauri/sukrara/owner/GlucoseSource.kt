// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
        /** The current sensor's start/end, when the source knows it (the sensor life badge, SensorLife). */
        fun onSensor(info: SensorInfo) {}
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
                sensor()?.let(sink::onSensor)
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
            // Guard against reading ourselves: if anything of ours ever holds Juggluco's port, its entries say
            // device "Sukrara" — refuse them instead of re-publishing our own stale values as fresh.
            check((0 until arr.length()).none { arr.getJSONObject(it).optString("device") == "Sukrara" }) {
                "port 17580 is served by Sukrara, not Juggluco — toggle Juggluco's web server off/on"
            }
            return (0 until arr.length()).map { i ->
                val e = arr.getJSONObject(i)
                val rate = if (e.optString("direction").isEmpty() || e.isNull("delta")) null else e.getDouble("delta") / 5.0
                Reading(e.getLong("date") / 1000, e.getInt("sgv"), rate, "juggluco")
            }
        }
    }

    /** The sensor's start from Juggluco's `sgv.json?sensor=Y` (the first entry's `sensor_status`), or null. */
    private fun sensor(): SensorInfo? = runCatching {
        val req = Request.Builder().url("$baseUrl/sgv.json?count=1&sensor=Y").build()
        http.newCall(req).also { call = it }.execute().use { resp ->
            if (!resp.isSuccessful) null else parseSensorStatus(resp.body!!.string(), ZoneId.systemDefault())
        }
    }.getOrNull()

    private fun live(sink: GlucoseSource.Sink, onData: () -> Unit) {
        val req = Request.Builder().url("$baseUrl/x/live").header("Accept", "text/event-stream").build()
        http.newCall(req).also { call = it }.execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            sink.onStatus("live (Juggluco SSE)")
            val src = resp.body!!.source()
            var event = ""
            var sensorId = -1
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
                        // a new sensor in Juggluco: read its start again
                        val id = j.optInt("sensorId", -1)
                        if (sensorId != -1 && id != sensorId) runCatching { sensor() }.getOrNull()?.let(sink::onSensor)
                        sensorId = id
                    }
                }
            }
        }
    }
}

/**
 * Juggluco's `sensor_status` (watchserver.cpp `firstdata`): `"dd-mm-yy HH:MM (3d 5h)"`, the sensor's start in the
 * phone's local time; the serial is the entry's `_id` before `#`. Juggluco doesn't publish the wear length, so the end
 * is start + [SensorLife.DEFAULT_WEAR_MIN] (Libre 3 Plus, 15 days). Null when absent or unreadable.
 */
internal fun parseSensorStatus(json: String, zone: ZoneId): SensorInfo? = runCatching {
    val e = JSONArray(json).getJSONObject(0)
    val st = e.optString("sensor_status").takeIf { it.length >= 14 } ?: return null
    val start = LocalDateTime.parse(st.substring(0, 14), DateTimeFormatter.ofPattern("dd-MM-yy HH:mm")).atZone(zone).toEpochSecond()
    SensorLife.fromStart(e.optString("_id").substringBefore('#'), start, 0)
}.getOrNull()

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
