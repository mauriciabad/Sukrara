// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.storage.ReadingsRepository
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.model.storage.TreatmentsRepository
import app.mauri.sukrara.integrations.export.ExportViewModel
import app.mauri.sukrara.integrations.export.Exporter
import app.mauri.sukrara.integrations.localserver.XdripServer
import app.mauri.sukrara.integrations.nightscout.NightscoutUploader
import app.mauri.sukrara.integrations.nightscout.NightscoutViewModel
import app.mauri.sukrara.integrations.nightscout.NsState
import app.mauri.sukrara.integrations.upload.UploadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/** Which outputs are on, for the settings overview's Share with apps summary. */
data class AppsSummary(
    val nightscoutUpload: Boolean,
    val xdripServer: Boolean,
)

/**
 * The integrations area (uploads, local servers, export): built from its inputs, no I/O in the constructor. [load]
 * reads what the pages need; [start] runs the Nightscout loop and the xDrip+ server for as long as the process lives
 * (each follows its own switch, both off by default).
 */
class IntegrationsModule(
    private val context: Context,
    settings: SettingsRepository,
    secure: SecureStore,
    nightscoutState: StateStore<NsState>,
    readings: ReadingsRepository,
    treatments: TreatmentsRepository,
    sensor: Flow<CurrentSensor?>,
    role: Flow<Role?>,
    jugglucoIsSource: Flow<Boolean>,
    http: OkHttpClient,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    log: (String) -> Unit,
) {
    val wearer: Flow<Boolean> = role.map { it == Role.Wearer }

    val exporter = Exporter(readings, treatments, sensor, clock)

    val nightscout =
        NightscoutUploader(
            settings,
            secure,
            nightscoutState,
            readings,
            treatments,
            sensor,
            role,
            ::battery,
            http,
            clock,
            dispatchers.io,
            log,
        )

    val xdrip = XdripServer(settings, readings, sensor, jugglucoIsSource, clock, dispatchers.io)

    val summary: Flow<AppsSummary> =
        combine(nightscout.page, xdrip.on) { ns, x -> AppsSummary(ns.status != UploadStatus.Off, x) }

    suspend fun load() {
        nightscout.load()
        // a shared export never lingers in the cache
        ExportViewModel.exportDir(context)
    }

    fun start(scope: CoroutineScope) {
        nightscout.start(scope)
        xdrip.start(scope)
    }

    // MARK: view models (the app's navigation entries create them)

    val appsViewModels: ViewModelProvider.Factory
        get() = viewModelFactory { initializer { AppsViewModel(this@IntegrationsModule, context, dispatchers.io) } }

    fun exportViewModels(follower: Boolean): ViewModelProvider.Factory =
        viewModelFactory { initializer { ExportViewModel(exporter, follower, dispatchers.io) } }

    fun nightscoutViewModels(wearer: Boolean): ViewModelProvider.Factory =
        viewModelFactory { initializer { NightscoutViewModel(nightscout, wearer) { clock.now() } } }

    /** The phone's battery (percent, charging) from the sticky broadcast, null when unknown. */
    private fun battery(): Pair<Int, Boolean>? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level * 100 / scale to (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
    }
}
