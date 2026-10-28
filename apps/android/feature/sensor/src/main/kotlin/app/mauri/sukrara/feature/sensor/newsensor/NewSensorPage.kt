// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import androidx.annotation.StringRes
import app.mauri.sukrara.feature.sensor.R

/** A button on a new-sensor page. The screen turns each into an intent, a permission request or a machine event. */
enum class NewSensorAction(@param:StringRes val label: Int) {
    TryAgain(R.string.newsensor_action_try_again),
    SignIn(R.string.newsensor_action_sign_in),
    NfcSettings(R.string.newsensor_action_nfc_settings),
    BluetoothSettings(R.string.newsensor_action_bt_settings),
    AllowBluetooth(R.string.newsensor_action_allow),
    AllowNotifications(R.string.newsensor_action_allow),
    AppSettings(R.string.newsensor_action_app_settings),
    Continue(R.string.newsensor_action_continue),
    NotNow(R.string.newsensor_action_not_now),
    UseJuggluco(R.string.newsensor_action_use_juggluco),
    Cancel(R.string.newsensor_action_cancel),
    Close(R.string.newsensor_action_close),
    Done(R.string.newsensor_action_done),
    ;

    /** Leaves the flow (shown as the last, quiet button). */
    val isExit: Boolean get() = this == Cancel || this == Close || this == Done
}

/**
 * What one step shows: plain-words [title] and [text], an optional [note], a spinner while [busy], and its buttons
 * (the first is the main one). Pure, so the "every state has a way out" rule is a unit test (NewSensorPageTest).
 */
data class NewSensorPage(
    @param:StringRes val title: Int,
    @param:StringRes val text: Int,
    val actions: List<NewSensorAction>,
    @param:StringRes val note: Int? = null,
    val busy: Boolean = false,
)

