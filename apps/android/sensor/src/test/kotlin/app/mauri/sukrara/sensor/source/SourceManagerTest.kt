// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.source

import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.sensor.ble.GattConnection
import app.mauri.sukrara.sensor.ble.GattConnector
import app.mauri.sukrara.sensor.ble.GattEvent
import app.mauri.sukrara.sensor.juggluco.JugglucoSource
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import app.mauri.sukrara.sensor.libre3.Libre3Security
import app.mauri.sukrara.sensor.pairing.Pairing
import app.mauri.sukrara.sensor.pairing.PairingStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.time.ZoneOffset
import java.util.concurrent.Executors
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

class SourceManagerTest {
    private val start = Instant.parse("2026-10-20T08:00:00Z")
    private val pairing = Pairing("3MH0001", "AA:BB:CC:DD:EE:FF", start.epochSecond, 0, 21_600, 60, pin = ByteArray(4), kAuth = ByteArray(149))

    private class FakeConnector : GattConnector {
        override var isBluetoothOn = true
        val opened = ArrayList<Int>()
        val closed = ArrayList<Int>()

        override fun connect(address: String, generation: Int, onEvent: (GattEvent) -> Unit): GattConnection {
            opened += generation
            return object : GattConnection {
                override fun discoverServices() = true

                override fun enableNotifications(characteristic: UUID) = true

                override fun write(characteristic: UUID, value: ByteArray) = true

                override fun close() {
                    closed += generation
                }
            }
        }
    }

    private object NoSecurity : Libre3Security {
        override fun begin(kAuth: ByteArray?, securityVersion: Int) = 0L

        override fun setPatchCertificate(ctx: Long, cert140: ByteArray) = false

        override fun createEphemeralPublicKey(ctx: Long): ByteArray? = null

        override fun deriveAuthorizationRoot(ctx: Long, patchEphemeral65: ByteArray) = false

        override fun exportSavedAuthorization(ctx: Long): ByteArray? = null

        override fun encryptChallengeReply(ctx: Long, nonce7: ByteArray, plain36: ByteArray): ByteArray? = null

        override fun decryptChallengeResponse(ctx: Long, nonce7: ByteArray, cipher60: ByteArray): ByteArray? = null

        override fun free(ctx: Long) = Unit

        override fun ccmDecrypt(key16: ByteArray, nonce13: ByteArray, cipherAndTag: ByteArray): ByteArray? = null

        override fun ccmEncrypt(key16: ByteArray, nonce13: ByteArray, plain: ByteArray): ByteArray? = null
    }

    private inner class Rig(test: TestScope, security: Libre3Security? = NoSecurity) {
        val secure = InMemorySecureStore()
        val store = PairingStore(secure)
        val connector = FakeConnector()
        val kind = MutableStateFlow(SensorSourceKind.Libre3)
        val clock = FakeClock(start.plus(Duration.ofDays(2)))
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val manager = SourceManager(
            kind, store, security, connector,
            juggluco = { JugglucoSource(OkHttpClient(), "http://127.0.0.1:1") { clock.zone } },
            clock = clock, dispatcher = dispatcher, io = dispatcher,
        )
    }

    @Test
    fun noSensorYetIsNotMonitoringAndNeverInventsReadings() = runTest {
        val r = Rig(this)
        r.manager.start()
        runCurrent()
        assertEquals(SensorStatus.NoSensor, r.manager.detail.value)
        assertEquals(SensorState.None, r.manager.sensor.value)
        assertEquals(SourceStatus.NotStarted, r.manager.status.value)
        assertTrue(r.connector.opened.isEmpty())
        r.manager.stop()
        runCurrent()
    }

    @Test
    fun aBuildWithoutTheNativeCoreSaysSo() = runTest {
        val r = Rig(this, security = null)
        r.store.save(pairing)
        r.manager.start()
        runCurrent()
        assertEquals(SensorState.CoreMissing, r.manager.sensor.value)
        assertTrue(r.connector.opened.isEmpty())
        r.manager.stop()
        runCurrent()
    }

    @Test
    fun anUnreadablePairingAsksForAScan() = runTest {
        val r = Rig(this)
        r.secure.write(PairingStore.NAME, "garbage".encodeToByteArray())
        r.manager.start()
        runCurrent()
        assertEquals(SensorState.ActionNeeded(SensorAction.ScanSensor), r.manager.sensor.value)
        r.manager.stop()
        runCurrent()
    }

