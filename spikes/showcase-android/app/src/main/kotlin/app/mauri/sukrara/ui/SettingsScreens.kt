// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.R
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.follower.FollowerUi
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.owner.Follower
import app.mauri.sukrara.owner.OwnerUi
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/*
 * The settings of both roles, per the settings guidelines (SettingsUi.kt): an overview with one row per subscreen
 * ("For 15 or more settings, group related settings under a subscreen"; "the label of the setting that opens a group
 * must match the subscreen title"), each showing its status. Rarely used, technical and experimental things live in
 * Advanced; version, source code and the other apps in About.
 */

private fun set(f: (FeatureState) -> FeatureState) { Features.update(f) }

private fun date(t: Long) = app.mauri.sukrara.graph.Clock.dateTime(t)

private fun intervalLabel(ctx: android.content.Context, min: Int) =
    if (min == 0) ctx.getString(R.string.interval_every_reading) else ctx.getString(R.string.interval_every_min, min)

/** "On" / "Off" as the stock Settings summaries say it. */
@Composable
private fun onOff(b: Boolean) = stringResource(if (b) R.string.settings_on else R.string.settings_off)

/** Summary of the Alerts row (overview of both roles). */
@Composable
private fun alertsSummary(s: AlertSettings, follower: Boolean): String {
    val ctx = LocalContext.current
    val n = Kind.ALL.count { s.cfg(it).on }
    val pack = soundName(ctx, s.pack)
    return when {
        !s.on -> stringResource(R.string.settings_off)
        follower && s.source == "wearer" -> stringResource(R.string.settings_alerts_summary_wearer, pack)
        else -> stringResource(R.string.settings_alerts_summary, n, Kind.ALL.size, pack)
    }
}

@Composable
private fun graphSummary(): String {
    val g by GraphSettings.state.collectAsState()
    val tir = stringResource(R.string.settings_graph_summary_tir)
    val rings = if (g.ringCount == 0) stringResource(R.string.settings_graph_summary_no_rings)
        else g.rings().joinToString(" + ") { "${it.kind.short.ifEmpty { tir }} ${it.period}" }
    return stringResource(R.string.settings_graph_summary, g.defaultHours, rings)
}

// ================================================================================================ wearer overview

/** The wearer's settings overview. [open] goes to a subscreen by its route. */
@Composable
fun WearerSettings(u: OwnerUi, onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    val alerts by Alerts.settings.collectAsState()
    val access by app.mauri.sukrara.features.SystemAccess.status.collectAsState()
    val gs = graphSummary()
    SettingsPage(stringResource(R.string.settings_title), onBack) {
        SettingsSearchBar()
        val nav = LocalNav.current
        LibreViewBanner(onSetUp = { nav?.open("libreview") })
        SettingsGroup {
            link(u.profile?.name?.ifBlank { null } ?: stringResource(R.string.home_add_your_name), stringResource(R.string.settings_profile_wearer),
                leading = { AvatarImage(u.profile?.avatar, u.profile?.name ?: "", 40.dp) }, to = "profile")
        }
        SettingsGroup {
            link(stringResource(R.string.alerts_title), alertsSummary(alerts, follower = false), leading = { CategoryIcon(R.drawable.ic_sym_notifications, Category.RED) }, to = "alerts")
            link(stringResource(R.string.source_title), stringResource(sourceLabel(f.source)) + " · " + st.source,
                leading = { CategoryIcon(R.drawable.ic_sym_glucose, Category.GREEN) }, to = "source")
            link(stringResource(R.string.sensorend_title), sensorEndSummary(), leading = { CategoryIcon(R.drawable.ic_stat_sensor_ended, Category.ORANGE) }, to = "sensorend")   // the status bar's "sensor ended" flag
        }
        SettingsGroup {
            val n = u.followers.count { it.revoked == null }
            link(stringResource(R.string.people_title), pluralStringResource(R.plurals.notif_followers, n, n) +
                (if (f.nostr) " · " + stringResource(R.string.settings_people_summary_internet) else "") +
                (if (f.bleFollowers) " · " + stringResource(R.string.settings_people_summary_bluetooth) else ""),
                leading = { CategoryIcon(R.drawable.ic_sym_group, Category.BLUE) }, to = "sharing")
            link(stringResource(R.string.apps_title), appsSummary(f, wearer = true), leading = { CategoryIcon(R.drawable.ic_sym_apps, Category.ORANGE) }, to = "apps")
        }
        SettingsGroup {
            link(stringResource(R.string.graph_title), gs, leading = { CategoryIcon(R.drawable.ic_sym_monitoring, Category.PURPLE) }, to = "graph")
            val bar = listOfNotNull(R.string.settings_statusbar_summary_chip.takeIf { f.chip }, R.string.settings_statusbar_summary_icon.takeIf { f.icon },
                R.string.settings_statusbar_summary_value.takeIf { f.hook }).map { stringResource(it) }
            link(stringResource(R.string.statusbar_title), bar.joinToString(" · ").ifEmpty { stringResource(R.string.settings_off) }.replaceFirstChar { it.uppercase() },
                leading = { CategoryIcon(R.drawable.ic_sym_smartphone, Category.INDIGO) }, to = "statusbar")
            link(stringResource(R.string.lock_title), if (!access.active && access.module != app.mauri.sukrara.features.SystemAccess.Module.CHECKING) stringResource(R.string.settings_needs_module)
                else if (f.lockWidget) stringResource(R.string.settings_on_with, stringResource(lockBackgroundLabel(f.lockBg))) else stringResource(R.string.settings_off),
                leading = { CategoryIcon(R.drawable.ic_sym_screen_lock_portrait, Category.TEAL) }, to = "lockscreen")
        }
        SettingsGroup {
            link(stringResource(R.string.backup_title), stringResource(R.string.settings_backup_summary,
                if (u.backupSavedAt > 0) stringResource(R.string.backup_saved_at, date(u.backupSavedAt)) else stringResource(R.string.backup_not_backed_up)),
                leading = { CategoryIcon(R.drawable.ic_sym_backup, if (u.backupSavedAt > 0) Category.GREEN else Category.RED) }, to = "backup")
            link(stringResource(R.string.export_title), stringResource(R.string.settings_export_summary),
                leading = { CategoryIcon(R.drawable.ic_sym_file_export, Category.YELLOW) }, to = "export")
        }
        SettingsGroup {
            link(stringResource(R.string.advanced_title), stringResource(R.string.settings_advanced_summary_wearer), leading = { CategoryIcon(R.drawable.ic_sym_code, Category.GREY) }, to = "advanced")
            link(stringResource(R.string.about_title), stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                leading = { CategoryIcon(R.drawable.ic_sym_info, Category.GREY) }, to = "about")
        }
    }
}

