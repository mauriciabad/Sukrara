// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.libreview

import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.core.model.TreatmentKind
import app.mauri.sukrara.core.model.TreatmentOrigin
import app.mauri.sukrara.core.model.storage.TreatmentRecord
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.core.testing.InMemoryTreatmentsRepository
import app.mauri.sukrara.integrations.CurrentSensor
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** The LibreView upload against a fake LibreView (MockWebServer on loopback): no real account is ever contacted. */
class LibreViewUploaderTest {
    private val server = MockWebServer()
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val clock = FakeClock(now, ZoneOffset.UTC)
    private val settings = InMemorySettingsRepository()
    private val secure = InMemorySecureStore()
    private val store = InMemoryStateStore(LvState())
    private val treatments = InMemoryTreatmentsRepository()
    private val role = MutableStateFlow<Role?>(Role.Wearer)
    private val sensor = MutableStateFlow<CurrentSensor?>(null)
    private val requests = mutableListOf<RecordedRequest>()

    /** The account uploads from another device: sign-in without take-over is refused. */
    private var otherDevice = false

    /** `/api/measurements` answers: null = ok. */
    private var measurements: MockResponse? = null
    private var redirectConfig = false

    private lateinit var uploader: LibreViewUploader

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val body = request.body.clone().readUtf8()
                    return when (request.path) {
                        "/assets" -> {
                            if (redirectConfig) {
                                MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/")
                            } else {
                                MockResponse().setBody("""{"Configuration":"${server.url("/config")}"}""")
                            }
                        }

                        "/config" -> {
                            MockResponse().setBody("""{"newYuUrl":"${server.url("/").toString().trimEnd('/')}","newYuApiKey":"KEY"}""")
                        }

                        "/api/nisperson/getauthentication" -> {
                            if (otherDevice && "\"SetDevice\":false" in body) {
                                MockResponse().setBody("""{"status":20,"reason":"wrongDeviceForUser"}""")
                            } else if ("\"Password\":\"secret-pw\"" !in body) {
                                MockResponse().setBody("""{"status":2}""")
                            } else {
                                MockResponse().setBody(
                                    """{"status":0,"result":{"UserToken":"tok-1","DateOfBirth":"1990-01-01","FirstName":"A","LastName":"B"}}""",
                                )
                            }
                        }

                        "/api/nisperson" -> {
                            MockResponse().setBody("""{"status":0}""")
                        }

                        "/api/measurements" -> {
                            measurements ?: MockResponse().setBody("""{"status":0}""")
                        }

                        else -> {
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
        server.start()
        uploader =
            LibreViewUploader(
                settings,
                secure,
                store,
                treatments,
                emptyFlow(),
                sensor,
                role,
                LibreViewClient(OkHttpClient(), server.url("/assets").toString(), allowLoopbackHttp = true),
                clock,
                Dispatchers.Unconfined,
            )
    }

    @After
    fun tearDown() = server.shutdown()

    private fun treatment(
        id: String,
        minAgo: Long,
        amount: Double = 2.0,
        modified: Instant = clock.now(),
        deleted: Boolean = false,
    ) = TreatmentRecord(
        Treatment(id, TreatmentKind.RapidInsulin, amount, now - Duration.ofMinutes(minAgo), "", modified, deleted, TreatmentOrigin.Phone),
    )

    private fun signedInAndOn() =
        runBlocking {
            assertEquals(LvSignIn.Done, uploader.signIn("me@example.com", "secret-pw", takeOver = false))
            uploader.change { it.copy(on = true) }
        }

    private fun uploads() = requests.filter { it.path == "/api/measurements" }.map { it.body.clone().readUtf8() }

    @Test
    fun `sign-in keeps the token, never the password`() =
        runBlocking {
            signedInAndOn()
            val saved = secure.read(LibreViewUploader.NAME)!!.decodeToString()
            assertTrue("tok-1" in saved)
            assertFalse("secret-pw" in saved)
            assertFalse("secret-pw" in store.data.first().toString())
            assertFalse("secret-pw" in settings.toString())
            assertEquals("me@example.com", store.data.first().email)
            // the config's key goes with the sign-in; nothing is sent with SetDevice:true
            val auth = requests.first { it.path == "/api/nisperson/getauthentication" }
            assertEquals("KEY", auth.getHeader("x-api-key"))
            assertTrue("\"SetDevice\":false" in auth.body.clone().readUtf8())
        }

    @Test
    fun `another uploading device is taken over only when asked`() =
        runBlocking {
            otherDevice = true
            assertEquals(LvSignIn.OtherDevice, uploader.signIn("me@example.com", "secret-pw", takeOver = false))
            assertEquals(null, secure.read(LibreViewUploader.NAME))
            assertEquals(LvSignIn.Done, uploader.signIn("me@example.com", "secret-pw", takeOver = true))
            val bodies = requests.filter { it.path == "/api/nisperson/getauthentication" }.map { it.body.clone().readUtf8() }
            assertEquals(listOf(false, true), bodies.map { "\"SetDevice\":true" in it })
        }

    @Test
    fun `a wrong password stores nothing`() =
        runBlocking {
            assertEquals(LvSignIn.BadCredentials, uploader.signIn("me@example.com", "nope", takeOver = false))
            assertEquals(null, secure.read(LibreViewUploader.NAME))
        }

    @Test
    fun `a redirect is never followed`() =
        runBlocking {
            redirectConfig = true
            assertTrue(uploader.signIn("me@example.com", "secret-pw", takeOver = false) is LvSignIn.Failed)
            assertEquals(listOf("/assets"), requests.map { it.path })
        }

    @Test
    fun `only entries changed after switching on go up, and edits retract the old version`() =
        runBlocking {
            treatments.upsertIfNewer(treatment("old", 60, modified = now - Duration.ofHours(1)))
            signedInAndOn()
            clock.advance(Duration.ofMinutes(1))
            treatments.upsertIfNewer(treatment("new", 5, modified = clock.now()))
            uploader.cycle()
            val first = uploads().single()
            assertTrue(LibreViewPayload.recordNumber("new", "rapid", clock.now().toEpochMilli()).toString() in first)
            assertFalse(
                "\"recordNumber\":${LibreViewPayload.recordNumber("old", "rapid", (now - Duration.ofHours(1)).toEpochMilli())}" in first,
            )
            val v1 = clock.now().toEpochMilli()

            // nothing new: nothing sent
            uploader.cycle()
            assertEquals(1, uploads().size)

            clock.advance(Duration.ofMinutes(1))
            treatments.upsertIfNewer(treatment("new", 5, amount = 3.0, modified = clock.now()))
            uploader.cycle()
            val edit = uploads().last()
            assertTrue(
                "\"action\":\"deleted\",\"linkedGlucoseRecordNumber\":\"0\"},\"recordNumber\":${LibreViewPayload.recordNumber(
                    "new",
                    "rapid",
                    v1,
                )}" in
                    edit,
            )
            assertTrue("\"units\":3.0" in edit)

