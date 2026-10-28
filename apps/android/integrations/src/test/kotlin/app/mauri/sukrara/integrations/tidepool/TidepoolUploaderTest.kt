// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.tidepool

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Base64

/** The Tidepool upload against a fake Tidepool (MockWebServer on loopback): no real account is ever contacted. */
class TidepoolUploaderTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val clock = FakeClock(now, ZoneId.of("Europe/Madrid"))
    private val settings = InMemorySettingsRepository()
    private val secure = InMemorySecureStore()
    private val store = InMemoryStateStore(TpState())
    private val readings = InMemoryReadingsRepository()
    private val treatments = InMemoryTreatmentsRepository()
    private val role = MutableStateFlow<Role?>(Role.Wearer)
    private val requests = mutableListOf<RecordedRequest>()

    /** Tokens the fake accepts; a refresh hands out the next one unless [refreshRefused]. */
    private val valid = mutableSetOf<String>()
    private var nextToken = 1
    private var refreshRefused = false

    /** Upload records on the account: id → device id; [gone] ones answer 404. */
    private val dataSets = linkedMapOf<String, String>()
    private val gone = mutableSetOf<String>()
    private var failData = false

    private lateinit var uploader: TidepoolUploader

    private fun token(): String = "tok-${nextToken++}".also { valid += it }

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    val tok = request.getHeader("X-Tidepool-Session-Token")
                    if (path == "/auth/login" && request.method == "POST") {
                        val basic = request.getHeader("Authorization")?.removePrefix("Basic ")
                        val pair = basic?.let { String(Base64.getDecoder().decode(it)) }
                        return if (pair == "me@example.com:secret-pw") {
                            MockResponse().setHeader("X-Tidepool-Session-Token", token()).setBody("""{"userid":"u1"}""")
                        } else {
                            MockResponse().setResponseCode(401)
                        }
                    }
                    if (path == "/auth/login" && request.method == "GET") {
                        return if (!refreshRefused && tok != null) {
                            MockResponse().setHeader("X-Tidepool-Session-Token", token())
                        } else {
                            MockResponse().setResponseCode(401)
                        }
                    }
                    if (tok !in valid) return MockResponse().setResponseCode(401)
                    val body = request.body.clone().readUtf8()
                    return when {
                        path == "/auth/logout" -> {
                            MockResponse()
                        }

                        path.startsWith("/v1/users/u1/data_sets?") -> {
                            val list =
                                dataSets.entries.filter { it.key !in gone }.joinToString(
                                    ",",
                                ) { """{"uploadId":"${it.key}","deviceId":"${it.value}"}""" }
                            MockResponse().setBody("[$list]")
                        }

                        path == "/v1/users/u1/data_sets" -> {
                            val id = "ds-${dataSets.size + 1}"
                            dataSets[id] =
                                Json
                                    .parseToJsonElement(body)
                                    .jsonObject["deviceId"]!!
                                    .jsonPrimitive.content
                            MockResponse().setBody("""{"data":{"uploadId":"$id"}}""")
                        }

                        path.startsWith("/v1/data_sets/") -> {
                            val id = path.removePrefix("/v1/data_sets/").substringBefore('/')
                            when {
                                id in gone -> MockResponse().setResponseCode(404)
                                failData -> MockResponse().setResponseCode(503)
                                else -> MockResponse().setBody("{}")
                            }
                        }

                        path == "/message/send/u1" -> {
                            MockResponse().setBody("""{"id":"msg-${requests.size}"}""")
                        }

                        path.startsWith("/message/") -> {
                            MockResponse()
                        }

                        else -> {
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
        server.start()
        uploader =
            TidepoolUploader(
                settings,
                secure,
                store,
                readings,
                treatments,
                role,
                OkHttpClient(),
                "1.0-test",
                clock,
                Dispatchers.Unconfined,
                baseUrl = server.url("/").toString(),
            )
        runBlocking { uploader.load() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun signedInAndOn() =
        runBlocking {
            assertEquals(TpSignIn.Done, uploader.signIn("me@example.com", "secret-pw"))
            uploader.change { it.copy(on = true) }
        }

    private fun entry(
        id: String,
        kind: TreatmentKind,
        amount: Double,
        minAgo: Long,
        note: String = "",
        modified: Instant = clock.now(),
        deleted: Boolean = false,
    ) = TreatmentRecord(Treatment(id, kind, amount, now - Duration.ofMinutes(minAgo), note, modified, deleted, TreatmentOrigin.Phone))

    private fun posted(path: String) = requests.filter { it.method == "POST" && it.path == path }.map { it.body.clone().readUtf8() }

    private fun data(dataSet: String = "ds-1"): List<JsonObject> =
        posted("/v1/data_sets/$dataSet/data").flatMap { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonObject } }

    private suspend fun status() = uploader.page.first().status

    @Test
    fun `sign-in keeps the session, never the password`() =
        runBlocking {
            signedInAndOn()
            val saved = secure.read(TidepoolUploader.NAME)!!.decodeToString()
            assertTrue("tok-1" in saved && "u1" in saved)
            assertFalse("secret-pw" in saved)
            assertFalse("secret-pw" in store.data.first().toString())
            assertFalse("secret-pw" in settings.toString())
            assertEquals("me@example.com", uploader.page.first().email)
        }

    @Test
    fun `a wrong password stores nothing`() =
        runBlocking {
            assertEquals(TpSignIn.BadCredentials, uploader.signIn("me@example.com", "nope"))
            assertNull(secure.read(TidepoolUploader.NAME))
            uploader.change { it.copy(on = true) }
            assertEquals(UploadStatus.Setup, status())
        }

    @Test
    fun `off sends nothing`() =
        runBlocking {
            assertEquals(TpSignIn.Done, uploader.signIn("me@example.com", "secret-pw"))
            readings.upsert(listOf(Reading(now, 120, null, SourceId.Libre3)))
            val before = requests.size
            uploader.cycle()
            assertEquals(before, requests.size)
            assertEquals(UploadStatus.Off, status())
        }

    @Test
    fun `readings go up one per 5 minutes and entries as food and insulin`() =
        runBlocking {
            signedInAndOn()
            // two readings in one slot: the earliest goes up
            readings.upsert(
                listOf(
                    Reading(now - Duration.ofMinutes(9), 110, 1.0, SourceId.Libre3),
                    Reading(now - Duration.ofMinutes(4), 118, null, SourceId.DexcomG7),
                    Reading(now - Duration.ofMinutes(3), 119, null, SourceId.DexcomG7),
                ),
            )
            treatments.upsertIfNewer(entry("c1", TreatmentKind.Carbs, 40.0, 10, note = "pasta"))
            treatments.upsertIfNewer(entry("r1", TreatmentKind.RapidInsulin, 4.5, 10))
            treatments.upsertIfNewer(entry("l1", TreatmentKind.LongInsulin, 18.0, 60))
            uploader.cycle()

            val created = posted("/v1/users/u1/data_sets").single()
            assertTrue("\"deviceModel\":\"Sukrara\"" in created)
            assertTrue("org.tidepool.deduplicator.dataset.delete.origin" in created)
            val sent = data()
            val cbg = sent.filter { it["type"]!!.jsonPrimitive.content == "cbg" }
            assertEquals(listOf(110, 118), cbg.map { it["value"]!!.jsonPrimitive.content.toInt() })
            assertTrue("Dexcom G7 / ONE+ (untested)" in cbg[1].toString())
            assertTrue("FreeStyle Libre 3" in cbg[0].toString())
            // local wall clock and offset (Madrid is UTC+1 on this date)
            assertEquals(60, cbg[0]["timezoneOffset"]!!.jsonPrimitive.content.toInt())
            assertEquals("Europe/Madrid", cbg[0]["timezone"]!!.jsonPrimitive.content)
            val food = sent.single { it["type"]!!.jsonPrimitive.content == "food" }
            assertTrue("\"net\":40.0" in food.toString() && "pasta" in food.toString())
            assertEquals("sukrara-tr-c1", food["origin"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            val insulin = sent.filter { it["type"]!!.jsonPrimitive.content == "insulin" }.map { it.toString() }
            assertTrue(insulin.any { "\"actingType\":\"rapid\"" in it && "4.5" in it })
            assertTrue(insulin.any { "\"actingType\":\"long\"" in it && "18.0" in it })
            assertEquals(UploadStatus.Ok(now, 5), status())

            // nothing new: nothing sent again
            val before = requests.size
            uploader.cycle()
            assertEquals(before, requests.size)
        }

    @Test
    fun `edits go up again and deletes remove the datum`() =
        runBlocking {
            signedInAndOn()
            treatments.upsertIfNewer(entry("c1", TreatmentKind.Carbs, 40.0, 10))
            uploader.cycle()
            clock.advance(Duration.ofMinutes(1))
            treatments.replace(entry("c1", TreatmentKind.Carbs, 45.0, 10, modified = clock.now()))
            uploader.cycle()
            assertTrue("\"net\":45.0" in data().last().toString())
            clock.advance(Duration.ofMinutes(1))
            treatments.replace(entry("c1", TreatmentKind.Carbs, 45.0, 10, modified = clock.now(), deleted = true))
            uploader.cycle()
            val delete = requests.single { it.method == "DELETE" && it.path == "/v1/data_sets/ds-1/data" }
            assertTrue("sukrara-tr-c1" in delete.body.clone().readUtf8())
            // an entry deleted before it ever went up is never sent
            treatments.upsertIfNewer(entry("gone", TreatmentKind.RapidInsulin, 2.0, 5, deleted = true))
            val before = requests.size
            uploader.cycle()
            assertEquals(before, requests.size)
        }

    @Test
    fun `notes are Tidepool notes, edited and removed with the entry`() =
        runBlocking {
            signedInAndOn()
            treatments.upsertIfNewer(entry("n1", TreatmentKind.Note, 0.0, 15, note = "walk"))
            uploader.cycle()
            val note = posted("/message/send/u1").single()
            assertTrue("walk" in note && "\"userid\":\"u1\"" in note)
            clock.advance(Duration.ofMinutes(1))
            treatments.replace(entry("n1", TreatmentKind.Note, 0.0, 15, note = "long walk", modified = clock.now()))
            uploader.cycle()
            assertTrue(requests.any { it.method == "PUT" && it.path!!.startsWith("/message/edit/msg-") })
            clock.advance(Duration.ofMinutes(1))
            treatments.replace(entry("n1", TreatmentKind.Note, 0.0, 15, note = "long walk", modified = clock.now(), deleted = true))
            uploader.cycle()
            assertTrue(requests.any { it.method == "DELETE" && it.path!!.startsWith("/message/remove/msg-") })
            assertEquals(1, posted("/message/send/u1").size)
        }

    @Test
    fun `a refused token is refreshed once and the new one kept`() =
        runBlocking {
            signedInAndOn()
            valid.clear()
            readings.upsert(listOf(Reading(now, 120, null, SourceId.Libre3)))
            uploader.cycle()
            assertTrue(requests.any { it.method == "GET" && it.path == "/auth/login" })
            assertTrue("tok-2" in secure.read(TidepoolUploader.NAME)!!.decodeToString())
            assertEquals(1, data().size)
            assertEquals(UploadStatus.Ok(now, 1), status())
        }

    @Test
    fun `signed out by Tidepool wipes the session and asks to sign in again`() =
        runBlocking {
            signedInAndOn()
            valid.clear()
            refreshRefused = true
            readings.upsert(listOf(Reading(now, 120, null, SourceId.Libre3)))
            uploader.cycle()
            assertNull(secure.read(TidepoolUploader.NAME))
            val page = uploader.page.first()
            assertFalse(page.signedIn)
            assertEquals(UploadStatus.AuthFailed, page.status)
            // the switch can still be turned off; signing in again clears it
            refreshRefused = false
            assertEquals(TpSignIn.Done, uploader.signIn("me@example.com", "secret-pw"))
            uploader.cycle()
            assertEquals(UploadStatus.Ok(now, 1), status())
        }

    @Test
    fun `a failure backs off and a deleted upload record is made again`() =
        runBlocking {
            signedInAndOn()
            readings.upsert(listOf(Reading(now - Duration.ofMinutes(5), 120, null, SourceId.Libre3)))
            uploader.cycle()
            failData = true
            readings.upsert(listOf(Reading(now, 125, null, SourceId.Libre3)))
            uploader.cycle()
            assertTrue(status() is UploadStatus.Failing)
            // backing off: nothing is tried before the next attempt
            val before = requests.size
            uploader.cycle()
            assertEquals(before, requests.size)
            failData = false
            gone += "ds-1"
            clock.advance(Duration.ofMinutes(2))
            uploader.cycle()
            assertEquals(
                "",
                store.data
                    .first()
                    .role(Role.Wearer)
                    .dataSet,
            )
            clock.advance(Duration.ofMinutes(3))
            uploader.cycle()
            assertEquals(
                "ds-2",
                store.data
                    .first()
                    .role(Role.Wearer)
                    .dataSet,
            )
            assertEquals(listOf(125), data("ds-2").map { it["value"]!!.jsonPrimitive.content.toInt() })
            assertEquals(UploadStatus.Ok(clock.now(), 1), status())
        }

    @Test
    fun `a forgotten upload record is found again by device id`() =
        runBlocking {
            signedInAndOn()
            readings.upsert(listOf(Reading(now - Duration.ofMinutes(5), 120, null, SourceId.Libre3)))
            uploader.cycle()
            store.update { it.withRole(Role.Wearer, it.role(Role.Wearer).copy(dataSet = "")) }
            readings.upsert(listOf(Reading(now, 125, null, SourceId.Libre3)))
            uploader.cycle()
            assertEquals(1, posted("/v1/users/u1/data_sets").size)
            assertEquals(2, data("ds-1").size)
        }

    @Test
    fun `a follower uploads the person they follow only after choosing to`() =
        runBlocking {
            role.value = Role.Follower
            signedInAndOn()
            readings.upsert(listOf(Reading(now, 130, null, SourceId.Relay)))
            val before = requests.size
            uploader.cycle()
            assertEquals(before, requests.size)
            val waiting = uploader.page.first()
            assertTrue(waiting.needsChoice)
            assertEquals(UploadStatus.Off, waiting.status)
            uploader.change { it.copy(uploadFollowed = true) }
            uploader.cycle()
            assertTrue("\"deviceModel\":\"Sukrara (following)\"" in posted("/v1/users/u1/data_sets").single())
            val cbg = data().single()
            // the followed person's sensor isn't known here: no sensor named
            assertFalse("payload" in cbg.toString())
            assertTrue(
                store.data
                    .first()
                    .role(Role.Follower)
                    .deviceId
                    .startsWith("Sukrara-following-"),
            )
        }

    @Test
    fun `another account starts from nothing, the same one keeps what it has`() =
        runBlocking {
            signedInAndOn()
            readings.upsert(listOf(Reading(now, 120, null, SourceId.Libre3)))
            uploader.cycle()
            val device =
                store.data
                    .first()
                    .role(Role.Wearer)
                    .deviceId
            uploader.signOut()
            assertNull(secure.read(TidepoolUploader.NAME))
            assertTrue(requests.any { it.path == "/auth/logout" })
            assertEquals(UploadStatus.Setup, status())
            assertEquals(TpSignIn.Done, uploader.signIn("me@example.com", "secret-pw"))
            assertEquals(
                device,
                store.data
                    .first()
                    .role(Role.Wearer)
                    .deviceId,
            )
            store.update { it.copy(account = "someone else") }
            assertEquals(TpSignIn.Done, uploader.signIn("me@example.com", "secret-pw"))
            assertNotEquals(
                device,
                store.data
                    .first()
                    .role(Role.Wearer)
                    .deviceId
                    .ifEmpty { "none" },
            )
        }

    @Test
    fun `glucose and entries switches`() =
        runBlocking {
            signedInAndOn()
            uploader.change { it.copy(glucose = false) }
            readings.upsert(listOf(Reading(now, 120, null, SourceId.Libre3)))
            treatments.upsertIfNewer(entry("c1", TreatmentKind.Carbs, 10.0, 5))
            uploader.cycle()
            assertEquals(listOf("food"), data().map { it["type"]!!.jsonPrimitive.content })
            uploader.change { it.copy(glucose = true, treatments = false) }
            clock.advance(Duration.ofMinutes(1))
            treatments.replace(entry("c1", TreatmentKind.Carbs, 12.0, 5, modified = clock.now()))
            uploader.cycle()
            assertEquals(listOf("food", "cbg"), data().map { it["type"]!!.jsonPrimitive.content })
        }

    @Test
    fun `json shapes`() {
        val zone = ZoneId.of("Europe/Madrid")
        val lo = TidepoolData.cbg(Reading.lo(now, SourceId.SmartGuide), zone, "d", "v")
        assertEquals(39, lo["value"]!!.jsonPrimitive.content.toInt())
        assertFalse("trendRate" in lo)
        assertTrue("(untested)" in lo.toString())
        assertEquals("2026-10-28T13:00:00", lo["deviceTime"]!!.jsonPrimitive.content)
        val note = Treatment("x", TreatmentKind.Note, 0.0, now, "hi", now, false, TreatmentOrigin.Phone)
        assertNull(TidepoolData.treatment(note, zone, "d", "v"))
        assertEquals(
            JsonArray(listOf(Json.parseToJsonElement("""{"origin":{"id":"a"}}"""))),
            TidepoolData.selectors(listOf("a")),
        )
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L), (1..8).map { TidepoolQueue.backoffMs(it) / 60_000 })
    }
}
