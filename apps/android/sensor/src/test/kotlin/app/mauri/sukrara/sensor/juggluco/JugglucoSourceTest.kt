// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.juggluco

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.sensor.SensorInfo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections

class JugglucoSourceTest {
    private val zone = ZoneId.of("Europe/Madrid")

    /** Juggluco's sgv.json `sensor_status` (watchserver.cpp firstdata) → the sensor's start; end = start + 15 days. PoC test. */
    @Test
    fun sensorStatus() {
        val json = """[{"_id":"3MH00ABCDE#1234","device":"Juggluco","date":1760776800000,"sgv":120,"sensor_status":"09-10-26 08:05 (9d 3h)"}]"""
        val start = ZonedDateTime.of(2026, 10, 9, 8, 5, 0, 0, zone).toEpochSecond()
        val info = JugglucoSource.parseSensorStatus(json, zone)!!
        assertEquals("3MH00ABCDE", info.serial)
        assertEquals(start, info.startEpoch)
        assertEquals(start + 15 * 86_400L, info.endEpoch)
        assertNull(JugglucoSource.parseSensorStatus("""[{"_id":"x#1","sgv":120}]""", zone))
        assertNull(JugglucoSource.parseSensorStatus("""[{"sensor_status":"yesterday-ish"}]""", zone))
        assertNull(JugglucoSource.parseSensorStatus("[]", zone))
    }

    @Test
    fun entries() {
        val body = """[{"date":1760776800000,"sgv":120,"delta":5.0,"direction":"FortyFiveUp","device":"Juggluco"},
            {"date":1760776740000,"sgv":118,"delta":null,"direction":"","device":"Juggluco"}]"""
        assertEquals(
            listOf(
                Reading(Instant.ofEpochSecond(1760776800), 120, 1.0f, SourceId.Juggluco),
                Reading(Instant.ofEpochSecond(1760776740), 118, null, SourceId.Juggluco),
            ),
            JugglucoSource.parseEntries(body),
        )
    }

    /** Our own values served back on Juggluco's port would be stale data shown as fresh: refused. */
    @Test
    fun refusesItsOwnEntries() {
        assertThrows(IllegalStateException::class.java) {
            JugglucoSource.parseEntries("""[{"date":1,"sgv":120,"device":"Sukrara"}]""")
        }
    }

    @Test
    fun liveEvent() {
        val (r, id) = JugglucoSource.parseLive("""{"timestamp":1760776860,"glucoseMgDl":121,"rateMgDlPerMinute":null,"sensorId":7}""")!!
        assertEquals(Reading(Instant.ofEpochSecond(1760776860), 121, null, SourceId.Juggluco), r)
        assertEquals(7, id)
        assertNull(JugglucoSource.parseLive("not json"))
    }

    /** A fake Juggluco on loopback: history, then the SSE stream feeds readings in order. */
    @Test
    fun historyThenLiveFromALocalServer() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun respond(path: String, type: String, body: String) = server.createContext(path) { ex ->
            val out = body.encodeToByteArray()
            ex.responseHeaders.add("Content-Type", type)
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        respond("/api/v1/entries.json", "application/json", """[{"date":1760776800000,"sgv":120,"delta":0,"direction":"Flat","device":"Juggluco"}]""")
        respond("/sgv.json", "application/json", """[{"_id":"3MH00ABCDE#1","sgv":120,"sensor_status":"09-10-26 08:05 (9d 3h)"}]""")
        respond(
            "/x/live", "text/event-stream",
            "event: glucose\ndata: {\"timestamp\":1760776860,\"glucoseMgDl\":121,\"rateMgDlPerMinute\":0.5,\"sensorId\":1}\n\n" +
                "event: other\ndata: {\"timestamp\":1,\"glucoseMgDl\":999}\n\n",
        )
        server.start()
        val readings = Collections.synchronizedList(ArrayList<Reading>())
        val statuses = Collections.synchronizedList(ArrayList<JugglucoStatus>())
        val gotLive = CompletableDeferred<Unit>()
        var sensor: SensorInfo? = null
        val sink = object : JugglucoSink {
            override fun onReadings(readings: List<Reading>) {
                readings.forEach { readingsAdd(it) }
            }

            fun readingsAdd(r: Reading) {
                readings += r
                if (r.mgdl == 121) gotLive.complete(Unit)
            }

            override fun onStatus(status: JugglucoStatus) {
                statuses += status
            }

            override fun onSensor(info: SensorInfo) {
                sensor = info
            }
        }
        val source = JugglucoSource(OkHttpClient(), "http://127.0.0.1:${server.address.port}") { zone }
        val job = launch(Dispatchers.IO) { source.run(sink) }
        try {
            withTimeout(10_000) { gotLive.await() }
        } finally {
            job.cancel()
            source.cancelCall()
            server.stop(0)
        }
        assertEquals(listOf(120, 121), readings.take(2).map { it.mgdl })
        assertEquals(0.5f, readings[1].rateMgdlPerMin)
        assertEquals("3MH00ABCDE", sensor?.serial)
        assertEquals(listOf(JugglucoStatus.FetchingHistory, JugglucoStatus.Live), statuses.take(2))
    }
}
