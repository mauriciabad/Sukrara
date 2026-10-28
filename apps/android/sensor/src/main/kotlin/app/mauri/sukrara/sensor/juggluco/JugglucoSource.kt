// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.juggluco

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.sensor.SensorInfo
import app.mauri.sukrara.sensor.SensorLife
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** What the Juggluco source is doing (the UI words live in the sensor screens). */
sealed interface JugglucoStatus {
    data object FetchingHistory : JugglucoStatus

    data object Live : JugglucoStatus

    /** Juggluco's server isn't answering (not running, web server off, wrong mode); retried with backoff. */
    data class Unreachable(val why: String) : JugglucoStatus
}

/** Where the Juggluco source sends what it learns. */
internal interface JugglucoSink {
    /** [live] = Juggluco's current value from the stream; false = history. */
    fun onReadings(readings: List<Reading>, live: Boolean)

    fun onStatus(status: JugglucoStatus)

    fun onSensor(info: SensorInfo)
}

/**
 * Glucose from Juggluco's web server on this phone ("Local only" mode, loopback cleartext):
 * GET /api/v1/entries.json for history, then hold GET /x/live (SSE) open — push, no polling. Reconnects with backoff
 * 5 s → 60 s. [run] loops until its coroutine is cancelled; cancelling also cancels the open call.
 */
internal class JugglucoSource(http: OkHttpClient, private val baseUrl: String = LOCAL_URL, private val zone: () -> ZoneId) {
    companion object {
        /** Juggluco's default web-server port on this phone. Loopback only (never leaves the device). */
        const val LOCAL_URL = "http://127.0.0.1:17580"
        const val FIRST_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 60_000L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Nightscout entries → readings. Refuses entries whose device is "Sukrara": if anything of ours ever holds
         * Juggluco's port, re-publishing our own values as fresh would be fake data.
         */
        fun parseEntries(body: String): List<Reading> {
            val arr = json.parseToJsonElement(body) as JsonArray
            val entries = arr.map { it.jsonObject }
            check(entries.none { it.str("device") == "Sukrara" }) { "port 17580 is served by Sukrara, not Juggluco" }
            return entries.mapNotNull { e ->
                val t = e["date"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val mgdl = e["sgv"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val delta = e["delta"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
                val rate = if (e.str("direction").isNullOrEmpty() || delta == null) null else delta / 5.0
                reading(t / 1000, mgdl, rate)
            }
        }

        /** One SSE `glucose` event's data → a reading and Juggluco's sensor id (-1 = none). */
        fun parseLive(data: String): Pair<Reading, Int>? {
            val j = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
            val t = j["timestamp"]?.jsonPrimitive?.longOrNull ?: return null
            val mgdl = j["glucoseMgDl"]?.jsonPrimitive?.intOrNull ?: return null
            val rate = j["rateMgDlPerMinute"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
            return reading(t, mgdl, rate) to (j["sensorId"]?.jsonPrimitive?.intOrNull ?: -1)
        }

        /**
         * Juggluco's `sensor_status` (watchserver.cpp `firstdata`): `"dd-mm-yy HH:MM (3d 5h)"`, the start in the phone's
         * local time; the serial is the entry's `_id` before `#`. No wear length published → the Libre 3 Plus default.
         */
        fun parseSensorStatus(body: String, zone: ZoneId): SensorInfo? = runCatching {
            val e = (json.parseToJsonElement(body) as JsonArray).first().jsonObject
            val st = e.str("sensor_status")?.takeIf { it.length >= 14 } ?: return null
            val start = LocalDateTime.parse(st.substring(0, 14), DateTimeFormatter.ofPattern("dd-MM-yy HH:mm"))
                .atZone(zone).toEpochSecond()
            SensorLife.fromStart(e.str("_id").orEmpty().substringBefore('#'), start, 0)
        }.getOrNull()

        private fun JsonObject.str(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

        private fun reading(epochSec: Long, mgdl: Int, rate: Double?) =
            Reading(Instant.ofEpochSecond(epochSec), mgdl, rate, SourceId.Juggluco)
    }

    private val http = http.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // > Juggluco's 20 s SSE keep-alive: a silent stream fails and reconnects
        .build()

    @Volatile private var call: Call? = null

    @Volatile private var liveCall: Call? = null

    /** Cancels the open requests, the live stream included (the coroutine running [run] must be cancelled too). */
    fun cancelCall() {
        call?.cancel()
        liveCall?.cancel()
    }

    suspend fun run(sink: JugglucoSink) {
        var first = true
        var backoff = FIRST_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                sink.onStatus(JugglucoStatus.FetchingHistory)
                sink.onReadings(get("/api/v1/entries.json?count=${if (first) 1600 else 180}&interval=0", ::parseEntries), live = false)
                first = false
                sensor()?.let(sink::onSensor)
                live(sink) { backoff = FIRST_BACKOFF_MS }
                sink.onStatus(JugglucoStatus.Unreachable("live stream ended"))
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                sink.onStatus(JugglucoStatus.Unreachable("${e.javaClass.simpleName}: ${e.message?.take(80)}"))
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun <T> get(path: String, parse: (String) -> T): T {
        val req = Request.Builder().url("$baseUrl$path").build()
        http.newCall(req).also { call = it }.execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            return parse(resp.body?.string().orEmpty())
        }
    }

    private fun sensor(): SensorInfo? = runCatching { get("/sgv.json?count=1&sensor=Y") { parseSensorStatus(it, zone()) } }.getOrNull()

    private fun live(sink: JugglucoSink, onData: () -> Unit) {
        val req = Request.Builder().url("$baseUrl/x/live").header("Accept", "text/event-stream").build()
        http.newCall(req).also { liveCall = it }.execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            sink.onStatus(JugglucoStatus.Live)
            val src = checkNotNull(resp.body).source()
            var event = ""
            var sensorId = -1
            while (true) {
                val line = src.readUtf8Line() ?: return
                when {
                    line.isEmpty() -> event = ""
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") && event == "glucose" -> {
                        val (reading, id) = parseLive(line.substring(5).trim()) ?: continue
                        onData()
                        sink.onReadings(listOf(reading), live = true)
                        // a new sensor in Juggluco: read its start again
                        if (sensorId != -1 && id != sensorId) sensor()?.let(sink::onSensor)
                        sensorId = id
                    }
                }
            }
        }
    }
}
