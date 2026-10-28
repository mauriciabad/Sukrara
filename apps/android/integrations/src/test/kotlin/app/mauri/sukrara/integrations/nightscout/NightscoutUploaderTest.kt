// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.nightscout

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The uploader against a fake Nightscout (MockWebServer on loopback): no real site is ever contacted. */
class NightscoutUploaderTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val clock = FakeClock(now, ZoneOffset.UTC)
    private val settings = InMemorySettingsRepository()
    private val secure = InMemorySecureStore()
    private val store = InMemoryStateStore(NsState())
    private val readings = InMemoryReadingsRepository()
    private val treatments = InMemoryTreatmentsRepository()
    private val role = MutableStateFlow<Role?>(Role.Wearer)
    private val requests = mutableListOf<RecordedRequest>()

    /** The site's sgv entries (date ms → device) the fake serves to the duplicate check. */
    private var siteEntries: Map<Long, String> = emptyMap()
    private var refuse = false
    private var fail503 = false

    private lateinit var uploader: NightscoutUploader

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.path.orEmpty()
                    return when {
                        refuse -> {
                            MockResponse().setResponseCode(401)
                        }

                        fail503 -> {
                            MockResponse().setResponseCode(503).setHeader("Retry-After", "120")
                        }

                        path.startsWith("/api/v2/authorization/request/") -> {
                            MockResponse().setBody("""{"token":"jwt-1","exp":${now.epochSecond + 3600}}""")
                        }

                        path.startsWith("/api/v1/status.json") -> {
                            MockResponse().setBody("""{"version":"15.0.3"}""")
                        }

                        path.startsWith("/api/v1/entries/sgv.json") -> {
                            MockResponse().setBody(
                                siteEntries.entries.joinToString(",", "[", "]") { (d, dev) -> """{"date":$d,"device":"$dev"}""" },
                            )
                        }

                        path.startsWith("/api/v1/treatments.json") -> {
                            MockResponse().setBody("[]")
                        }

                        path.startsWith("/api/v1/old") -> {
                            MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/")
                        }

                        request.method == "POST" -> {
                            MockResponse().setBody("[]")
                        }

                        else -> {
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
        server.start()
        uploader =
            NightscoutUploader(
                settings,
                secure,
                store,
                readings,
                treatments,
                flowOf(null),
                role,
                { 80 to false },
                OkHttpClient(),
                clock,
                Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() = server.shutdown()

    private val url get() = server.url("/").toString()

    private fun reading(minAgo: Long) = Reading(now - Duration.ofMinutes(minAgo), 120, 0.5, SourceId.Libre3)

    private fun setUpSite(kind: NsCredentialKind = NsCredentialKind.Token) =
        runBlocking {
            uploader.configure(url, kind, "upload-0123456789abcdef", NsApi.V1)
            uploader.change { it.copy(on = true) }
        }

    @Test
    fun `nothing goes up while switched off or on a follower phone`() =
        runBlocking {
            readings.upsert(listOf(reading(5)))
            uploader.configure(url, NsCredentialKind.Token, "upload-0123456789abcdef", NsApi.V1)
            uploader.cycle()
            assertTrue(requests.isEmpty())
            uploader.change { it.copy(on = true) }
            role.value = Role.Follower
            uploader.cycle()
            assertTrue(requests.isEmpty())
            assertEquals(UploadStatus.Off, uploader.page.first().status)
        }

    @Test
    fun `readings go up in one v1 batch with a JWT, only from this phone's sensor, and are not sent twice`() =
        runBlocking {
            readings.upsert(listOf(reading(10), reading(5), Reading(now - Duration.ofMinutes(3), 99, null, SourceId("synthetic"))))
            setUpSite()
            uploader.cycle()
            val post = requests.single { it.method == "POST" && it.path == "/api/v1/entries" }
            assertEquals("Bearer jwt-1", post.getHeader("Authorization"))
            val docs = Json.parseToJsonElement(post.body.readUtf8()).jsonArray
            assertEquals(2, docs.size)
            val device = docs[0].jsonObject["device"]!!.jsonPrimitive.content
            assertTrue(device, device.matches(Regex("Sukrara-[0-9a-f]{6}")))
            assertEquals("sgv", docs[0].jsonObject["type"]!!.jsonPrimitive.content)
            requests.clear()
            uploader.cycle()
            assertTrue(requests.none { it.method == "POST" && it.path == "/api/v1/entries" })
            assertTrue(uploader.page.first().status is UploadStatus.Ok)
        }

    @Test
    fun `an API secret is sent only as its SHA-1 and never in a URL`() =
        runBlocking {
            readings.upsert(listOf(reading(5)))
            setUpSite(NsCredentialKind.Secret)
            uploader.cycle()
            assertTrue(requests.isNotEmpty())
            for (r in requests) {
                assertEquals(NsDocs.sha1Hex("upload-0123456789abcdef"), r.getHeader("api-secret"))
                assertFalse(r.path.orEmpty().contains("upload-0123456789abcdef"))
            }
        }

    @Test
    fun `a refused credential stops uploading until it is changed`() =
        runBlocking {
            readings.upsert(listOf(reading(5)))
            setUpSite()
            refuse = true
            uploader.cycle()
            assertEquals(UploadStatus.AuthFailed, uploader.page.first().status)
            requests.clear()
            refuse = false
            uploader.cycle()
            assertTrue(requests.isEmpty())
            uploader.configure(url, NsCredentialKind.Token, "upload-fedcba9876543210", NsApi.V1)
            uploader.cycle()
            assertTrue(requests.any { it.path == "/api/v1/entries" })
        }

    @Test
    fun `a server error backs off and honours Retry-After`() =
        runBlocking {
            readings.upsert(listOf(reading(5)))
            setUpSite()
            fail503 = true
            uploader.cycle()
            val st = store.data.first()
            assertEquals(1, st.failures)
            assertTrue(st.nextAttemptMs >= now.toEpochMilli() + 120_000)
            assertTrue(uploader.page.first().status is UploadStatus.Failing)
            requests.clear()
            fail503 = false
            uploader.cycle()
            assertTrue("no retry before the backoff ends", requests.isEmpty())
            clock.advance(Duration.ofMinutes(3))
            uploader.cycle()
            assertTrue(requests.any { it.path == "/api/v1/entries" })
        }

    @Test
    fun `another uploader on the site pauses everything until Upload anyway`() =
        runBlocking {
            readings.upsert(listOf(reading(10), reading(5)))
            treatments.upsertIfNewer(record("t1"))
            // the other app writes as "Sukrara" (another install): it is not this one
            siteEntries = mapOf((now - Duration.ofMinutes(4)).toEpochMilli() to "Sukrara")
            setUpSite()
            uploader.cycle()
            assertTrue(requests.none { it.method == "POST" || it.method == "DELETE" })
            assertEquals(UploadStatus.Paused("Sukrara"), uploader.page.first().status)
            uploader.acceptConflict()
            uploader.cycle()
            val posted = requests.filter { it.method == "POST" }.map { it.path }
            assertTrue(posted.toString(), "/api/v1/entries" in posted && "/api/v1/treatments" in posted)
            // the minute the other device covers (and its neighbours) isn't doubled
            val entries =
                Json.parseToJsonElement(requests.first { it.path == "/api/v1/entries" }.body.readUtf8()).jsonArray
            assertEquals(1, entries.size)
        }

    @Test
    fun `a redirect is never followed`() {
        val site = NsSite.parse(server.url("/api/v1/old").toString())
        val c = NsClient(NsConfig(site, NsAuth.ApiSecret("secret-value"), NsApi.V1, "d"), OkHttpClient()) { now.toEpochMilli() }
        val (_, o) = c.status()
        assertTrue(o.toString(), o is Outcome.Retry)
        assertEquals(1, requests.size)
    }

    @Test
    fun `a saved token is never sent to a new address`() =
        runBlocking {
            setUpSite()
            requests.clear()
            val other = server.url("/other").toString()
            assertTrue(uploader.test(other, NsCredentialKind.Token, null, NsApi.V1) is NsTestResult.Failed)
            assertTrue(requests.isEmpty())
            assertTrue(runCatching { uploader.configure(other, NsCredentialKind.Token, null, NsApi.V1) }.isFailure)
        }

    @Test
    fun `remove forgets the site, the credential and the state`() =
        runBlocking {
            setUpSite()
            uploader.remove()
            assertNull(secure.read(NightscoutUploader.NAME))
            assertEquals(NsState(), store.data.first())
            assertEquals(NsSettings(), uploader.settingsFlow.first())
        }

    @Test
    fun `site rules accept https anywhere and http only to this phone`() {
        assertNull(NsSite.parse("https://ns.example.org/").problem)
        assertNull(NsSite.parse("http://127.0.0.1:1337").problem)
        assertEquals(NsSiteProblem.Https, NsSite.parse("http://127.0.0.2:1337").problem)
        assertEquals(NsSiteProblem.Https, NsSite.parse("http://192.168.1.10:1337").problem)
        assertEquals(NsSiteProblem.Https, NsSite.parse("http://ns.example.org").problem)
        assertEquals(NsSiteProblem.Url, NsSite.parse("https://user:pw@ns.example.org").problem)
        assertEquals(NsSiteProblem.Url, NsSite.parse("ftp://ns.example.org").problem)
        assertEquals("AccessToken(***)", NsAuth.AccessToken("secret-value").toString())
    }

    private fun record(id: String) =
        TreatmentRecord(
            Treatment(
                id,
                TreatmentKind.Carbs,
                30.0,
                now - Duration.ofMinutes(20),
                "",
                now - Duration.ofMinutes(20),
                origin = TreatmentOrigin.Phone,
            ),
        )
}
