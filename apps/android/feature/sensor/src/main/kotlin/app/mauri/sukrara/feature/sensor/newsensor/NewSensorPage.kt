// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import androidx.annotation.StringRes
import app.mauri.sukrara.feature.sensor.R
import app.mauri.sukrara.sensor.IdKind
import app.mauri.sukrara.sensor.SensorSetup
import app.mauri.sukrara.sensor.ble.LinkStatus

/** A button on a new-sensor page. The screen turns each into an intent, a permission request or a machine event. */
enum class NewSensorAction(
    @param:StringRes val label: Int,
) {
    TryAgain(R.string.newsensor_action_try_again),
    SignIn(R.string.newsensor_action_sign_in),
    NfcSettings(R.string.newsensor_action_nfc_settings),
    BluetoothSettings(R.string.newsensor_action_bt_settings),
    AllowBluetooth(R.string.newsensor_action_allow),
    AllowNotifications(R.string.newsensor_action_allow),
    AppSettings(R.string.newsensor_action_app_settings),
    Continue(R.string.newsensor_action_continue),
    ContinueAnyway(R.string.newsensor_action_continue_anyway),
    Pair(R.string.newsensor_action_pair),
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
    /** Filled into the title's and the text's `%1$s` (a family's brand name, not translated). */
    val arg: String? = null,
    /** The warning look (the untested-sensor confirmation). */
    val warning: Boolean = false,
)

