// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSettingsCodec
import app.mauri.sukrara.alerts.AlertState
import app.mauri.sukrara.alerts.SensorWindow
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.core.model.storage.StateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/**
 * What the alert runtime keeps across a process death (typed DataStore `alert_state`, the [StateStore]):
 * the machine's state (episodes, this device's mutes / Remind less, Mute all, the last reading) and whether monitoring
 * has started, so the alarm receiver can check no data with the service dead and never before monitoring began.
 */
@Serializable
data class AlertRecord(
    val machine: AlertState? = null,
    /** Monitoring has started (the hub's `monitoringStartedAt` is set): only then can no data alert. */
    val monitoring: Boolean = false,
    /** The followed person's name for a follower's notifications. */
    val who: String? = null,
    /** The sensor's life (no `no_data` while it is warming up or has ended), also for the receiver. */
    val sensor: SavedSensor? = null,
)

/** [SensorWindow] as saved. */
@Serializable
data class SavedSensor(val start: Long, val end: Long, val warmupEnd: Long) {
    constructor(w: SensorWindow) : this(w.startEpoch, w.endEpoch, w.warmupEndEpoch)

    val window: SensorWindow get() = SensorWindow(start, end, warmupEnd)
}

/**
 * The alert settings (THE schema: store `alerts`, key `alerts`, the SPEC §3 JSON) and the runtime's [AlertRecord].
 * Unset or unreadable settings read as the defaults (an alert is never switched off by a bad value).
 */
class AlertStateStore(
    private val settingsRepo: SettingsRepository,
    private val record: StateStore<AlertRecord>,
) {
    val settings: Flow<AlertSettings> = settingsRepo.flow(SETTINGS).map { AlertSettingsCodec.decode(it) ?: AlertSettings() }

    suspend fun setSettings(settings: AlertSettings) = settingsRepo.set(SETTINGS, AlertSettingsCodec.encode(settings))

    /** Changes the stored settings in one atomic edit (two screens editing at once never lose a change). */
    suspend fun updateSettings(transform: (AlertSettings) -> AlertSettings) =
        settingsRepo.update(
            SETTINGS,
        ) { stored -> AlertSettingsCodec.encode(transform(AlertSettingsCodec.decode(stored) ?: AlertSettings())) }

    suspend fun load(): AlertRecord = record.data.first()

    suspend fun save(transform: (AlertRecord) -> AlertRecord) = record.update(transform)

    companion object {
        /**
         * contracts/settings/settings.json `alerts.*` → Android store `alerts`, key `alerts`: every `alerts.*` entry is a
         * path inside that one JSON value, so any of them resolves the key (the repository only accepts schema settings).
         */
        val SETTINGS: SettingSpec<String> = SchemaSettings.text("alerts.on", default = "")
    }
}