@Composable
private fun appsSummary(f: FeatureState, wearer: Boolean) = listOfNotNull(
    "LibreView".takeIf { wearer && f.libreview }, "xDrip+".takeIf { f.garmin }, "Nightscout".takeIf { f.nightscout },
).joinToString(" · ").ifEmpty { stringResource(R.string.settings_off) }

/** Uploading to LibreView matters (it's the doctor's record): ask until it's set up or the user says not now. */
@Composable
fun LibreViewBanner(onSetUp: () -> Unit, containerColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    val f by Features.state.collectAsState()
    val lv by Treat.uploader.settings.collectAsState()
    val ready = f.libreview && lv.email.isNotEmpty() && lv.hasPassword
    Reveal(!ready && !f.libreviewBannerDismissed) {
        SettingsBanner(R.drawable.ic_sym_cloud_upload, stringResource(R.string.libreview_banner_title),
            stringResource(R.string.libreview_banner_text), stringResource(R.string.libreview_banner_action), onSetUp,
            onDismiss = { set { it.copy(libreviewBannerDismissed = true) } }, containerColor = containerColor)
    }
}

// ================================================================================================ glucose source

/** Glucose sources (value, label, one-line description). Libre 3 directly is the default. */
private val SOURCES = listOf(
    Triple("direct", R.string.source_direct, R.string.source_direct_summary),
    Triple("juggluco", R.string.source_juggluco, R.string.source_juggluco_summary),
)

private fun sourceLabel(v: String) = (SOURCES.firstOrNull { it.first == v } ?: SOURCES[0]).second

@Composable
fun SourceSettings(onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var confirmDirect by remember { mutableStateOf(false) }
    val direct = f.source == "direct"
    val nav = LocalNav.current
    SettingsPage(stringResource(R.string.source_title), onBack) {
        SettingsIntro(stringResource(R.string.source_intro))
        SettingsGroup {
            value(stringResource(R.string.source_source), stringResource(sourceLabel(f.source)) + " · " + st.source + (st.sourceError?.let { " — $it" } ?: ""),
                icon = if (direct) R.drawable.ic_brand_abbott else null, leading = if (direct) null else ({ AppIcon(R.drawable.ic_brand_juggluco) })) { choosing = true }
            link(stringResource(R.string.sensor_title), if (direct) st.sensorSerial?.let { stringResource(R.string.source_sensor_summary, it) } ?: stringResource(R.string.source_sensor_summary_none)
                else stringResource(R.string.source_sensor_unavailable), icon = R.drawable.ic_sym_nfc, enabled = direct, to = "sensor")
        }
        SettingsFooter(stringResource(R.string.source_footer))
    }
    if (choosing) ChoiceDialog(stringResource(R.string.source_source), SOURCES.map { Choice(stringResource(it.second), it.first, stringResource(it.third)) }, f.source, { choosing = false }) { v ->
        if (v == "direct" && f.source != "direct") confirmDirect = true else set { it.copy(source = v) }
    }
    if (confirmDirect) AlertDialog(
        onDismissRequest = { confirmDirect = false },
        title = { Text(stringResource(R.string.source_direct_confirm_title)) },
        text = { Text(stringResource(R.string.source_direct_confirm_text)) },
        confirmButton = { Button(onClick = { confirmDirect = false; nav?.open("newsensor") }) { Text(stringResource(R.string.source_direct_confirm_scan)) } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    )
}

// ================================================================================================ sharing

@Composable
fun SharingSettings(u: OwnerUi, a: OwnerActions, onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Follower?>(null) }
    val active = u.followers.filter { it.revoked == null }
    val pairing = u.nearbyUntil > Proto.nowS()
    SettingsPage(stringResource(R.string.people_title), onBack) {
        SettingsIntro(stringResource(R.string.people_intro))
        SettingsGroup {
            action(stringResource(R.string.share_title), stringResource(R.string.people_share_summary), icon = R.drawable.ic_sym_share, onClick = a::share)
            action(stringResource(if (pairing) R.string.people_pairing else R.string.people_pair), when {
                !u.bleOn -> stringResource(R.string.people_pair_bluetooth_off)
                pairing -> stringResource(R.string.people_pairing_summary, date(u.nearbyUntil))
                else -> stringResource(R.string.people_pair_summary)
            }, icon = R.drawable.ic_sym_bluetooth_searching, enabled = u.bleOn, onClick = a::nearbyPairing)
        }
        SettingsGroup(stringResource(R.string.people_group_followers)) {
            if (active.isEmpty()) info(stringResource(R.string.people_nobody))
            for (fl in active) link(fl.name, stringResource(R.string.people_follower_summary, date(fl.since),
                    stringResource(if (fl.f in u.writers) R.string.people_can_add_entries else R.string.people_sees_only)),
                leading = { AvatarImage(fl.avatar, fl.name, 40.dp) }) { editing = fl }
            for (fl in u.followers.filter { it.revoked != null }) info(fl.name, stringResource(R.string.people_removed_at, date(fl.revoked!!)), icon = R.drawable.ic_sym_person_remove)
        }
        SettingsGroup(stringResource(R.string.people_group_travel)) {
            nostrRows(f) { choosing = true }
            switch(stringResource(R.string.people_bluetooth), f.bleFollowers, icon = R.drawable.ic_brand_bluetooth,
                summary = if (u.bleOn) stringResource(R.string.people_bluetooth_connected, u.bleCentrals) else stringResource(R.string.people_bluetooth_off)) { v -> set { it.copy(bleFollowers = v) } }
        }
        SettingsGroup { link(stringResource(R.string.apps_title), "LibreView, xDrip+, Nightscout", icon = R.drawable.ic_sym_apps, to = "apps") }
        SettingsFooter(stringResource(R.string.people_footer))
    }
    if (choosing) NostrIntervalDialog(f) { choosing = false }
    editing?.let { fl -> FollowerDialog(fl, fl.f in u.writers, a) { editing = null } }
}

