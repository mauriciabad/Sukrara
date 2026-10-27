// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import java.time.Duration
import java.time.Instant

/** One instance of every step (every variant of the ones with a cause), named, for page tests and screenshots. */
internal object Steps {
    val NOW: Instant = Instant.parse("2026-10-27T08:00:00Z")
    private const val SERIAL = "0D00012345"

    val all: List<Pair<String, NewSensorStep>> = listOf(
        "idle" to NewSensorStep.Idle,
        "core-missing" to NewSensorStep.CoreMissing,
        "no-nfc" to NewSensorStep.NoNfc,
        "nfc-off" to NewSensorStep.NfcOff,
        "needs-libreview" to NewSensorStep.NeedsLibreViewSignIn,
        "showcase-warning" to NewSensorStep.ShowcaseWarning,
        "official-app-warning" to NewSensorStep.OfficialAppWarning,
        "needs-bt-permission" to NewSensorStep.NeedsBluetoothPermission,
        "bluetooth-off" to NewSensorStep.BluetoothOff,
        "needs-notifications" to NewSensorStep.NeedsNotifications,
        "waiting-for-tap" to NewSensorStep.WaitingForTap,
        "reading-nfc" to NewSensorStep.ReadingNfc,
        "nfc-failed-moved" to NewSensorStep.NfcFailed(NfcFailure.MovedTooSoon),
        "nfc-failed-not-libre3" to NewSensorStep.NfcFailed(NfcFailure.NotLibre3),
        "nfc-failed-ended" to NewSensorStep.NfcFailed(NfcFailure.SensorEnded),
        "takeover-failed-showcase" to NewSensorStep.TakeoverFailed(showcaseInstalled = true),
        "takeover-failed-other" to NewSensorStep.TakeoverFailed(showcaseInstalled = false),
        "connecting-started" to NewSensorStep.ConnectingBle(SERIAL, NOW, activated = true),
        "connecting-paired" to NewSensorStep.ConnectingBle(SERIAL, NOW, activated = false),
        "ble-failed-off" to NewSensorStep.BleFailed(SERIAL, BleFailure.BluetoothOff),
        "ble-failed-permission" to NewSensorStep.BleFailed(SERIAL, BleFailure.PermissionMissing),
        "ble-failed-timeout" to NewSensorStep.BleFailed(SERIAL, BleFailure.Timeout),
        "warming-up" to NewSensorStep.WarmingUp(SERIAL, NOW.plus(Duration.ofMinutes(52)).minusSeconds(20)),
        "ready" to NewSensorStep.Ready,
        "using-juggluco" to NewSensorStep.UsingJuggluco,
        "left-running" to NewSensorStep.LeftRunning,
        "cancelled" to NewSensorStep.Cancelled,
    )
}
