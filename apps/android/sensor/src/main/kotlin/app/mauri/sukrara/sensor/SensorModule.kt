// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor

import android.content.Context
import android.util.Log
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.sensor.ble.AndroidGatt
import app.mauri.sukrara.sensor.ble.AndroidScanner
import app.mauri.sukrara.sensor.juggluco.JugglucoSource
import app.mauri.sukrara.sensor.libre3.Libre3Crypto
import app.mauri.sukrara.sensor.libreview.LibreViewAccount
import app.mauri.sukrara.sensor.pairing.NfcPairing
import app.mauri.sukrara.sensor.pairing.PairingStore
import app.mauri.sukrara.sensor.pairing.ReceiverIdState
import app.mauri.sukrara.sensor.source.SensorSourceKind
import app.mauri.sukrara.sensor.source.SourceManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient

/**
 * The sensor's object graph (DI: AppGraph composes this; nothing here edits it). [sourceKind] is the `source.source`
 * setting; [receiverIds] is a typed DataStore (`receiver-ids.json`); [secure] holds the pairing (`sensor`).
 * The wearer role starts [source] (`SourceManager.start`); the hub reads it as its one [app.mauri.sukrara.core.model.ReadingSource].
 */
class SensorModule(
    context: Context,
    secure: SecureStore,
    receiverIds: StateStore<ReceiverIdState>,
    sourceKind: Flow<SensorSourceKind>,
    http: OkHttpClient,
    clock: Clock,
    dispatchers: DispatcherProvider,
) {
    companion object {
        private const val TAG = "Sukrara.sensor"
    }

    private val app = context.applicationContext
    private val log: (String) -> Unit = { Log.i(TAG, it) }

    val pairings = PairingStore(secure)

    /** Whether this build can read a Libre 3 directly (release builds always can; verifyNativeCore). */
    val hasLibre3Core: Boolean get() = Libre3Crypto.isAvailable

    @OptIn(ExperimentalCoroutinesApi::class)
    val source =
        SourceManager(
            kind = sourceKind,
            pairings = pairings,
            security = Libre3Crypto.takeIf { it.isAvailable },
            connector = AndroidGatt(app),
            scanner = AndroidScanner(app),
            juggluco = { JugglucoSource(http, zone = { clock.zone }) },
            clock = clock,
            dispatcher = dispatchers.default.limitedParallelism(1),
            io = dispatchers.io,
            log = log,
        )

    val libreView = LibreViewAccount(receiverIds, http, dispatchers.io)

    val nfc = NfcPairing(pairings, receiverIds, onPaired = source::retry, io = dispatchers.io, log = log)
}
