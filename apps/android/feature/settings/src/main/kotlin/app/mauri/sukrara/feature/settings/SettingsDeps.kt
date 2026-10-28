// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertControls
import app.mauri.sukrara.core.model.AlertReadinessSource
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SystemAccessPort
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

    /** Whether this phone reads insulin pens (the wearer's phone with NFC): Settings shows *Insulin pens* only then. */
    fun pensAvailable(): Boolean = false

    /** Root for Sukrara and the System UI module (Settings › Status bar, "With root"). */
    val systemAccess: SystemAccessPort

    /** The wearer's sensor as the hub knows it (Settings › Sensor end shows its end). */
    val sensor: Flow<SensorState>

    /** An alert's sound preview and *Send a test alert* (the alert runtime's notifier); nothing in screenshots. */
    val alertPlayback: AlertPlayback get() = AlertPlayback.None
}

/**
 * Hearing an alert from its settings page, only on the person's tap: [play] its sound for a pack (with its vibration
 * when [vibrate]) on the volume it rings on, [stop] it, [sendTest] the real notification on its channel labelled as
 * a test (no alert state changes), and the kind's notification channel ([channelId]) for Android's settings.
 */
interface AlertPlayback {
    fun play(
        kind: AlertKind,
        pack: String,
        alarm: Boolean,
        vibrate: Boolean,
    )

    fun stop()

    /** Android's settings silence this alert (its channel's sound off or importance too low). */
    fun silenced(
        kind: AlertKind,
        alarm: Boolean,
    ): Boolean

    fun sendTest(
        kind: AlertKind,
        settings: AlertSettings,
    )

    fun channelId(
        kind: AlertKind,
        alarm: Boolean,
    ): String?

    object None : AlertPlayback {
        override fun play(
            kind: AlertKind,
            pack: String,
            alarm: Boolean,
            vibrate: Boolean,
        ) = Unit

        override fun stop() = Unit

        override fun silenced(
            kind: AlertKind,
            alarm: Boolean,
        ) = false

        override fun sendTest(
            kind: AlertKind,
            settings: AlertSettings,
        ) = Unit

        override fun channelId(
            kind: AlertKind,
            alarm: Boolean,
        ): String? = null
    }
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
