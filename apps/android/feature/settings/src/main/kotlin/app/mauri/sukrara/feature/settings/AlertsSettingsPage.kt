// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.designsystem.alerts.MuteText
import app.mauri.sukrara.designsystem.alerts.rememberAlertWords
import app.mauri.sukrara.designsystem.alerts.rememberCountdownNow
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SplitSwitchRow
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.settings.SettingsSpec
import java.time.Instant

/** What the Alerts page can do (the ViewModel's actions; no-ops in screenshots). */
class AlertsActions(
    val edit: ((AlertSettings) -> AlertSettings) -> Unit = {},
    val muteAll: (Double) -> Unit = {},
    val unmuteAll: () -> Unit = {},
    val openKind: (AlertKind) -> Unit = {},
    val openReview: () -> Unit = {},
    val playback: AlertPlayback = AlertPlayback.None,
)

/** The alert rows' groups: lows, highs, rates, signal. */
val KindGroups =
    listOf(
        R.string.st_group_lows to listOf(AlertKind.VERY_LOW, AlertKind.LOW),
        R.string.st_group_highs to listOf(AlertKind.HIGH, AlertKind.VERY_HIGH, AlertKind.EXTREMELY_HIGH),
        R.string.st_group_rates to listOf(AlertKind.FALLING_FAST, AlertKind.RISING_FAST),
        R.string.st_group_signal to listOf(AlertKind.NO_DATA),
    )

/** "Below 70 mg/dL · every 5 min · Quest": when it alerts, how often it reminds, which sound. */
@Composable
fun kindSummary(
    s: AlertSettings,
    kind: AlertKind,
): String {
    val rule = s.rule(kind)
    val repeat =
        if (rule.repeatMin <= 0) {
            numberLabel(SchemaPrefs.RepeatSpec, 0.0)
        } else {
            stringResource(R.string.st_repeat_every, numberLabel(SchemaPrefs.RepeatSpec, rule.repeatMin.toDouble()))
        }
    return listOf(thresholdSentence(kind, rule.threshold), repeat, packName(s.sounds.soundOf(kind))).joinToString(" · ")
}

/** Mute all's "Alerts muted · 1 h 12 min left · Unmute · Change time" strip ([MuteText], counting down), or nothing. */
@Composable
fun MuteAllStrip(
    alerts: AlertUiState,
    clock: Clock,
    onUnmute: () -> Unit,
    onChangeTime: () -> Unit,
) {
    val words = rememberAlertWords()
    val now = rememberCountdownNow(alerts.mutedUntil, clock::now)
    val text = MuteText.muteAll(alerts, now, words) ?: return
    StateStrip(text, listOf(stringResource(R.string.st_unmute) to onUnmute, stringResource(R.string.st_change_time) to onChangeTime))
}