/** Nostr relays, on both sharing pages (followers' phones and Macs read from them). */
@Composable
private fun SettingsGroupScope.nostrRows(f: FeatureState, onInterval: () -> Unit) {
    switch(stringResource(R.string.people_nostr), f.nostr, icon = R.drawable.ic_brand_nostr, summary = stringResource(R.string.people_nostr_summary)) { v ->
        set { it.copy(nostr = v) }
    }
    value(stringResource(R.string.sharing_upload_interval), if (f.nostr) intervalLabel(LocalContext.current, f.nostrIntervalMin) else stringResource(R.string.sharing_upload_interval_off),
        enabled = f.nostr, onClick = onInterval)
}

@Composable
private fun NostrIntervalDialog(f: FeatureState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    IntChoiceDialog(stringResource(R.string.sharing_upload_interval), listOf(0, 5, 15, 30), f.nostrIntervalMin, { intervalLabel(ctx, it) },
        NumberRange(1.0, 120.0, stringResource(R.string.unit_min)), onClose) { v -> set { it.copy(nostrIntervalMin = v) } }
}

/** One follower: whether they may add entries, and removing them. */
@Composable
private fun FollowerDialog(fl: Follower, writer: Boolean, a: OwnerActions, onClose: () -> Unit) {
    var removing by remember { mutableStateOf(false) }
    if (removing) AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.people_remove_title, fl.name)) },
        text = { Text(stringResource(R.string.people_remove_text)) },
        confirmButton = { Button(onClick = { a.revoke(fl.f); onClose() }) { Text(stringResource(R.string.people_remove)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    ) else AlertDialog(
        onDismissRequest = onClose,
        icon = { AvatarImage(fl.avatar, fl.name, 48.dp) },
        title = { Text(fl.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.people_following_since, date(fl.since)), style = MaterialTheme.typography.bodyMedium)
                SettingsGroup { switch(stringResource(R.string.people_writer), writer, summary = stringResource(R.string.people_writer_summary)) { a.setWriter(fl.f, it) } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_dialog_done)) } },
        dismissButton = { TextButton(onClick = { removing = true }) { Text(stringResource(R.string.people_remove), color = MaterialTheme.colorScheme.error) } },
    )
}

// ================================================================================================ root & LSPosed

/**
 * What a page's features are missing, with the one step that fixes it: root for Sukrara, then the LSPosed module
 * enabled with System UI in its scope and loaded. Checks again whenever the page comes back to the front.
 */
@Composable
private fun SystemAccessBanner(root: Boolean, module: Boolean) {
    if (indexing) return
    val rootOk by app.mauri.sukrara.status.RootStatusBar.root.collectAsState()
    val access by app.mauri.sukrara.features.SystemAccess.status.collectAsState()
    var restarting by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        app.mauri.sukrara.status.RootStatusBar.checkRoot()
        app.mauri.sukrara.features.SystemAccess.refresh()
        onPauseOrDispose {}
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val icon = R.drawable.ic_sym_key
    when {
        root && rootOk == false -> SettingsBanner(icon, stringResource(R.string.access_root_title), stringResource(R.string.access_root_text),
            stringResource(R.string.access_root_action), { app.mauri.sukrara.status.RootStatusBar.checkRoot() })
        module && access.module == app.mauri.sukrara.features.SystemAccess.Module.MISSING -> SettingsBanner(icon, stringResource(R.string.access_module_title),
            stringResource(R.string.access_module_text), null, {})
        module && access.module == app.mauri.sukrara.features.SystemAccess.Module.NO_SCOPE -> SettingsBanner(icon, stringResource(R.string.access_scope_title),
            stringResource(R.string.access_scope_text, access.framework ?: "LSPosed") + (problem?.let { " ($it)" } ?: ""),
            stringResource(R.string.access_scope_action), { app.mauri.sukrara.features.SystemAccess.requestSystemUi { problem = it } })
        module && access.module in setOf(app.mauri.sukrara.features.SystemAccess.Module.RESTART, app.mauri.sukrara.features.SystemAccess.Module.UPDATE) -> SettingsBanner(icon, stringResource(R.string.access_restart_title),
            stringResource(if (access.module == app.mauri.sukrara.features.SystemAccess.Module.UPDATE) R.string.access_restart_text_updated else R.string.access_restart_text),
            if (rootOk == true && !restarting) stringResource(R.string.access_restart_title) else null, {
                restarting = true
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.mauri.sukrara.status.RootStatusBar.restartSystemUi() }
                    kotlinx.coroutines.delay(4_000)
                    app.mauri.sukrara.features.SystemAccess.refresh()
                    restarting = false
                }
            })
    }
}

