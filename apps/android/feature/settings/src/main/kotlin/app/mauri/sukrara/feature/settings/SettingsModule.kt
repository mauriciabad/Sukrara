// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.DispatcherProvider
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.storage.SettingsRepository
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.time.Instant

/**
 * The settings feature's wiring: `AppGraph` builds it with one line and implements [SettingsDeps] by
 * returning [deps]. Construction does no I/O.
 */
class SettingsModule(
    settings: SettingsRepository,
    alertSettings: AlertSettingsStore,
    alertControls: AlertControls,
    alertState: Flow<AlertUiState>,
    readiness: AlertReadinessSource,
    appVersion: String,
    prerelease: Boolean,
    http: OkHttpClient,
    dispatchers: DispatcherProvider,
    clock: Clock,
    sensor: Flow<SensorState>,
    alertPlayback: AlertPlayback,
) {
    val deps: SettingsDeps =
        object : SettingsDeps {
            override val settings = settings
            override val alertSettings = alertSettings
            override val alertControls = alertControls
            override val alertState = alertState
            override val readiness = readiness
            override val appVersion = appVersion
            override val prerelease = prerelease
            override val releases: ReleasesSource by lazy { GitHubReleases(http, dispatchers.io) }
            override val clock = clock
            override val sensor = sensor
            override val alertPlayback = alertPlayback
        }

    /** When the alert thresholds were confirmed (null = never): home's Finish-setup card asks until they are. */
    val thresholdsConfirmedAt: Flow<Instant?> = SchemaPrefs.thresholdsConfirmedAt(settings)
}
