// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.source

import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import kotlinx.serialization.Serializable

/** The wearer's glucose source (setting `source.source`). */
@Serializable
enum class SensorSourceKind(val id: String) {
    /** Sukrara reads the Libre 3 / 3 Plus itself (NFC + Bluetooth). The default. */
    Libre3("libre3"),

    /** Juggluco on this phone owns the sensor; Sukrara reads its local web server. */
    Juggluco("juggluco"),
    ;

    companion object {
        fun fromId(id: String?): SensorSourceKind = entries.firstOrNull { it.id == id } ?: Libre3
    }
}

/**
 * The sensor source in detail, for the sensor screen and the new-sensor flow (chunk 18 owns the words). Every state
 * that needs the person has an [action]; the hub gets the coarse [SensorState].
 */
sealed interface SensorStatus {
    /** What the person can do about it (null = nothing needed, the app is working on it). */
    val action: SensorAction? get() = null

    /** No sensor set up yet ("Not monitoring: no sensor yet"). */
    data object NoSensor : SensorStatus {
        override val action get() = SensorAction.ScanSensor
    }

    /** This build has no Libre 3 native core (debug build made without Juggluco); release builds always have it. */
    data object CoreMissing : SensorStatus

    /** The saved pairing can't be used; scanning the sensor again fixes it. */
    data class PairingUnreadable(val reason: String) : SensorStatus {
        override val action get() = SensorAction.ScanSensor
    }

    /** A sensor this version can't read (Lingo / another security version). */
    data class Unsupported(val why: String) : SensorStatus {
        override val action get() = SensorAction.ScanSensor
    }

    /** The sensor has ended; a new one is needed. */
    data object Ended : SensorStatus {
        override val action get() = SensorAction.ScanSensor
    }

    /**
     * The sensor keeps refusing V1: another app took it over (the showcase, Juggluco, Abbott's app). Readings stopped;
     * "Try again" (`SourceManager.retry`) or stop the other app and scan the sensor (take over).
     */
    data class OwnedByAnotherApp(val showcaseInstalled: Boolean) : SensorStatus {
        override val action get() = if (showcaseInstalled) SensorAction.StopOtherApp else SensorAction.ScanSensor
    }

    data object BluetoothPermissionMissing : SensorStatus {
        override val action get() = SensorAction.GrantBluetooth
    }

    data class Libre3(val serial: String, val link: LinkStatus) : SensorStatus

    data class Juggluco(val status: JugglucoStatus) : SensorStatus
}