// ================================================================================================ status bar

@Composable
fun StatusBarSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val rootBar = app.mauri.sukrara.status.RootStatusBar
    val root by rootBar.root.collectAsState()
    LaunchedEffect(Unit) { rootBar.checkRoot() }
    val ok = root == true
    val access by app.mauri.sukrara.features.SystemAccess.status.collectAsState()
    val module = access.active
    val hookOn = ok && f.hook
    val placement = hookPositionLabel(f.hookPosition)?.let { stringResource(it) } ?: f.hookPosition
    // "With the system icons" works with root alone; the other placements are drawn by the module
    fun needsModule(pos: String) = pos != app.mauri.sukrara.features.HookPosition.SLOT_PREFIX
    var choosing by remember { mutableStateOf(false) }
    val off = stringResource(R.string.statusbar_unavailable)
    val needs = stringResource(R.string.settings_needs_module)
    val nm = ctx.getSystemService(NotificationManager::class.java)
    SettingsPage(stringResource(R.string.statusbar_title), onBack) {
        SettingsIntro(stringResource(R.string.statusbar_intro))
        SystemAccessBanner(root = true, module = true)
        SettingsGroup {
            switch(stringResource(R.string.statusbar_chip), f.chip, summary = stringResource(R.string.statusbar_chip_summary)) { v -> set { it.copy(chip = v) } }
            if (f.chip && !nm.canPostPromotedNotifications()) link(stringResource(R.string.statusbar_allow_live_updates), stringResource(R.string.statusbar_allow_live_updates_summary), external = true) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            switch(stringResource(R.string.statusbar_icon), f.icon) { v -> set { it.copy(icon = v) } }
        }
        SettingsGroup(stringResource(R.string.statusbar_group_root)) {
            switch(stringResource(R.string.statusbar_value), f.hook && ok, enabled = ok, summary = when (root) {
                null -> stringResource(R.string.statusbar_value_checking)
                false -> stringResource(R.string.statusbar_value_no_root)
                true -> if (!f.hook) stringResource(R.string.settings_off) else if (needsModule(f.hookPosition) && !module) stringResource(R.string.statusbar_value_needs_module, placement) else placement
            }) { v -> set { it.copy(hook = v) } }
            switch(stringResource(R.string.statusbar_colour), f.hookColor, enabled = hookOn && module,
                summary = if (!hookOn) off else if (!module) needs else null) { v -> set { it.copy(hookColor = v) } }
            value(stringResource(R.string.statusbar_placement), if (hookOn) placement else off, enabled = hookOn) { choosing = true }
            switch(stringResource(R.string.statusbar_hide_icon), f.hideNotifIcon, enabled = hookOn && module,
                summary = if (!hookOn) off else if (!module) needs else stringResource(R.string.statusbar_hide_icon_summary)) { v ->
                set { it.copy(hideNotifIcon = v) }
            }
        }
        SettingsFooter(stringResource(R.string.statusbar_footer) + if (f.systemIcons) " Icons: ${rootBar.state}." else "")   // i18n-ok: debug state
    }
    if (choosing) ChoiceDialog(stringResource(R.string.statusbar_placement), Features.HOOK_POSITIONS.map { pos ->
            val ok = module || !needsModule(pos)
            Choice(stringResource(hookPositionLabel(pos)!!), pos, sub = if (ok) null else needs, enabled = ok)
        }, f.hookPosition,
        { choosing = false }) { pos -> set { it.copy(hookPosition = pos) } }
}

// ================================================================================================ lock screen

@Composable
fun LockscreenSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    var choosing by remember { mutableStateOf<String?>(null) }
    fun change(u: (FeatureState) -> FeatureState) {
        set(u)
        app.mauri.sukrara.status.RootStatusBar.pushState(ctx)
        app.mauri.sukrara.widgets.SukraraHooks.refresh(ctx, "lock screen style", force = true)
    }
    val access by app.mauri.sukrara.features.SystemAccess.status.collectAsState()
    val module = access.active
    val on = f.lockWidget && module
    val off = stringResource(if (!module) R.string.settings_needs_module else R.string.lock_unavailable)
    val bg = app.mauri.sukrara.features.LockBackground
    val look = app.mauri.sukrara.features.LockAppearance
    SettingsPage(stringResource(R.string.lock_title), onBack) {
        SettingsIntro(stringResource(R.string.lock_intro))
        SystemAccessBanner(root = false, module = true)
        SettingsMainSwitch(stringResource(R.string.lock_main_switch), on, enabled = module) { v -> change { it.copy(lockWidget = v) } }
        SettingsGroup {
            value(stringResource(R.string.lock_background), if (on) stringResource(lockBackgroundLabel(f.lockBg)) else off, enabled = on) { choosing = "bg" }
            value(stringResource(R.string.lock_appearance), if (on) stringResource(lockAppearanceLabel(f.lockLook)) else off, enabled = on) { choosing = "look" }
        }
        SettingsFooter(stringResource(R.string.lock_footer))
    }
    when (choosing) {
        "bg" -> ChoiceDialog(stringResource(R.string.lock_background), bg.ALL.map { Choice(stringResource(lockBackgroundLabel(it)), it, stringResource(lockBackgroundSummary(it))) },
            f.lockBg, { choosing = null }) { v -> change { it.copy(lockBg = v) } }
        "look" -> ChoiceDialog(stringResource(R.string.lock_appearance), look.ALL.map { Choice(stringResource(lockAppearanceLabel(it)), it, stringResource(lockAppearanceSummary(it))) },
            f.lockLook, { choosing = null }) { v -> change { it.copy(lockLook = v) } }
    }
}

