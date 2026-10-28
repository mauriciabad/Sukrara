// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.designsystem.banner.Banner
import app.mauri.sukrara.designsystem.banner.BannerSlot
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import kotlinx.coroutines.launch
import app.mauri.sukrara.core.designsystem.R as DsR

/** Where the overview's rows lead. */
class OverviewActions(
    val open: (SettingsRoute) -> Unit = {},
    val openSensor: () -> Unit = {},
    val openSharing: () -> Unit = {},
    val openApps: () -> Unit = {},
    val unmuteAll: () -> Unit = {},
    /** *Share your data with your clinic*: Set up (the clinic upload's page) and Dismiss. */
    val openClinicUpload: () -> Unit = {},
    val dismissClinicBanner: () -> Unit = {},
    /** Undo on the "Tip closed" snackbar. */
    val restoreClinicBanner: () -> Unit = {},
    /** *Show closed tips*: home's and this page's closed tips can show again. */
    val restoreClosedTips: () -> Unit = {},
    /** Settings › Insulin pens is offered (the wearer's phone with NFC). */
    val pens: Boolean = false,
    /** The sharing profile (name and picture followers see). */
    val openProfile: () -> Unit = {},
    /** Backup of the sharing identity (password manager, recovery code). */
    val openBackup: () -> Unit = {},
    /** Export data (CSV / Nightscout JSON). */
    val openExport: () -> Unit = {},
)

/** The settings overview (wearer): *Share your data with your clinic* until set up, Mute all's state with Unmute, then one row per page. */
@Composable
fun SettingsOverviewContent(
    alertsOn: Boolean,
    alerts: AlertUiState,
    readiness: AlertReadiness,
    clock: Clock,
    actions: OverviewActions,
    appsShared: Set<AppOutput> = emptySet(),
    clinicBanner: Boolean = false,
    closedTips: Int = 0,
    sharing: OverviewSharing = OverviewSharing(),
    onBack: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val closedText = stringResource(R.string.st_tip_closed)
    val shownText = stringResource(R.string.st_tips_shown)
    val undoText = stringResource(R.string.st_undo)
    SettingsPage(stringResource(R.string.st_settings_title), onBack, snackbar = snackbar) {
        BannerSlot(
            Banner(
                "clinic",
                DsR.drawable.ds_ic_info,
                stringResource(R.string.st_clinic_title),
                stringResource(R.string.st_clinic_text),
                stringResource(R.string.st_clinic_action),
                actions.openClinicUpload,
                {
                    haptics.confirm()
                    actions.dismissClinicBanner()
                    scope.launch {
                        val result =
                            snackbar.showSnackbar(
                                closedText,
                                undoText,
                                withDismissAction = true,
                                duration = SnackbarDuration.Short,
                            )
                        if (result == SnackbarResult.ActionPerformed) actions.restoreClinicBanner()
                    }
                },
            ).takeIf { clinicBanner },
        )
        if (sharing.wearer) {
            SettingsGroup {
                NavigationRow(
                    sharing.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.st_profile_add_name),
                    actions.openProfile,
                    summary = stringResource(R.string.st_profile_summary),
                )
            }
        }
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
            NavigationRow(stringResource(R.string.st_apps), actions.openApps, summary = appsSummary(appsShared))
            if (actions.pens) {
                NavigationRow(stringResource(R.string.st_pens_title), {
                    actions.open(SettingsRoute.Pens)
                }, summary = stringResource(R.string.st_overview_pens))
            }
        }
        SettingsGroup {
            if (sharing.wearer) {
                NavigationRow(
                    stringResource(R.string.st_backup_title),
                    actions.openBackup,
                    summary =
                        stringResource(
                            when (sharing.backedUp) {
                                true -> R.string.st_backup_saved
                                false -> R.string.st_backup_not_saved
                                null -> R.string.st_backup_no_identity
                            },
                        ),
                )
            }
            NavigationRow(
                stringResource(R.string.st_export_title),
                actions.openExport,
                summary = stringResource(R.string.st_export_summary),
            )
        }
        SettingsGroup {
            if (closedTips > 0) {
                NavigationRow(
                    stringResource(R.string.st_show_closed_tips),
                    {
                        haptics.confirm()
                        actions.restoreClosedTips()
                        scope.launch { snackbar.showSnackbar(shownText, withDismissAction = true, duration = SnackbarDuration.Short) }
                    },
                    summary = pluralStringResource(R.plurals.st_closed_tips, closedTips, closedTips),
                )
            }
            NavigationRow(stringResource(R.string.st_adv_title), {
                actions.open(SettingsRoute.Advanced)
            }, summary = stringResource(R.string.st_adv_overview_summary))
            NavigationRow(stringResource(R.string.st_about_title), { actions.open(SettingsRoute.About) })
        }
    }
}

/** "LibreView · Nightscout upload · xDrip+ server", or what the page is for while nothing is on. */
@Composable
private fun appsSummary(on: Set<AppOutput>): String {
    if (on.isEmpty()) return stringResource(R.string.st_apps_summary)
    val names =
        on.sortedBy { it.ordinal }.map {
            when (it) {
                AppOutput.LibreView -> stringResource(R.string.st_apps_libreview)
                AppOutput.NightscoutUpload -> stringResource(R.string.st_apps_nightscout)
                AppOutput.XdripServer -> stringResource(R.string.st_apps_xdrip)
                AppOutput.NightscoutApi -> stringResource(R.string.st_apps_nightscout_api)
                AppOutput.Tidepool -> stringResource(R.string.st_apps_tidepool)
            }
        }
    return names.joinToString(" · ")
}
