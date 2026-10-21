// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import app.mauri.sukrara.settings.WearerSettings

/** "Wearer" / "Default" / "Custom". */
@Composable
fun settingsFromLabel(m: String) = stringResource(when (m) {
    WearerSettings.DEFAULT -> R.string.settings_from_default
    WearerSettings.CUSTOM -> R.string.settings_from_custom
    else -> R.string.settings_from_wearer
})

/**
 * Followers (round f28): Settings from — Wearer (the default), Default or Custom — for everything a follower can
 * mirror (schema rows with "mirror": true: ranges, lines, window, rings, last treatment, sensor countdown, the alert
 * rules and quiet times). Only in Custom are those settings shown and changed; there, two actions start Custom from
 * the wearer's settings or from the defaults, each after a confirmation (it replaces what was customised). On the
 * follower's overview, Graph and Alerts pages.
 */
@Composable
fun SettingsFromGroup(actions: Boolean = true) {
    val ctx = LocalContext.current
    val mode by WearerSettings.mode.collectAsState()
    val wearer by WearerSettings.wearer.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.settings_from_title)
    SettingsGroup {
        value(title, settingsFromLabel(mode), icon = R.drawable.ic_sym_sync) { choosing = true }
        if (mode == WearerSettings.WEARER) info(wearer?.let { stringResource(R.string.settings_from_updated, app.mauri.sukrara.graph.Clock.dateTime(it.at)) }
            ?: stringResource(R.string.settings_from_waiting))
        if (actions && mode == WearerSettings.CUSTOM) {
            action(stringResource(R.string.settings_from_copy), stringResource(R.string.settings_from_copy_summary),
                icon = R.drawable.ic_sym_person, enabled = wearer != null) { confirm = WearerSettings.WEARER }
            action(stringResource(R.string.settings_from_reset), stringResource(R.string.settings_from_reset_summary),
                icon = R.drawable.ic_sym_restart_alt) { confirm = WearerSettings.DEFAULT }
        }
    }
    if (actions && mode != WearerSettings.CUSTOM) SettingsFooter(stringResource(R.string.settings_from_hidden))
    if (choosing) ChoiceDialog(title, WearerSettings.MODES.map { m ->
            Choice(settingsFromLabel(m), m, stringResource(when (m) {
                WearerSettings.DEFAULT -> R.string.settings_from_default_summary
                WearerSettings.CUSTOM -> R.string.settings_from_custom_summary
                else -> R.string.settings_from_wearer_summary
            }))
        }, mode, { choosing = false }) { WearerSettings.setMode(it) }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (what == WearerSettings.WEARER) R.string.settings_from_copy_title else R.string.settings_from_reset_title)) },
            text = { Text(stringResource(R.string.settings_from_confirm_text)) },
            confirmButton = { Button(onClick = {
                if (what == WearerSettings.WEARER) WearerSettings.copyWearerToCustom(ctx) else WearerSettings.resetCustomToDefault(ctx)
                confirm = null
            }) { Text(stringResource(R.string.settings_from_replace)) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.settings_dialog_cancel)) } },
        )
    }
}
