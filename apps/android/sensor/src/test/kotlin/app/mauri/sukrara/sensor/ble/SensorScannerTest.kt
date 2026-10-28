// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandEvents
import app.mauri.sukrara.sensor.brand.ScanSpec
import app.mauri.sukrara.sensor.dexcom.G6Driver
import app.mauri.sukrara.sensor.pairing.Pairing
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scan windows, the filters, giving up on Bluetooth off / no permission, and "not found" after a while. */
class SensorScannerTest {
    private class FakeScanner : BleScanner {
        override var isBluetoothOn = true
        var denied = false
        var onHit: (ScanHit) -> Unit = {}
        val starts = ArrayList<List<ScanSpec>>()
        var stops = 0

        override fun start(filters: List<ScanSpec>, onHit: (ScanHit) -> Unit, onFailed: (Int) -> Unit): Boolean {
            if (denied) throw SecurityException("BLUETOOTH_SCAN")
            starts += filters
            this.onHit = onHit
            return true
        }

        override fun stop() {
            stops++
        }
    }

    private val g6 = G6Driver(Pairing.scanned(SensorProfile.DexcomG6, "8GABCD", 1L), BrandEvents())

    @Test
    fun scansFilteredWindowsUntilTheSensorShowsUp() =
        runTest {
            val scanner = FakeScanner()
            val events = BrandEvents()
            val found = async { SensorScanner(scanner).find(g6, events) }
            runCurrent()
            assertEquals(listOf(listOf(ScanSpec(name = "DexcomCD"))), scanner.starts)
            assertEquals(LinkStatus.Scanning, events.last())
            advanceTimeBy(SensorScanner.WINDOW_MS + 1)
            assertEquals(1, scanner.stops) // the window closed: a pause, no scan
            advanceTimeBy(SensorScanner.PAUSE_MS)
            runCurrent()
            assertEquals(2, scanner.starts.size)
            scanner.onHit(ScanHit(Advert("AA:01", "DexcomXY"), null)) // passes nothing
            scanner.onHit(ScanHit(Advert("AA:02", "DexcomCD"), null))
            runCurrent()
            assertEquals("AA:02", found.await()?.address)
            assertEquals(2, scanner.stops)
        }

    @Test
    fun saysNotFoundAfterAWhileAndKeepsScanning() =
        runTest {
            val scanner = FakeScanner()
            val events = BrandEvents()
            val found = async { SensorScanner(scanner).find(g6, events) }
            repeat(SensorScanner.NOT_FOUND_WINDOWS) { advanceTimeBy(SensorScanner.WINDOW_MS + SensorScanner.PAUSE_MS) }
            runCurrent()
            assertEquals(LinkStatus.SensorNotFound, events.last())
            scanner.onHit(ScanHit(Advert("AA:02", "DexcomCD"), null))
            runCurrent()
            assertEquals("AA:02", found.await()?.address)
        }

    @Test
    fun bluetoothOffOrNoPermissionStopsWithAState() =
        runTest {
            val off = FakeScanner().apply { isBluetoothOn = false }
            val events = BrandEvents()
            assertNull(SensorScanner(off).find(g6, events))
            assertEquals(LinkStatus.WaitingForBluetooth, events.last())

            val denied = FakeScanner().apply { this.denied = true }
            assertNull(SensorScanner(denied).find(g6, events))
            assertEquals(LinkStatus.PermissionMissing, events.last())
            assertTrue(denied.starts.isEmpty())
        }
}
