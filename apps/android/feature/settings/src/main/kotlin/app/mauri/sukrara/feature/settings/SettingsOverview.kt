// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup

/** Where the overview's rows lead. */
class OverviewActions(
    val open: (SettingsRoute) -> Unit = {},
    val openSensor: () -> Unit = {},
    val openSharing: () -> Unit = {},
    val unmuteAll: () -> Unit = {},
    /** Settings › Insulin pens is offered (the wearer's phone with NFC). */
    val pens: Boolean = false,
)

/** The settings overview (wearer): Mute all's state with Unmute, then one row per page. */
@Composable
fun SettingsOverviewContent(
    alertsOn: Boolean,
    alerts: AlertUiState,
    readiness: AlertReadiness,
    clock: Clock,
    actions: OverviewActions,
    onBack: () -> Unit,
) {
    SettingsPage(stringResource(R.string.st_settings_title), onBack) {
        MuteAllStrip(alerts, clock, actions.unmuteAll) { actions.open(SettingsRoute.Alerts) }
        SettingsGroup {
            NavigationRow(
                stringResource(R.string.st_alerts_title),
                { actions.open(SettingsRoute.Alerts) },
                summary = stringResource(if (alertsOn) R.string.st_overview_alerts_on else R.string.st_overview_alerts_off),
            )
            NavigationRow(
                stringResource(R.string.st_reliability_title),
                { actions.open(SettingsRoute.Reliability) },
                summary =
                    readiness.firstProblem?.let { stringResource(R.string.st_overview_reliability_problem, readiness.missing.size) }
                        ?: stringResource(R.string.st_reliability_ready),
            )
        }
        SettingsGroup {
            NavigationRow(stringResource(R.string.st_ranges_title), {
                actions.open(SettingsRoute.Ranges)
            }, summary = stringResource(R.string.st_overview_ranges))
            NavigationRow(stringResource(R.string.st_graph_title), {
                actions.open(SettingsRoute.Graph)
            }, summary = stringResource(R.string.st_overview_graph))
        }
        SettingsGroup {
            NavigationRow(stringResource(R.string.st_statusbar_title), {
                actions.open(SettingsRoute.StatusBar)
            }, summary = stringResource(R.string.st_overview_statusbar))
            NavigationRow(stringResource(R.string.st_lock_title), {
                actions.open(SettingsRoute.LockScreen)
            }, summary = stringResource(R.string.st_overview_lock))
        }
        SettingsGroup {
            NavigationRow(stringResource(R.string.st_sensor), actions.openSensor, summary = stringResource(R.string.st_sensor_summary))
            NavigationRow(stringResource(R.string.st_sensorend_title), {
                actions.open(SettingsRoute.SensorEnd)
            }, summary = stringResource(R.string.st_overview_sensorend))
            NavigationRow(stringResource(R.string.st_sharing), actions.openSharing, summary = stringResource(R.string.st_sharing_summary))
            if (actions.pens) {
                NavigationRow(stringResource(R.string.st_pens_title), {
                    actions.open(SettingsRoute.Pens)
                }, summary = stringResource(R.string.st_overview_pens))
            }
        }
        SettingsGroup {
            NavigationRow(stringResource(R.string.st_about_title), { actions.open(SettingsRoute.About) })
        }
    }
}