    @Test
    fun anEndedSensorIsNotConnected() = runTest {
        val r = Rig(this)
        r.store.save(pairing.copy(ended = true))
        r.manager.start()
        runCurrent()
        assertEquals(SensorState.Ended, r.manager.sensor.value)
        assertTrue(r.connector.opened.isEmpty())
        r.manager.stop()
        runCurrent()
    }

    @Test
    fun aRunningSensorConnectsAndTheWatchdogRestartsASilentLink() = runTest {
        val r = Rig(this)
        r.store.save(pairing)
        r.manager.start()
        runCurrent()
        assertEquals(listOf(1), r.connector.opened)
        assertEquals(SensorState.Active(Instant.ofEpochSecond(pairing.info.endEpoch)), r.manager.sensor.value)
        advanceTimeBy(SourceManager.WATCHDOG_MS - 120_000)
        r.clock.advance(Duration.ofMillis(SourceManager.WATCHDOG_MS - 120_000))
        assertEquals(listOf(1), r.connector.opened)
        r.clock.advance(Duration.ofMinutes(3))
        advanceTimeBy(3 * 60_000L)
        runCurrent()
        assertEquals(listOf(1), r.connector.closed) // the old connection is closed before the new one opens
        assertEquals(2, r.connector.opened.size)
        r.manager.stop()
        runCurrent()
        assertEquals(r.connector.opened.size, r.connector.closed.size)
    }

    @Test
    fun bluetoothOffAndBackOn() = runTest {
        val r = Rig(this)
        r.store.save(pairing)
        r.manager.start()
        runCurrent()
        r.connector.isBluetoothOn = false
        r.manager.onBluetoothOff()
        runCurrent()
        assertEquals(SensorState.BluetoothOff, r.manager.sensor.value)
        assertEquals(listOf(1), r.connector.closed)
        r.connector.isBluetoothOn = true
        r.manager.onBluetoothOn()
        runCurrent()
        assertEquals(2, r.connector.opened.size)
        assertTrue(r.manager.sensor.value is SensorState.Active)
        r.manager.stop()
        runCurrent()
    }

    @Test
    fun switchingToJugglucoClosesTheSensorLinkFirst() = runTest {
        val r = Rig(this)
        r.store.save(pairing)
        r.manager.start()
        runCurrent()
        r.kind.value = SensorSourceKind.Juggluco
        runCurrent()
        assertEquals(listOf(1), r.connector.closed)
        assertTrue(r.manager.detail.value is SensorStatus.Juggluco)
        assertTrue((r.manager.detail.value as SensorStatus.Juggluco).status !is JugglucoStatus.Live)
        r.manager.stop()
        runCurrent()
    }

    /** A switch away from Juggluco must not wait for its open live stream (it never ends by itself). Real threads. */
    @Test
    fun switchingAwayFromAnOpenJugglucoStreamIsImmediate(): Unit = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun respond(path: String, body: String, holdOpen: Boolean = false) = server.createContext(path) { ex ->
            val out = body.encodeToByteArray()
            ex.sendResponseHeaders(200, if (holdOpen) 0 else out.size.toLong())
            ex.responseBody.write(out)
            ex.responseBody.flush()
            if (holdOpen) runCatching { Thread.sleep(60_000) }
            ex.responseBody.close()
        }
        respond("/api/v1/entries.json", "[]")
        respond("/sgv.json", "[]")
        respond("/x/live", "event: glucose\ndata: {\"timestamp\":1760776860,\"glucoseMgDl\":121,\"sensorId\":1}\n\n", holdOpen = true)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        val kind = MutableStateFlow(SensorSourceKind.Juggluco)
        val manager = SourceManager(
            kind, PairingStore(InMemorySecureStore()), NoSecurity, FakeConnector(),
            juggluco = { JugglucoSource(OkHttpClient(), "http://127.0.0.1:${server.address.port}") { ZoneOffset.UTC } },
            clock = FakeClock(), dispatcher = Dispatchers.Default.limitedParallelism(1), io = Dispatchers.IO,
        )
        try {
            manager.start()
            withTimeout(10_000) { manager.status.first { it is SourceStatus.Live } }
            kind.value = SensorSourceKind.Libre3
            withTimeout(5_000) { manager.detail.first { it == SensorStatus.NoSensor } }
        } finally {
            manager.stop()
            server.stop(0)
        }
    }

    @Test
    fun everyProblemHasItsAction() {
        assertEquals(SensorAction.ScanSensor, SensorStatus.OwnedByAnotherApp.action)
        assertEquals(SensorAction.GrantBluetooth, SensorStatus.BluetoothPermissionMissing.action)
    }
}
