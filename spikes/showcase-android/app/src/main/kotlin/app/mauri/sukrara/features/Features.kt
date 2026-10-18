// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Every switchable feature of the wearer role, in one place (plain prefs, one StateFlow). The settings screen and the
 * adb-only [DebugReceiver] (investigation 11's battery lab) both write here; the running service reacts to changes
 * without restarting the process.
 */
data class FeatureState(
    /** "direct" (default: this app reads the Libre 3 itself) or "juggluco" (reads Juggluco's web server; it keeps the sensor and its alarms). */
    val source: String = "direct",
    /** Sharing over Nostr relays. */
    val nostr: Boolean = true,
    /** 0 = a live event per reading + a chunk every 5 min; N > 0 = no live events, one batch (chunk) every N min. */
    val nostrIntervalMin: Int = 0,
    /** Bluetooth followers: the v2 GATT service (7c6a0001-…), same events as the relays. */
    val bleFollowers: Boolean = true,
    /** Status bar: Live Update chip (promoted ongoing notification). */
    val chip: Boolean = true,
    /** Status bar: the notification small icon is the number itself (bitmap). */
    val icon: Boolean = true,
    /**
     * Derived, read-only: number + arrow as Android system icons next to Wi-Fi/battery (root daemon,
     * status/RootStatusBar). On whenever [hook] is on with a `slot:` placement.
     */
    val systemIcons: Boolean = false,
    /** Root status bar on (investigation 05): placement in [hookPosition]. */
    val hook: Boolean = false,
    /**
     * Where: next to the clock / left of the icons (drawn by the LSPosed module), or `slot:…` = as system icons
     * (root), optionally moved right after another icon and coloured — both by the module when it's loaded.
     */
    val hookPosition: String = HookPosition.AFTER_CLOCK,
    /** Colour the root status bar value by range (module only; off = like the clock / the other icons). */
    val hookColor: Boolean = true,
    /**
     * While the root status bar shows the value, drop Sukrara's notification icon from the status bar's notification
     * icons (module only). The notification stays in the shade, on the lock screen and on AOD. With the Live Update
     * chip showing, SystemUI already hides that icon itself.
     */
    val hideNotifIcon: Boolean = true,
    /**
     * Investigation 17: the lock screen widget (glucose + graph around the lock icon, tap = treatment dialog). Drawn
     * by the LSPosed module inside SystemUI, so without the module it does nothing.
     */
    val lockWidget: Boolean = true,
    /** What sits behind the lock screen widget for contrast: none, shadow, dim, card, frosted ([LockBackground]). */
    val lockBg: String = LockBackground.FROSTED,
    /** Lock screen widget light or dark, or following the system's dark theme ([LockAppearance]). */
    val lockLook: String = LockAppearance.AUTO,
    /** Don't re-post the glucose notification while the screen is off; catch up on screen on. */
    val screenOffSkip: Boolean = false,
    /** xDrip+-compatible local server on 127.0.0.1:17580, what Garmin watch apps read (only binds if the port is free). Off until wanted. */
    val garmin: Boolean = false,
    /** Nightscout-compatible read API (inv. 03 tier 1) on 127.0.0.1:17581, for Home Assistant / xDrip+ / Garmin / clocks. Off until wanted. */
    val nightscout: Boolean = false,
    /** Serve that API on the local network too (all interfaces). Non-loopback callers need the API secret. */
    val nightscoutLan: Boolean = false,
    /** Treatments + glucose upload to LibreView. OFF until the user sets it up. */
    val libreview: Boolean = false,
    /** LibreView upload cycle, minutes. */
    val libreviewIntervalMin: Int = 5,
    /** Direct mode only: sensor link priority "default" | "low" | "balanced" | "high". */
    val connPriority: String = "default",
    /** The "Set up LibreView" banner (home + settings) was dismissed. */
    val libreviewBannerDismissed: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("nostr", nostr).put("nostrInterval", nostrIntervalMin)
        .put("bleFollowers", bleFollowers)
        .put("chip", chip).put("icon", icon).put("systemIcons", systemIcons).put("hook", hook).put("hookPosition", hookPosition).put("hookColor", hookColor)
        .put("hideNotifIcon", hideNotifIcon).put("lockWidget", lockWidget)
        .put("lockBg", lockBg).put("lockLook", lockLook)
        .put("screenOffSkip", screenOffSkip).put("garmin", garmin)
        .put("nightscout", nightscout).put("nightscoutLan", nightscoutLan)
        .put("libreview", libreview).put("libreviewInterval", libreviewIntervalMin)
        .put("connPriority", connPriority).put("libreviewBanner", libreviewBannerDismissed)

    companion object {
        /** [toJson] back, any missing or malformed key keeps [d]'s value (settings import). */
        fun fromJson(j: JSONObject, d: FeatureState = FeatureState()) = FeatureState(
            source = j.optString("source", d.source), nostr = j.optBoolean("nostr", d.nostr),
            nostrIntervalMin = j.optInt("nostrInterval", d.nostrIntervalMin), bleFollowers = j.optBoolean("bleFollowers", d.bleFollowers),
            chip = j.optBoolean("chip", d.chip), icon = j.optBoolean("icon", d.icon), hook = j.optBoolean("hook", d.hook),
            hookPosition = j.optString("hookPosition", d.hookPosition), hookColor = j.optBoolean("hookColor", d.hookColor),
            hideNotifIcon = j.optBoolean("hideNotifIcon", d.hideNotifIcon), lockWidget = j.optBoolean("lockWidget", d.lockWidget),
            lockBg = j.optString("lockBg", d.lockBg), lockLook = j.optString("lockLook", d.lockLook),
            screenOffSkip = j.optBoolean("screenOffSkip", d.screenOffSkip), garmin = j.optBoolean("garmin", d.garmin),
            nightscout = j.optBoolean("nightscout", d.nightscout), nightscoutLan = j.optBoolean("nightscoutLan", d.nightscoutLan),
            libreview = j.optBoolean("libreview", d.libreview), libreviewIntervalMin = j.optInt("libreviewInterval", d.libreviewIntervalMin),
            connPriority = j.optString("connPriority", d.connPriority),
            libreviewBannerDismissed = j.optBoolean("libreviewBanner", d.libreviewBannerDismissed),
        )
    }
}

object Features {
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(FeatureState())
    val state: StateFlow<FeatureState> = _state
    val value: FeatureState get() = _state.value

    @Synchronized fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("features", Context.MODE_PRIVATE)
        prefs = p
        val d = FeatureState()
        _state.value = FeatureState(
            source = p.getString("source", d.source)!!,
            nostr = p.getBoolean("nostr", d.nostr),
            nostrIntervalMin = p.getInt("nostrInterval", d.nostrIntervalMin),
            bleFollowers = p.getBoolean("bleFollowers", d.bleFollowers),
            chip = p.getBoolean("chip", d.chip),
            icon = p.getBoolean("icon", d.icon),
            hook = p.getBoolean("hook", d.hook),
            hookPosition = p.getString("hookPosition", d.hookPosition)!!,
            hookColor = p.getBoolean("hookColor", d.hookColor),
            hideNotifIcon = p.getBoolean("hideNotifIcon", d.hideNotifIcon),
            lockWidget = p.getBoolean("lockWidget", d.lockWidget),
            lockBg = LockBackground.valid(p.getString("lockBg", d.lockBg)),
            lockLook = LockAppearance.valid(p.getString("lockLook", d.lockLook)),
            screenOffSkip = p.getBoolean("screenOffSkip", d.screenOffSkip),
            garmin = p.getBoolean("garmin", d.garmin),
            nightscout = p.getBoolean("nightscout", d.nightscout),
            nightscoutLan = p.getBoolean("nightscoutLan", d.nightscoutLan),
            libreview = p.getBoolean("libreview", d.libreview),
            libreviewIntervalMin = p.getInt("libreviewInterval", d.libreviewIntervalMin),
            connPriority = p.getString("connPriority", d.connPriority)!!,
            libreviewBannerDismissed = p.getBoolean("libreviewBanner", d.libreviewBannerDismissed),
        ).let { it.copy(systemIcons = it.hook && it.hookPosition.startsWith(HookPosition.SLOT_PREFIX)) }
    }

    @Synchronized fun update(f: (FeatureState) -> FeatureState): FeatureState {
        val s = f(_state.value).let {
            it.copy(
                nostrIntervalMin = it.nostrIntervalMin.coerceIn(0, 240),
                systemIcons = it.hook && it.hookPosition.startsWith(HookPosition.SLOT_PREFIX),
                libreviewIntervalMin = it.libreviewIntervalMin.coerceIn(1, 240),
                source = if (it.source == "juggluco") "juggluco" else "direct",
                connPriority = it.connPriority.takeIf { p -> p in PRIORITIES } ?: "default",
                lockBg = LockBackground.valid(it.lockBg),
                lockLook = LockAppearance.valid(it.lockLook),
            )
        }
        prefs?.edit()?.apply {
            putString("source", s.source); putBoolean("nostr", s.nostr); putInt("nostrInterval", s.nostrIntervalMin)
            putBoolean("bleFollowers", s.bleFollowers)
            putBoolean("chip", s.chip); putBoolean("icon", s.icon); putBoolean("screenOffSkip", s.screenOffSkip)
            putBoolean("hook", s.hook); putString("hookPosition", s.hookPosition); putBoolean("hookColor", s.hookColor)
            putBoolean("hideNotifIcon", s.hideNotifIcon); putBoolean("lockWidget", s.lockWidget)
            putString("lockBg", s.lockBg); putString("lockLook", s.lockLook)
            putBoolean("garmin", s.garmin); putBoolean("nightscout", s.nightscout); putBoolean("nightscoutLan", s.nightscoutLan)
            putBoolean("libreview", s.libreview)
            putInt("libreviewInterval", s.libreviewIntervalMin); putString("connPriority", s.connPriority)
            putBoolean("libreviewBanner", s.libreviewBannerDismissed)
        }?.apply()
        _state.value = s
        return s
    }

    val PRIORITIES = listOf("default", "low", "balanced", "high")

    /** Placements offered in Settings (their words: ui/SettingsLabels.kt). All need root; all but "System icons" need the LSPosed module. */
    val HOOK_POSITIONS = listOf(
        HookPosition.AFTER_CLOCK,
        HookPosition.BEFORE_CLOCK,
        "${HookPosition.ICONS_PREFIX}0",
        HookPosition.SLOT_PREFIX,   // leftmost system icons; works with root alone (no module)
        "${HookPosition.SLOT_PREFIX}vpn",
        "${HookPosition.SLOT_PREFIX}bluetooth",
        "${HookPosition.SLOT_PREFIX}mobile",
        "${HookPosition.SLOT_PREFIX}wifi",
    )

    const val NIGHTSCOUT_PORT = 17581

    /**
     * The local Nightscout API's secret (what HA / xDrip+ call "API secret"; Nightguard / Garmin take it as
     * `?token=`). Generated once, 16 lowercase letters/digits; only callers from another device need it.
     */
    @Synchronized fun nightscoutSecret(ctx: Context): String {
        val p = ctx.applicationContext.getSharedPreferences("features", Context.MODE_PRIVATE)
        p.getString("nightscoutSecret", null)?.let { return it }
        val abc = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        val s = (1..16).map { abc[rnd.nextInt(abc.length)] }.joinToString("")
        p.edit().putString("nightscoutSecret", s).apply()
        return s
    }

    /** The phone's Wi-Fi/LAN IPv4 addresses, for showing the LAN URL. */
    fun lanAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().map { it.hostAddress ?: "" }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())
}