// ================================================================================================ watch & other apps

/**
 * Sharing with apps: LibreView (wearer), the xDrip+-compatible server (what Garmin watch apps read), the Nightscout
 * API and the Nostr relays (wearer). Off by default except Nostr; each says how other apps reach it.
 */
@Composable
fun AppsSettings(wearer: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var secret by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.apps_title), onBack) {
        SettingsIntro(stringResource(if (wearer) R.string.apps_intro_wearer else R.string.apps_intro_follower))
        if (wearer) {
            val lv by Treat.uploader.settings.collectAsState()
            SettingsGroup("LibreView") {
                link("LibreView", when {
                    lv.email.isEmpty() || !lv.hasPassword -> stringResource(R.string.apps_libreview_not_set_up)
                    f.libreview -> stringResource(R.string.apps_libreview_uploading, lv.email)
                    else -> stringResource(R.string.settings_off) + " · " + lv.email
                }, leading = { AppIcon(R.drawable.ic_brand_libreview) }, to = "libreview")
            }
        }
        SettingsGroup("xDrip+") {
            switch(stringResource(R.string.apps_xdrip), f.garmin, leading = { AppIcon(R.drawable.ic_brand_xdrip) }, summary = stringResource(R.string.apps_xdrip_summary, st.garmin) +
                (if (st.watchPolledAt > 0) " · " + stringResource(R.string.apps_xdrip_last_asked, sinceText(st.watchPolledAt / 1000)) else "")) { v -> set { it.copy(garmin = v) } }
        }
        SettingsGroup("Nightscout") {
            switch(stringResource(R.string.apps_nightscout), f.nightscout, icon = R.drawable.ic_nightscout,
                summary = if (f.nightscout) "http://127.0.0.1:${Features.NIGHTSCOUT_PORT} · ${st.nightscout}" else stringResource(R.string.apps_nightscout_summary)) { v ->
                set { it.copy(nightscout = v) }
            }
            switch(stringResource(R.string.apps_nightscout_lan), f.nightscoutLan, enabled = f.nightscout,
                summary = stringResource(if (f.nightscout) R.string.apps_nightscout_lan_summary else R.string.apps_nightscout_unavailable)) { v -> set { it.copy(nightscoutLan = v) } }
            if (f.nightscout && f.nightscoutLan) action(stringResource(R.string.apps_show_secret), icon = R.drawable.ic_sym_key) { secret = true }
        }
        if (wearer) SettingsGroup("Nostr") { nostrRows(f) { choosing = true } }
        if (wearer) SettingsGroup { link(stringResource(R.string.people_title), stringResource(R.string.apps_people_summary), icon = R.drawable.ic_sym_group, to = "sharing") }
        SettingsFooter(stringResource(R.string.apps_footer))
    }
    if (choosing) NostrIntervalDialog(f) { choosing = false }
    if (secret) AlertDialog(
        onDismissRequest = { secret = false },
        title = { Text(stringResource(R.string.apps_secret_title)) },
        text = { Text(Features.nightscoutSecret(ctx), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        confirmButton = { TextButton(onClick = { secret = false }) { Text(stringResource(R.string.recovery_close)) } },
    )
}

@Composable
private fun sinceText(t: Long): String {
    val s = System.currentTimeMillis() / 1000 - t
    return if (s < 90) stringResource(R.string.duration_now) else stringResource(R.string.duration_ago, stringResource(R.string.duration_minutes, (s / 60).toInt()))
}

/**
 * LibreView (investigation 04): the account, then the main switch, then what goes up — every part on by default, so
 * switching the upload on uploads everything, like LibreLink — each disabled with the reason while the upload is off.
 */
@Composable
fun LibreViewSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Treat.init(ctx) }
    val f by Features.state.collectAsState()
    val uploader = Treat.uploader
    val s by uploader.settings.collectAsState()
    val status by uploader.status.collectAsState()
    var signIn by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val signedIn = s.email.isNotEmpty() && s.hasPassword
    val on = f.libreview && signedIn
    val off = stringResource(R.string.libreview_unavailable)
    val direct = f.source == "direct"
    SettingsPage("LibreView", onBack) {
        SettingsIntro(stringResource(R.string.libreview_intro))
        SettingsGroup { value(stringResource(R.string.libreview_account), if (signedIn) s.email else stringResource(R.string.libreview_not_signed_in), icon = R.drawable.ic_sym_person) { signIn = true } }
        SettingsMainSwitch(stringResource(R.string.libreview_main_switch), on, enabled = signedIn) { v -> set { it.copy(libreview = v) }; uploader.kick() }
        SettingsGroup {
            info(stringResource(R.string.libreview_status), if (!signedIn) stringResource(R.string.libreview_sign_in_first) else if (on) status.replaceFirstChar { it.uppercase() } else stringResource(R.string.settings_off))
            switch(stringResource(R.string.libreview_glucose), s.glucose, enabled = on && direct, summary = when {
                !on -> off
                !direct -> stringResource(R.string.libreview_glucose_needs_direct)
                else -> uploader.glucoseFrom?.let { stringResource(R.string.libreview_glucose_from, app.mauri.sukrara.graph.Clock.dateTime(it)) }
                    ?: if (s.glucose) stringResource(R.string.libreview_glucose_from_now) else stringResource(R.string.settings_off)
            }) { uploader.configure(glucose = it) }
            switch(stringResource(R.string.libreview_entries), s.treatments, enabled = on, summary = if (on) stringResource(R.string.libreview_entries_summary) else off) {
                uploader.configure(treatments = it)
            }
            switch(stringResource(R.string.libreview_takeover), s.takeover, enabled = on, summary = if (!on) off
                else stringResource(if (s.takeover) R.string.libreview_takeover_on else R.string.libreview_takeover_off)) {
                uploader.configure(takeover = it)
            }
            value(stringResource(R.string.libreview_upload_every), if (on) intervalLabel(ctx, f.libreviewIntervalMin) else off, enabled = on) { choosing = true }
        }
        SettingsFooter(stringResource(R.string.libreview_footer))
    }
    if (choosing) IntChoiceDialog(stringResource(R.string.libreview_upload_every), listOf(5, 15, 60), f.libreviewIntervalMin, { intervalLabel(ctx, it) },
        NumberRange(1.0, 240.0, stringResource(R.string.unit_min)), { choosing = false }) { v -> set { it.copy(libreviewIntervalMin = v) } }
    if (signIn) LibreViewSignIn(s, onClose = { signIn = false })
}

