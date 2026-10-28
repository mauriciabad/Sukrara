// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

/**
 * Finds a sensor that pairing didn't give the address of (investigation 23, step 2: every family but the Libre 3):
 * short scans, **always filtered** by the driver's [SensorDriver.scan] (a service UUID or the exact name; Android
 * doesn't deliver unfiltered scans with the screen off, and filtering keeps the radio work small), [WINDOW_MS] on,
 * [PAUSE_MS] off, until [SensorDriver.matches] accepts a device. Each result first goes to
 * [SensorDriver.onAdvertisement] (Aidex X readings ride in it). The found address is saved, so later connections go
 * by address with `autoConnect` like the Libre 3's and the phone doesn't scan again for this sensor.
 *
 * Needs the Nearby devices permission (`BLUETOOTH_SCAN`, declared `neverForLocation`: no location permission).
 * The Libre 3 never gets here ([DirectSource.transport]).
 */
@SuppressLint("MissingPermission")
internal class SensorScanner(
    private val context: Context,
    private val driver: SensorDriver,
    private val events: SensorEvents,
    private val onFound: (Advert) -> Unit,
) {
    companion object {
        private const val TAG = "Sukrara.scan"
        const val WINDOW_MS = 30_000L
        const val PAUSE_MS = 60_000L
        const val SCANNING = "scanning for the sensor"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var scanning = false

    fun start() {
        stopped = false
        window()
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        stopScan()
    }

    private val next = Runnable { window() }
    private val endWindow = Runnable {
        stopScan()
        if (!stopped) handler.postDelayed(next, PAUSE_MS)
    }

    private fun window() {
        if (stopped) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            events.onStatus("waiting for Bluetooth", "Bluetooth is off")
            handler.postDelayed(next, PAUSE_MS)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            events.onStatus(BrandDriver.FAILED, "Sukrara needs the Nearby devices permission to find the sensor")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: return handler.postDelayed(next, PAUSE_MS).let {}
        val filters = driver.scan.map { spec ->
            ScanFilter.Builder().apply {
                spec.service?.let { setServiceUuid(ParcelUuid(it)) }
                spec.name?.let { setDeviceName(it) }
            }.build()
        }
        if (filters.isEmpty()) return events.onStatus(BrandDriver.FAILED, "${driver.profile.name}: nothing to scan for")
        events.onStatus(SCANNING)
        scanner.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        scanning = true
        handler.postDelayed(endWindow, WINDOW_MS)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            if (stopped) return
            val record = r.scanRecord
            val advert = Advert(r.device.address, record?.deviceName ?: r.device.name,
                record?.serviceUuids?.map { it.uuid } ?: emptyList(), bonded = r.device.bondState == BluetoothDevice.BOND_BONDED)
            record?.bytes?.let { driver.onAdvertisement(advert, it) }
            if (!driver.matches(advert)) return
            Log.i(TAG, "found ${advert.name}")
            stop()
            onFound(advert)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            handler.removeCallbacks(endWindow)
            if (!stopped) handler.postDelayed(next, PAUSE_MS)
        }
    }
}
