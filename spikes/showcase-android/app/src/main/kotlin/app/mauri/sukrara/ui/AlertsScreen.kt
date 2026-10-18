// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.VibrationAttributes
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.alerts.AlertHaptics
import app.mauri.sukrara.alerts.AlertNote
import app.mauri.sukrara.alerts.AlertNotifier
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSounds
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.alerts.KindCfg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Glucose alerts settings (investigation 15, spikes/alerts/SPEC.md), built like the stock Settings pages:
 *  - Alerts: a main switch, then one row per alert as AOSP's PrimarySwitchPreference (SPA TwoTargetSwitchPreference):
 *    the switch turns the alert on or off, the rest of the row opens the alert's own page. "If a setting has related
 *    settings, show them on a separate screen" (settings guidelines), like Settings → Notifications → an app → a
 *    category, which is a page with its own main switch, not a dialog.
 *  - One alert ([AlertKindScreen]): main switch, when it alerts (threshold, reminders), how it sounds (sound,
 *    volume), and testing it. Followers who mirror the wearer's alerts see the wearer's rules read-only.
 * Number choices offer presets and a custom value (SettingsDialogs.kt). Every ▶ plays the alert's vibration with its
 * sound unless "Vibrate" is unticked.
 */

/** Route of one alert's page: `alert/<kind>`. */
const val ALERT_ROUTE = "alert/"

/**
 * Sound tests: the sound (AlertSounds.preview, on the alert's stream) and, unless the "Vibrate" check box is off,
 * the alert's own vibration (AlertHaptics, the effect its notification channel plays) at the same time. The check
 * box is a UI preference of this phone, on by default.
 */
private object TestPlayback {
    private const val PREFS = "settings-ui"
    private const val KEY = "testVibrate"
    private val _vibrate = MutableStateFlow(true)
    val vibrate: StateFlow<Boolean> = _vibrate
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        _vibrate.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }

    fun setVibrate(ctx: Context, on: Boolean) {
        _vibrate.value = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    fun play(ctx: Context, kind: String, pack: String, alarm: Boolean) {
        AlertSounds.preview(ctx, kind, pack, alarm)
        if (!_vibrate.value) return
        val v = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        v.cancel()
        v.vibrate(AlertHaptics.effect(ctx, kind),
            VibrationAttributes.createForUsage(if (alarm) VibrationAttributes.USAGE_ALARM else VibrationAttributes.USAGE_NOTIFICATION))
    }

    fun stop(ctx: Context) {
        AlertSounds.stopPreview()
        ctx.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
    }
}

/** "< 65 mg/dL", "≥ 185 mg/dL", "faster than −2.0 mg/dL/min", "after 20 min". */
@Composable
private fun thresholdText(k: String, v: Double): String = thresholdText0(LocalContext.current, k, v)

@Composable
private fun repeatText(m: Int) = if (m == 0) stringResource(R.string.alerts_remind_once) else stringResource(R.string.alerts_remind_every, m)

@Composable
private fun soundName(p: String) = soundName(LocalContext.current, p)

/** The alert's sound in words: the pack's or its own, or "set in system settings" when the channel's was changed there. */
@Composable
private fun soundSummary(k: String, s: AlertSettings): String {
    val ctx = LocalContext.current
    val c = s.cfg(k)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    val custom = nm.getNotificationChannel(AlertNotifier.channelId(k, c.alarm))?.sound?.let { it != AlertSounds.uri(ctx, k) } == true
    return if (custom) stringResource(R.string.alerts_sound_system_settings) else soundName(s.soundOf(k))
}

/** The rule can't be changed here: a follower mirroring the wearer's alerts (no data is checked on each phone). */
private fun mirrored(follower: Boolean, s: AlertSettings, k: String) = follower && s.source == "wearer" && k != Kind.NO_DATA

