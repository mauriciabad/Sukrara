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
import app.mauri.sukrara.alerts.MuteCopy
import app.mauri.sukrara.alerts.KindCfg
import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.settings.WearerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Glucose alerts settings (investigation 15, spikes/alerts/SPEC.md), built like the stock Settings pages:
 *  - Alerts: a main switch, then one row per alert as AOSP's PrimarySwitchPreference (SPA TwoTargetSwitchPreference):
 *    the switch turns the alert on or off, the rest of the row opens the alert's own page. "If a setting has related
 *    settings, show them on a separate screen" (settings guidelines), like Settings → Notifications → an app → a
 *    category, which is a page with its own main switch, not a dialog.
 *  - One alert ([AlertKindScreen]): main switch, when it alerts (threshold, reminders), how it sounds (sound,
 *    volume), and testing it. Followers see the rules they don't customise (Settings from) read-only.
 * Numbers use the one number input (NumberDialog, SettingsDialogs.kt) with the schema's spec: presets and a custom value. Every ▶ plays the alert's vibration with its
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
private fun repeatText(m: Int) = if (m == 0) stringResource(R.string.alerts_remind_once) else stringResource(R.string.alerts_remind_every, Durations.text(m))

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

/**
 * The rule can't be changed here: a follower whose Settings from isn't Custom (the wearer's or the default rules; no
 * data is checked on each phone and always this phone's).
 */
private fun mirrored(follower: Boolean, from: String, k: String) = follower && from != WearerSettings.CUSTOM && k != Kind.NO_DATA

/** Who sets a rule this follower can't change. */
@Composable
private fun setBy(from: String) = stringResource(if (from == WearerSettings.DEFAULT) R.string.alerts_set_by_default else R.string.alerts_set_by_wearer)

/**
 * Each alert's icon: the level alerts are the reading (a dot) below or above one, two or three threshold lines (custom,
 * drawn on the Material Symbols grid); the rates are Material's double arrows, like a CGM's fast trend arrows.
 */
internal fun kindIcon(k: String): Int = when (k) {
    Kind.VERY_LOW -> R.drawable.ic_dia_very_low
    Kind.LOW -> R.drawable.ic_dia_low
    Kind.HIGH -> R.drawable.ic_dia_high
    Kind.VERY_HIGH -> R.drawable.ic_dia_very_high
    Kind.EXTREMELY_HIGH -> R.drawable.ic_dia_extremely_high
    Kind.FALLING_FAST -> R.drawable.ic_sym_keyboard_double_arrow_down
    Kind.RISING_FAST -> R.drawable.ic_sym_keyboard_double_arrow_up
    else -> R.drawable.ic_stat_no_readings
}

