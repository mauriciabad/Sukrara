// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import app.mauri.sukrara.core.model.GlucoseRange
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The Nightscout read API on this phone: routes, filters, labels, and that nothing is served without the secret. */
class NightscoutApiTest {
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val secret = "0f1e2d3c4b5a69788796a5b4c3d2e1f0"

    private fun r(
        minAgo: Long,
        mgdl: Int,
        source: SourceId = SourceId.Libre3,
        rate: Double? = null,
    ) = Reading(now - Duration.ofMinutes(minAgo), mgdl, rate, source)

    private val readings = (0L until 30L).map { r(it, 100 + it.toInt(), rate = if (it % 2 == 0L) 0.4 else null) }
    private val treatments =
        listOf(
            Treatment("a", TreatmentKind.RapidInsulin, 4.5, now - Duration.ofHours(1), "", now, false, TreatmentOrigin.Phone),
            Treatment("b", TreatmentKind.Carbs, 30.0, now - Duration.ofHours(2), "lunch", now, false, TreatmentOrigin.Phone),
            Treatment("c", TreatmentKind.Note, 0.0, now - Duration.ofHours(3), "gone", now, true, TreatmentOrigin.Phone),
        )

    private fun call(
        path: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = mapOf("api-secret" to NightscoutApi.sha1(secret)),
        list: List<Reading> = readings,
    ) = NightscoutApi.route(HttpRequest("GET", path, query, headers), secret) {
        NightscoutApi.Snapshot(list, treatments.filter { !it.deleted }, now, ZoneOffset.UTC)
    }

    private fun array(a: HttpAnswer): JsonArray = Json.parseToJsonElement(a.body).jsonArray

    @Test
    fun `every data route needs the secret, status answers without anything about the person`() {
        for (p in listOf(
            "api/v1/entries.json",
            "api/v1/entries/current.json",
            "api/v1/treatments.json",
            "api/v2/properties",
            "api/v1/verifyauth",
        )) {
            assertTrue(p, call(p, headers = emptyMap()).status.startsWith("401"))
        }
        val status = call("api/v1/status.json", headers = emptyMap())
        assertTrue(status.status.startsWith("200"))
        val o = Json.parseToJsonElement(status.body).jsonObject
        assertEquals("Sukrara", o["name"]!!.jsonPrimitive.content)
        assertFalse(status.body.contains("thresholds"))
        assertFalse(status.body.contains("sgv"))
        assertEquals("null", o["authorized"].toString())
    }

    @Test
    fun `the secret is accepted as sha1 header, plain header, token or bearer, nothing else`() {
        fun ok(
            headers: Map<String, String>,
            query: Map<String, String> = emptyMap(),
        ) = NightscoutApi.authorized(secret, HttpRequest("GET", "api/v1/entries", query, headers))
        assertTrue(ok(mapOf("api-secret" to NightscoutApi.sha1(secret).uppercase())))
        assertTrue(ok(mapOf("api-secret" to secret)))
        assertTrue(ok(mapOf("api-secret" to "token=$secret")))
        assertTrue(ok(emptyMap(), mapOf("token" to secret)))
        assertTrue(ok(mapOf("authorization" to "Bearer $secret")))
        assertFalse(ok(emptyMap(), mapOf("secret" to NightscoutApi.sha1(secret))))
        assertFalse(ok(mapOf("api-secret" to "wrong")))
        assertFalse(ok(emptyMap()))
        assertFalse(NightscoutApi.authorized("", HttpRequest("GET", "x", mapOf("token" to ""), emptyMap())))
    }

    @Test
    fun `entries are newest first, filtered by date and capped`() {
        val all = array(call("api/v1/entries.json", mapOf("count" to "5")))
        assertEquals(5, all.size)
        val dates = all.map { it.jsonObject["date"]!!.jsonPrimitive.long }
        assertEquals(dates.sortedDescending(), dates)
        assertEquals(now.toEpochMilli(), dates.first())
        val since = (now - Duration.ofMinutes(3)).toEpochMilli()
        val filtered = array(call("api/v1/entries/sgv.json", mapOf("count" to "100", "find[date][\$gte]" to "$since")))
        assertEquals(4, filtered.size)
        val iso =
            array(
                call(
                    "api/v1/entries.json",
                    mapOf(
                        "count" to "100",
                        "find[dateString][\$gt]" to (now - Duration.ofMinutes(2)).toString(),
                    ),
                ),
            )
        assertEquals(2, iso.size)
        assertEquals(0, array(call("api/v1/entries.json", mapOf("find[type]" to "mbg"))).size)
        val huge = array(call("api/v1/entries.json", mapOf("count" to "999999")))
        assertEquals(readings.size, huge.size)
    }

    @Test
    fun `each entry says where it came from, unknown sources are never served`() {
        val list = listOf(r(0, 120, SourceId.Relay), r(1, 121, SourceId.DexcomG7), r(2, 122, SourceId("synthetic")), r(3, 123))
        val devices =
            array(call("api/v1/entries.json", mapOf("count" to "10"), list = list)).map {
                it.jsonObject["device"]!!.jsonPrimitive.content
            }
        assertEquals(listOf("Sukrara (following)", "Sukrara · Dexcom G7 (untested)", "Sukrara · FreeStyle Libre 3"), devices)
    }