// ================================================================================================ backup

@Composable
fun BackupSettings(u: OwnerUi, a: OwnerActions, onBack: () -> Unit) {
    var showRecovery by remember { mutableStateOf(false) }
    val saved = u.backupSavedAt > 0
    val transfer = settingsTransfer()
    SettingsPage(stringResource(R.string.backup_title), onBack) {
        SettingsIntro(stringResource(if (saved) R.string.backup_intro_saved else R.string.backup_intro_not_saved))
        SettingsGroup {
            action(stringResource(if (saved) R.string.backup_save_again else R.string.backup_save),
                if (saved) stringResource(R.string.backup_saved_at, date(u.backupSavedAt)) else stringResource(R.string.backup_recommended),
                icon = R.drawable.ic_sym_key, onClick = a::saveBackup)
            action(stringResource(R.string.recovery_title), stringResource(R.string.backup_recovery_summary), icon = R.drawable.ic_sym_history_edu) { showRecovery = true }
            info(stringResource(R.string.backup_relays), if (u.backupPublishedAt > 0) stringResource(R.string.backup_relays_updated, date(u.backupPublishedAt))
                else stringResource(R.string.backup_relays_not_yet), icon = R.drawable.ic_sym_hub)
        }
        transfer.group(this)
        SettingsFooter(stringResource(R.string.backup_footer))
    }
    if (showRecovery) RecoveryDialog(a) { showRecovery = false }
    transfer.dialogs()
}

/** Export / import / reset of the options ([SettingsTransfer]), for both roles' settings. */
private class TransferUi(val group: @Composable (androidx.compose.foundation.layout.ColumnScope) -> Unit, val dialogs: @Composable () -> Unit)

@Composable
private fun settingsTransfer(): TransferUi {
    val ctx = LocalContext.current
    val T = app.mauri.sukrara.features.SettingsTransfer
    var resetting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument(T.MIME)) { uri ->
        if (uri != null) message = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(T.export().toString(2).toByteArray()) }
            ctx.getString(R.string.transfer_exported)
        }.getOrElse { ctx.getString(R.string.transfer_write_failed, it.message) }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = runCatching {
            val j = JSONObject(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            T.import(ctx, j) ?: ctx.getString(R.string.transfer_imported)
        }.getOrElse { ctx.getString(R.string.transfer_read_failed, it.message) }
    }
    return TransferUi(group = {
        SettingsGroup(stringResource(R.string.transfer_group)) {
            action(stringResource(R.string.transfer_export), stringResource(R.string.transfer_export_summary), icon = R.drawable.ic_sym_file_export) { exporter.launch(T.fileName()) }
            action(stringResource(R.string.transfer_import), stringResource(R.string.transfer_import_summary), icon = R.drawable.ic_sym_file_upload) { importer.launch(arrayOf(T.MIME, "text/plain", "*/*")) }
            action(stringResource(R.string.transfer_reset), stringResource(R.string.transfer_reset_summary), icon = R.drawable.ic_sym_restart_alt) { resetting = true }
        }
    }, dialogs = {
        if (resetting) AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.transfer_reset_title)) },
            text = { Text(stringResource(R.string.transfer_reset_text)) },
            confirmButton = { Button(onClick = { T.reset(ctx); resetting = false; message = ctx.getString(R.string.transfer_reset_done) }) { Text(stringResource(R.string.transfer_reset_confirm)) } },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.settings_dialog_cancel)) } },
        )
        message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.settings_dialog_ok)) } }) }
    })
}

// ================================================================================================ advanced

