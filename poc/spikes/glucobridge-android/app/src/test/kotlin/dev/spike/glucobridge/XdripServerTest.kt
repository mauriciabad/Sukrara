// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real server on a spare loopback port and checks the shapes Garmin apps parse. */
class XdripServerTest {
    private val port = 27581
    private val log = File.createTempFile("xdrip", ".log")
    private val server = XdripServer(log, port)
    private val now = System.currentTimeMillis() / 1000

    @Before fun setUp() {
        assertEquals(null, server.start())
        server.onSensor("XX0TEST", now - 12 * 86400 - 6 * 3600)
        // 1-minute live readings for 20 min (rising 2 mg/dL/min), plus 5-minute history further back.
        for (m in 60 downTo 25 step 5) server.add(Bridge.Reading(now - m * 60, 100, null))
        for (m in 20 downTo 0) server.add(Bridge.Reading(now - m * 60, 140 - 2 * m, 2.0))
    }

    @After fun tearDown() { server.stop(); log.delete() }

    private fun get(path: String): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port/$path").openConnection() as HttpURLConnection
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code == 200) assertEquals("application/json; charset=utf-8", c.contentType)
        return code to body
    }

    @Test fun briefSgvMatchesJugglucoShape() {
        val (code, body) = get("sgv.json?count=3&brief_mode=Y&sensor=Y")
        assertEquals(200, code)
        val lines = body.trim().lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0], Regex("""^\[\{"date":${now}000,"sgv":140,"delta":10\.000,"direction":"FortyFiveUp","noise":1,"units_hint":"mgdl","sensor_status":"\d\d-\d\d-\d\d \d\d:\d\d \(12d 6h\)"\},$""").matches(lines[0]))
        // spaced ≥ 270 s apart: the next one is 5 minutes older
        assertTrue(lines[1], lines[1].startsWith("{\"date\":${now - 300}000,\"sgv\":130,"))
    }

    @Test fun fullSgvAndHistorySlope() {
        val (_, body) = get("sgv.json?count=100")
        assertTrue(body.contains("\"_id\":\"XX0TEST#"))
        assertTrue(body.contains("\"type\":\"sgv\""))
        // the history points have no rate: flat 100 → delta 0, Flat
        assertTrue(body.contains("\"date\":${now - 3600}000,\"sgv\":100,\"delta\":0.000,\"direction\":\"NONE\""))
        assertTrue(body.contains("\"date\":${now - 3300}000,\"sgv\":100,\"delta\":0.000,\"direction\":\"Flat\""))
    }

    @Test fun watchQueryShapes() {
        // as sent by the Garmin apps (investigation 08): float count, Juggluco's interval
        val tenMin = get("sgv.json?all_data=Y&count=72.0&sensor=Y&interval=600&brief_mode=true").second.trim().lines()
        assertTrue(tenMin[1], tenMin[1].startsWith("{\"date\":${now - 600}000,"))
        val everyMinute = get("api/v1/entries.json?count=180&interval=0").second.trim().lines()
        assertEquals(21 + 8, everyMinute.size)
    }

    @Test fun readingsSurviveRestart() {
        val f = File.createTempFile("readings", ".csv")
        XdripServer(log, port + 1, f).apply { add(Bridge.Reading(now - 60, 90, 1.5)); add(Bridge.Reading(now, 92, null)) }
        val again = XdripServer(log, port + 1, f)
        assertEquals(null, again.start())
        val c = URL("http://127.0.0.1:${port + 1}/sgv.json?interval=0&brief_mode=Y").openConnection() as HttpURLConnection
        val body = c.inputStream.bufferedReader().readText()
        again.stop(); f.delete()
        assertTrue(body, body.contains("\"sgv\":92,") && body.contains("\"sgv\":90,\"delta\":7.500,"))
    }

    @Test fun otherEndpoints() {
        assertEquals(200, get("pebble").first)
        assertTrue(get("status.json").second.contains("\"units\":\"mg/dL\""))
        assertEquals(200, get("api/v1/entries/sgv.json?count=1").first)
        assertEquals(404, get("nope").first)
        get("sgv.json?count=3&brief_mode=Y&sensor=Y")
        // the log line is written right after the response is flushed
        repeat(20) { if (log.readText().contains("GET /sgv.json?count=3&brief_mode=Y&sensor=Y -> 200")) return; Thread.sleep(50) }
        throw AssertionError("request not logged: ${log.readText()}")
    }
}
