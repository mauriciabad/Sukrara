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
import app.mauri.sukrara.alerts.AlertSounds
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
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The settings of both roles, per the settings guidelines (SettingsUi.kt): an overview with one row per subscreen
 * ("For 15 or more settings, group related settings under a subscreen"; "the label of the setting that opens a group
 * must match the subscreen title"), each showing its status. Rarely used, technical and experimental things live in
 * Advanced; version, source code and the other apps in About.
 */

private fun set(f: (FeatureState) -> FeatureState) { Features.update(f) }

private fun date(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t * 1000))

private fun intervalLabel(ctx: android.content.Context, min: Int) =
    if (min == 0) ctx.getString(R.string.interval_every_reading) else ctx.getString(R.string.interval_every_min, min)

private fun on(b: Boolean) = if (b) "On" else "Off"

/** Summary of the Alerts row (overview of both roles). */
private fun alertsSummary(s: AlertSettings, follower: Boolean): String {
    val n = Kind.ALL.count { s.cfg(it).on }
    return when {
        !s.on -> "Off"
        follower && s.source == "wearer" -> "Same as the wearer's app · ${AlertSounds.label(s.pack).substringBefore(" (")}"
        else -> "$n of ${Kind.ALL.size} on · ${AlertSounds.label(s.pack).substringBefore(" (")}"
    }
}

@Composable
private fun graphSummary(): String {
    val g by GraphSettings.state.collectAsState()
    return "${g.defaultHours} h · " + if (g.ringCount == 0) "no rings" else g.rings().joinToString(" + ") { "${it.kind.short.ifEmpty { "TIR" }} ${it.period}" }
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
    SettingsPage("Settings", onBack) {
        SettingsSearchBar()
        val nav = LocalNav.current
        LibreViewBanner(onSetUp = { nav?.open("libreview") })
        SettingsGroup {
            link(u.profile?.name?.ifBlank { null } ?: "Add your name", "Wearer · what your followers see",
                leading = { AvatarImage(u.profile?.avatar, u.profile?.name ?: "", 40.dp) }, to = "profile")
        }
        SettingsGroup {
            link("Alerts", alertsSummary(alerts, follower = false), leading = { CategoryIcon(R.drawable.ic_sym_notifications, Category.RED) }, to = "alerts")
            link("Glucose source", (if (f.source == "direct") "Libre 3 directly" else "Juggluco") + " · " + st.source,
                leading = { CategoryIcon(R.drawable.ic_sym_glucose, Category.GREEN) }, to = "source")
        }
        SettingsGroup {
            val n = u.followers.count { it.revoked == null }
            link(PEOPLE, "$n follower${if (n == 1) "" else "s"}" + (if (f.nostr) " · internet" else "") + (if (f.bleFollowers) " · Bluetooth" else ""),
                leading = { CategoryIcon(R.drawable.ic_sym_group, Category.BLUE) }, to = "sharing")
            link(APPS, appsSummary(f, wearer = true), leading = { CategoryIcon(R.drawable.ic_sym_apps, Category.ORANGE) }, to = "apps")
        }
        SettingsGroup {
            link("Graph & time in range", gs, leading = { CategoryIcon(R.drawable.ic_sym_monitoring, Category.PURPLE) }, to = "graph")
            link("Status bar", listOfNotNull("chip".takeIf { f.chip }, "number icon".takeIf { f.icon }, "value".takeIf { f.hook })
                .joinToString(" · ").ifEmpty { "Off" }.replaceFirstChar { it.uppercase() },
                leading = { CategoryIcon(R.drawable.ic_sym_smartphone, Category.INDIGO) }, to = "statusbar")
            link("Lock screen", if (!access.active && access.module != app.mauri.sukrara.features.SystemAccess.Module.CHECKING) NEEDS_MODULE
                else if (f.lockWidget) "On · " + app.mauri.sukrara.features.LockBackground.CHOICES.first { it.second == f.lockBg }.first else "Off",
                leading = { CategoryIcon(R.drawable.ic_sym_screen_lock_portrait, Category.TEAL) }, to = "lockscreen")
        }
        SettingsGroup {
            link("Backup", (if (u.backupSavedAt > 0) "Saved ${date(u.backupSavedAt)}" else "Not backed up") + " · export settings",
                leading = { CategoryIcon(R.drawable.ic_sym_backup, if (u.backupSavedAt > 0) Category.GREEN else Category.RED) }, to = "backup")
        }
        SettingsGroup {
            link("Advanced", "Relays, log, sensor link, experiments", leading = { CategoryIcon(R.drawable.ic_sym_code, Category.GREY) }, to = "advanced")
            link("About Sukrara", "Version ${BuildConfig.VERSION_NAME} · source code · other devices",
                leading = { CategoryIcon(R.drawable.ic_sym_info, Category.GREY) }, to = "about")
        }
    }
}

/** Sharing with people (followers) and with apps (LibreView, xDrip+, Nightscout clients, Nostr): two pages that link to each other. */
private const val PEOPLE = "Share with people"
private const val APPS = "Share with apps"

private fun appsSummary(f: FeatureState, wearer: Boolean) = listOfNotNull(
    "LibreView".takeIf { wearer && f.libreview }, "xDrip+".takeIf { f.garmin }, "Nightscout".takeIf { f.nightscout },
).joinToString(" · ").ifEmpty { "Off" }

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

/** Glucose sources (label, value, one-line description). Libre 3 directly is the default. */
private val SOURCES = listOf(
    Triple("Libre 3 directly", "direct", "Sukrara holds the sensor (scan it once with NFC)"),
    Triple("Juggluco", "juggluco", "Reads Juggluco's web server; Juggluco keeps the sensor and its alarms"),
)

@Composable
fun SourceSettings(onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    var confirmDirect by remember { mutableStateOf(false) }
    val direct = f.source == "direct"
    val nav = LocalNav.current
    SettingsPage("Glucose source", onBack) {
        SettingsIntro("Where Sukrara gets your readings from.")
        SettingsGroup {
            value("Source", SOURCES.first { it.second == f.source }.first + " · " + st.source + (st.sourceError?.let { " — $it" } ?: ""),
                icon = if (direct) R.drawable.ic_brand_abbott else null, leading = if (direct) null else ({ AppIcon(R.drawable.ic_brand_juggluco) })) { choosing = true }
            link("Sensor", if (direct) st.sensorSerial?.let { "Libre 3 $it · scan to start or take over" } ?: "Scan to start or take over"
                else "Unavailable while Juggluco holds the sensor", icon = R.drawable.ic_sym_nfc, enabled = direct, to = "sensor")
        }
        SettingsFooter("Only one app can hold a Libre 3. With Libre 3 directly, Juggluco's own alarms don't ring; use Alerts.")
    }
    if (choosing) ChoiceDialog("Source", SOURCES.map { Choice(it.first, it.second, it.third) }, f.source, { choosing = false }) { v ->
        if (v == "direct" && f.source != "direct") confirmDirect = true else set { it.copy(source = v) }
    }
    if (confirmDirect) AlertDialog(
        onDismissRequest = { confirmDirect = false },
        title = { Text("Read the sensor directly?") },
        text = { Text("Sukrara reads your Libre 3 itself: you scan the sensor once with NFC. Only one app can hold a " +
            "Libre 3, so stop Juggluco first (Pause app, then Force stop). While Sukrara holds the sensor, Juggluco's alarms " +
            "(low/high) do NOT ring.") },
        confirmButton = { Button(onClick = { confirmDirect = false; nav?.open("sensor") }) { Text("Scan sensor") } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text("Cancel") } },
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
    SettingsPage(PEOPLE, onBack) {
        SettingsIntro("The people you share with see your glucose live, end-to-end encrypted: over the internet or over Bluetooth nearby.")
        SettingsGroup {
            action("Share with someone", "Send an invite link", icon = R.drawable.ic_sym_share, onClick = a::share)
            action(if (pairing) "Pairing nearby…" else "Pair nearby", when {
                !u.bleOn -> "Unavailable while Bluetooth is off"
                pairing -> "Open until ${date(u.nearbyUntil)} · tap to extend 10 min"
                else -> "For 10 minutes, no internet needed"
            }, icon = R.drawable.ic_sym_bluetooth_searching, enabled = u.bleOn, onClick = a::nearbyPairing)
        }
        SettingsGroup("Followers") {
            if (active.isEmpty()) info("Nobody follows you yet")
            for (fl in active) link(fl.name, "Since ${date(fl.since)} · " + if (fl.f in u.writers) "can add entries" else "sees only",
                leading = { AvatarImage(fl.avatar, fl.name, 40.dp) }) { editing = fl }
            for (fl in u.followers.filter { it.revoked != null }) info(fl.name, "Removed ${date(fl.revoked!!)}", icon = R.drawable.ic_sym_person_remove)
        }
        SettingsGroup("How readings travel") {
            nostrRows(f) { choosing = true }
            switch("Over Bluetooth nearby", f.bleFollowers, icon = R.drawable.ic_brand_bluetooth,
                summary = if (u.bleOn) "${u.bleCentrals} connected now" else "Bluetooth is off") { v -> set { it.copy(bleFollowers = v) } }
        }
        SettingsGroup { link(APPS, "LibreView, xDrip+, Nightscout", icon = R.drawable.ic_sym_apps, to = "apps") } 
        SettingsFooter("Removing someone rotates the read key, so they stop receiving new readings at once. Followers you " +
            "allow to add entries can log insulin, carbs and notes for you.")
    }
    if (choosing) NostrIntervalDialog(f) { choosing = false }
    editing?.let { fl -> FollowerDialog(fl, fl.f in u.writers, a) { editing = null } }
}

/** Nostr relays, on both sharing pages (followers' phones and Macs read from them). */
@Composable
private fun SettingsGroupScope.nostrRows(f: FeatureState, onInterval: () -> Unit) {
    switch("Over the internet (Nostr relays)", f.nostr, icon = R.drawable.ic_brand_nostr, summary = "End-to-end encrypted; followers anywhere") { v ->
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
        title = { Text("Remove ${fl.name}?") },
        text = { Text("They stop receiving new readings immediately: Sukrara rotates the read key and re-sends it to everybody else.") },
        confirmButton = { Button(onClick = { a.revoke(fl.f); onClose() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    ) else AlertDialog(
        onDismissRequest = onClose,
        icon = { AvatarImage(fl.avatar, fl.name, 48.dp) },
        title = { Text(fl.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Following since ${date(fl.since)}", style = MaterialTheme.typography.bodyMedium)
                SettingsGroup { switch("Can add entries", writer, summary = "Insulin, carbs and notes") { a.setWriter(fl.f, it) } }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        dismissButton = { TextButton(onClick = { removing = true }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
    )
}

// ================================================================================================ root & LSPosed

private const val NEEDS_MODULE = "Needs the Sukrara LSPosed module"

/**
 * What a page's features are missing, with the one step that fixes it: root for Sukrara, then the LSPosed module
 * enabled with System UI in its scope and loaded. Checks again whenever the page comes back to the front.
 */
@Composable
private fun SystemAccessBanner(root: Boolean, module: Boolean) {
    if (indexing) return
    val ctx = LocalContext.current
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
        root && rootOk == false -> SettingsBanner(icon, "Root needed",
            "Grant Sukrara root in KernelSU (or Magisk): Superuser → Sukrara → allow. Then check again.",
            "Check again", { app.mauri.sukrara.status.RootStatusBar.checkRoot() })
        module && access.module == app.mauri.sukrara.features.SystemAccess.Module.MISSING -> SettingsBanner(icon, "LSPosed module off",
            "Install LSPosed, enable the Sukrara module and tick System UI in its scope, then restart System UI.", null, {})
        module && access.module == app.mauri.sukrara.features.SystemAccess.Module.NO_SCOPE -> SettingsBanner(icon, "System UI not in scope",
            "The Sukrara module is enabled in ${access.framework ?: "LSPosed"} but not for System UI." + (problem?.let { " ($it)" } ?: ""),
            "Add System UI", { app.mauri.sukrara.features.SystemAccess.requestSystemUi { problem = it } })
        module && access.module in setOf(app.mauri.sukrara.features.SystemAccess.Module.RESTART,
            app.mauri.sukrara.features.SystemAccess.Module.UPDATE) -> SettingsBanner(icon, "Restart System UI",
            if (access.module == app.mauri.sukrara.features.SystemAccess.Module.UPDATE)
                "Sukrara was updated: System UI still runs the previous module. Restart it to load the new one (the status bar redraws for a moment)."
            else "System UI runs without the Sukrara module. Restarting it loads the module (the status bar redraws for a moment).",
            if (rootOk == true && !restarting) "Restart System UI" else null, {
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
    val placement = Features.HOOK_POSITIONS.firstOrNull { it.second == f.hookPosition }?.first ?: f.hookPosition
    // "With the system icons" works with root alone; the other placements are drawn by the module
    fun needsModule(pos: String) = pos != app.mauri.sukrara.features.HookPosition.SLOT_PREFIX
    var choosing by remember { mutableStateOf(false) }
    val off = "Unavailable while the value in the status bar is off"
    val nm = ctx.getSystemService(NotificationManager::class.java)
    SettingsPage("Status bar", onBack) {
        SettingsIntro("Your glucose at a glance in the status bar, without opening anything.")
        SystemAccessBanner(root = true, module = true)
        SettingsGroup {
            switch("Live Update chip", f.chip, summary = "The number next to the clock") { v -> set { it.copy(chip = v) } }
            if (f.chip && !nm.canPostPromotedNotifications()) link("Allow Live Updates for Sukrara", "Needed for the chip", external = true) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
            switch("Number as notification icon", f.icon) { v -> set { it.copy(icon = v) } }
        }
        SettingsGroup("With root") {
            switch("Value in the status bar", f.hook && ok, enabled = ok, summary = when (root) {
                null -> "Checking root…"
                false -> "Unavailable: needs root for Sukrara (KernelSU)"
                true -> if (!f.hook) "Off" else if (needsModule(f.hookPosition) && !module) "Not shown: $placement needs the LSPosed module" else placement
            }) { v -> set { it.copy(hook = v) } }
            switch("Colour by range", f.hookColor, enabled = hookOn && module,
                summary = if (!hookOn) off else if (!module) NEEDS_MODULE else null) { v -> set { it.copy(hookColor = v) } }
            value("Placement", if (hookOn) placement else off, enabled = hookOn) { choosing = true }
            switch("Hide Sukrara's notification icon", f.hideNotifIcon, enabled = hookOn && module,
                summary = if (!hookOn) off else if (!module) NEEDS_MODULE else "In the status bar only; the notification stays in the shade") { v ->
                set { it.copy(hideNotifIcon = v) }
            }
        }
        SettingsFooter("The chip needs Android 16. The value in the status bar uses root; colour, hiding the notification " +
            "icon and every placement except \"With the system icons\" also need the Sukrara module enabled in LSPosed " +
            "(scope System UI), then a System UI restart." + if (f.systemIcons) " Icons: ${rootBar.state}." else "")
    }
    if (choosing) ChoiceDialog("Placement", Features.HOOK_POSITIONS.map { (label, pos) ->
            val ok = module || !needsModule(pos)
            Choice(label, pos, sub = if (ok) null else NEEDS_MODULE, enabled = ok)
        }, f.hookPosition,
        { choosing = false }) { pos -> set { it.copy(hookPosition = pos) } }
}

// ================================================================================================ lock screen

@Composable
fun LockscreenSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    fun change(u: (FeatureState) -> FeatureState) {
        set(u)
        app.mauri.sukrara.status.RootStatusBar.pushState(ctx)
        app.mauri.sukrara.widgets.SukraraHooks.refresh(ctx, "lock screen style", force = true)
    }
    val access by app.mauri.sukrara.features.SystemAccess.status.collectAsState()
    val module = access.active
    val on = f.lockWidget && module
    val off = if (!module) NEEDS_MODULE else "Unavailable while glucose on the lock screen is off"
    val bg = app.mauri.sukrara.features.LockBackground
    SettingsPage("Lock screen", onBack) {
        SettingsIntro("Your glucose and graph around the fingerprint icon. Tap it to add an entry without unlocking.")
        SystemAccessBanner(root = false, module = true)
        SettingsMainSwitch("Glucose on the lock screen", on, enabled = module) { v -> change { it.copy(lockWidget = v) } }
        SettingsGroup {
            value("Background", if (on) bg.CHOICES.first { it.second == f.lockBg }.first else off, enabled = on) { choosing = true }
            switch("In range in white", f.lockWhite, enabled = on, summary = if (on) "Highs and lows keep their colours" else off) { v ->
                change { it.copy(lockWhite = v) }
            }
        }
        SettingsFooter("Needs root and the Sukrara module enabled in LSPosed (scope System UI). Anyone holding the locked " +
            "phone can add an entry; turn this off if you don't want that.")
    }
    if (choosing) ChoiceDialog("Background", bg.CHOICES.map { Choice(it.first, it.second, it.third) }, f.lockBg,
        { choosing = false }) { v -> change { it.copy(lockBg = v) } }
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
    SettingsPage(APPS, onBack) {
        SettingsIntro("Your glucose in other apps: " + (if (wearer) "LibreView for your doctor, " else "") +
            "Garmin watches through the xDrip+ server, and anything that reads Nightscout.")
        if (wearer) {
            val lv by Treat.uploader.settings.collectAsState()
            SettingsGroup("LibreView") {
                link("LibreView", when {
                    lv.email.isEmpty() || !lv.hasPassword -> "Not set up"
                    f.libreview -> "Uploading as ${lv.email}"
                    else -> "Off · ${lv.email}"
                }, leading = { AppIcon(R.drawable.ic_brand_libreview) }, to = "libreview")
            }
        }
        SettingsGroup("xDrip+") {
            switch("xDrip+ server", f.garmin, leading = { AppIcon(R.drawable.ic_brand_xdrip) }, summary = "For Garmin watch faces and data fields · " + st.garmin +
                (if (st.watchPolledAt > 0) " · last asked ${sinceText(st.watchPolledAt / 1000)}" else "")) { v -> set { it.copy(garmin = v) } }
        }
        SettingsGroup("Nightscout") {
            switch("Nightscout-compatible API", f.nightscout, icon = R.drawable.ic_nightscout,
                summary = if (f.nightscout) "http://127.0.0.1:${Features.NIGHTSCOUT_PORT} · ${st.nightscout}" else "For Home Assistant, Nightguard, clocks") { v ->
                set { it.copy(nightscout = v) }
            }
            switch("Also on this Wi-Fi network", f.nightscoutLan, enabled = f.nightscout,
                summary = if (f.nightscout) "Other devices need the API secret" else "Unavailable while the API is off") { v -> set { it.copy(nightscoutLan = v) } }
            if (f.nightscout && f.nightscoutLan) action("Show API secret", icon = R.drawable.ic_sym_key) { secret = true }
        }
        if (wearer) SettingsGroup("Nostr") { nostrRows(f) { choosing = true } }
        if (wearer) SettingsGroup { link(PEOPLE, "Followers, invites, nearby pairing", icon = R.drawable.ic_sym_group, to = "sharing") }
        SettingsFooter("The xDrip+ server listens on 127.0.0.1:17580, like xDrip+ itself, so Garmin faces and data fields " +
            "made for xDrip+ read it. The Nightscout API is plain HTTP: share it only on networks you trust.")
    }
    if (choosing) NostrIntervalDialog(f) { choosing = false }
    if (secret) AlertDialog(
        onDismissRequest = { secret = false },
        title = { Text("API secret") },
        text = { Text(Features.nightscoutSecret(ctx), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
        confirmButton = { TextButton(onClick = { secret = false }) { Text("Close") } },
    )
}

private fun sinceText(t: Long): String {
    val s = System.currentTimeMillis() / 1000 - t
    return if (s < 90) "now" else "${s / 60} min ago"
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
    val off = "Unavailable while the upload is off"
    val direct = f.source == "direct"
    SettingsPage("LibreView", onBack) {
        SettingsIntro("LibreView is the record your doctor sees. Sukrara can upload your glucose and entries to it.")
        SettingsGroup { value("Account", if (signedIn) s.email else "Not signed in", icon = R.drawable.ic_sym_person) { signIn = true } }
        SettingsMainSwitch("Upload to LibreView", on, enabled = signedIn) { v -> set { it.copy(libreview = v) }; uploader.kick() }
        SettingsGroup {
            info("Status", if (!signedIn) "Sign in first" else if (on) status.replaceFirstChar { it.uppercase() } else "Off")
            switch("Glucose readings", s.glucose, enabled = on && direct, summary = when {
                !on -> off
                !direct -> "Only while Sukrara reads the sensor itself"
                else -> uploader.glucoseFrom?.let { "From ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000))}" }
                    ?: if (s.glucose) "From now on" else "Off"
            }) { uploader.configure(glucose = it) }
            switch("Insulin, carbs & notes", s.treatments, enabled = on, summary = if (on) "Yours and your followers'" else off) {
                uploader.configure(treatments = it)
            }
            switch("Use this phone as the account's device", s.takeover, enabled = on, summary = if (!on) off
                else if (s.takeover) "The Libre app or Juggluco stops uploading to this account" else "Uploads stop if another app holds the account") {
                uploader.configure(takeover = it)
            }
            value(stringResource(R.string.libreview_upload_every), if (on) intervalLabel(ctx, f.libreviewIntervalMin) else off, enabled = on) { choosing = true }
        }
        SettingsFooter("Uploaded entries can't be deleted in LibreView. Glucose goes up from the moment you switch it on.")
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
    SettingsPage("Backup", onBack) {
        SettingsIntro(if (saved) "Your identity is in a password manager. A new phone restores it and your followers continue."
            else "Not backed up: if you lose this phone, every follower must be invited again.")
        SettingsGroup {
            action(if (saved) "Save again" else "Save to password manager", if (saved) "Saved ${date(u.backupSavedAt)}" else "Recommended",
                icon = R.drawable.ic_sym_key, onClick = a::saveBackup)
            action("Recovery code", "An encrypted key to write down or store elsewhere", icon = R.drawable.ic_sym_history_edu) { showRecovery = true }
            info("Backup on relays", if (u.backupPublishedAt > 0) "Updated ${date(u.backupPublishedAt)}" else "Not yet", icon = R.drawable.ic_sym_hub)
        }
        transfer.group(this)
        SettingsFooter("The backup on relays holds your followers, keys and invites, encrypted to your identity: restoring " +
            "the identity brings them back. A settings file holds only the options: alerts, graph, status bar, lock " +
            "screen and sharing switches.")
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
            "Settings exported"
        }.getOrElse { "Couldn't write the file: ${it.message}" }
    }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = runCatching {
            val j = JSONObject(ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
            T.import(ctx, j) ?: "Settings imported"
        }.getOrElse { "Couldn't read the file: ${it.message}" }
    }
    return TransferUi(group = {
        SettingsGroup("Settings") {
            action("Export settings", "Save the options to a file", icon = R.drawable.ic_sym_file_export) { exporter.launch(T.fileName()) }
            action("Import settings", "Use the options from a file", icon = R.drawable.ic_sym_file_upload) { importer.launch(arrayOf(T.MIME, "text/plain", "*/*")) }
            action("Reset settings", "Every option back to its default", icon = R.drawable.ic_sym_restart_alt) { resetting = true }
        }
    }, dialogs = {
        if (resetting) AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text("Reset settings?") },
            text = { Text("Alerts, graph, status bar, lock screen and sharing options go back to their defaults. Your identity, " +
                "followers, sensor, glucose source and LibreView upload stay as they are.") },
            confirmButton = { Button(onClick = { T.reset(ctx); resetting = false; message = "Settings reset" }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel") } },
        )
        message?.let { m -> AlertDialog(onDismissRequest = { message = null }, text = { Text(m) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
    })
}

// ================================================================================================ advanced

@Composable
fun WearerAdvanced(u: OwnerUi, relays: Map<String, RelayStats>, a: OwnerActions, onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var choosing by remember { mutableStateOf(false) }
    val direct = f.source == "direct"
    SettingsPage("Advanced", onBack) {
        SettingsGroup("Relays") {
            card { if (relays.isEmpty()) Text("No relays yet", style = MaterialTheme.typography.bodyMedium) else RelayList(relays) }
            info("Access requests", if (u.inboxLive) "Live while the app is open" else "Checked with every upload (~5 min)")
            action("Check requests now", icon = R.drawable.ic_sym_update, onClick = a::checkNow)
        }
        SettingsGroup("Diagnostics") {
            link("Log", "What Sukrara did lately", icon = R.drawable.ic_sym_receipt_long, to = "log")
            info("Identity", "${u.iPub?.let { Proto.shortNpub(it) } ?: "–"} · key epoch ${u.epoch}", icon = R.drawable.ic_sym_key)
            info("Sent", "${u.liveSent} live · ${u.chunksSent} chunks · ${st.notificationPosts} notification posts, ${st.notificationSkipped} skipped")
        }
        SettingsGroup("Experimental") {
            value("Sensor link priority", if (direct) f.connPriority.replaceFirstChar { it.uppercase() }
                else "Unavailable while Juggluco holds the sensor", enabled = direct) { choosing = true }
            switch("Pause refreshes while the screen is off", f.screenOffSkip, summary = "Saves a post per minute; catches up on screen on") { v ->
                set { it.copy(screenOffSkip = v) }
            }
            if (BuildConfig.DEBUG) switch("Synthetic source", u.synthetic, summary = "Debug builds only", icon = R.drawable.ic_sym_bug_report, onChange = a::setSynthetic)
        }
    }
    if (choosing) ChoiceDialog("Sensor link priority", Features.PRIORITIES.map { Choice(it.replaceFirstChar { c -> c.uppercase() }, it) },
        f.connPriority, { choosing = false }) { v -> set { it.copy(connPriority = v) } }
}

@Composable
fun LogPage(log: List<String>, onBack: () -> Unit) {
    SettingsPage("Log", onBack) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp)) { if (log.isEmpty()) Text("Nothing yet") else LogList(log, max = 300) }
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
    SettingsPage("Settings", onBack) {
        SettingsSearchBar()
        SettingsGroup {
            link(u.name ?: "Add your name", "How you appear to ${u.wearer?.name ?: "the wearer"}",
                leading = { AvatarImage(u.me?.avatar, u.name ?: "", 40.dp) }, to = "profile")
        }
        SettingsGroup {
            link("Alerts", alertsSummary(alerts, follower = true), leading = { CategoryIcon(R.drawable.ic_sym_notifications, Category.RED) }, to = "alerts")
            link("Following", (u.wearer?.name ?: "Sukrara") + " · " + u.status, leading = { CategoryIcon(R.drawable.ic_sym_person, Category.BLUE) }, to = "following")
        }
        SettingsGroup {
            link("Graph & time in range", gs, leading = { CategoryIcon(R.drawable.ic_sym_monitoring, Category.PURPLE) }, to = "graph")
            link(APPS, appsSummary(f, wearer = false), leading = { CategoryIcon(R.drawable.ic_sym_apps, Category.ORANGE) }, to = "apps")
        }
        SettingsGroup {
            link("Advanced", "Relays, log, diagnostics", leading = { CategoryIcon(R.drawable.ic_sym_code, Category.GREY) }, to = "advanced")
            link("About Sukrara", "Version ${BuildConfig.VERSION_NAME} · source code · other devices",
                leading = { CategoryIcon(R.drawable.ic_sym_info, Category.GREY) }, to = "about")
        }
    }
}

@Composable
fun FollowingSettings(u: FollowerUi, a: FollowerActions, onBack: () -> Unit) {
    var leaving by remember { mutableStateOf(false) }
    SettingsPage("Following", onBack) {
        SettingsGroup {
            info(u.wearer?.name ?: "Sukrara", u.ownerPub?.let { Proto.shortNpub(it) }, icon = R.drawable.ic_sym_person)
            info("Can add entries", if (u.canWrite) "Yes, the wearer allowed it" else "No, ask the wearer")
            info("Bluetooth", u.ble, icon = R.drawable.ic_brand_bluetooth)
        }
        SettingsGroup { action("Stop following", "Readings already on this phone stay", icon = R.drawable.ic_sym_person_remove) { leaving = true } }
    }
    if (leaving) AlertDialog(
        onDismissRequest = { leaving = false },
        title = { Text("Stop following?") },
        text = { Text("You need a new invite to follow ${u.wearer?.name ?: "them"} again.") },
        confirmButton = { Button(onClick = { leaving = false; a.leave() }) { Text("Stop following") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("Cancel") } },
    )
}

@Composable
fun FollowerAdvanced(u: FollowerUi, relays: Map<String, RelayStats>, a: FollowerActions, onBack: () -> Unit) {
    val transfer = settingsTransfer()
    transfer.dialogs()
    SettingsPage("Advanced", onBack) {
        transfer.group(this)
        SettingsGroup("Relays") { card { if (relays.isEmpty()) Text("No relays yet", style = MaterialTheme.typography.bodyMedium) else RelayList(relays) } }
        SettingsGroup("Diagnostics") {
            link("Log", "What Sukrara did lately", icon = R.drawable.ic_sym_receipt_long, to = "log")
            info("Stored readings", "${u.count} on this phone · rejected events ${u.rejects}" + (u.lastReject?.let { " (last: $it)" } ?: ""))
            info("Keys", "Key epochs up to ${u.epoch} · trusted devices ${u.devices.joinToString { it.take(8) }}", icon = R.drawable.ic_sym_key)
            info("This phone", u.fPub?.let { Proto.npub(it) })
        }
        if (BuildConfig.DEBUG) SettingsGroup("Experimental") {
            switch("Subscribe without the relay-side author filter", u.testUnfiltered, summary = "Debug builds only",
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
private fun platformOf(name: String): Pair<String, Int> = when {
    name.endsWith(".apk") -> "Android" to R.drawable.ic_brand_android
    name.endsWith(".dmg") -> "Mac" to R.drawable.ic_brand_apple
    name.contains("mac") && name.endsWith(".zip") -> "Mac (zip)" to R.drawable.ic_brand_apple
    name.endsWith(".prg") || name.endsWith(".iq") -> "Garmin watch" to R.drawable.ic_brand_garmin
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
    SettingsPage("About Sukrara", onBack) {
        SettingsHeader { Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // the launcher icon is adaptive (no painterResource): draw what the launcher draws
            val icon = remember { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(288, 288).asImageBitmap() }
            androidx.compose.foundation.Image(icon, contentDescription = null, modifier = Modifier.size(96.dp))
            Text("Sukrara", style = MaterialTheme.typography.headlineSmall)
            Text("Version ${BuildConfig.VERSION_NAME}" + if (BuildConfig.DEBUG) " (debug)" else "", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        val r = release?.getOrNull()
        if (r != null && newer(r.tag, BuildConfig.VERSION_NAME)) SettingsGroup {
            action("Update to ${r.tag.removePrefix("v")}", "Download the new version", icon = R.drawable.ic_sym_download) { open(r.url) }
        }
        SettingsGroup("Sukrara on other devices") {
            when {
                release == null -> info("Looking for the latest release…")
                r == null -> link("Downloads", "Couldn't reach GitHub · open the releases page", icon = R.drawable.ic_sym_download, external = true) { open("$REPO/releases/latest") }
                else -> for (d in r.files) {
                    val (label, icon) = platformOf(d.name)
                    val mine = label == "Android" && r.tag.removePrefix("v") == BuildConfig.VERSION_NAME
                    link(label, d.name + if (mine) " · this app" else "", icon = icon, external = true) { open(d.url) }
                }
            }
        }
        SettingsGroup {
            link("Source code", "GitHub · GPL-3.0", icon = R.drawable.ic_brand_github, external = true) { open(REPO) }
            link("Website", "sukrara.mauri.app", icon = R.drawable.ic_sym_link, external = true) { open("https://sukrara.mauri.app") }
        }
        SettingsFooter("An alpha research app, not a medical device. Don't make treatment decisions from it alone.")
    }
}