@Composable
fun WearerAdvanced(u: OwnerUi, relays: Map<String, RelayStats>, a: OwnerActions, onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val direct = f.source == "direct"
    SettingsPage(stringResource(R.string.advanced_title), onBack) {
        SettingsGroup(stringResource(R.string.advanced_relays)) {
            card { if (relays.isEmpty()) Text(stringResource(R.string.advanced_no_relays), style = MaterialTheme.typography.bodyMedium) else RelayList(relays) }
            info(stringResource(R.string.advanced_requests), stringResource(if (u.inboxLive) R.string.advanced_requests_live else R.string.advanced_requests_polled))
            action(stringResource(R.string.advanced_check_requests), icon = R.drawable.ic_sym_update, onClick = a::checkNow)
        }
        SettingsGroup(stringResource(R.string.advanced_diagnostics)) {
            link(stringResource(R.string.advanced_log), stringResource(R.string.advanced_log_summary), icon = R.drawable.ic_sym_receipt_long, to = "log")
            info(stringResource(R.string.advanced_identity), stringResource(R.string.advanced_identity_summary, u.iPub?.let { Proto.shortNpub(it) } ?: "–", u.epoch), icon = R.drawable.ic_sym_key)
            info(stringResource(R.string.advanced_sent), stringResource(R.string.advanced_sent_summary, u.liveSent, u.chunksSent, st.notificationPosts, st.notificationSkipped))
        }
        SettingsGroup(stringResource(R.string.advanced_experimental)) {
            value(stringResource(R.string.advanced_priority), if (direct) stringResource(priorityLabel(f.connPriority))
                else stringResource(R.string.source_sensor_unavailable), enabled = direct) { choosing = true }
            switch(stringResource(R.string.advanced_screen_off), f.screenOffSkip, summary = stringResource(R.string.advanced_screen_off_summary)) { v ->
                set { it.copy(screenOffSkip = v) }
            }
            if (BuildConfig.DEBUG) switch(stringResource(R.string.advanced_synthetic), u.synthetic, summary = stringResource(R.string.advanced_debug_only), icon = R.drawable.ic_sym_bug_report, onChange = a::setSynthetic)
        }
    }
    if (choosing) ChoiceDialog(stringResource(R.string.advanced_priority), Features.PRIORITIES.map { Choice(stringResource(priorityLabel(it)), it) },
        f.connPriority, { choosing = false }) { v -> set { it.copy(connPriority = v) } }
}

@Composable
fun LogPage(log: List<String>, onBack: () -> Unit) {
    SettingsPage(stringResource(R.string.advanced_log), onBack) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp)) { if (log.isEmpty()) Text(stringResource(R.string.advanced_log_empty)) else LogList(log, max = 300) }
        }
    }
}

// ================================================================================================ follower

/** The follower's settings overview. */
@Composable
fun FollowerSettings(u: FollowerUi, onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val alerts by Alerts.settings.collectAsState()
    val gs = graphSummary()
    SettingsPage(stringResource(R.string.settings_title), onBack) {
        SettingsSearchBar()
        SettingsGroup {
            link(u.name ?: stringResource(R.string.home_add_your_name),
                u.wearer?.name?.let { stringResource(R.string.settings_profile_follower, it) } ?: stringResource(R.string.settings_profile_follower_unnamed),
                leading = { AvatarImage(u.me?.avatar, u.name ?: "", 40.dp) }, to = "profile")
        }
        SettingsGroup {
            link(stringResource(R.string.alerts_title), alertsSummary(alerts, follower = true), leading = { CategoryIcon(R.drawable.ic_sym_notifications, Category.RED) }, to = "alerts")
            link(stringResource(R.string.following_title), (u.wearer?.name ?: "Sukrara") + " · " + u.status, leading = { CategoryIcon(R.drawable.ic_sym_person, Category.BLUE) }, to = "following")
        }
        SettingsGroup {
            link(stringResource(R.string.graph_title), gs, leading = { CategoryIcon(R.drawable.ic_sym_monitoring, Category.PURPLE) }, to = "graph")
            link(stringResource(R.string.apps_title), appsSummary(f, wearer = false), leading = { CategoryIcon(R.drawable.ic_sym_apps, Category.ORANGE) }, to = "apps")
        }
        SettingsGroup {
            link(stringResource(R.string.export_title), stringResource(R.string.settings_export_summary),
                leading = { CategoryIcon(R.drawable.ic_sym_file_export, Category.YELLOW) }, to = "export")
        }
        SettingsGroup {
            link(stringResource(R.string.advanced_title), stringResource(R.string.settings_advanced_summary_follower), leading = { CategoryIcon(R.drawable.ic_sym_code, Category.GREY) }, to = "advanced")
            link(stringResource(R.string.about_title), stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                leading = { CategoryIcon(R.drawable.ic_sym_info, Category.GREY) }, to = "about")
        }
    }
}

@Composable
fun FollowingSettings(u: FollowerUi, a: FollowerActions, onBack: () -> Unit) {
    var leaving by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.following_title), onBack) {
        SettingsGroup {
            info(u.wearer?.name ?: "Sukrara", u.ownerPub?.let { Proto.shortNpub(it) }, icon = R.drawable.ic_sym_person)
            info(stringResource(R.string.people_writer), stringResource(if (u.canWrite) R.string.following_can_write else R.string.following_cannot_write))
            info("Bluetooth", u.ble, icon = R.drawable.ic_brand_bluetooth)
        }
        SettingsGroup { action(stringResource(R.string.following_stop), stringResource(R.string.following_stop_summary), icon = R.drawable.ic_sym_person_remove) { leaving = true } }
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text(stringResource(R.string.following_stop_title)) },
        text = { Text(u.wearer?.name?.let { stringResource(R.string.following_stop_text, it) } ?: stringResource(R.string.following_stop_text_unnamed)) },
        confirmButton = { Button(onClick = { leaving = false; a.leave() }) { Text(stringResource(R.string.following_stop)) } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    )
}

