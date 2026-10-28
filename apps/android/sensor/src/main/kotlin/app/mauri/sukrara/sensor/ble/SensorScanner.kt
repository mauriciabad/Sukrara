// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandDriver
import app.mauri.sukrara.sensor.brand.ScanSpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One scan result: what the device advertises, and the raw advertising data. */
internal class ScanHit(val advert: Advert, val record: ByteArray?)

/** What [SensorScanner] needs from Android's Bluetooth LE scanner, so its windows and recovery run on the JVM. */
internal interface BleScanner {
    val isBluetoothOn: Boolean

    /**
     * Start a filtered scan; results and failures arrive on any thread. False = the stack refused. Throws
     * `SecurityException` without the Nearby devices permission (BLUETOOTH_SCAN).
     */
    fun start(
        filters: List<ScanSpec>,
        onHit: (ScanHit) -> Unit,
        onFailed: (Int) -> Unit,
    ): Boolean

    fun stop()
}

/**
 * Finds a sensor whose address pairing didn't give (every family but the Libre 3): scans of [WINDOW_MS], always
 * filtered by the driver's [BrandDriver.scan] (Android delivers no unfiltered scans with the screen off, and filters keep
 * the radio work small), [PAUSE_MS] apart, until [BrandDriver.matches] accepts a device. Each result first goes to
 * [BrandDriver.onAdvertisement]. After [NOT_FOUND_WINDOWS] windows without the sensor the status says so (check the id,
 * bring the phone closer) and the scans go on.
 *
 * Runs in the caller's coroutine (the source's serial dispatcher); cancelling it stops the scan.
 */
internal class SensorScanner(
    private val scanner: BleScanner,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val WINDOW_MS = 30_000L
        const val PAUSE_MS = 60_000L
        const val NOT_FOUND_WINDOWS = 5
    }

    /** The matching device, or null when scanning can't go on (Bluetooth off, permission missing): the status says why. */
    suspend fun find(
        driver: BrandDriver,
        events: SensorEvents,
    ): Advert? {
        var windows = 0
        while (true) {
            if (!scanner.isBluetoothOn) {
                events.onStatus(LinkStatus.WaitingForBluetooth)
                return null
            }
            val hits = Channel<ScanHit>(Channel.UNLIMITED)
            val started =
                try {
                    scanner.start(driver.scan, { hits.trySend(it) }, { code ->
                        log("scan failed: $code")
                        hits.close()
                    })
                } catch (_: SecurityException) {
                    events.onStatus(LinkStatus.PermissionMissing)
                    return null
                }
            events.onStatus(if (windows >= NOT_FOUND_WINDOWS) LinkStatus.SensorNotFound else LinkStatus.Scanning)
            val found =
                try {
                    if (!started) {
                        null
                    } else {
                        withTimeoutOrNull(WINDOW_MS) {
                            var match: Advert? = null
                            for (h in hits) {
                                h.record?.let { driver.onAdvertisement(h.advert, it) }
                                if (driver.accepts(h.advert)) {
                                    match = h.advert
                                    break
                                }
                            }
                            match
                        }
                    }
                } finally {
                    runCatching { scanner.stop() }
                    hits.close()
                }
            if (found != null) return found
            windows++
            delay(PAUSE_MS)
        }
    }
}

/** [BleScanner] over Android's `BluetoothLeScanner` (device-tested only: no emulator Bluetooth). */
@SuppressLint("MissingPermission") // a missing BLUETOOTH_SCAN throws SecurityException, handled by SensorScanner
internal class AndroidScanner(private val context: Context) : BleScanner {
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    override val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    override fun start(
        filters: List<ScanSpec>,
        onHit: (ScanHit) -> Unit,
        onFailed: (Int) -> Unit,
    ): Boolean {
        if (filters.isEmpty()) return false // never an unfiltered scan
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val cb =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    r: ScanResult,
                ) {
                    val record = r.scanRecord
                    val advert =
                        Advert(
                            r.device.address,
                            record?.deviceName ?: r.device.name,
                            record?.serviceUuids?.map { it.uuid }.orEmpty(),
                            bonded = r.device.bondState == BluetoothDevice.BOND_BONDED,
                        )
                    onHit(ScanHit(advert, record?.bytes))
                }

                override fun onScanFailed(errorCode: Int) = onFailed(errorCode)
            }
        val androidFilters =
            filters.map { spec ->
                ScanFilter
                    .Builder()
                    .apply {
                        spec.service?.let { setServiceUuid(ParcelUuid(it)) }
                        spec.name?.let { setDeviceName(it) }
                    }.build()
            }
        scanner.startScan(androidFilters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        callback = cb
        return true
    }

    override fun stop() {
        val cb = callback ?: return
        callback = null
        adapter?.bluetoothLeScanner?.stopScan(cb)
    }
}