/** The Alerts page: main switch, Mute all, each alert, quiet rules, notification actions, sounds, threshold review. */
@Composable
fun AlertsSettingsContent(
    ui: AlertsUi,
    confirmedAt: Instant?,
    clock: Clock,
    actions: AlertsActions,
    onBack: () -> Unit,
) {
    val s = ui.settings
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    SettingsPage(stringResource(R.string.st_alerts_title), onBack) {
        MuteAllStrip(ui.alerts, clock, actions.unmuteAll) { dialog = "muteAll" }
        SettingsGroup {
            SwitchRow(
                stringResource(R.string.st_alerts_main),
                s.on,
                { on -> if (on) actions.edit { it.copy(on = true) } else dialog = "alertsOff" },
                summary = stringResource(if (s.on) R.string.st_alerts_main_on else R.string.st_alerts_main_off),
            )
            ValueRow(
                stringResource(R.string.st_mute_all),
                ui.alerts.mutedUntil?.let {
                    if (ui.alerts.mutedUntilTurnedOff) {
                        stringResource(
                            R.string.st_special_until_turned_off,
                        )
                    } else {
                        stringResource(R.string.st_until, clockTime(it, clock.zone))
                    }
                } ?: stringResource(R.string.st_not_muted),
                { dialog = "muteAll" },
            )
        }
        AlertsLately(ui.alerts.lately, clock)
        // each alert: the row opens its page, the switch turns it on or off (very low / no data off asks first)
        for ((title, kinds) in KindGroups) {
            SettingsGroup(title = stringResource(title)) {
                for (kind in kinds) {
                    val rule = s.rule(kind)
                    SplitSwitchRow(
                        kindName(kind),
                        rule.on,
                        { on ->
                            if (!on &&
                                kind in ConfirmOff
                            ) {
                                dialog = "kindOff:${kind.wire}"
                            } else {
                                actions.edit(AlertEdits.kindOn(kind, on))
                            }
                        },
                        { actions.openKind(kind) },
                        summary = if (rule.on) kindSummary(s, kind) else stringResource(R.string.st_off),
                        enabled = s.on,
                    )
                }
            }
        }
        SettingsGroup {
            NavigationRow(
                stringResource(R.string.st_review_title),
                actions.openReview,
                summary =
                    confirmedAt?.let { stringResource(R.string.st_review_confirmed) } ?: stringResource(R.string.st_review_not_confirmed),
            )
        }
        SettingsGroup(title = stringResource(R.string.st_quiet_title)) {
            for (which in QuietKind.entries) {
                val rule = AlertEdits.quietRule(s, which)
                val spec = SchemaPrefs.spec(AlertEdits.quietSpecId(which))
                SwitchRow(
                    stringResource(
                        when (which) {
                            QuietKind.AfterInsulin -> R.string.st_quiet_insulin
                            QuietKind.AfterCarbs -> R.string.st_quiet_carbs
                            QuietKind.FallingHigh -> R.string.st_quiet_falling
                        },
                    ),
                    rule.on,
                    { on -> actions.edit(AlertEdits.quiet(which) { it.copy(on = on) }) },
                    summary = stringResource(R.string.st_quiet_for, numberLabel(spec, rule.min.toDouble())),
                )
                if (rule.on) {
                    ValueRow(stringResource(R.string.st_quiet_length), numberLabel(spec, rule.min.toDouble()), {
                        dialog =
                            "quiet:${which.name}"
                    })
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.st_actions_title)) {
            ValueRow(
                stringResource(
                    R.string.st_action_mute,
                ),
                numberLabel(SchemaPrefs.spec("alerts.actions.muteMin"), s.actions.muteMin.toDouble()),
                {
                    dialog = "actionMute"
                },
            )
            ValueRow(
                stringResource(
                    R.string.st_action_relax,
                ),
                numberLabel(SchemaPrefs.spec("alerts.actions.relaxMin"), s.actions.relaxMin.toDouble()),
                {
                    dialog = "actionRelax"
                },
            )
            ValueRow(
                stringResource(
                    R.string.st_action_back_mgdl,
                ),
                numberLabel(SchemaPrefs.spec("alerts.actions.backMgdl"), s.actions.backMgdl.toDouble()),
                {
                    dialog = "backMgdl"
                },
            )
            ValueRow(
                stringResource(
                    R.string.st_action_back_min,
                ),
                numberLabel(SchemaPrefs.spec("alerts.actions.backMin"), s.actions.backMin.toDouble()),
                {
                    dialog = "backMin"
                },
            )
        }
        SettingsGroup(title = stringResource(R.string.st_sounds_title)) {
            ValueRow(stringResource(R.string.st_sound_pack), packName(s.sounds.pack), { dialog = "pack" })
            SwitchRow(
                stringResource(R.string.st_shuffle),
                s.sounds.shuffle,
                { on -> actions.edit { it.copy(sounds = it.sounds.copy(shuffle = on)) } },
                summary = stringResource(R.string.st_shuffle_summary),
            )
            NavigationRow(
                stringResource(R.string.st_notification_settings),
                { context.openNotificationSettings() },
                summary = stringResource(R.string.st_notification_settings_summary),
            )
        }
        FooterText(stringResource(R.string.st_alerts_footer))
    }
    AlertsDialogs(dialog, ui, actions) { dialog = null }
}

@Composable
private fun AlertsDialogs(
    dialog: String?,
    ui: AlertsUi,
    actions: AlertsActions,
    close: () -> Unit,
) {
    val s = ui.settings
    val label = numberLabeler()
    when {
        dialog == null -> {
            Unit
        }

        dialog.startsWith("kindOff:") -> {
            val kind = AlertKind.fromWire(dialog.removePrefix("kindOff:")) ?: return close()
            ConfirmDialog(
                stringResource(R.string.st_kind_off_title, kindName(kind)),
                stringResource(if (kind == AlertKind.NO_DATA) R.string.st_kind_off_text_no_data else R.string.st_kind_off_text_very_low),
                stringResource(R.string.st_turn_off),
                close,
            ) { actions.edit(AlertEdits.kindOn(kind, false)) }
        }

        dialog == "alertsOff" -> {
            ConfirmDialog(
                stringResource(R.string.st_alerts_off_title),
                stringResource(R.string.st_alerts_off_text),
                stringResource(R.string.st_turn_off),
                close,
            ) { actions.edit { it.copy(on = false) } }
        }

        dialog == "muteAll" -> {
            val spec = SettingsSpec.MUTE_ALL
            NumberDialog(
                stringResource(R.string.st_mute_all),
                NumberInput(spec),
                spec.default,
                { label(spec, it) },
                close,
            ) { actions.muteAll(it) }
        }

        dialog.startsWith("quiet:") -> {
            val which = QuietKind.valueOf(dialog.removePrefix("quiet:"))
            val spec = SchemaPrefs.spec(AlertEdits.quietSpecId(which))
            NumberDialog(stringResource(R.string.st_quiet_length), NumberInput(spec), AlertEdits.quietRule(s, which).min.toDouble(), {
                label(spec, it)
            }, close) { v ->
                actions.edit(AlertEdits.quiet(which) { it.copy(min = spec.coerce(v).toInt()) })
            }
        }

        dialog == "actionMute" -> {
            val spec = SchemaPrefs.spec("alerts.actions.muteMin")
            NumberDialog(
                stringResource(R.string.st_action_mute),
                NumberInput(spec),
                s.actions.muteMin.toDouble(),
                { label(spec, it) },
                close,
            ) { v ->
                actions.edit { it.copy(actions = it.actions.copy(muteMin = spec.coerce(v).toInt())) }
            }
        }

        dialog == "actionRelax" -> {
            val spec = SchemaPrefs.spec("alerts.actions.relaxMin")
            // only intervals slower than every level alert's repeat (SPEC §3 relaxMin)
            val input =
                NumberInput(
                    spec,
                    presets = s.relaxChoices().map { it.toDouble() },
                    customMin = (s.relaxFloor() + 1).toDouble().coerceAtMost(spec.max),
                )
            NumberDialog(stringResource(R.string.st_action_relax), input, s.actions.relaxMin.toDouble(), { label(spec, it) }, close) { v ->
                actions.edit { it.copy(actions = it.actions.copy(relaxMin = spec.coerce(v).toInt())) }
            }
        }

        dialog == "backMgdl" -> {
            val spec = SchemaPrefs.spec("alerts.actions.backMgdl")
            NumberDialog(stringResource(R.string.st_action_back_mgdl), NumberInput(spec), s.actions.backMgdl.toDouble(), {
                label(spec, it)
            }, close) { v ->
                actions.edit { it.copy(actions = it.actions.copy(backMgdl = spec.coerce(v).toInt())) }
            }
        }

        dialog == "backMin" -> {
            val spec = SchemaPrefs.spec("alerts.actions.backMin")
            NumberDialog(stringResource(R.string.st_action_back_min), NumberInput(spec), s.actions.backMin.toDouble(), {
                label(spec, it)
            }, close) { v ->
                actions.edit { it.copy(actions = it.actions.copy(backMin = spec.coerce(v).toInt())) }
            }
        }

        dialog == "pack" -> {
            val packs = SettingsSpec.OPTIONS.getValue("soundPack")
            // a pack is heard as the low alert rings: the alert most people hear most
            val playback = actions.playback
            DisposableEffect(playback) { onDispose { playback.stop() } }
            val alarm = s.rule(AlertKind.LOW).alarm
            val options = packs.map { Choice(packName(it), it, trailing = soundPreview(AlertKind.LOW, it, alarm, playback)) }
            ChoiceDialog(stringResource(R.string.st_sound_pack), options, s.sounds.pack, close) { pack ->
                actions.edit { it.copy(sounds = it.sounds.copy(pack = pack)) }
            }
        }
    }
}

/** A confirmation for turning off something that protects the person (all alerts, the very low alarm). */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onClose()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.st_cancel)) } },
    )
}