/**
 * Contrast behind the lock screen widget (investigation 17), after Material's text protection (m1.material.io/style/
 * imagery: scrims). Shadow is drawn by the widget, the others by the LSPosed module around it. Each follows the
 * widget's [LockAppearance]: dark (black tint, light content) or light (the home widgets' background colour, dark
 * content).
 */
object LockBackground {
    const val NONE = "none"
    const val SHADOW = "shadow"
    const val DIM = "dim"
    const val CARD = "card"
    /** The wallpaper blurred *and* tinted (dark: black 35 %, light: the home widgets' background colour). */
    const val FROSTED = "frosted"
    val ALL = listOf(NONE, SHADOW, DIM, CARD, FROSTED)
    /** Unknown or old values (no backwards compatibility) → the default. */
    fun valid(v: String?) = v?.takeIf { it in ALL } ?: FROSTED
    /** Card and frosted glass have an edge: the widget keeps its content off it. */
    fun boxed(b: String) = b == CARD || b == FROSTED
}

/**
 * The lock screen widget's colours: dark (light content on a black tint, the original look) or light (the home-screen
 * widgets' look: their Material You background colour, dark content), or [AUTO] = the system's dark theme decides.
 */
object LockAppearance {
    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"
    val ALL = listOf(AUTO, LIGHT, DARK)
    fun valid(v: String?) = v?.takeIf { it in ALL } ?: AUTO
    /** Whether [look] draws dark, given the system's current night mode. */
    fun dark(look: String, systemNight: Boolean) = when (look) { LIGHT -> false; DARK -> true; else -> systemNight }
    fun dark(look: String, c: android.content.Context) = dark(look,
        (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES)
}

/** Where the SystemUI hook places the glucose view. Shared with the hook through the broadcast. */
object HookPosition {
    const val AFTER_CLOCK = "after_clock"
    const val BEFORE_CLOCK = "before_clock"
    /** "icons:0" = left of all right-side icons ("icons:-1", right of the battery, gets clipped by the corner). */
    const val ICONS_PREFIX = "icons:"
    /** "slot:" = as system icons (root); "slot:wifi" = moved right after that icon (needs the module too). */
    const val SLOT_PREFIX = "slot:"
}