fun NewSensorStep.page(): NewSensorPage =
    when (this) {
        NewSensorStep.Idle -> {
            NewSensorPage(R.string.newsensor_checking_title, R.string.newsensor_checking_text, listOf(NewSensorAction.Cancel), busy = true)
        }

        is NewSensorStep.ChooseSensor -> {
            NewSensorPage(
                R.string.newsensor_choose_title,
                R.string.newsensor_choose_text,
                listOf(NewSensorAction.Continue, NewSensorAction.Cancel),
            )
        }

        is NewSensorStep.ConfirmUntested -> {
            NewSensorPage(
                R.string.newsensor_untested_title,
                R.string.newsensor_untested_text,
                listOf(NewSensorAction.ContinueAnyway, NewSensorAction.Cancel),
                arg = profile.label,
                warning = true,
            )
        }

        is NewSensorStep.SensorSetupStep -> {
            NewSensorPage(
                R.string.newsensor_setup_title,
                when (val setup = profile.setup) {
                    is SensorSetup.TypedId -> {
                        if (setup.kind ==
                            IdKind.TransmitterId
                        ) {
                            R.string.newsensor_setup_transmitter_text
                        } else {
                            R.string.newsensor_setup_serial_text
                        }
                    }

                    is SensorSetup.BlePasskey -> {
                        R.string.newsensor_setup_pin_text
                    }

                    else -> {
                        R.string.newsensor_setup_vendor_text
                    }
                },
                listOf(NewSensorAction.Pair, NewSensorAction.Cancel),
                arg = profile.label,
            )
        }

        NewSensorStep.CoreMissing -> {
            NewSensorPage(
                R.string.newsensor_core_missing_title,
                R.string.newsensor_core_missing_text,
                listOf(NewSensorAction.UseJuggluco, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.NoNfc -> {
            NewSensorPage(
                R.string.newsensor_no_nfc_title,
                R.string.newsensor_no_nfc_text,
                listOf(NewSensorAction.UseJuggluco, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.NfcOff -> {
            NewSensorPage(
                R.string.newsensor_nfc_off_title,
                R.string.newsensor_nfc_off_text,
                listOf(NewSensorAction.NfcSettings, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.NeedsLibreViewSignIn -> {
            NewSensorPage(
                R.string.newsensor_signin_title,
                R.string.newsensor_signin_text,
                listOf(NewSensorAction.SignIn, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.OfficialAppWarning -> {
            NewSensorPage(
                R.string.newsensor_official_title,
                R.string.newsensor_official_text,
                listOf(NewSensorAction.Continue, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.NeedsBluetoothPermission -> {
            NewSensorPage(
                R.string.newsensor_bt_permission_title,
                R.string.newsensor_bt_permission_text,
                listOf(NewSensorAction.AllowBluetooth, NewSensorAction.AppSettings, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.BluetoothOff -> {
            NewSensorPage(
                R.string.newsensor_bt_off_title,
                R.string.newsensor_bt_off_text,
                listOf(NewSensorAction.BluetoothSettings, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.NeedsNotifications -> {
            NewSensorPage(
                R.string.newsensor_notifications_title,
                R.string.newsensor_notifications_text,
                listOf(NewSensorAction.AllowNotifications, NewSensorAction.NotNow, NewSensorAction.Cancel),
            )
        }

        NewSensorStep.WaitingForTap -> {
            NewSensorPage(R.string.newsensor_tap_title, R.string.newsensor_tap_text, listOf(NewSensorAction.Cancel))
        }

        // the tap is being read: no way out for these few seconds (stopping could lose a started sensor's pairing)
        NewSensorStep.ReadingNfc -> {
            NewSensorPage(R.string.newsensor_reading_title, R.string.newsensor_reading_text, emptyList(), busy = true)
        }

        is NewSensorStep.NfcFailed -> {
            when (cause) {
                NfcFailure.MovedTooSoon -> {
                    NewSensorPage(
                        R.string.newsensor_failed_moved_title,
                        R.string.newsensor_failed_moved_text,
                        listOf(NewSensorAction.TryAgain, NewSensorAction.UseJuggluco, NewSensorAction.Done),
                    )
                }

                NfcFailure.NotLibre3 -> {
                    NewSensorPage(
                        R.string.newsensor_failed_not_libre3_title,
                        R.string.newsensor_failed_not_libre3_text,
                        listOf(NewSensorAction.UseJuggluco, NewSensorAction.Done),
                    )
                }

                NfcFailure.SensorEnded -> {
                    NewSensorPage(
                        R.string.newsensor_failed_ended_title,
                        R.string.newsensor_failed_ended_text,
                        listOf(NewSensorAction.TryAgain, NewSensorAction.UseJuggluco, NewSensorAction.Done),
                    )
                }
            }
        }

        NewSensorStep.TakeoverFailed -> {
            NewSensorPage(
                R.string.newsensor_takeover_title,
                R.string.newsensor_takeover_other_text,
                listOf(NewSensorAction.TryAgain, NewSensorAction.Done),
            )
        }

        is NewSensorStep.ConnectingBle -> {
            NewSensorPage(
                R.string.newsensor_connecting_title,
                when {
                    link == LinkStatus.Scanning -> R.string.newsensor_scanning_text
                    link == LinkStatus.SensorNotFound -> R.string.newsensor_not_found_text
                    link == LinkStatus.EnterPin -> R.string.newsensor_enter_pin_text
                    link == LinkStatus.WaitingForVendorApp -> R.string.newsensor_vendor_app_text
                    link == LinkStatus.NoSession -> R.string.newsensor_no_session_text
                    profile.needsScan -> R.string.newsensor_scanning_text
                    activated -> R.string.newsensor_connecting_started_text
                    else -> R.string.newsensor_connecting_paired_text
                },
                listOf(NewSensorAction.Close),
                note = R.string.newsensor_keeps_going,
                busy = true,
            )
        }

        is NewSensorStep.BleFailed -> {
            when (cause) {
                BleFailure.BluetoothOff -> {
                    NewSensorPage(
                        R.string.newsensor_bt_off_title,
                        R.string.newsensor_ble_off_text,
                        listOf(NewSensorAction.BluetoothSettings, NewSensorAction.TryAgain, NewSensorAction.Close),
                    )
                }

                BleFailure.PermissionMissing -> {
                    NewSensorPage(
                        R.string.newsensor_bt_permission_title,
                        R.string.newsensor_ble_permission_text,
                        listOf(NewSensorAction.AllowBluetooth, NewSensorAction.AppSettings, NewSensorAction.Close),
                    )
                }

                BleFailure.WrongId, BleFailure.Refused, BleFailure.BondFailed -> {
                    NewSensorPage(
                        R.string.newsensor_pair_failed_title,
                        when (cause) {
                            BleFailure.WrongId -> R.string.newsensor_wrong_id_text
                            BleFailure.Refused -> R.string.newsensor_refused_text
                            else -> R.string.newsensor_bond_failed_text
                        },
                        listOf(NewSensorAction.TryAgain, NewSensorAction.Close),
                    )
                }

                BleFailure.Timeout -> {
                    NewSensorPage(
                        R.string.newsensor_ble_timeout_title,
                        R.string.newsensor_ble_timeout_text,
                        listOf(NewSensorAction.TryAgain, NewSensorAction.Close),
                        note = R.string.newsensor_keeps_going,
                    )
                }
            }
        }

        is NewSensorStep.WarmingUp -> {
            NewSensorPage(
                R.string.newsensor_warming_title,
                R.string.newsensor_warming_text,
                listOf(NewSensorAction.Close),
                note = R.string.newsensor_keeps_going,
            )
        }

        NewSensorStep.Ready -> {
            NewSensorPage(R.string.newsensor_ready_title, R.string.newsensor_ready_text, listOf(NewSensorAction.Done))
        }

        NewSensorStep.UsingJuggluco -> {
            NewSensorPage(R.string.newsensor_juggluco_title, R.string.newsensor_juggluco_text, listOf(NewSensorAction.Done))
        }

        // the screen closes itself on these two (they show for one frame at most)
        NewSensorStep.LeftRunning -> {
            NewSensorPage(R.string.newsensor_connecting_title, R.string.newsensor_keeps_going, listOf(NewSensorAction.Done))
        }

        NewSensorStep.Cancelled -> {
            NewSensorPage(R.string.newsensor_screen_title, R.string.newsensor_checking_text, listOf(NewSensorAction.Done))
        }
    }
