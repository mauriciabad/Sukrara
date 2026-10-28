// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import app.mauri.sukrara.core.model.storage.SettingSpec
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.feature.settings.sensor.LibreViewSummary
import app.mauri.sukrara.feature.settings.sensor.OpenOnScan
import app.mauri.sukrara.feature.settings.sensor.SensorFamily
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsPort
import app.mauri.sukrara.feature.settings.sensor.SensorSettingsState
import app.mauri.sukrara.feature.settings.sensor.SensorSummary
import app.mauri.sukrara.feature.settings.sensor.SourceChoice
import app.mauri.sukrara.feature.settings.sensor.SourceLink
import app.mauri.sukrara.sensor.SensorModule
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.ble.NoValueReason
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import app.mauri.sukrara.sensor.pairing.PairingLoad
import app.mauri.sukrara.sensor.source.SensorSourceKind
import app.mauri.sukrara.sensor.source.SensorStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Settings › Sensor over the sensor module and the source setting (`source.source`): the settings feature
 * can't see the sensor feature, so the app joins them here.
 */
class SensorSettingsBridge(
    private val sensors: SensorModule,
    private val settings: SettingsRepository,
    private val sourceSetting: SettingSpec<String>,
    /** The app's scope: a source change finishes even if the page is left right after confirming. */
    private val scope: CoroutineScope,
    private val startMonitoring: suspend () -> Unit,
    /** Disarms the no-data alarm ("monitoring since" goes; alerts showing now stay as they are). */
    private val resetMonitoring: suspend () -> Unit,
) : SensorSettingsPort {
    private val tap = sensors.tapLaunch

    override val state: Flow<SensorSettingsState> =
        combine(
            combine(settings.flow(sourceSetting), sensors.source.sensor, sensors.source.detail, ::Triple),
            combine(sensors.source.sensorInfo, tap.on, ::Pair),
            sensors.libreView.email,
            sensors.libreView.receiverId,
        ) { (source, sensor, detail), (info, tapOn), email, receiverId ->
            SensorSettingsState(
                source =
                    if (SensorSourceKind.fromId(source) ==
                        SensorSourceKind.Juggluco
                    ) {
                        SourceChoice.Juggluco
                    } else {
                        SourceChoice.Libre3Direct
                    },
                state = sensor,
                sensor = info?.let { SensorSummary(it.serial, Instant.ofEpochSecond(it.startEpoch), Instant.ofEpochSecond(it.endEpoch)) },
                account = email?.let { LibreViewSummary(it, receiverId) },
                link = linkOf(detail),
                family = (detail as? SensorStatus.Direct)?.let { SensorFamily(it.profile.label, !it.profile.verified, it.skipped) },
                // the source restarts on every pairing change, so [detail] moving is when to look again
                paired = sensors.pairings.load() != PairingLoad.None,
                openOnScan = OpenOnScan(tapOn, tapOn && tap.blocked()).takeIf { tap.available },
            )
        }

    /** Whether readings really arrive: [SensorState] calls a connecting link or a silent Juggluco Active. */
    private fun linkOf(detail: SensorStatus): SourceLink =
        when (detail) {
            is SensorStatus.Juggluco -> {
                when (detail.status) {
                    JugglucoStatus.Live -> SourceLink.Receiving
                    JugglucoStatus.FetchingHistory -> SourceLink.Connecting
                    is JugglucoStatus.Unreachable -> SourceLink.JugglucoUnreachable
                }
            }

            is SensorStatus.Direct -> {
                when (val link = detail.link) {
                    LinkStatus.Live, is LinkStatus.WarmingUp, LinkStatus.SensorEnded, LinkStatus.WaitingForBluetooth,
                    LinkStatus.PermissionMissing,
                    -> {
                        SourceLink.Receiving
                    }

                    LinkStatus.Scanning, LinkStatus.SensorNotFound -> {
                        SourceLink.Searching
                    }

                    is LinkStatus.NoValue -> {
                        when (link.why) {
                            NoValueReason.Calibration -> SourceLink.NoValueCalibration
                            NoValueReason.Temporary -> SourceLink.NoValueTemporary
                            NoValueReason.SensorError -> SourceLink.NoValueSensorError
                            NoValueReason.Missing -> SourceLink.NoValueMissing
                        }
                    }

                    else -> {
                        SourceLink.Connecting
                    }
                }
            }

            else -> {
                SourceLink.Receiving
            }
        }

    override fun setSource(source: SourceChoice) {
        val kind = if (source == SourceChoice.Juggluco) SensorSourceKind.Juggluco else SensorSourceKind.Direct
        // a Libre tap would open the new-sensor flow of a source that isn't read: the row is hidden then, so off
        if (kind == SensorSourceKind.Juggluco) tap.set(false)
        scope.launch {
            settings.set(sourceSetting, kind.id)
            startMonitoring() // Juggluco is a source to monitor even with no sensor paired here
        }
    }

    override suspend fun signOut() = sensors.libreView.signOut()

    private companion object {
        const val STOP_WAIT_MS = 5_000L
    }

    override fun setOpenOnScan(on: Boolean) = tap.set(on)

    override fun forget() {
        scope.launch {
            sensors.pairings.forget()
            sensors.source.retry()
            // removed on purpose: no "no data" alarm for a sensor that isn't read any more. Once the source has
            // stopped (no reading can arrive after the reset and arm it again)
            withTimeoutOrNull(STOP_WAIT_MS) { sensors.source.detail.first { it == SensorStatus.NoSensor } }
            resetMonitoring()
        }
    }
}