fun NewSensorStep.page(): NewSensorPage =
    when (this) {
        NewSensorStep.Idle -> NewSensorPage(R.string.newsensor_checking_title, R.string.newsensor_checking_text, listOf(NewSensorAction.Cancel), busy = true)
        NewSensorStep.CoreMissing -> NewSensorPage(
            R.string.newsensor_core_missing_title,
            R.string.newsensor_core_missing_text,
            listOf(NewSensorAction.UseJuggluco, NewSensorAction.Cancel),
        )
        NewSensorStep.NoNfc -> NewSensorPage(
            R.string.newsensor_no_nfc_title,
            R.string.newsensor_no_nfc_text,
            listOf(NewSensorAction.UseJuggluco, NewSensorAction.Cancel),
        )
        NewSensorStep.NfcOff -> NewSensorPage(
            R.string.newsensor_nfc_off_title,
            R.string.newsensor_nfc_off_text,
            listOf(NewSensorAction.NfcSettings, NewSensorAction.Cancel),
        )
        NewSensorStep.NeedsLibreViewSignIn -> NewSensorPage(
            R.string.newsensor_signin_title,
            R.string.newsensor_signin_text,
            listOf(NewSensorAction.SignIn, NewSensorAction.Cancel),
        )
        NewSensorStep.OfficialAppWarning -> NewSensorPage(
            R.string.newsensor_official_title,
            R.string.newsensor_official_text,
            listOf(NewSensorAction.Continue, NewSensorAction.Cancel),
        )
        NewSensorStep.NeedsBluetoothPermission -> NewSensorPage(
            R.string.newsensor_bt_permission_title,
            R.string.newsensor_bt_permission_text,
            listOf(NewSensorAction.AllowBluetooth, NewSensorAction.AppSettings, NewSensorAction.Cancel),
        )
        NewSensorStep.BluetoothOff -> NewSensorPage(
            R.string.newsensor_bt_off_title,
            R.string.newsensor_bt_off_text,
            listOf(NewSensorAction.BluetoothSettings, NewSensorAction.Cancel),
        )
        NewSensorStep.NeedsNotifications -> NewSensorPage(
            R.string.newsensor_notifications_title,
            R.string.newsensor_notifications_text,
            listOf(NewSensorAction.AllowNotifications, NewSensorAction.NotNow, NewSensorAction.Cancel),
        )
        NewSensorStep.WaitingForTap -> NewSensorPage(R.string.newsensor_tap_title, R.string.newsensor_tap_text, listOf(NewSensorAction.Cancel))
        // the tap is being read: no way out for these few seconds (stopping could lose a started sensor's pairing)
        NewSensorStep.ReadingNfc -> NewSensorPage(R.string.newsensor_reading_title, R.string.newsensor_reading_text, emptyList(), busy = true)
        is NewSensorStep.NfcFailed -> when (cause) {
            NfcFailure.MovedTooSoon -> NewSensorPage(
                R.string.newsensor_failed_moved_title,
                R.string.newsensor_failed_moved_text,
                listOf(NewSensorAction.TryAgain, NewSensorAction.UseJuggluco, NewSensorAction.Done),
            )
            NfcFailure.NotLibre3 -> NewSensorPage(
                R.string.newsensor_failed_not_libre3_title,
                R.string.newsensor_failed_not_libre3_text,
                listOf(NewSensorAction.UseJuggluco, NewSensorAction.Done),
            )
            NfcFailure.SensorEnded -> NewSensorPage(
                R.string.newsensor_failed_ended_title,
                R.string.newsensor_failed_ended_text,
                listOf(NewSensorAction.TryAgain, NewSensorAction.UseJuggluco, NewSensorAction.Done),
            )
        }
        NewSensorStep.TakeoverFailed -> NewSensorPage(
            R.string.newsensor_takeover_title,
            R.string.newsensor_takeover_other_text,
            listOf(NewSensorAction.TryAgain, NewSensorAction.Done),
        )
        is NewSensorStep.ConnectingBle -> NewSensorPage(
            R.string.newsensor_connecting_title,
            if (activated) R.string.newsensor_connecting_started_text else R.string.newsensor_connecting_paired_text,
            listOf(NewSensorAction.Close),
            note = R.string.newsensor_keeps_going,
            busy = true,
        )
        is NewSensorStep.BleFailed -> when (cause) {
            BleFailure.BluetoothOff -> NewSensorPage(
                R.string.newsensor_bt_off_title,
                R.string.newsensor_ble_off_text,
                listOf(NewSensorAction.BluetoothSettings, NewSensorAction.TryAgain, NewSensorAction.Close),
            )
            BleFailure.PermissionMissing -> NewSensorPage(
                R.string.newsensor_bt_permission_title,
                R.string.newsensor_ble_permission_text,
                listOf(NewSensorAction.AllowBluetooth, NewSensorAction.AppSettings, NewSensorAction.Close),
            )
            BleFailure.Timeout -> NewSensorPage(
                R.string.newsensor_ble_timeout_title,
                R.string.newsensor_ble_timeout_text,
                listOf(NewSensorAction.TryAgain, NewSensorAction.Close),
                note = R.string.newsensor_keeps_going,
            )
        }
        is NewSensorStep.WarmingUp -> NewSensorPage(
            R.string.newsensor_warming_title,
            R.string.newsensor_warming_text,
            listOf(NewSensorAction.Close),
            note = R.string.newsensor_keeps_going,
        )
        NewSensorStep.Ready -> NewSensorPage(R.string.newsensor_ready_title, R.string.newsensor_ready_text, listOf(NewSensorAction.Done))
        NewSensorStep.UsingJuggluco -> NewSensorPage(R.string.newsensor_juggluco_title, R.string.newsensor_juggluco_text, listOf(NewSensorAction.Done))
        // the screen closes itself on these two (they show for one frame at most)
        NewSensorStep.LeftRunning ->
            NewSensorPage(R.string.newsensor_connecting_title, R.string.newsensor_keeps_going, listOf(NewSensorAction.Done))
        NewSensorStep.Cancelled ->
            NewSensorPage(R.string.newsensor_screen_title, R.string.newsensor_checking_text, listOf(NewSensorAction.Done))
    }
