// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.feature.sensor.libreview.LibreViewPort
import app.mauri.sukrara.feature.sensor.libreview.LibreViewSignInViewModel
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorPort
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorViewModel
import app.mauri.sukrara.feature.sensor.newsensor.SensorEnv
import app.mauri.sukrara.sensor.SensorModule
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.libreview.SignInResult
import app.mauri.sukrara.sensor.pairing.PairOutcome
import app.mauri.sukrara.sensor.pairing.Pairing
import app.mauri.sukrara.sensor.source.SensorSourceKind
import app.mauri.sukrara.sensor.source.SensorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * The sensor screens' wiring (DI: `AppGraph` composes it with one line from the [SensorModule]). Implements
 * the flows' ports over the sensor source, the NFC pairing and the LibreView account, and reads the phone's state
 * (NFC, Bluetooth, permissions, installed apps) fresh at every gate. [appScope] outlives screens: an NFC start runs
 * to the end there even if the person leaves.
 */
class SensorUiModule(
    context: Context,
    private val sensors: SensorModule,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val appScope: CoroutineScope,
    /** Starts the monitoring service (the app's start path); called in the foreground, right after a tap. */
    private val startMonitoring: suspend () -> Unit,
) {
    private val app = context.applicationContext

    val newSensor: NewSensorPort =
        object : NewSensorPort {
            override suspend fun env(): SensorEnv {
                val nfc = NfcAdapter.getDefaultAdapter(app)
                return SensorEnv(
                    coreAvailable = sensors.hasLibre3Core,
                    nfcAvailable = nfc != null,
                    nfcOn = nfc?.isEnabled == true,
                    signedIn = sensors.libreView.email.first() != null,
                    officialAppInstalled = OfficialApps.anyInstalled(app.packageManager),
                    bluetoothPermission = granted(Manifest.permission.BLUETOOTH_CONNECT),
                    bluetoothOn = app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true,
                    notificationsAllowed = NotificationManagerCompat.from(app).areNotificationsEnabled(),
                    scanPermission = granted(Manifest.permission.BLUETOOTH_SCAN),
                )
            }

            override suspend fun pair(tag: Tag): PairOutcome = sensors.nfc.pair(tag)

            override suspend fun pairScanned(
                profile: SensorProfile,
                id: String,
            ): String {
                val pairing = Pairing.scanned(profile, id, clock.now().toEpochMilli())
                sensors.pairings.saveScanned(pairing)
                settings.set(SOURCE, SensorSourceKind.Direct.id)
                sensors.source.retry() // the source starts over with the new pairing (scan, then connect)
                startMonitoring()
                return SensorStatus.Direct.keyOf(pairing)
            }

            override val detail: StateFlow<SensorStatus> get() = sensors.source.detail
            override val sensor: StateFlow<SensorState> get() = sensors.source.sensor

            override fun retryLink() = sensors.source.retry()

            override suspend fun useJuggluco() {
                settings.set(SOURCE, SensorSourceKind.Juggluco.id)
                startMonitoring() // Juggluco is a source to monitor even with no sensor paired here
            }

            override suspend fun onPaired() {
                settings.set(SOURCE, SensorSourceKind.Direct.id)
                startMonitoring()
            }
        }

    val libreView: LibreViewPort =
        object : LibreViewPort {
            override val email: Flow<String?> get() = sensors.libreView.email

            override suspend fun signIn(email: String, password: String): SignInResult = sensors.libreView.signIn(email, password)

            override suspend fun signOut() = sensors.libreView.signOut()
        }

    /** For `viewModel(factory = …)` in the app's navigation entries. */
    val newSensorViewModels: ViewModelProvider.Factory =
        viewModelFactory {
            initializer { NewSensorViewModel(newSensor, clock, appScope) }
        }

    val libreViewViewModels: ViewModelProvider.Factory =
        viewModelFactory {
            initializer { LibreViewSignInViewModel(libreView) }
        }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /** The glucose source setting (schema `source.source`); the source manager follows it. */
        val SOURCE = SchemaSettings.text("source.source", SensorSourceKind.Direct.id, SensorSourceKind.entries.map { it.id }.toSet())
    }
}
