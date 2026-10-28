// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.source

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.BleScanner
import app.mauri.sukrara.sensor.ble.FailReason
import app.mauri.sukrara.sensor.ble.GattConnection
import app.mauri.sukrara.sensor.ble.GattConnector
import app.mauri.sukrara.sensor.ble.GattEvent
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.ScanHit
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.ScanSpec
import app.mauri.sukrara.sensor.dexcom.Dexcom
import app.mauri.sukrara.sensor.juggluco.JugglucoSource
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import app.mauri.sukrara.sensor.libre3.Libre3Security
import app.mauri.sukrara.sensor.pairing.Pairing
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.pairing.PairingStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors

class SourceManagerTest {
    private val start = Instant.parse("2026-10-20T08:00:00Z")
    private val pairing =
        Pairing("3MH0001", "AA:BB:CC:DD:EE:FF", start.epochSecond, 0, 21_600, 60, pin = ByteArray(4), kAuth = ByteArray(149))

    private class FakeConnector : GattConnector {
        override var isBluetoothOn = true
        val opened = ArrayList<Int>()
        val closed = ArrayList<Int>()
        val addresses = ArrayList<String>()
        var onEvent: (GattEvent) -> Unit = {}

        override fun connect(address: String, generation: Int, onEvent: (GattEvent) -> Unit): GattConnection {
            opened += generation
            addresses += address
            this.onEvent = onEvent
            return object : GattConnection {
                override val isBonded = true

                override fun enableIndications(characteristic: UUID) = true

                override fun discoverServices() = true

                override fun enableNotifications(characteristic: UUID) = true

                override fun write(characteristic: UUID, value: ByteArray) = true

                override fun close() {
                    closed += generation
                }
            }
        }
    }

    private class FakeScanner : BleScanner {
        override var isBluetoothOn = true
        var onHit: (ScanHit) -> Unit = {}
        var scans = 0

        override fun start(filters: List<ScanSpec>, onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): Boolean {
            scans++
            this.onHit = onHit
            return true
        }

        override fun stop() = Unit
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
        val scanner = FakeScanner()
        val kind = MutableStateFlow(SensorSourceKind.Direct)
        val clock = FakeClock(start.plus(Duration.ofDays(2)))
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        val manager =
            SourceManager(
                kind,
                store,
                security,
                connector,
                scanner,
                juggluco = { JugglucoSource(OkHttpClient(), "http://127.0.0.1:1") { clock.zone } },
                clock = clock,
                dispatcher = dispatcher,
                io = dispatcher,
            )
    }