    @Test
    fun `a LO or HI reading has no direction or slope`() {
        val list =
            listOf(Reading.lo(now, SourceId.Libre3), r(5, 60, rate = -1.5), Reading.hi(now - Duration.ofMinutes(10), SourceId.Libre3))
        val e = array(call("api/v1/entries.json", mapOf("count" to "10"), list = list)).map { it.jsonObject }
        assertEquals(GlucoseRange.LO, e[0]["sgv"]!!.jsonPrimitive.int)
        assertEquals("NONE", e[0]["direction"]!!.jsonPrimitive.content)
        assertEquals(0, e[0]["trend"]!!.jsonPrimitive.int)
        assertEquals("FortyFiveDown", e[1]["direction"]!!.jsonPrimitive.content)
        assertEquals(5, e[1]["trend"]!!.jsonPrimitive.int)
        assertEquals("NONE", e[2]["direction"]!!.jsonPrimitive.content)
    }

    @Test
    fun `treatments leave deleted ones out, v3 is not found, the rest is empty`() {
        val t = array(call("api/v1/treatments.json"))
        assertEquals(2, t.size)
        assertEquals("Correction Bolus", t[0].jsonObject["eventType"]!!.jsonPrimitive.content)
        assertEquals(
            24,
            t[0]
                .jsonObject["_id"]!!
                .jsonPrimitive.content.length,
        )
        assertTrue(call("api/v3/entries").status.startsWith("404"))
        assertEquals("[]", call("api/v1/devicestatus.json").body)
        assertTrue(call("api/v1/nope").status.startsWith("404"))
        val props = Json.parseToJsonElement(call("api/v2/properties").body).jsonObject
        assertEquals(100, props["bgnow"]!!.jsonObject["last"]!!.jsonPrimitive.int)
        assertNull(props["iob"])
        // the list form clients use answers the same object
        val listed = Json.parseToJsonElement(call("api/v2/properties/bgnow,delta,direction").body)
        assertTrue(listed is JsonObject)
    }

    @Test
    fun `each role serves only its own person's readings`() {
        assertTrue(SourceId.Libre3 in NightscoutApi.sources(Role.Wearer))
        assertFalse(SourceId.Relay in NightscoutApi.sources(Role.Wearer))
        assertEquals(setOf(SourceId.Relay, SourceId.Nearby), NightscoutApi.sources(Role.Follower))
    }

    private class Unreadable : SecureStore {
        override suspend fun read(name: String): ByteArray? = throw SecretUnreadableException(name, true, null)

        override suspend fun write(
            name: String,
            bytes: ByteArray,
        ) {}

        override suspend fun delete(name: String) {}
    }

    private fun server(secure: SecureStore = InMemorySecureStore()) =
        NightscoutApiServer(
            InMemorySettingsRepository(),
            secure,
            InMemoryReadingsRepository(),
            InMemoryTreatmentsRepository(),
            flowOf(Role.Wearer),
            FakeClock(now),
            Dispatchers.IO,
            port = 0,
        )

    @Test
    fun `switching on makes a secret, keeps it when off, new secret replaces it`() =
        runBlocking<Unit> {
            val secure = InMemorySecureStore()
            val api = server(secure)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                api.start(scope)
                api.setOn(true)
                withTimeout(5_000) { api.state.first { it is NsApiState.Listening } }
                val first = api.secret()!!
                assertEquals(32, first.length)
                api.setOn(false)
                withTimeout(5_000) { api.state.first { it == NsApiState.Off } }
                assertEquals(first, api.secret())
                val renewed = api.renewSecret()
                assertTrue(renewed != first)
                assertEquals(renewed, api.secret())
            } finally {
                api.stop()
                scope.cancel()
            }
        }

    @Test
    fun `an unreadable secret serves nothing`() =
        runBlocking<Unit> {
            val api = server(Unreadable())
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                api.start(scope)
                api.setOn(true)
                withTimeout(5_000) { api.state.first { it == NsApiState.SecretUnreadable } }
            } finally {
                api.stop()
                scope.cancel()
            }
        }

    @Test
    fun `over the socket, no secret is 401 and the secret is 200`() {
        val server = LocalHttpServer(0, { req -> call(req.path, req.query, req.headers) })
        try {
            assertTrue(server.bind())

            fun get(raw: String) =
                Socket(InetAddress.getLoopbackAddress(), server.boundPort).use { s ->
                    s.soTimeout = 10_000
                    s.getOutputStream().write(raw.toByteArray())
                    s.getInputStream().readBytes().decodeToString()
                }
            assertTrue(get("GET /api/v1/entries.json HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 401"))
            val ok = get("GET /api/v1/entries.json?token=$secret HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(ok.startsWith("HTTP/1.1 200"))
            assertTrue(Json.parseToJsonElement(ok.substringAfter("\r\n\r\n")) is JsonArray)
            assertFalse(Json.parseToJsonElement(ok.substringAfter("\r\n\r\n")) is JsonObject)
        } finally {
            server.stop()
        }
    }
}
