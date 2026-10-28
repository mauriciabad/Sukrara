// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.source

import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.juggluco.JugglucoStatus
import app.mauri.sukrara.sensor.pairing.Pairing
import kotlinx.serialization.Serializable

/** The wearer's glucose source (setting `source.source`). */
@Serializable
enum class SensorSourceKind(val id: String) {
    /** Sukrara reads the paired sensor itself (a Libre 3 by NFC + Bluetooth, the other families by Bluetooth). The default. */
    Direct("direct"),

    /** Juggluco on this phone owns the sensor; Sukrara reads its local web server. */
    Juggluco("juggluco"),
    ;

    companion object {
        fun fromId(id: String?): SensorSourceKind = entries.firstOrNull { it.id == id } ?: Direct
    }
}

/**
 * The sensor source in detail, for the sensor screen and the new-sensor flow (they own the words). Every state
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
     * The sensor keeps refusing Sukrara: another app took it over (Juggluco, Abbott's app). Readings stopped;
     * "Try again" (`SourceManager.retry`) or stop the other app and scan the sensor (take over).
     */
    data object OwnedByAnotherApp : SensorStatus {
        override val action get() = SensorAction.ScanSensor
    }

    data object BluetoothPermissionMissing : SensorStatus {
        override val action get() = SensorAction.GrantBluetooth
    }

    /**
     * The paired sensor, read directly: its family, what the screens call it ([label]: the serial, the advertised
     * name or the family), which pairing this is ([key]: tells two pairings of an id-less family apart) and the link.
     */
    data class Direct(val profile: SensorProfile, val label: String, val key: String, val link: LinkStatus) : SensorStatus {
        override val action: SensorAction?
            get() =
                when (link) {
                    is LinkStatus.Failed -> SensorAction.ScanSensor
                    else -> null
                }

        companion object {
            /** The Libre 3's serial (a re-scan of the same sensor is the same one); a scanned family's id + pairing time. */
            fun keyOf(p: Pairing): String = if (p.profile.needsScan) "${p.profile.id}:${p.serial}:${p.pairedAt}" else p.serial
        }
    }

    data class Juggluco(val status: JugglucoStatus) : SensorStatus
}
