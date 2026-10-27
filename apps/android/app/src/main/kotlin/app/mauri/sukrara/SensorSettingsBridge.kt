// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.feature.settings.sensor.LibreViewSummary
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsPort
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsState
import app.mauri.sukrara.feature.settings.sensor.SensorSummary
import app.mauri.sukrara.feature.settings.sensor.SourceChoice
import app.mauri.sukrara.sensor.SensorModule
import app.mauri.sukrara.sensor.source.SensorSourceKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant

/**
 * Settings › Sensor over chunk 12's sensor module and the source setting (`source.source`): the settings feature
 * can't see the sensor feature, so the app joins them here.
 */
class SensorSettingsBridge(
    private val sensors: SensorModule,
    private val settings: SettingsRepository,
    private val sourceSetting: SettingSpec<String>,
    private val startMonitoring: suspend () -> Unit,
) : SensorSettingsPort {
    override val state: Flow<SensorSettingsState> =
        combine(
            settings.flow(sourceSetting),
            sensors.source.sensor,
            sensors.source.sensorInfo,
            sensors.libreView.email,
            sensors.libreView.receiverId,
        ) { source, sensor, info, email, receiverId ->
            SensorSettingsState(
                source = if (SensorSourceKind.fromId(source) == SensorSourceKind.Juggluco) SourceChoice.Juggluco else SourceChoice.Libre3Direct,
                state = sensor,
                sensor = info?.let { SensorSummary(it.serial, Instant.ofEpochSecond(it.startEpoch), Instant.ofEpochSecond(it.endEpoch)) },
                account = email?.let { LibreViewSummary(it, receiverId) },
            )
        }

    override suspend fun setSource(source: SourceChoice) {
        val kind = if (source == SourceChoice.Juggluco) SensorSourceKind.Juggluco else SensorSourceKind.Libre3
        settings.set(sourceSetting, kind.id)
        startMonitoring() // Juggluco is a source to monitor even with no sensor paired here
    }

    override suspend fun signOut() = sensors.libreView.signOut()
}
