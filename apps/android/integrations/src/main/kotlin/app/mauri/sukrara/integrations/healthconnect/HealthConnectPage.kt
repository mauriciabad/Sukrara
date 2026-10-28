// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.healthconnect

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.integrations.R
import app.mauri.sukrara.integrations.ui.IntroText
import app.mauri.sukrara.integrations.ui.ago
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

data class HcUi(
    val on: Boolean,
    val status: HcStatus,
    /** Health Connect's sheet said no (the last time it was asked from this page). */
    val denied: Boolean = false,
)

class HcActions(
    /** The switch: on asks for the permission first (the page launches the sheet), off stops writing. */
    val setOn: (Boolean) -> Unit = {},
    val openHealthConnect: () -> Unit = {},
)

class HealthConnectViewModel(
    private val sync: HealthConnectSync,
    private val health: HealthStore,
) : ViewModel() {
    private val denied = MutableStateFlow(false)

    val ui: StateFlow<HcUi?> =
        combine(sync.on, sync.status, denied) { on, st, d -> HcUi(on, st, d) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun granted(): Boolean = runCatching { health.granted() }.getOrDefault(false)

    fun recheck() = sync.recheck()

    /** After the permission sheet: on when granted, otherwise the switch stays off and the page says why. */
    fun onPermission(granted: Boolean) {
        denied.value = !granted
        viewModelScope.launch {
            runCatching { if (granted) sync.setOn(true) else sync.recheck() }
        }
    }

    fun setOff() {
        denied.value = false
        viewModelScope.launch { runCatching { sync.setOn(false) } }
    }
}

@Composable
fun HealthConnectPage(
    viewModel: HealthConnectViewModel,
    now: () -> Instant,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 14+ routes a health permission request to Health Connect's own sheet
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            viewModel.onPermission(result[PlatformHealth.PERMISSION] == true)
        }
    LifecycleResumeEffect(viewModel) {
        // allowed or removed in Health Connect meanwhile
        viewModel.recheck()
        onPauseOrDispose {}
    }
    ui?.let {
        HealthConnectContent(
            it,
            now(),
            HcActions(
                setOn = { on ->
                    when {
                        !on -> viewModel.setOff()
                        viewModel.granted() -> viewModel.onPermission(true)
                        else -> ask.launch(arrayOf(PlatformHealth.PERMISSION))
                    }
                },
                openHealthConnect = {
                    try {
                        context.startActivity(PlatformHealth.manageIntent(context))
                    } catch (e: ActivityNotFoundException) {
                        // Health Connect missing or disabled: the status line already says so
                    }
                },
            ),
            modifier,
        )
    }
}

/**
 * Settings › Share with apps › *Health Connect*: the switch (asks Health Connect's permission sheet when turned on),
 * what was last written or why nothing is, and the ways out: *Open Health Connect* to allow again or to see and delete
 * what was written, and the switch to stop (records already written stay).
 */
@Composable
fun HealthConnectContent(
    ui: HcUi,
    now: Instant,
    actions: HcActions,
    modifier: Modifier = Modifier,
) = Column(modifier) {
    IntroText(stringResource(R.string.in_hc_intro))
    if (ui.status == HcStatus.Follower || ui.status == HcStatus.Unavailable) {
        val why = if (ui.status == HcStatus.Follower) R.string.in_hc_follower else R.string.in_hc_unavailable
        SettingsGroup {
            InfoRow(stringResource(why))
            // left on from before (another role, Health Connect turned off since): it can still be turned off here
            if (ui.on) SwitchRow(stringResource(R.string.in_hc_switch), true, { actions.setOn(false) })
        }
        return@Column
    }
    SettingsGroup {
        SwitchRow(
            stringResource(R.string.in_hc_switch),
            ui.on,
            actions.setOn,
            summary = if (ui.denied && !ui.on) stringResource(R.string.in_hc_denied) else healthStatusText(ui.status, now),
        )
        NavigationRow(
            stringResource(R.string.in_hc_open),
            actions.openHealthConnect,
            summary =
                stringResource(
                    when {
                        ui.status == HcStatus.NeedsPermission || ui.denied -> R.string.in_hc_open_allow
                        !ui.on -> R.string.in_hc_open_off
                        else -> R.string.in_hc_open_summary
                    },
                ),
        )
    }
    FooterText(stringResource(R.string.in_hc_footer))
}

/** The status line on the page and on Share with apps. */
@Composable
fun healthStatusText(
    status: HcStatus,
    now: Instant,
): String =
    when (status) {
        HcStatus.Off -> {
            stringResource(R.string.in_hc_off)
        }

        HcStatus.Unavailable -> {
            stringResource(R.string.in_hc_unavailable_short)
        }

        HcStatus.NeedsPermission -> {
            stringResource(R.string.in_hc_needs_permission)
        }

        HcStatus.Follower -> {
            stringResource(R.string.in_hc_follower_short)
        }

        HcStatus.Waiting -> {
            stringResource(R.string.in_hc_waiting)
        }

        is HcStatus.Written -> {
            stringResource(R.string.in_hc_written, ago(status.at, now))
        }

        is HcStatus.Failed -> {
            if (status.code == HealthConnectSync.ERROR_RATE_LIMIT) {
                stringResource(R.string.in_hc_rate_limited, ago(status.at, now))
            } else {
                stringResource(R.string.in_hc_failed, status.code)
            }
        }
    }
