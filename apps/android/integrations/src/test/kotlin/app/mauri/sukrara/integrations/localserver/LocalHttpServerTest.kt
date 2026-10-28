// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.integrations.CurrentSensor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The bounded loopback server and the xDrip+ answers Garmin watch apps read. */
class LocalHttpServerTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val readings =
        (0L until 60L).map { m -> Reading(now - Duration.ofMinutes(m), 100 + m.toInt(), if (m % 2 == 0L) 0.4 else null, SourceId.Libre3) }
    private val sensor = CurrentSensor("3MH00ABCDEF", now - Duration.ofDays(3) - Duration.ofHours(5), now + Duration.ofDays(12))
    private val asked = mutableListOf<String>()
    private val server =
        LocalHttpServer(0, { XdripEndpoint.route(it, readings, sensor, now, ZoneOffset.UTC) }) { r, _ -> asked += r.path }

    @After
    fun tearDown() = server.stop()

    private fun get(raw: String): String =
        Socket(InetAddress.getLoopbackAddress(), server.boundPort).use { s ->
            s.soTimeout = 10_000
            s.getOutputStream().write(raw.toByteArray())
            s.getInputStream().readBytes().decodeToString()
        }

    private fun body(response: String) = response.substringAfter("\r\n\r\n")

    @Test
    fun `binds loopback only`() {
        assertTrue(server.bind())
        assertTrue(server.boundAddress!!.isLoopbackAddress)
        assertTrue(server.running)
    }

    @Test
    fun `a taken port fails the bind without throwing`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { taken ->
            val second = LocalHttpServer(taken.localPort, { HttpAnswer.NOT_FOUND })
            assertFalse(second.bind())
            assertFalse(second.running)
        }
    }

    @Test
    fun `sgv json is Juggluco-compatible, spaced and newest first`() {
        server.bind()
        val r = get("GET /sgv.json?count=3.0&sensor=Y HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
        assertTrue(r, r.startsWith("HTTP/1.1 200 OK"))
        val arr = Json.parseToJsonElement(body(r)).jsonArray
        assertEquals(3, arr.size)
        val first = arr[0].jsonObject
        assertEquals(now.toEpochMilli(), first["date"]!!.jsonPrimitive.content.toLong())
        assertEquals("Sukrara", first["device"]!!.jsonPrimitive.content)
        assertEquals("3MH00ABCDEF#${now.epochSecond / 60}", first["_id"]!!.jsonPrimitive.content)
        assertEquals("25-10-26 07:00 (3d 5h)", first["sensor_status"]!!.jsonPrimitive.content)
        // 270 s spacing: 0, 5 and 10 minutes ago
        assertEquals(
            (now - Duration.ofMinutes(5)).toEpochMilli(),
            arr[1]
                .jsonObject["date"]!!
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(listOf("sgv.json"), asked)
    }

    @Test
    fun `brief mode drops the long fields`() {
        server.bind()
        val o =
            Json
                .parseToJsonElement(
                    body(get("GET /api/v1/entries/sgv.json?count=1&brief_mode=Y HTTP/1.1\r\n\r\n")),
                ).jsonArray[0]
                .jsonObject
        assertFalse("_id" in o)
        assertEquals("Flat", o["direction"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown paths, other methods and oversized requests are refused`() {
        server.bind()
        assertTrue(get("GET /nope HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 404"))
        assertTrue(get("POST /sgv.json HTTP/1.1\r\nContent-Length: 0\r\n\r\n").startsWith("HTTP/1.1 405"))
        val huge = "GET /sgv.json HTTP/1.1\r\nX-Pad: " + "a".repeat(LocalHttpServer.MAX_HEAD) + "\r\n\r\n"
        // the answer is 431, or the connection is reset because the rest was never read: never served
        val answer = runCatching { get(huge) }.getOrDefault("reset")
        assertTrue(answer, answer.startsWith("HTTP/1.1 431") || answer == "reset" || answer.isEmpty())
        assertTrue(asked.none { it == "sgv.json" })
        // a page in the browser reaching the port through another host name is refused
        assertTrue(get("GET /sgv.json HTTP/1.1\r\nHost: evil.example:17580\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertFalse(get("GET /sgv.json HTTP/1.1\r\nHost: 127.0.0.1:17580\r\n\r\n").contains("Access-Control-Allow-Origin"))
        // HEAD answers without a body
        assertEquals("", body(get("HEAD /status.json HTTP/1.1\r\n\r\n")))
    }

    @Test
    fun `stopping frees the port`() {
        server.bind()
        val port = server.boundPort
        server.stop()
        assertFalse(server.running)
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
    }
}
