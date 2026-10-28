// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.share.ble.NearbyServerCore
import app.mauri.sukrara.share.ble.NearbyStatus

/**
 * Bluetooth nearby on the wearer's sharing page: the switch ([on], Settings › Sharing › Bluetooth), what the server
 * says ([status]), the follower devices connected now whose identity proof named a follower ([links]) and
 * whether *Pair nearby* is open ([pairingUntil]).
 */
data class NearbyUi(
    val on: Boolean = true,
    val status: NearbyStatus = NearbyStatus.Stopped,
    val links: List<NearbyServerCore.Named> = emptyList(),
    /** *Pair nearby* is open until this epoch second (null = closed). */
    val pairingUntil: Long? = null,
)
