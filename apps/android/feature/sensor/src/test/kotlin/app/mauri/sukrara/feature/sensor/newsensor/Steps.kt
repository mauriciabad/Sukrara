// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.LinkStatus
import java.time.Duration
import java.time.Instant

/** One instance of every step (every variant of the ones with a cause), named, for page tests and screenshots. */
internal object Steps {
    val NOW: Instant = Instant.parse("2026-10-27T08:00:00Z")
    private const val SERIAL = "0D00012345"

    val all: List<Pair<String, NewSensorStep>> =
        listOf(
            "idle" to NewSensorStep.Idle,
            "choose" to NewSensorStep.ChooseSensor(SensorProfile.Libre3),
            "choose-untested" to NewSensorStep.ChooseSensor(SensorProfile.DexcomG7),
            "confirm-untested" to NewSensorStep.ConfirmUntested(SensorProfile.DexcomG7, NOW.minusSeconds(3)),
            "setup-transmitter" to NewSensorStep.SensorSetupStep(SensorProfile.DexcomG6, "8GAB"),
            "setup-serial" to NewSensorStep.SensorSetupStep(SensorProfile.AidexX, "22222A2WC8"),
            "setup-vendor-app" to NewSensorStep.SensorSetupStep(SensorProfile.DexcomG7),
            "setup-pin" to NewSensorStep.SensorSetupStep(SensorProfile.SmartGuide),
            "connecting-scanning" to
                NewSensorStep.ConnectingBle("k", NOW, activated = false, SensorProfile.DexcomG6, LinkStatus.Scanning),
            "connecting-not-found" to
                NewSensorStep.ConnectingBle("k", NOW, activated = false, SensorProfile.AidexX, LinkStatus.SensorNotFound),
            "connecting-pin" to NewSensorStep.ConnectingBle("k", NOW, activated = false, SensorProfile.SmartGuide, LinkStatus.EnterPin),
            "connecting-vendor-app" to
                NewSensorStep.ConnectingBle("k", NOW, activated = false, SensorProfile.DexcomG7, LinkStatus.WaitingForVendorApp),
            "connecting-no-session" to
                NewSensorStep.ConnectingBle("k", NOW, activated = false, SensorProfile.DexcomG6, LinkStatus.NoSession),
            "ble-failed-wrong-id" to NewSensorStep.BleFailed("k", BleFailure.WrongId),
            "ble-failed-refused" to NewSensorStep.BleFailed("k", BleFailure.Refused),
            "ble-failed-bond" to NewSensorStep.BleFailed("k", BleFailure.BondFailed),
            "core-missing" to NewSensorStep.CoreMissing,
            "no-nfc" to NewSensorStep.NoNfc,
            "nfc-off" to NewSensorStep.NfcOff,
            "needs-libreview" to NewSensorStep.NeedsLibreViewSignIn,
            "official-app-warning" to NewSensorStep.OfficialAppWarning,
            "needs-bt-permission" to NewSensorStep.NeedsBluetoothPermission,
            "bluetooth-off" to NewSensorStep.BluetoothOff,
            "needs-notifications" to NewSensorStep.NeedsNotifications,
            "waiting-for-tap" to NewSensorStep.WaitingForTap,
            "reading-nfc" to NewSensorStep.ReadingNfc,
            "nfc-failed-moved" to NewSensorStep.NfcFailed(NfcFailure.MovedTooSoon),
            "nfc-failed-not-libre3" to NewSensorStep.NfcFailed(NfcFailure.NotLibre3),
            "nfc-failed-ended" to NewSensorStep.NfcFailed(NfcFailure.SensorEnded),
            "takeover-failed" to NewSensorStep.TakeoverFailed,
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
