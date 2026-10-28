// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.statusbar

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.data.settings.HookPosition
import app.mauri.sukrara.core.model.ModuleState
import app.mauri.sukrara.core.model.SystemAccessState
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R

/** The root status bar's settings (`statusbar.hook`, `hookPosition`, `hookColor`, `hideNotifIcon`). */
data class RootSettings(
    val on: Boolean,
    val position: String,
    val color: Boolean,
    val hideIcon: Boolean,
)

/** What the root section can do; [busy] = a root / LSPosed action is running, [problem] = why the last one failed. */
class RootActions(
    val setOn: (Boolean) -> Unit,
    val setPosition: (String) -> Unit,
    val setColor: (Boolean) -> Unit,
    val setHideIcon: (Boolean) -> Unit,
    val checkRoot: () -> Unit,
    val requestScope: () -> Unit,
    val restart: () -> Unit,
)

/** A placement's name. */
@StringRes
fun placementLabel(pos: String): Int =
    when (pos) {
        HookPosition.AFTER_CLOCK -> R.string.st_root_pos_after_clock
        HookPosition.BEFORE_CLOCK -> R.string.st_root_pos_before_clock
        HookPosition.LEFT_OF_ICONS -> R.string.st_root_pos_left_of_icons
        HookPosition.SLOT_PREFIX -> R.string.st_root_pos_with_icons
        "${HookPosition.SLOT_PREFIX}vpn" -> R.string.st_root_pos_after_vpn
        "${HookPosition.SLOT_PREFIX}bluetooth" -> R.string.st_root_pos_after_bluetooth
        HookPosition.DEFAULT -> R.string.st_root_pos_before_mobile
        "${HookPosition.SLOT_PREFIX}mobile" -> R.string.st_root_pos_after_mobile
        else -> R.string.st_root_pos_after_wifi
    }

/**
 * "With root": *Value in the status bar* (its summary says what's missing: root, or the module for this placement),
 * *Colour by range*, *Placement* (dialog; placements that need the module say so, and stay choosable when they show as
 * system icons meanwhile), *Hide Sukrara's notification icon*. Above them the one step that's missing ([AccessBanner]).
 */
@Composable
fun RootStatusBarSection(
    s: RootSettings,
    access: SystemAccessState,
    busy: Boolean,
    problem: String?,
    choosing: Boolean,
    onChoosing: (Boolean) -> Unit,
    actions: RootActions,
) {
    val root = access.root == true
    val module = access.module.active
    val hookOn = root && s.on
    val placement = stringResource(placementLabel(s.position))
    val waiting = HookPosition.needsModule(s.position) && !module
    val meanwhile = HookPosition.isSystemIcons(s.position)
    val off = stringResource(R.string.st_root_unavailable)
    val needs = stringResource(R.string.st_root_module_needed)
    val placementText =
        when {
            !waiting -> placement
            meanwhile -> stringResource(R.string.st_root_needs_module_icons, placement)
            else -> stringResource(R.string.st_root_needs_module, placement)
        }
    AccessBanner(access, s.on, busy, problem, actions)
    SettingsGroup(title = stringResource(R.string.st_root_group)) {
        SwitchRow(
            stringResource(R.string.st_root_value),
            s.on,
            actions.setOn,
            summary =
                when (access.root) {
                    null -> stringResource(R.string.st_root_value_checking)
                    false -> stringResource(R.string.st_root_value_no_root)
                    true -> if (!s.on) stringResource(R.string.st_root_value_off) else placementText
                },
            // on stays switchable without root (revoked, refused), so it can always be turned off
            enabled = root || s.on,
        )
        SwitchRow(
            stringResource(R.string.st_root_colour),
            s.color,
            actions.setColor,
            summary =
                if (!hookOn) {
                    off
                } else if (!module) {
                    needs
                } else {
                    null
                },
            enabled = hookOn && module,
        )
        ValueRow(stringResource(R.string.st_root_placement), if (hookOn) placementText else off, { onChoosing(true) }, enabled = hookOn)
        SwitchRow(
            stringResource(R.string.st_root_hide_icon),
            s.hideIcon,
            actions.setHideIcon,
            summary =
                when {
                    !hookOn -> off
                    !module -> needs
                    else -> stringResource(R.string.st_root_hide_icon_summary)
                },
            enabled = hookOn && module,
        )
    }
    FooterText(stringResource(R.string.st_root_footer))
    if (choosing) PlacementDialog(s.position, module, { onChoosing(false) }, actions.setPosition)
}

/** The placement choices; without the module the clock placements are off and the slot ones say what shows meanwhile. */
@Composable
private fun PlacementDialog(
    current: String,
    module: Boolean,
    onClose: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.st_root_placement)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                for (pos in HookPosition.ALL) {
                    val full = module || !HookPosition.needsModule(pos)
                    val icons = !full && HookPosition.isSystemIcons(pos)
                    val enabled = full || icons
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(pos == current, enabled = enabled, role = Role.RadioButton) {
                                onPick(pos)
                                onClose()
                            }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pos == current, onClick = null, enabled = enabled)
                        Column(Modifier.padding(start = 16.dp)) {
                            val color =
                                if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = 0.38f,
                                    )
                                }
                            Text(stringResource(placementLabel(pos)), color = color)
                            if (!full) {
                                Text(
                                    stringResource(if (icons) R.string.st_root_pos_needs_module_icons else R.string.st_root_module_needed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.st_root_cancel)) } },
    )
}

/**
 * The one step a root feature is missing, with its fix: root for Sukrara (Check again), then the LSPosed module
 * (enable it; add System UI to its scope; restart System UI). Module steps show only while the value is on.
 */
@Composable
fun AccessBanner(
    access: SystemAccessState,
    valueOn: Boolean,
    busy: Boolean,
    problem: String?,
    actions: RootActions,
    modifier: Modifier = Modifier,
) {
    val step: Triple<Int, String, Pair<Int, () -> Unit>?>? =
        when {
            !valueOn -> {
                null
            }

            access.root == false -> {
                Triple(
                    R.string.st_access_root_title,
                    stringResource(R.string.st_access_root_text),
                    R.string.st_access_root_action to actions.checkRoot,
                )
            }

            access.root != true -> {
                null
            }

            access.module == ModuleState.Missing -> {
                Triple(R.string.st_access_module_title, stringResource(R.string.st_access_module_text), null)
            }

            access.module == ModuleState.NoScope -> {
                Triple(
                    R.string.st_access_scope_title,
                    stringResource(R.string.st_access_scope_text, access.framework ?: "LSPosed"),
                    R.string.st_access_scope_action to actions.requestScope,
                )
            }

            access.module.needsRestart -> {
                Triple(
                    R.string.st_access_restart_title,
                    stringResource(
                        if (access.module ==
                            ModuleState.Update
                        ) {
                            R.string.st_access_restart_text_updated
                        } else {
                            R.string.st_access_restart_text
                        },
                    ),
                    R.string.st_access_restart_title to actions.restart,
                )
            }

            else -> {
                null
            }
        }
    step ?: return
    SettingsGroup(modifier = modifier) {
        CardRow(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(stringResource(step.first), style = MaterialTheme.typography.titleSmall)
            Text(step.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            problem?.let {
                Text(
                    stringResource(R.string.st_access_failed, it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            step.third?.let { (label, run) ->
                Button(onClick = run, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(if (busy) R.string.st_access_restarting else label))
                }
            }
        }
    }
}