@Composable
fun FollowerAdvanced(u: FollowerUi, relays: Map<String, RelayStats>, a: FollowerActions, onBack: () -> Unit) {
    val transfer = settingsTransfer()
    transfer.dialogs()
    SettingsPage(stringResource(R.string.advanced_title), onBack) {
        transfer.group(this)
        SettingsGroup(stringResource(R.string.advanced_relays)) { card { if (relays.isEmpty()) Text(stringResource(R.string.advanced_no_relays), style = MaterialTheme.typography.bodyMedium) else RelayList(relays) } }
        SettingsGroup(stringResource(R.string.advanced_diagnostics)) {
            link(stringResource(R.string.advanced_log), stringResource(R.string.advanced_log_summary), icon = R.drawable.ic_sym_receipt_long, to = "log")
            info(stringResource(R.string.advanced_stored), stringResource(R.string.advanced_stored_summary, u.count, u.rejects) + (u.lastReject?.let { " ($it)" } ?: ""))
            info(stringResource(R.string.advanced_keys), stringResource(R.string.advanced_keys_summary, u.epoch, u.devices.joinToString { it.take(8) }), icon = R.drawable.ic_sym_key)
            info(stringResource(R.string.advanced_this_phone), u.fPub?.let { Proto.npub(it) })
        }
        if (BuildConfig.DEBUG) SettingsGroup(stringResource(R.string.advanced_experimental)) {
            switch(stringResource(R.string.advanced_unfiltered), u.testUnfiltered, summary = stringResource(R.string.advanced_debug_only),
                icon = R.drawable.ic_sym_bug_report, onChange = a::setTestUnfiltered)
        }
    }
}

// ================================================================================================ about

/** One file of the latest GitHub release: what the About page lists as "Sukrara on other devices". */
private data class Download(val name: String, val url: String)
private data class Release(val tag: String, val url: String, val files: List<Download>)

private const val REPO = "https://github.com/mauriciabad/cgm-reading-mac-spike"

/**
 * The other apps come from the latest release's files, not from a list in this app: a new platform shows up here as
 * soon as a release attaches it.
 */
private suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL("https://api.github.com/repos/mauriciabad/cgm-reading-mac-spike/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val assets = j.getJSONArray("assets")
        Release(j.getString("tag_name"), j.getString("html_url"), (0 until assets.length()).map { i ->
            assets.getJSONObject(i).let { Download(it.getString("name"), it.getString("browser_download_url")) }
        })
    }.getOrNull()
}

/** Whether release [tag] ("v0.11.12") is a newer version than [installed] ("0.11.13" → no): numbers compared part by part. */
internal fun newer(tag: String, installed: String): Boolean {
    fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(tag); val b = parts(installed)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** What a release file is for, from its name (unknown kinds still show, by file name). */
@Composable
private fun platformOf(name: String): Pair<String, Int> = when {
    name.endsWith(".apk") -> "Android" to R.drawable.ic_brand_android
    name.endsWith(".dmg") -> "Mac" to R.drawable.ic_brand_apple
    name.contains("mac") && name.endsWith(".zip") -> "Mac (zip)" to R.drawable.ic_brand_apple
    name.endsWith(".prg") || name.endsWith(".iq") -> stringResource(R.string.about_garmin) to R.drawable.ic_brand_garmin
    else -> name to R.drawable.ic_sym_download
}

@Composable
fun AboutSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    // null = loading, Result.failure = offline
    val idx = indexing
    val release by produceState<Result<Release>?>(null) {
        if (!idx) value = latestRelease()?.let { Result.success(it) } ?: Result.failure(Exception())
    }
    SettingsPage(stringResource(R.string.about_title), onBack) {
        SettingsHeader { Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the launcher icon is adaptive (no painterResource): draw what the launcher draws
            val icon = remember { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(288, 288).asImageBitmap() }
            androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(96.dp))
            Text("Sukrara", style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME) + if (BuildConfig.DEBUG) " (debug)" else "", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        val r = release?.getOrNull()
        if (r != null && newer(r.tag, BuildConfig.VERSION_NAME)) SettingsGroup {
            action(stringResource(R.string.about_update, r.tag.removePrefix("v")), stringResource(R.string.about_update_summary), icon = R.drawable.ic_sym_download) { open(r.url) }
        }
        SettingsGroup(stringResource(R.string.about_other_devices)) {
            when {
                release == null -> info(stringResource(R.string.about_looking))
                r == null -> link(stringResource(R.string.about_downloads), stringResource(R.string.about_downloads_offline), icon = R.drawable.ic_sym_download, external = true) { open("$REPO/releases/latest") }
                else -> for (d in r.files) {
                    val (label, icon) = platformOf(d.name)
                    val mine = label == "Android" && r.tag.removePrefix("v") == BuildConfig.VERSION_NAME
                    link(label, d.name + if (mine) " · " + stringResource(R.string.about_this_app) else "", icon = icon, external = true) { open(d.url) }
                }
            }
        }
        SettingsGroup {
            link(stringResource(R.string.about_source), "GitHub · GPL-3.0", icon = R.drawable.ic_brand_github, external = true) { open(REPO) }
            link(stringResource(R.string.about_website), "sukrara.mauri.app", icon = R.drawable.ic_sym_link, external = true) { open("https://sukrara.mauri.app") }
        }
        SettingsFooter(stringResource(R.string.about_footer))
    }
}
