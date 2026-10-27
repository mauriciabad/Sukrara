// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.storage.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * What the settings pages need from the rest of the app (features never depend on `:app` or on each other). The app
 * implements it by delegating to [SettingsModule].
 */
interface SettingsDeps {
    /** THE schema's settings (Preferences DataStore, `:core:data`). */
    val settings: SettingsRepository

    /** The alert settings (SPEC §3 JSON, store `alerts` key `alerts`), owned by the alert runtime. */
    val alertSettings: AlertSettingsStore

    /** Mute / Unmute / Mute all: the same actions the alert notification offers. */
    val alertControls: AlertControls

    /** What is muted or quiet now (the hub's `GlucoseState.alerts`). */
    val alertState: Flow<AlertUiState>

    /** The alert-readiness checklist (notifications, full-screen, exact alarm, battery, channel, Bluetooth). */
    val readiness: AlertReadinessSource

    /** The installed version (`BuildConfig.VERSION_NAME`) and whether it is a 0.x prerelease. */
    val appVersion: String
    val prerelease: Boolean

    val releases: ReleasesSource

    val clock: Clock
}

/** Reads and changes the alert settings; [update] applies a change to the stored value (never to a stale copy). */
interface AlertSettingsStore {
    val settings: Flow<AlertSettings>

    suspend fun update(transform: (AlertSettings) -> AlertSettings)
}

/** The project's GitHub releases, newest first (no network in tests: a fake returns recorded JSON). */
fun interface ReleasesSource {
    /** The releases, or a failure when offline / the API refused (rate limit). */
    suspend fun fetch(): Result<List<Release>>
}
