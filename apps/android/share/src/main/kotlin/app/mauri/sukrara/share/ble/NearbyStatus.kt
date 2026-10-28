// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

/**
 * The Bluetooth share link as the person sees it, on the wearer (the GATT server) and the follower (the client). Every
 * state but [Connected] / [Searching] names its way out: turn Bluetooth on, allow Nearby devices, turn the switch on.
 * Following and sharing over the internet go on in every state.
 */
sealed interface NearbyStatus {
    /** Not running: the switch is off, nobody to share with / not following, or the engine is stopped. */
    data object Stopped : NearbyStatus

    /** This phone can't do it (no Bluetooth LE, or no advertising for a wearer). */
    data object Unsupported : NearbyStatus

    data object BluetoothOff : NearbyStatus

    /** The Nearby devices permission is missing (asked from the row that shows this). */
    data object NeedsPermission : NearbyStatus

    /** Wearer: advertising, nobody connected yet. Follower: looking for the wearer's phone. */
    data object Searching : NearbyStatus

    /** Wearer: [count] named follower devices connected. Follower: connected to the wearer's phone (count 1). */
    data class Connected(val count: Int) : NearbyStatus
}