    /**
     * A scanned family end to end (Dexcom G7 read along with the Dexcom app): scan → the bonded sensor → its address
     * saved → connected → the Dexcom app's session → one live value with the sensor's start. The same value again after
     * a restart is history, never a second live value.
     */
    @Test
    fun aScannedSensorIsFoundConnectedAndReadOnce() =
        runTest {
            val r = Rig(this)
            val g7 = Pairing.scanned(SensorProfile.DexcomG7, "", pairedAtMs = 1L)
            r.store.save(g7)
            val received = ArrayList<Reading>()
            backgroundScope.launch { r.manager.readings.collect { received += it } }
            r.manager.start()
            runCurrent()
            assertEquals(LinkStatus.Scanning, (r.manager.detail.value as SensorStatus.Direct).link)
            r.scanner.onHit(ScanHit(Advert("aa:bb:cc:dd:ee:01", "DXCM4A", bonded = false), null)) // not this phone's
            r.scanner.onHit(ScanHit(Advert("aa:bb:cc:dd:ee:02", "DXCM4B", bonded = true), null))
            runCurrent()
            assertEquals(listOf("aa:bb:cc:dd:ee:02"), r.connector.addresses)
            assertEquals("AA:BB:CC:DD:EE:02", (r.store.load() as PairingLoad.Ok).pairing.address)
            val now = r.clock.now().epochSecond

            fun g7Session() {
                r.connector.onEvent(GattEvent.ConnectionChanged(r.connector.opened.last(), true, 0))
                runCurrent()
                r.connector.onEvent(
                    GattEvent.ServicesDiscovered(
                        r.connector.opened.last(),
                        0,
                        setOf(Dexcom.AUTHENTICATION, Dexcom.CONTROL, Dexcom.BACKFILL),
                    ),
                )
                runCurrent()
                r.connector.onEvent(GattEvent.OperationDone(r.connector.opened.last(), Dexcom.AUTHENTICATION, 0))
                r.connector.onEvent(GattEvent.Notification(r.connector.opened.last(), Dexcom.AUTHENTICATION, byteArrayOf(5, 1, 1)))
                runCurrent()
                r.connector.onEvent(GattEvent.OperationDone(r.connector.opened.last(), Dexcom.CONTROL, 0))
                r.connector.onEvent(GattEvent.OperationDone(r.connector.opened.last(), Dexcom.BACKFILL, 0))
                r.connector.onEvent(GattEvent.Notification(r.connector.opened.last(), Dexcom.CONTROL, EGV_97))
                runCurrent()
            }
            g7Session()
            assertEquals(listOf(97), received.map { it.mgdl })
            assertEquals(SourceId.DexcomG7, received.single().source)
            assertEquals(now - 5, received.single().t.epochSecond) // measured 5 s before it was sent
            assertTrue(r.manager.status.value is SourceStatus.Live)
            assertEquals(
                now - 2005,
                r.manager.sensorInfo.value
                    ?.startEpoch,
            )
            assertEquals(now - 2005, (r.store.load() as PairingLoad.Ok).pairing.startEpoch)
            assertEquals(
                "DXCM4B",
                r.manager.sensorInfo.value
                    ?.serial,
            )

            r.manager.retry() // a restart: the next driver connects straight to the saved address, no scan
            runCurrent()
            assertEquals(1, r.scanner.scans)
            assertEquals("AA:BB:CC:DD:EE:02", r.connector.addresses.last())
            g7Session()
            assertEquals(2, received.size) // delivered again, but as history
            assertEquals(received[0].t, received[1].t)
            r.manager.stop()
            runCurrent()
        }

    /**
     * An old G7 still bonded (past its 10 days: ended or in its grace period) found first: it is never adopted, its
     * address is kept out of every later scan, and the scan goes on to the new sensor.
     */
    @Test
    fun anOldBondedG7IsSkippedAndTheScanGoesOn() =
        runTest {
            val r = Rig(this)
            r.store.saveScanned(Pairing.scanned(SensorProfile.DexcomG7, "", pairedAtMs = 1L))
            r.manager.start()
            runCurrent()
            r.scanner.onHit(ScanHit(Advert("aa:bb:cc:dd:ee:01", "DXCMOLD", bonded = true), null))
            runCurrent()
            assertEquals(listOf("aa:bb:cc:dd:ee:01"), r.connector.addresses)
            val gen = r.connector.opened.last()
            r.connector.onEvent(GattEvent.ConnectionChanged(gen, true, 0))
            runCurrent()
            r.connector.onEvent(GattEvent.ServicesDiscovered(gen, 0, setOf(Dexcom.AUTHENTICATION, Dexcom.CONTROL, Dexcom.BACKFILL)))
            runCurrent()
            r.connector.onEvent(GattEvent.OperationDone(gen, Dexcom.AUTHENTICATION, 0))
            r.connector.onEvent(GattEvent.Notification(gen, Dexcom.AUTHENTICATION, byteArrayOf(5, 1, 1)))
            runCurrent()
            r.connector.onEvent(GattEvent.OperationDone(gen, Dexcom.CONTROL, 0))
            r.connector.onEvent(GattEvent.OperationDone(gen, Dexcom.BACKFILL, 0))
            r.connector.onEvent(GattEvent.Notification(gen, Dexcom.CONTROL, EGV_OLD))
            runCurrent()
            val saved = (r.store.load() as PairingLoad.Ok).pairing
            assertEquals(listOf("AA:BB:CC:DD:EE:01"), saved.skipAddresses)
            assertEquals("", saved.address)
            assertTrue(!saved.ended)
            assertEquals(2, r.scanner.scans) // scanning again
            r.scanner.onHit(ScanHit(Advert("aa:bb:cc:dd:ee:01", "DXCMOLD", bonded = true), null))
            r.scanner.onHit(ScanHit(Advert("aa:bb:cc:dd:ee:02", "DXCMNEW", bonded = true), null))
            runCurrent()
            assertEquals("aa:bb:cc:dd:ee:02", r.connector.addresses.last())
            assertEquals(2, r.connector.addresses.size)
            r.manager.stop()
            runCurrent()
        }

