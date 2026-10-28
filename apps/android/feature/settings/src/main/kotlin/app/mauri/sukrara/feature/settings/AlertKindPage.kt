// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
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
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.settings.SettingsSpec

/** What an alert's page can do besides editing its rule. */
class AlertKindActions(
    val edit: ((app.mauri.sukrara.alerts.AlertSettings) -> app.mauri.sukrara.alerts.AlertSettings) -> Unit = {},
    val unmute: (AlertKind) -> Unit = {},
    val remindAsUsual: (AlertKind) -> Unit = {},
)

/** Turning these off removes a safety net: it asks first (ux §2 #9). */
private val ConfirmOff = setOf(AlertKind.VERY_LOW, AlertKind.NO_DATA)

/**
 * The side's quiet state with its exit, worded by [MuteText] and counting down: "Low alerts muted · 12 min left ·
 * Unmute", "Low alerts muted after carbs · 12 min left · Alert me anyway".
 */
@Composable
fun QuietStrip(
    kind: AlertKind,
    quiet: AlertQuietSide,
    clock: Clock,
    actions: AlertKindActions,
) {
    val words = rememberAlertWords()
    val now = rememberCountdownNow(quiet.until, clock::now)
    val text = MuteText.side(quiet, now, words) ?: return
    val exit =
        when (quiet.reason) {
            AlertQuietReason.Muted -> stringResource(R.string.st_unmute) to { actions.unmute(kind) }
            null -> stringResource(R.string.st_remind_as_usual) to { actions.remindAsUsual(kind) }
            else -> stringResource(R.string.st_alert_anyway) to { actions.unmute(kind) }
        }
    StateStrip(text, listOf(exit))
}

/** One alert's page: on, when it alerts, how often it reminds, like an alarm, its sound. */
@Composable
fun AlertKindContent(
    kind: AlertKind,
    ui: AlertsUi,
    clock: Clock,
    actions: AlertKindActions,
    onBack: () -> Unit,
) {
    val rule = ui.settings.rule(kind)
    val context = LocalContext.current
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val thresholdSpec = SchemaPrefs.thresholdSpec(kind)
    val repeatSpec = SchemaPrefs.RepeatSpec
    val label = numberLabeler()
    SettingsPage(kindName(kind), onBack) {
        ui.quietOf(kind)?.let { QuietStrip(kind, it, clock, actions) }
        SettingsGroup {
            SwitchRow(
                stringResource(R.string.st_kind_use, kindName(kind)),
                rule.on,
                { on -> if (!on && kind in ConfirmOff) dialog = "off" else actions.edit(AlertEdits.kindOn(kind, on)) },
                enabled = ui.settings.on,
                summary = if (ui.settings.on) null else stringResource(R.string.st_alerts_main_off),
            )
        }
        SettingsGroup(title = stringResource(R.string.st_alert_when)) {
            ValueRow(stringResource(R.string.st_threshold), thresholdSentence(kind, rule.threshold), { dialog = "threshold" }, enabled = rule.on)
            ValueRow(stringResource(R.string.st_repeat), numberLabel(repeatSpec, rule.repeatMin.toDouble()), { dialog = "repeat" }, enabled = rule.on)
        }
        SettingsGroup(title = stringResource(R.string.st_sound_title)) {
            SwitchRow(
                stringResource(R.string.st_alarm),
                rule.alarm,
                { on -> if (!on && kind in ConfirmOff) dialog = "alarmOff" else actions.edit(AlertEdits.alarm(kind, on)) },
                summary = stringResource(R.string.st_alarm_summary),
                enabled = rule.on,
            )
            val own = ui.settings.sounds.perKind[kind]
            ValueRow(
                stringResource(R.string.st_sound),
                own?.let { packName(it) } ?: stringResource(R.string.st_sound_same, packName(ui.settings.sounds.pack)),
                { dialog = "sound" },
                enabled = rule.on,
            )
            NavigationRow(
                stringResource(R.string.st_notification_settings),
                { context.openNotificationSettings() },
                summary = stringResource(R.string.st_notification_settings_summary),
            )
        }
        FooterText(stringResource(if (kind.isLevel) R.string.st_kind_footer_level else R.string.st_kind_footer_other))
    }
    when (dialog) {
        "off" ->
            ConfirmDialog(
                stringResource(R.string.st_kind_off_title, kindName(kind)),
                stringResource(if (kind == AlertKind.NO_DATA) R.string.st_kind_off_text_no_data else R.string.st_kind_off_text_very_low),
                stringResource(R.string.st_turn_off),
                { dialog = null },
            ) { actions.edit(AlertEdits.kindOn(kind, false)) }

        "alarmOff" ->
            ConfirmDialog(
                stringResource(R.string.st_alarm_off_title, kindName(kind)),
                stringResource(R.string.st_alarm_off_text),
                stringResource(R.string.st_turn_off),
                { dialog = null },
            ) { actions.edit(AlertEdits.alarm(kind, false)) }

        "threshold" ->
            NumberDialog(stringResource(R.string.st_threshold), thresholdInput(ui.settings, kind), rule.threshold, { label(thresholdSpec, it) }, { dialog = null }) {
                actions.edit(AlertEdits.threshold(kind, it))
            }

        "repeat" ->
            NumberDialog(stringResource(R.string.st_repeat), NumberInput(repeatSpec), rule.repeatMin.toDouble(), { label(repeatSpec, it) }, { dialog = null }) {
                actions.edit(AlertEdits.repeat(kind, it))
            }

        "sound" -> {
            val same = stringResource(R.string.st_sound_same, packName(ui.settings.sounds.pack))
            val options = listOf(Choice<String?>(same, null)) + SettingsSpec.OPTIONS.getValue("soundPack").map { Choice<String?>(packName(it), it) }
            ChoiceDialog(stringResource(R.string.st_sound), options, ui.settings.sounds.perKind[kind], { dialog = null }) {
                actions.edit(AlertEdits.sound(kind, it))
            }
        }
    }
}
