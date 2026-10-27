// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import app.mauri.sukrara.core.model.Reading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The link's queue, timeouts and recovery against a fake Bluetooth stack (virtual time). */
class GattLinkTest {
    private val chA = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
    private val chB = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")

    private class FakeConnection(val generation: Int, val log: MutableList<String>) : GattConnection {
        var closed = false

        override fun discoverServices(): Boolean {
            log += "discover#$generation"
            return true
        }

        override fun enableNotifications(characteristic: UUID): Boolean {
            log += "sub#$generation ${characteristic.toString().take(8)}"
            return true
        }

        override fun write(characteristic: UUID, value: ByteArray): Boolean {
            log += "write#$generation ${characteristic.toString().take(8)}"
            return true
        }

        override fun close() {
            closed = true
            log += "close#$generation"
        }
    }

    private class FakeConnector : GattConnector {
        override var isBluetoothOn = true
        var permissionRevoked = false
        var stackFails = 0
        val log = ArrayList<String>()
        val connections = ArrayList<FakeConnection>()
        val callbacks = ArrayList<(GattEvent) -> Unit>()

        override fun connect(address: String, generation: Int, onEvent: (GattEvent) -> Unit): GattConnection {
            if (permissionRevoked) throw SecurityException("BLUETOOTH_CONNECT")
            if (stackFails > 0) {
                stackFails--
                throw IllegalStateException("connectGatt returned null")
            }
            log += "connect#$generation"
            callbacks += onEvent
            return FakeConnection(generation, log).also { connections += it }
        }

        val current get() = connections.last()

        fun emit(e: GattEvent) = callbacks.last()(e)

        fun take(): List<String> = ArrayList(log).also { log.clear() }
    }

    private class FakeDriver : SensorDriver {
        val calls = ArrayList<String>()
        var link: BleLink? = null
        var onConnectedAction: (BleLink) -> Unit = {}

        override fun onConnected(link: BleLink, characteristics: Set<UUID>, status: Int) {
            this.link = link
            calls += "connected"
            onConnectedAction(link)
        }

        override fun onNotification(characteristic: UUID, value: ByteArray) {
            calls += "notify ${value.size}"
        }

        override fun onLinkFailed(why: String) {
            calls += "failed $why"
            link?.disconnect() // what Libre3Driver.fail does
        }

        override fun onDisconnected() {
            calls += "disconnected"
        }

        override fun onClosed() {
            calls += "closed"
        }
    }

    private class Statuses : SensorEvents {
        val all = ArrayList<LinkStatus>()

        override fun onStatus(status: LinkStatus) {
            all += status
        }

        override fun onReading(reading: Reading) = Unit

        override fun onHistory(readings: List<Reading>) = Unit
    }