    /** A failure only the person can fix survives a restart: no scan, no connection, no PIN dialog in the background. */
    @Test
    fun aFailedPairingIsNotRetriedAfterARestart() =
        runTest {
            val r = Rig(this)
            r.store.save(Pairing.scanned(SensorProfile.SmartGuide, "", pairedAtMs = 1L).copy(failed = FailReason.BondFailed))
            r.manager.start()
            runCurrent()
            assertEquals(LinkStatus.Failed(FailReason.BondFailed), (r.manager.detail.value as SensorStatus.Direct).link)
            assertEquals(SensorState.ActionNeeded(SensorAction.ScanSensor), r.manager.sensor.value)
            assertEquals(0, r.scanner.scans)
            assertTrue(r.connector.opened.isEmpty())
            r.manager.retry()
            runCurrent()
            assertEquals(0, r.scanner.scans)
            // a new pairing (the new-sensor flow) clears it and scans
            r.store.saveScanned(Pairing.scanned(SensorProfile.SmartGuide, "", pairedAtMs = 2L))
            r.manager.retry()
            runCurrent()
            assertEquals(1, r.scanner.scans)
            r.manager.stop()
            runCurrent()
        }

    @Test
    fun noSensorYetIsNotMonitoringAndNeverInventsReadings() =
        runTest {
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
    fun aBuildWithoutTheNativeCoreSaysSo() =
        runTest {
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
    fun anUnreadablePairingAsksForAScan() =
        runTest {
            val r = Rig(this)
            r.secure.write(PairingStore.NAME, "garbage".encodeToByteArray())
            r.manager.start()
            runCurrent()
            assertEquals(SensorState.ActionNeeded(SensorAction.ScanSensor), r.manager.sensor.value)
            r.manager.stop()
            runCurrent()
        }

    @Test
    fun anEndedSensorIsNotConnected() =
        runTest {
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
    fun aRunningSensorConnectsAndTheWatchdogRestartsASilentLink() =
        runTest {
            val r = Rig(this)
            r.store.save(pairing)
            r.manager.start()
            runCurrent()
            assertEquals(listOf(1), r.connector.opened)
            assertEquals(SensorState.Active(Instant.ofEpochSecond(pairing.info!!.endEpoch)), r.manager.sensor.value)
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
    fun bluetoothOffAndBackOn() =
        runTest {
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
    fun switchingToJugglucoClosesTheSensorLinkFirst() =
        runTest {
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
    fun switchingAwayFromAnOpenJugglucoStreamIsImmediate(): Unit =
        runBlocking {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

            fun respond(path: String, body: String, holdOpen: Boolean = false) =
                server.createContext(path) { ex ->
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
            val manager =
                SourceManager(
                    kind,
                    PairingStore(InMemorySecureStore()),
                    NoSecurity,
                    FakeConnector(),
                    FakeScanner(),
                    juggluco = { JugglucoSource(OkHttpClient(), "http://127.0.0.1:${server.address.port}") { ZoneOffset.UTC } },
                    clock = FakeClock(),
                    dispatcher = Dispatchers.Default.limitedParallelism(1),
                    io = Dispatchers.IO,
                )
            try {
                manager.start()
                withTimeout(10_000) { manager.status.first { it is SourceStatus.Live } }
                kind.value = SensorSourceKind.Direct
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

    private companion object {
        /** LoopKit G7SensorKit's test EGV: sensor time 2005 s, sequence 9, age 5 s, 97 mg/dL, state ok. */
        val EGV_97 = "4e00d507000009000001050061000601ffff0e".hexToByteArray()

        /** The same message from a sensor 900 000 s (10.4 days) into its session: an old one. */
        val EGV_OLD = "4e00a0bb0d0009000001050061000601ffff0e".hexToByteArray()
    }
}
