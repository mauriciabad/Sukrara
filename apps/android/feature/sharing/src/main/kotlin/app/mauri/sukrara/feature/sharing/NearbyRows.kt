// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.share.ble.NearbyStatus

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
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onRefresh() }
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
                onClick = { ask.launch(permissions.toTypedArray()) },
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