@Composable
fun AlertsScreen(follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Alerts.init(ctx); TestPlayback.init(ctx) }
    val s by Alerts.settings.collectAsState()
    val state by Alerts.state.collectAsState()
    val history by Alerts.history.collectAsState()
    val active by Alerts.active.collectAsState()
    val allMuted by Alerts.allMuted.collectAsState()
    var muteAll by remember { mutableStateOf(false) }
    var pickPack by remember { mutableStateOf(false) }
    val from by WearerSettings.mode.collectAsState()
    var minutes by remember { mutableStateOf<String?>(null) }   // which quiet rule's minutes dialog is open
    DisposableEffect(Unit) { onDispose { TestPlayback.stop(ctx) } }
    fun set(f: (AlertSettings) -> AlertSettings) = Alerts.update(ctx, f)
    val mirror = follower && from != WearerSettings.CUSTOM

    SettingsPage(stringResource(R.string.alerts_title), onBack) {
        // what the rules see now, like a live preview at the top of the page
        SettingsHeader { Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // the quiet ones are under Active now, with their ways out
                val now = state.values.filter { st -> active.none { Kind.group(it.kind) == Kind.group(st.kind) } }
                Swap(now.isEmpty()) { empty ->
                    // with something under Active now, "nothing to alert about" would read as a contradiction
                    if (empty) { if (active.isEmpty() || !s.on) Text(stringResource(if (s.on) R.string.alerts_now_nothing else R.string.alerts_now_off), style = MaterialTheme.typography.bodyLarge) }
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
        // Active now (round f29, f30): Mute all and every muted / quiet / relaxed alert on this phone with its ways out
        // (Unmute, Alert me anyway, Change time, Remind as usual), all reachable without the notification; the lines
        // tick down with the mutes (round f31)
        if (active.isNotEmpty() || allMuted != 0L) {
            val t = nowSeconds(countdowns = active.map { it.until } + allMuted)
            SettingsGroup(stringResource(R.string.alerts_active_now)) {
                if (allMuted != 0L) card { MuteAllState(allMuted, onChange = { muteAll = true }) }
                for (a in active) card { QuietRow(a, t) }
            }
        }
        SettingsMainSwitch(stringResource(R.string.alerts_main_switch), s.on) { v -> set { it.copy(on = v) } }
        if (follower) SettingsFromGroup()

        // each alert: the switch turns it on or off, the row opens its page
        val wearers = setBy(from)
        @Composable
        fun kinds(title: String, ks: List<String>) {
            val rows = ks.map { k ->
                val c = s.cfg(k)
                Triple(k, c, (if (mirrored(follower, from, k)) wearers else thresholdText(k, c.value) + " · " + repeatText(c.repeatMin)) +
                    " · " + soundSummary(k, s))
            }
            SettingsGroup(title) {
                for ((k, c, summary) in rows) splitSwitch(AlertNotifier.label(ctx, k), summary, checked = c.on && !mirrored(follower, from, k),
                    rowEnabled = s.on, switchEnabled = s.on && !mirrored(follower, from, k), icon = kindIcon(k), to = ALERT_ROUTE + k) { v ->
                    set { it.copy(kinds = it.kinds + (k to it.cfg(k).copy(on = v))) }
                }
            }
        }
        kinds(stringResource(R.string.alerts_group_lows), Kind.ALL.filter { it in Kind.LOW_SIDE })
        kinds(stringResource(R.string.alerts_group_highs), Kind.ALL.filter { it in Kind.LEVELS && it !in Kind.LOW_SIDE })
        kinds(stringResource(R.string.alerts_group_rates), Kind.RATES)
        kinds(stringResource(R.string.alerts_group_signal), listOf(Kind.NO_DATA))

        val rules = s.on
        if (!mirror) SettingsGroup(stringResource(R.string.alerts_group_quiet)) {
            splitSwitch(stringResource(R.string.alerts_quiet_insulin), stringResource(R.string.alerts_quiet_insulin_summary, Durations.text(s.muteInsulinMin)),
                checked = s.muteInsulin, rowEnabled = rules && s.muteInsulin, switchEnabled = rules, icon = R.drawable.ic_dia_insulin_rapid, onClick = { minutes = "insulin" }) { v ->
                set { it.copy(muteInsulin = v) }
            }
            splitSwitch(stringResource(R.string.alerts_quiet_falling), stringResource(R.string.alerts_quiet_falling_summary, s.cfg(Kind.HIGH).value.toInt(), Durations.text(s.muteFallingMin)),
                checked = s.muteFalling, rowEnabled = rules && s.muteFalling, switchEnabled = rules, icon = R.drawable.ic_sym_trending_down, onClick = { minutes = "falling" }) { v ->
                set { it.copy(muteFalling = v) }
            }
            splitSwitch(stringResource(R.string.alerts_quiet_carbs), stringResource(R.string.alerts_quiet_carbs_summary, Durations.text(s.muteCarbsMin)),
                checked = s.muteCarbs, rowEnabled = rules && s.muteCarbs, switchEnabled = rules, icon = R.drawable.ic_dia_carbs, onClick = { minutes = "carbs" }) { v ->
                set { it.copy(muteCarbs = v) }
            }
        }

        // the alert notification's buttons: local on every device (a follower mirroring the wearer mutes its own copy)
        SettingsGroup(stringResource(R.string.alerts_group_actions)) {
            value(stringResource(R.string.alerts_action_mute), Durations.text(s.actionMuteMin),
                enabled = s.on, icon = R.drawable.ic_sym_notifications_off) { minutes = "action" }
            value(stringResource(R.string.alerts_action_remind_less), stringResource(R.string.alerts_action_remind_less_summary, Durations.text(s.relaxMin)),
                enabled = s.on, icon = R.drawable.ic_sym_snooze) { minutes = "relax" }
            // both end once back in range by the margin, for long enough (SPEC §2): glucose that oscillates at the edge stays quiet
            value(stringResource(R.string.alerts_back_mgdl), stringResource(R.string.alerts_back_mgdl_value, s.backMgdl),
                enabled = s.on, icon = R.drawable.ic_sym_trending_down) { minutes = "backMgdl" }
            value(stringResource(R.string.alerts_back_min), Durations.text(s.backMin),
                enabled = s.on, icon = R.drawable.ic_sym_repeat) { minutes = "backMin" }
        }

        SettingsGroup(stringResource(R.string.alerts_group_sounds)) {
            value(stringResource(R.string.alerts_sound_pack), soundLabel(ctx, s.pack), enabled = s.on, icon = R.drawable.ic_sym_volume_up) { pickPack = true }
            switch(stringResource(R.string.alerts_shuffle), s.shuffle, enabled = s.on, icon = R.drawable.ic_sym_shuffle,
                summary = stringResource(R.string.alerts_shuffle_summary, AlertSounds.VARIANTS)) { v -> set { it.copy(shuffle = v) } }
            link(stringResource(R.string.alerts_notification_settings), stringResource(R.string.alerts_notification_settings_summary),
                icon = R.drawable.ic_sym_notifications, external = true) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }
        SettingsFooter(stringResource(R.string.alerts_footer))
    }

    val minLabel: (Int) -> String = { Durations.text(it) }
    val S = SettingsSpec
    when (minutes) {
        "insulin" -> IntNumberDialog(stringResource(R.string.alerts_quiet_insulin), S.MUTE_INSULIN, s.muteInsulinMin, minLabel,
            { minutes = null }) { m -> set { it.copy(muteInsulinMin = m) } }
        "falling" -> IntNumberDialog(stringResource(R.string.alerts_quiet_falling), S.MUTE_FALLING, s.muteFallingMin,
            { stringResource0(ctx, R.string.alerts_within_minutes, Durations.text(it)) }, { minutes = null }) { m -> set { it.copy(muteFallingMin = m) } }
        "carbs" -> IntNumberDialog(stringResource(R.string.alerts_quiet_carbs), S.MUTE_CARBS, s.muteCarbsMin, minLabel,
            { minutes = null }) { m -> set { it.copy(muteCarbsMin = m) } }
        "action" -> IntNumberDialog(stringResource(R.string.alerts_action_mute), S.ACTION_MUTE, s.actionMuteMin, minLabel,
            { minutes = null }) { m -> set { it.copy(actionMuteMin = m) } }
        // only intervals slower than every level alert's repeat: the button shows on all of them (SPEC §3)
        "relax" -> IntNumberDialog(stringResource(R.string.alerts_action_remind_less), S.ACTION_RELAX, s.relaxMin,
            { stringResource0(ctx, R.string.alerts_remind_every, Durations.text(it)) }, { minutes = null },
            presets = s.relaxChoices(), customMin = (s.relaxFloor() + 1).coerceIn(S.ACTION_RELAX.min.toInt(), S.ACTION_RELAX.max.toInt())) { m ->
            set { it.copy(relaxMin = m) } }
        "backMgdl" -> IntNumberDialog(stringResource(R.string.alerts_back_mgdl), S.BACK_MGDL, s.backMgdl,
            { stringResource0(ctx, R.string.alerts_back_mgdl_value, it) }, { minutes = null }) { m -> set { it.copy(backMgdl = m) } }
        "backMin" -> IntNumberDialog(stringResource(R.string.alerts_back_min), S.BACK_MIN, s.backMin, minLabel,
            { minutes = null }) { m -> set { it.copy(backMin = m) } }
    }
    if (muteAll) MuteAllDialog(onClose = { muteAll = false })
    if (pickPack) SoundDialog(stringResource(R.string.alerts_sound_pack), Kind.HIGH, null, s.pack, s.cfg(Kind.HIGH).alarm,
        onClose = { pickPack = false }) { p -> set { it.copy(pack = p ?: it.pack) } }
}

/**
 * Why an alert is quiet now, in the app's language. The engine (pure Kotlin, shared timelines) words it in English:
 * "insulin 12 min ago", "carbs 5 min ago", "falling, back in range in ~8 min"; anything else shows as it is.
 */
private fun quietReason(ctx: Context, m: String): String {
    // the engine's durations are Durations.text in English ("12 min", "1 h 5 min"): read back, worded here
    fun d(s: String) = Durations.parse(s)?.let { Durations.text(it) } ?: s
    Regex("^(insulin|carbs) (.+) ago$").find(m)?.let { r ->
        return ctx.getString(if (r.groupValues[1] == "insulin") R.string.alerts_quiet_reason_insulin else R.string.alerts_quiet_reason_carbs, d(r.groupValues[2]))
    }
    // the time left as the mute lines say it ("12 min left" / "quedan 12 min", MuteCopy.left)
    fun left(s: String) = Durations.parse(s)?.let { MuteCopy.left(ctx, it.toLong()) } ?: s
    Regex("^muted from the notification, (.+) left$").find(m)?.let { r -> return ctx.getString(R.string.alerts_quiet_reason_notification, left(r.groupValues[1])) }
    Regex("^muted \\(all alerts\\), (.+) left$").find(m)?.let { r -> return ctx.getString(R.string.alerts_quiet_reason_all, left(r.groupValues[1])) }
    if (m == "all alerts muted") return ctx.getString(R.string.alerts_quiet_reason_all_never)
    Regex("^falling, back in range in ~(.+)$").find(m)?.let { r -> return ctx.getString(R.string.alerts_quiet_reason_falling, d(r.groupValues[1])) }
    return m
}

/** One line of the "lately" list: "14:05  Low glucose · eat more (2) · 62 mg/dL". */
private fun noteText(ctx: Context, n: AlertNote): String {
    val k = n.kinds.firstOrNull() ?: ""
    val text = when (n.what) {
        AlertNote.FIRED -> ctx.getString(if (n.remote) R.string.alerts_note_fired_remote else R.string.alerts_note_fired,
            AlertNotifier.headline(ctx, k, n.treated) + if (n.n > 1) " (${n.n})" else "", n.sgv?.toString() ?: "–")
        AlertNote.CLEARED -> ctx.getString(if (n.remote) R.string.alerts_note_cleared_remote else R.string.alerts_note_cleared, AlertNotifier.label(ctx, k))
        // a side's mute and remind less name the side (MuteCopy, round f31): "Low alerts muted for 1 h"
        AlertNote.MUTED -> ctx.getString(R.string.alerts_note_muted, MuteCopy.side(ctx, k), Durations.text(n.n))
        AlertNote.RELAXED -> MuteCopy.sideLess(ctx, k, n.n)
        AlertNote.UNMUTED -> ctx.getString(R.string.alerts_note_unmuted, MuteCopy.side(ctx, k))
        AlertNote.UNRELAXED -> ctx.getString(R.string.alerts_note_unrelaxed, MuteCopy.side(ctx, k), Durations.text(n.n))
        AlertNote.BACK -> ctx.getString(R.string.alerts_note_back, MuteCopy.side(ctx, k))
        AlertNote.ALL_MUTED -> if (n.n == Alerts.UNTIL_TURNED_OFF_MIN) ctx.getString(R.string.alerts_note_all_muted_never)
            else ctx.getString(R.string.alerts_note_all_muted, Durations.text(n.n))
        AlertNote.ALL_BACK -> ctx.getString(R.string.alerts_note_all_back)
        else -> ctx.getString(R.string.alerts_note_resumed, n.kinds.joinToString { AlertNotifier.label(ctx, it) })
    }
    val t = app.mauri.sukrara.graph.Clock.time(n.at)
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

/** The schema's number format of [k]'s threshold (spikes/settings/settings.json). */
internal fun thresholdSpec(k: String): NumberSpec = when (k) {
    Kind.VERY_LOW -> SettingsSpec.THRESHOLD_VERY_LOW
    Kind.LOW -> SettingsSpec.THRESHOLD_LOW
    Kind.HIGH -> SettingsSpec.THRESHOLD_HIGH
    Kind.VERY_HIGH -> SettingsSpec.THRESHOLD_VERY_HIGH
    Kind.EXTREMELY_HIGH -> SettingsSpec.THRESHOLD_EXTREMELY_HIGH
    in Kind.RATES -> SettingsSpec.THRESHOLD_RATE
    else -> SettingsSpec.THRESHOLD_NO_DATA
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
    val from by WearerSettings.mode.collectAsState()
    val locked = mirrored(follower, from, k)
    val ruleOn = s.on && !locked
    val wearers = setBy(from)
    val off = stringResource(if (!s.on) R.string.alerts_unavailable_all_off else R.string.alerts_unavailable_off)
    val volume = stringResource(if (c.alarm) R.string.alerts_volume_alarm else R.string.alerts_volume_notification)

    SettingsPage(AlertNotifier.label(ctx, k), onBack) {
        SettingsMainSwitch(stringResource(R.string.alerts_kind_main_switch, AlertNotifier.label(ctx, k)), c.on && !locked, enabled = ruleOn) { v -> change { it.copy(on = v) } }
        val on = c.on && s.on
        SettingsGroup(stringResource(R.string.alerts_group_when)) {
            value(stringResource(if (k == Kind.NO_DATA) R.string.alerts_after else if (k in Kind.RATES) R.string.alerts_rate else R.string.alerts_threshold),
                if (locked) "$wearers · ${thresholdText(k, c.value)}" else if (!on) off else thresholdText(k, c.value),
                enabled = on && !locked, icon = kindIcon(k)) { dialog = "threshold" }
            value(stringResource(R.string.alerts_remind), if (locked) "$wearers · ${repeatText(c.repeatMin)}" else if (!on) off else repeatText(c.repeatMin),
                enabled = on && !locked, icon = R.drawable.ic_sym_repeat) { dialog = "repeat" }
        }
        SettingsGroup(stringResource(R.string.alerts_group_sound)) {
            value(stringResource(R.string.alerts_sound), if (!s.on) off else soundSummary(k, s), enabled = s.on, icon = R.drawable.ic_sym_music_note) { dialog = "sound" }
            value(stringResource(R.string.alerts_volume), if (!s.on) off else volume, enabled = s.on, icon = R.drawable.ic_sym_volume_up) { dialog = "volume" }
        }
        SettingsGroup(stringResource(R.string.alerts_group_test)) {
            action(stringResource(R.string.alerts_play_sound), stringResource(if (vibrate) R.string.alerts_play_sound_vibrate else R.string.alerts_play_sound_only,
                soundName(s.soundOf(k)), volume), enabled = s.soundOf(k) != AlertSounds.NONE || vibrate, icon = R.drawable.ic_sym_play_arrow) {
                TestPlayback.play(ctx, k, s.soundOf(k), c.alarm)
            }
            checkbox(stringResource(R.string.alerts_vibrate), vibrate, summary = stringResource(R.string.alerts_vibrate_summary), icon = R.drawable.ic_sym_vibration) { TestPlayback.setVibrate(ctx, it) }
            action(stringResource(R.string.alerts_test), stringResource(R.string.alerts_test_summary), icon = R.drawable.ic_sym_science) {
                Alerts.test(ctx, k, share = false)
            }
            link(stringResource(R.string.alerts_channel_settings), stringResource(R.string.alerts_channel_settings_summary), icon = R.drawable.ic_sym_tune, external = true) {
                ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, AlertNotifier.channelId(k, c.alarm)))
            }
        }
        SettingsFooter(stringResource(if (locked) R.string.alerts_kind_footer_follower else R.string.alerts_kind_footer))
    }

    when (dialog) {
        "threshold" -> NumberDialog(stringResource(if (k == Kind.NO_DATA) R.string.alerts_after else if (k in Kind.RATES) R.string.alerts_rate else R.string.alerts_threshold),
            thresholdSpec(k), c.value, { v -> thresholdText0(ctx, k, v) }, { dialog = null }) { v -> change { it.copy(value = v) } }
        "repeat" -> IntNumberDialog(stringResource(R.string.alerts_remind), SettingsSpec.ALERT_REPEAT, c.repeatMin,
            { m -> if (m == 0) ctx.getString(R.string.alerts_remind_once) else ctx.getString(R.string.alerts_remind_every, Durations.text(m)) },
            { dialog = null }) { m -> change { it.copy(repeatMin = m) } }
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
    Kind.NO_DATA -> ctx.getString(R.string.alerts_threshold_no_data, Durations.text(v.toInt()))
    else -> ctx.getString(R.string.alerts_threshold_above, v.toInt())
}