    private inner class Rig(val test: TestScope) {
        val connector = FakeConnector()
        val driver = FakeDriver()
        val statuses = Statuses()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler))
        val link = GattLink(connector, "AA:BB:CC:DD:EE:FF", driver, statuses, scope)

        fun connectAndDiscover() {
            val gen = connector.current.generation
            connector.emit(GattEvent.ConnectionChanged(gen, connected = true, status = 0))
            test.runCurrent()
            connector.emit(GattEvent.ServicesDiscovered(gen, 0, setOf(chA, chB)))
            test.runCurrent()
        }
    }

    @Test
    fun operationsRunOneAtATime() = runTest {
        val r = Rig(this)
        r.driver.onConnectedAction = { it.subscribe(chA); it.write(chB, byteArrayOf(1)) }
        r.link.start()
        r.connectAndDiscover()
        assertEquals(listOf("connect#1", "discover#1", "sub#1 00000001"), r.connector.take())
        r.connector.emit(GattEvent.OperationDone(1, chA, 0))
        runCurrent()
        assertEquals(listOf("write#1 00000002"), r.connector.take())
        r.scope.cancel()
    }

    @Test
    fun anOperationThatNeverCompletesTimesOutAndANewConnectionIsMade() = runTest {
        val r = Rig(this)
        r.driver.onConnectedAction = { it.subscribe(chA) }
        r.link.start()
        r.connectAndDiscover()
        r.connector.take()
        advanceTimeBy(GattLink.OP_TIMEOUT_MS + 1)
        assertTrue(r.driver.calls.any { it.startsWith("failed") && it.endsWith("timed out") })
        assertEquals(listOf("close#1"), r.connector.take()) // closed at once, no waiting for a callback
        assertEquals(LinkStatus.WaitingForSensor, r.statuses.all.last())
        advanceTimeBy(GattLink.FIRST_BACKOFF_MS + 1)
        assertEquals(listOf("connect#3"), r.connector.take()) // a NEW connection (never connect() on the stale one)
        r.scope.cancel()
    }

    @Test
    fun serviceDiscoveryTimesOut() = runTest {
        val r = Rig(this)
        r.link.start()
        r.connector.emit(GattEvent.ConnectionChanged(1, connected = true, status = 0))
        runCurrent()
        advanceTimeBy(GattLink.OP_TIMEOUT_MS + 1)
        assertEquals(listOf("connect#1", "discover#1", "close#1"), r.connector.take())
        r.scope.cancel()
    }

    @Test
    fun backoffDoublesAndResetsWhenTheSensorTalks() = runTest {
        val r = Rig(this)
        r.link.start()
        runCurrent()
        val waits = ArrayList<Long>()
        repeat(4) {
            val gen = r.connector.current.generation
            r.connector.emit(GattEvent.ConnectionChanged(gen, connected = false, status = 133))
            runCurrent()
            val before = currentTime
            while (r.connector.connections.last().generation == gen) advanceTimeBy(100)
            waits += currentTime - before
        }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), waits.map { (it + 99) / 1000 * 1000 })
        r.connectAndDiscover()
        r.connector.emit(GattEvent.Notification(r.connector.current.generation, chA, byteArrayOf(1)))
        runCurrent()
        val gen = r.connector.current.generation
        r.connector.emit(GattEvent.ConnectionChanged(gen, connected = false, status = 8))
        runCurrent()
        advanceTimeBy(GattLink.FIRST_BACKOFF_MS + 1)
        assertTrue(r.connector.current.generation > gen)
        r.scope.cancel()
    }

    /** Bluetooth on is the source's job: it starts a new link + driver (SourceManagerTest.bluetoothOffAndBackOn). */
    @Test
    fun bluetoothOffClosesWithoutRetries() = runTest {
        val r = Rig(this)
        r.link.start()
        r.connectAndDiscover()
        r.connector.take()
        r.connector.isBluetoothOn = false
        r.link.bluetoothOff()
        assertEquals(listOf("close#1"), r.connector.take())
        assertEquals(LinkStatus.WaitingForBluetooth, r.statuses.all.last())
        assertEquals("disconnected", r.driver.calls.last())
        advanceTimeBy(5 * 60_000L)
        assertEquals(emptyList<String>(), r.connector.take()) // no retries while off
        r.scope.cancel()
    }

    @Test
    fun bluetoothOffCancelsAPendingReconnect() = runTest {
        val r = Rig(this)
        r.link.start()
        r.connectAndDiscover()
        r.link.disconnect() // a reconnect is now armed
        r.connector.take()
        r.link.bluetoothOff()
        advanceTimeBy(5 * 60_000L)
        assertEquals(emptyList<String>(), r.connector.take())
        r.scope.cancel()
    }

    @Test
    fun bluetoothAlreadyOffAtStartWaits() = runTest {
        val r = Rig(this)
        r.connector.isBluetoothOn = false
        r.link.start()
        advanceTimeBy(5 * 60_000L)
        assertEquals(emptyList<String>(), r.connector.take())
        assertEquals(LinkStatus.WaitingForBluetooth, r.statuses.all.last())
        r.scope.cancel()
    }

    /** The stack throwing (adapter gone, Bluetooth turning off mid-call) is a dropped link, never a crash. */
    @Test
    fun aFailingStackRetriesWithBackoff() = runTest {
        val r = Rig(this)
        r.connector.stackFails = 2
        r.link.start()
        assertEquals(LinkStatus.WaitingForSensor, r.statuses.all.last())
        advanceTimeBy(GattLink.FIRST_BACKOFF_MS + 1)
        assertEquals(emptyList<String>(), r.connector.take()) // second failure
        advanceTimeBy(2 * GattLink.FIRST_BACKOFF_MS + 1)
        assertEquals(listOf("connect#3"), r.connector.take())
        r.scope.cancel()
    }

    @Test
    fun lateCallbacksOfAClosedConnectionAreIgnored() = runTest {
        val r = Rig(this)
        r.link.start()
        r.connectAndDiscover()
        val old = r.connector.callbacks.last()
        r.link.disconnect()
        advanceTimeBy(GattLink.FIRST_BACKOFF_MS + 1)
        r.driver.calls.clear()
        old(GattEvent.Notification(1, chA, byteArrayOf(1, 2, 3)))
        old(GattEvent.ServicesDiscovered(1, 0, setOf(chA)))
        runCurrent()
        assertEquals(emptyList<String>(), r.driver.calls)
        r.scope.cancel()
    }

    @Test
    fun aRevokedPermissionStopsTheLinkWithAState() = runTest {
        val r = Rig(this)
        r.connector.permissionRevoked = true
        r.link.start()
        assertEquals(LinkStatus.PermissionMissing, r.statuses.all.last())
        advanceTimeBy(5 * 60_000L)
        assertEquals(emptyList<String>(), r.connector.take())
        r.scope.cancel()
    }

    @Test
    fun closeStopsEverything() = runTest {
        val r = Rig(this)
        var fired = false
        r.link.start()
        r.connectAndDiscover()
        r.link.schedule(1_000) { fired = true }
        r.link.close()
        assertEquals("closed", r.driver.calls.last())
        advanceTimeBy(10 * 60_000L)
        assertEquals(false, fired)
        assertEquals(listOf("connect#1", "discover#1", "close#1"), r.connector.take())
        r.scope.cancel()
    }

    @Test
    fun aFailedOperationStatusTellsTheDriver() = runTest {
        val r = Rig(this)
        r.driver.onConnectedAction = { it.subscribe(chA) }
        r.link.start()
        r.connectAndDiscover()
        r.connector.emit(GattEvent.OperationDone(1, chA, 133))
        runCurrent()
        assertTrue(r.driver.calls.any { it.startsWith("failed operation on") && it.endsWith("133") })
        r.scope.cancel()
    }
}