            val v2 = clock.now().toEpochMilli()
            clock.advance(Duration.ofMinutes(1))
            treatments.upsertIfNewer(treatment("new", 5, amount = 3.0, modified = clock.now(), deleted = true))
            uploader.cycle()
            val delete = uploads().last()
            assertTrue(
                "\"action\":\"deleted\",\"linkedGlucoseRecordNumber\":\"0\"},\"recordNumber\":${LibreViewPayload.recordNumber(
                    "new",
                    "rapid",
                    v2,
                )}" in
                    delete,
            )
            assertFalse(LibreViewPayload.recordNumber("new", "rapid", clock.now().toEpochMilli()).toString() in delete)
            assertEquals(
                setOf("new"),
                store.data
                    .first()
                    .ledger.keys,
            )
        }

    @Test
    fun `a token LibreView no longer accepts stops until signing in again`() =
        runBlocking {
            signedInAndOn()
            treatments.upsertIfNewer(treatment("a", 5))
            measurements = MockResponse().setBody("""{"status":20,"reason":"wrongDeviceInToken"}""")
            uploader.cycle()
            assertEquals(UploadStatus.AuthFailed, uploader.page.first().status)
            val n = requests.size
            uploader.cycle()
            assertEquals(n, requests.size)
            measurements = null
            uploader.signIn("me@example.com", "secret-pw", takeOver = false)
            uploader.cycle()
            assertTrue(uploader.page.first().status is UploadStatus.Ok)
        }

    @Test
    fun `server trouble backs off, a refusal waits for a change`() =
        runBlocking {
            signedInAndOn()
            treatments.upsertIfNewer(treatment("a", 5))
            measurements = MockResponse().setResponseCode(503)
            uploader.cycle()
            assertTrue(uploader.page.first().status is UploadStatus.Failing)
            val n = requests.size
            uploader.cycle()
            assertEquals("backoff: nothing sent", n, requests.size)

            clock.advance(Duration.ofMinutes(20))
            measurements = MockResponse().setBody("""{"status":5,"reason":"bad"}""")
            uploader.cycle()
            assertTrue(store.data.first().refused)
            val m = requests.size
            clock.advance(Duration.ofMinutes(20))
            uploader.cycle()
            assertEquals("refused: nothing sent until a change", m, requests.size)
        }

    @Test
    fun `glucose goes up only for the current sensor, from the moment it was switched on`() =
        runBlocking {
            signedInAndOn()
            val from = store.data.first().glucoseFrom
            clock.advance(Duration.ofMinutes(10))
            val t = clock.now().epochSecond
            sensor.value = CurrentSensor("3MH000ABCD", now - Duration.ofDays(2), now + Duration.ofDays(12))
            // another sensor's batch: never sent under this sensor
            uploader.buffer(LvReadings("OTHER00001", LvGlucose(2000, 99, t), emptyList()))
            uploader.cycle()
            assertEquals(0, uploads().size)

            uploader.buffer(
                LvReadings(
                    "3MH000ABCD",
                    LvGlucose(2880, 123, t, 0.2),
                    listOf(LvGlucose(2870, 118, from - 600), LvGlucose(2875, 120, t - 300)),
                ),
            )
            uploader.cycle()
            assertEquals(1, requests.count { it.path == "/api/nisperson" && it.method == "PUT" })
            val body = uploads().single()
            val h = LibreViewPayload.histor("3MH000ABCD")
            assertTrue("\"recordNumber\":${h or 2880L}," in body)
            assertTrue("\"recordNumber\":${h or 2875L}," in body)
            assertFalse("before the switch: not sent", "\"recordNumber\":${h or 2870L}," in body)
            assertTrue("sensorstart" in body)

            // sent once
            uploader.cycle()
            assertEquals(1, uploads().size)
        }

    @Test
    fun `switching off ends the windows and a follower never uploads`() =
        runBlocking {
            signedInAndOn()
            uploader.change { it.copy(on = false) }
            assertEquals(0L, store.data.first().glucoseFrom)
            assertEquals(0L, store.data.first().entriesFrom)
            uploader.change { it.copy(on = true) }
            role.value = Role.Follower
            treatments.upsertIfNewer(treatment("a", 5, modified = clock.now() + Duration.ofSeconds(1)))
            uploader.cycle()
            assertEquals(0, uploads().size)
            assertEquals(UploadStatus.Off, uploader.page.first().status)
        }

    @Test
    fun `sign out forgets the token`() =
        runBlocking {
            signedInAndOn()
            uploader.signOut()
            assertEquals(null, secure.read(LibreViewUploader.NAME))
            assertEquals(UploadStatus.Setup, uploader.page.first().status)
        }
}
