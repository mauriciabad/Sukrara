// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.share.ble.NearbyStatus
import kotlinx.coroutines.delay

/**
 * The way out of a blocked Bluetooth state, under the row that shows it: Bluetooth off → its settings page; Nearby
 * devices missing → the permission dialog ([permissions]). [onRefresh] runs when the person comes back or answers
 * (the server / client looks again; nothing polls).
 */
@Composable
fun ColumnScope.NearbyFixRow(
    status: NearbyStatus,
    permissions: List<String>,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    // after a refusal Android may not show the dialog again: the next tap opens the app's settings page instead
    var refused by rememberSaveable { mutableStateOf(false) }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            refused = result.values.any { !it }
            onRefresh()
        }
    val refresh by rememberUpdatedState(onRefresh)
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    when (status) {
        NearbyStatus.NeedsPermission -> {
            NavigationRow(
                title = stringResource(R.string.sh_nearby_allow),
                summary = stringResource(R.string.sh_nearby_allow_summary),
                onClick = {
                    if (refused) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    } else {
                        ask.launch(permissions.toTypedArray())
                    }
                },
            )
        }

        NearbyStatus.BluetoothOff -> {
            NavigationRow(
                title = stringResource(R.string.sh_nearby_turn_on),
                onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            )
        }

        else -> {
            Unit
        }
    }
}

/** One line for the Bluetooth link's state; [wearer] = the follower's side (one phone), else the wearer's (N devices). */
@Composable
fun nearbySummary(status: NearbyStatus, on: Boolean, wearer: Boolean): String =
    when {
        !on -> stringResource(R.string.sh_nearby_off)
        status == NearbyStatus.BluetoothOff -> stringResource(R.string.sh_nearby_bluetooth_off)
        status == NearbyStatus.NeedsPermission -> stringResource(R.string.sh_nearby_permission)
        status == NearbyStatus.Unsupported -> stringResource(R.string.sh_nearby_unsupported)
        status is NearbyStatus.Connected && wearer -> pluralStringResource(R.plurals.sh_nearby_connected, status.count, status.count)
        status is NearbyStatus.Connected -> stringResource(R.string.sh_nearby_connected_phone)
        status == NearbyStatus.Searching && wearer -> stringResource(R.string.sh_nearby_waiting)
        status == NearbyStatus.Searching -> stringResource(R.string.sh_nearby_searching)
        wearer -> stringResource(R.string.sh_nearby_no_followers)
        else -> stringResource(R.string.sh_nearby_idle)
    }

/** Bluetooth states the page already explains with a fix row: nothing to pair over. */
val BLOCKED = setOf(NearbyStatus.BluetoothOff, NearbyStatus.NeedsPermission, NearbyStatus.Unsupported)

/**
 * *Pair nearby*: on for 10 minutes, phones in range can ask to follow without a link (the wearer still types the
 * code their phone shows). Its summary counts down while open; the switch turns it off (*Stop*). It closes by itself
 * at the first request or after 10 minutes.
 */
@Composable
fun PairNearbyRow(
    until: Long?,
    onChange: (Boolean) -> Unit,
    now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val clock by rememberUpdatedState(now)
    val left by produceState(until?.let { it - clock() }, until) {
        while (until != null) {
            value = (until - clock()).coerceAtLeast(0)
            delay(1_000)
        }
    }
    SwitchRow(
        title = stringResource(R.string.sh_pair_nearby),
        checked = until != null,
        onCheckedChange = onChange,
        summary =
            if (until == null) {
                stringResource(R.string.sh_pair_nearby_off)
            } else {
                val s = left ?: 0
                stringResource(R.string.sh_pair_nearby_on, "%d:%02d".format(s / 60, s % 60))
            },
    )
}