@Composable
fun AlertsScreen(follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Alerts.init(ctx); TestPlayback.init(ctx) }
    val s by Alerts.settings.collectAsState()
    val state by Alerts.state.collectAsState()
    val history by Alerts.history.collectAsState()
    var pickPack by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf<String?>(null) }   // which quiet rule's minutes dialog is open
    DisposableEffect(Unit) { onDispose { TestPlayback.stop(ctx) } }
    fun set(f: (AlertSettings) -> AlertSettings) = Alerts.update(ctx, f)
    val mirror = follower && s.source == "wearer"

    SettingsPage(stringResource(R.string.alerts_title), onBack) {
        // what the rules see now, like a live preview at the top of the page
        SettingsHeader { Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val now = state.values.toList()
                Swap(now.isEmpty()) { empty ->
                    if (empty) Text(stringResource(if (s.on) R.string.alerts_now_nothing else R.string.alerts_now_off), style = MaterialTheme.typography.bodyLarge)
                    else Column {
                        now.forEach { st ->
                            Text(AlertNotifier.label(ctx, st.kind) + " · " + (st.muted?.let { stringResource(R.string.alerts_now_quiet, quietReason(ctx, it)) }
                                ?: stringResource(if (st.n > 0) R.string.alerts_now_alerted else R.string.alerts_now_alerted_recently)),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                history.take(4).forEach { Text(noteText(ctx, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } }
        SettingsMainSwitch(stringResource(R.string.alerts_main_switch), s.on) { v -> set { it.copy(on = v) } }
        if (follower) SettingsGroup {
            value(stringResource(R.string.alerts_rules), stringResource(if (s.source == "wearer") R.string.alerts_rules_wearer else R.string.alerts_rules_own),
                enabled = s.on) { pickSource = true }
        }

        // each alert: the switch turns it on or off, the row opens its page
        val wearers = stringResource(R.string.alerts_set_by_wearer)
        @Composable
        fun kinds(title: String, ks: List<String>) {
            val rows = ks.map { k ->
                val c = s.cfg(k)
                Triple(k, c, (if (mirrored(follower, s, k)) wearers else thresholdText(k, c.value) + " · " + repeatText(c.repeatMin)) +
                    " · " + soundSummary(k, s))
            }
            SettingsGroup(title) {
                for ((k, c, summary) in rows) splitSwitch(AlertNotifier.label(ctx, k), summary, checked = c.on && !mirrored(follower, s, k),
                    rowEnabled = s.on, switchEnabled = s.on && !mirrored(follower, s, k), to = ALERT_ROUTE + k) { v ->
                    set { it.copy(kinds = it.kinds + (k to it.cfg(k).copy(on = v))) }
                }
            }
        }
        kinds(stringResource(R.string.alerts_group_lows), Kind.ALL.filter { it in Kind.LOW_SIDE })
        kinds(stringResource(R.string.alerts_group_highs), Kind.ALL.filter { it in Kind.LEVELS && it !in Kind.LOW_SIDE })
        kinds(stringResource(R.string.alerts_group_rates), Kind.RATES)
        kinds(stringResource(R.string.alerts_group_signal), listOf(Kind.NO_DATA))

        val rules = s.on && !mirror
        SettingsGroup(stringResource(R.string.alerts_group_quiet)) {
            splitSwitch(stringResource(R.string.alerts_quiet_insulin), if (mirror) wearers else stringResource(R.string.alerts_quiet_insulin_summary, s.muteInsulinMin),
                checked = s.muteInsulin, rowEnabled = rules && s.muteInsulin, switchEnabled = rules, onClick = { minutes = "insulin" }) { v ->
                set { it.copy(muteInsulin = v) }
            }
            splitSwitch(stringResource(R.string.alerts_quiet_falling), if (mirror) wearers
                else stringResource(R.string.alerts_quiet_falling_summary, s.cfg(Kind.HIGH).value.toInt(), s.muteFallingMin),
                checked = s.muteFalling, rowEnabled = rules && s.muteFalling, switchEnabled = rules, onClick = { minutes = "falling" }) { v ->
                set { it.copy(muteFalling = v) }
            }
            splitSwitch(stringResource(R.string.alerts_quiet_carbs), if (mirror) wearers else stringResource(R.string.alerts_quiet_carbs_summary, s.muteCarbsMin),
                checked = s.muteCarbs, rowEnabled = rules && s.muteCarbs, switchEnabled = rules, onClick = { minutes = "carbs" }) { v ->
                set { it.copy(muteCarbs = v) }
            }
        }

        SettingsGroup(stringResource(R.string.alerts_group_sounds)) {
            value(stringResource(R.string.alerts_sound_pack), soundLabel(ctx, s.pack), enabled = s.on, icon = R.drawable.ic_sym_volume_up) { pickPack = true }
            switch(stringResource(R.string.alerts_shuffle), s.shuffle, enabled = s.on,
                summary = stringResource(R.string.alerts_shuffle_summary, AlertSounds.VARIANTS)) { v -> set { it.copy(shuffle = v) } }
            link(stringResource(R.string.alerts_notification_settings), stringResource(R.string.alerts_notification_settings_summary),
                icon = R.drawable.ic_sym_notifications, external = true) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }
        SettingsFooter(stringResource(R.string.alerts_footer))
    }

    val min = stringResource(R.string.unit_min)
    val minLabel: (Int) -> String = { stringResource0(ctx, R.string.alerts_minutes, it) }
    when (minutes) {
        "insulin" -> IntChoiceDialog(stringResource(R.string.alerts_quiet_insulin), listOf(30, 45, 60, 90, 120, 180), s.muteInsulinMin, minLabel,
            NumberRange(10.0, 360.0, min), { minutes = null }) { m -> set { it.copy(muteInsulinMin = m) } }
        "falling" -> IntChoiceDialog(stringResource(R.string.alerts_quiet_falling), listOf(10, 15, 20, 30, 45, 60), s.muteFallingMin,
            { stringResource0(ctx, R.string.alerts_within_minutes, it) }, NumberRange(5.0, 120.0, min), { minutes = null }) { m -> set { it.copy(muteFallingMin = m) } }
        "carbs" -> IntChoiceDialog(stringResource(R.string.alerts_quiet_carbs), listOf(10, 15, 20, 30, 45), s.muteCarbsMin, minLabel,
            NumberRange(5.0, 120.0, min), { minutes = null }) { m -> set { it.copy(muteCarbsMin = m) } }
    }
    if (pickPack) SoundDialog(stringResource(R.string.alerts_sound_pack), Kind.HIGH, null, s.pack, s.cfg(Kind.HIGH).alarm,
        onClose = { pickPack = false }) { p -> set { it.copy(pack = p ?: it.pack) } }
    if (pickSource) ChoiceDialog(stringResource(R.string.alerts_rules), listOf(
            Choice(stringResource(R.string.alerts_rules_wearer), "wearer", stringResource(R.string.alerts_rules_wearer_summary)),
            Choice(stringResource(R.string.alerts_rules_own), "own", stringResource(R.string.alerts_rules_own_summary))),
        s.source, { pickSource = false }) { v -> set { it.copy(source = v) } }
}

/**
 * Why an alert is quiet now, in the app's language. The engine (pure Kotlin, shared timelines) words it in English:
 * "insulin 12 min ago", "carbs 5 min ago", "falling, back in range in ~8 min"; anything else shows as it is.
 */
private fun quietReason(ctx: Context, m: String): String {
    Regex("^(insulin|carbs) (\\d+) min ago$").find(m)?.let { r ->
        val min = r.groupValues[2].toInt()
        return ctx.getString(if (r.groupValues[1] == "insulin") R.string.alerts_quiet_reason_insulin else R.string.alerts_quiet_reason_carbs, min)
    }
    Regex("^falling, back in range in ~(\\d+) min$").find(m)?.let { r -> return ctx.getString(R.string.alerts_quiet_reason_falling, r.groupValues[1].toInt()) }
    return m
}

/** One line of the "lately" list: "14:05  Low glucose · eat more (2) · 62 mg/dL". */
private fun noteText(ctx: Context, n: AlertNote): String {
    val k = n.kinds.firstOrNull() ?: ""
    val text = when (n.what) {
        AlertNote.FIRED -> ctx.getString(if (n.remote) R.string.alerts_note_fired_remote else R.string.alerts_note_fired,
            AlertNotifier.headline(ctx, k, n.treated) + if (n.n > 1) " (${n.n})" else "", n.sgv?.toString() ?: "–")
        AlertNote.CLEARED -> ctx.getString(if (n.remote) R.string.alerts_note_cleared_remote else R.string.alerts_note_cleared, AlertNotifier.label(ctx, k))
        else -> ctx.getString(R.string.alerts_note_resumed, n.kinds.joinToString { AlertNotifier.label(ctx, it) })
    }
    val t = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(n.at * 1000))
    return "$t  " + if (n.test) ctx.getString(R.string.alerts_note_test, text) else text
}

/** A plain string resource with one argument, for labels built outside composition (dialog option lambdas). */
private fun stringResource0(ctx: Context, id: Int, arg: Any) = ctx.getString(id, arg)

/**
 * Pick a sound: the radio list with a ▶ per sound that plays it with the alert's vibration, and the "Vibrate" check
 * box under them. [packDefault] non-null adds "Pack default (…)" as the first option (value null).
 */
@Composable
private fun SoundDialog(title: String, kind: String, packDefault: String?, selected: String?, alarm: Boolean,
                        onClose: () -> Unit, onPick: (String?) -> Unit) {
    val ctx = LocalContext.current
    val vibrate by TestPlayback.vibrate.collectAsState()
    val options = (if (packDefault != null) listOf<String?>(null) else emptyList()) + AlertSounds.PACKS + AlertSounds.SYSTEM + AlertSounds.NONE
    val play = stringResource(R.string.alerts_play)
    ChoiceDialog(title, options.map { o ->
        val p = o ?: packDefault!!
        Choice(if (o == null) stringResource(R.string.alerts_sound_pack_default, soundLabel(ctx, packDefault!!)) else soundLabel(ctx, o), o,
            trailing = if (p == AlertSounds.NONE) null else ({
                IconButton(onClick = { TestPlayback.play(ctx, kind, p, alarm) }) { Icon(painterResource(R.drawable.ic_sym_play_arrow), contentDescription = play) }
            }))
    }, selected, onClose = { TestPlayback.stop(ctx); onClose() }, closeOnChoose = false,
        bottom = { DialogCheckbox(stringResource(R.string.alerts_vibrate), vibrate) { TestPlayback.setVibrate(ctx, it) } }) { onPick(it) }
}

/** Five common thresholds around the kind's default (which is always one of them). */
private fun thresholdPresets(k: String): List<Double> {
    val d = AlertSettings.DEFAULTS.getValue(k).value
    val common = when (k) {
        in Kind.LOW_SIDE -> listOf(50.0, 54.0, 55.0, 60.0, 65.0, 70.0, 75.0, 80.0)
        in Kind.RATES -> listOf(1.0, 1.5, 2.0, 2.5, 3.0)
        Kind.NO_DATA -> listOf(10.0, 15.0, 20.0, 30.0, 60.0)
        else -> listOf(160.0, 170.0, 180.0, 185.0, 200.0, 220.0, 250.0, 270.0, 300.0, 350.0)
    }
    return (common + d).distinct().sortedBy { kotlin.math.abs(it - d) }.take(5).sorted()
}

private fun thresholdRange(k: String, unit: String) = when (k) {
    in Kind.RATES -> NumberRange(0.5, 10.0, unit, decimals = 1)
    Kind.NO_DATA -> NumberRange(5.0, 240.0, unit)
    else -> NumberRange(40.0, 400.0, unit)
}

/**
 * One alert's page. Main switch (the alert on or off), then "Alert when" (threshold, reminders), "Sound" (sound,
 * volume) and "Test". A follower mirroring the wearer's rules sees the rule read-only with the reason; sound and
 * volume are always this phone's.
 */
@Composable
fun AlertKindScreen(k: String, follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Alerts.init(ctx); TestPlayback.init(ctx) }
    if (k !in Kind.ALL) return
    val s by Alerts.settings.collectAsState()
    val vibrate by TestPlayback.vibrate.collectAsState()
    val c = s.cfg(k)
    var dialog by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { TestPlayback.stop(ctx) } }
    fun change(f: (KindCfg) -> KindCfg) = Alerts.update(ctx) { it.copy(kinds = it.kinds + (k to f(it.cfg(k)))) }
    val locked = mirrored(follower, s, k)
    val ruleOn = s.on && !locked
    val wearers = stringResource(R.string.alerts_set_by_wearer)
    val off = stringResource(if (!s.on) R.string.alerts_unavailable_all_off else R.string.alerts_unavailable_off)
    val unit = Kind.unit(k)
    val volume = stringResource(if (c.alarm) R.string.alerts_volume_alarm else R.string.alerts_volume_notification)

    SettingsPage(AlertNotifier.label(ctx, k), onBack) {
        SettingsMainSwitch(stringResource(R.string.alerts_kind_main_switch, AlertNotifier.label(ctx, k)), c.on && !locked, enabled = ruleOn) { v -> change { it.copy(on = v) } }
        val on = c.on && s.on
        SettingsGroup(stringResource(R.string.alerts_group_when)) {
            value(stringResource(if (k == Kind.NO_DATA) R.string.alerts_after else if (k in Kind.RATES) R.string.alerts_rate else R.string.alerts_threshold),
                if (locked) "$wearers · ${thresholdText(k, c.value)}" else if (!on) off else thresholdText(k, c.value),
                enabled = on && !locked) { dialog = "threshold" }
            value(stringResource(R.string.alerts_remind), if (locked) "$wearers · ${repeatText(c.repeatMin)}" else if (!on) off else repeatText(c.repeatMin),
                enabled = on && !locked) { dialog = "repeat" }
        }
        SettingsGroup(stringResource(R.string.alerts_group_sound)) {
            value(stringResource(R.string.alerts_sound), if (!s.on) off else soundSummary(k, s), enabled = s.on) { dialog = "sound" }
            value(stringResource(R.string.alerts_volume), if (!s.on) off else volume, enabled = s.on) { dialog = "volume" }
        }
        SettingsGroup(stringResource(R.string.alerts_group_test)) {
            action(stringResource(R.string.alerts_play_sound), stringResource(if (vibrate) R.string.alerts_play_sound_vibrate else R.string.alerts_play_sound_only,
                soundName(s.soundOf(k)), volume), enabled = s.soundOf(k) != AlertSounds.NONE || vibrate) {
                TestPlayback.play(ctx, k, s.soundOf(k), c.alarm)
            }
            checkbox(stringResource(R.string.alerts_vibrate), vibrate, summary = stringResource(R.string.alerts_vibrate_summary)) { TestPlayback.setVibrate(ctx, it) }
            action(stringResource(R.string.alerts_test), stringResource(R.string.alerts_test_summary)) {
                Alerts.test(ctx, k, share = false)
            }
            link(stringResource(R.string.alerts_channel_settings), stringResource(R.string.alerts_channel_settings_summary), external = true) {
                ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AlertNotifier.channelId(k, c.alarm)))
            }
        }
        SettingsFooter(stringResource(if (locked) R.string.alerts_kind_footer_follower else R.string.alerts_kind_footer))
    }

    when (dialog) {
        "threshold" -> NumberChoiceDialog(stringResource(if (k == Kind.NO_DATA) R.string.alerts_after else if (k in Kind.RATES) R.string.alerts_rate else R.string.alerts_threshold),
            thresholdPresets(k), c.value, { v -> thresholdText0(ctx, k, v) }, thresholdRange(k, unit), { dialog = null }) { v -> change { it.copy(value = v) } }
        "repeat" -> IntChoiceDialog(stringResource(R.string.alerts_remind), listOf(0, 5, 10, 15, 30, 60, 120), c.repeatMin,
            { m -> if (m == 0) ctx.getString(R.string.alerts_remind_once) else ctx.getString(R.string.alerts_remind_every, m) },
            NumberRange(1.0, 240.0, stringResource(R.string.unit_min)), { dialog = null }) { m -> change { it.copy(repeatMin = m) } }
        "sound" -> SoundDialog(stringResource(R.string.alerts_sound), k, s.pack, s.packFor[k], c.alarm, onClose = { dialog = null }) { snd ->
            Alerts.update(ctx) { it.copy(packFor = if (snd == null) it.packFor - k else it.packFor + (k to snd)) }
        }
        "volume" -> ChoiceDialog(stringResource(R.string.alerts_volume), listOf(
                Choice(stringResource(R.string.alerts_volume_alarm), true, stringResource(R.string.alerts_volume_alarm_summary)),
                Choice(stringResource(R.string.alerts_volume_notification), false, stringResource(R.string.alerts_volume_notification_summary))),
            c.alarm, { dialog = null }) { a -> change { it.copy(alarm = a) } }
    }
}

/** [thresholdText] outside composition too (the dialog's option labels). */
private fun thresholdText0(ctx: Context, k: String, v: Double): String = when (k) {
    in Kind.LOW_SIDE -> ctx.getString(R.string.alerts_threshold_below, v.toInt())
    Kind.FALLING_FAST -> ctx.getString(R.string.alerts_threshold_falling, "%.1f".format(v))
    Kind.RISING_FAST -> ctx.getString(R.string.alerts_threshold_rising, "%.1f".format(v))
    Kind.NO_DATA -> ctx.getString(R.string.alerts_threshold_no_data, v.toInt())
    else -> ctx.getString(R.string.alerts_threshold_above, v.toInt())
}
