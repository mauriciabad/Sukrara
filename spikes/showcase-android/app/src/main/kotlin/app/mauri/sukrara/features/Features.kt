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
    /** "juggluco" (default, keeps Juggluco's alarms) or "direct" (this app reads the Libre 3 itself). */
    val source: String = "juggluco",
    /** Sharing over Nostr relays. */
    val nostr: Boolean = true,
    /** 0 = a live event per reading + a chunk every 5 min; N > 0 = no live events, one batch (chunk) every N min. */
    val nostrIntervalMin: Int = 0,
    /** Bluetooth followers: the v2 GATT service (7c6a0001-…), same events as the relays. */
    val bleFollowers: Boolean = true,
    /** Legacy Mac link: GlucoBridge's GATT service 6c1f0001-… (READING / HISTORY / TREATMENTS) for GlucoseBar. */
    val macBle: Boolean = true,
    /** Status bar: Live Update chip (promoted ongoing notification). */
    val chip: Boolean = true,
    /** Status bar: the notification small icon is the number itself (bitmap). */
    val icon: Boolean = true,
    /** Not bundled tonight (needs root): system-icon slot daemon. Kept as a switch so the battery lab sees "off". */
    val systemIcons: Boolean = false,
    /** Not bundled tonight (needs the LSPosed module): SystemUI hook. Kept as a switch, always reported off. */
    val hook: Boolean = false,
    /** Don't re-post the glucose notification while the screen is off; catch up on screen on. */
    val screenOffSkip: Boolean = false,
    /** xDrip+-compatible local server on 127.0.0.1:17580 for Garmin watch apps (only binds if the port is free). */
    val garmin: Boolean = true,
    /** Treatments + glucose upload to LibreView. OFF until the user sets it up. */
    val libreview: Boolean = false,
    /** LibreView upload cycle, minutes. */
    val libreviewIntervalMin: Int = 5,
    /** Direct mode only: sensor link priority "default" | "low" | "balanced" | "high". */
    val connPriority: String = "default",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("nostr", nostr).put("nostrInterval", nostrIntervalMin)
        .put("bleFollowers", bleFollowers).put("macBle", macBle)
        .put("chip", chip).put("icon", icon).put("systemIcons", systemIcons).put("hook", hook)
        .put("screenOffSkip", screenOffSkip).put("garmin", garmin)
        .put("libreview", libreview).put("libreviewInterval", libreviewIntervalMin)
        .put("connPriority", connPriority)
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
            macBle = p.getBoolean("macBle", d.macBle),
            chip = p.getBoolean("chip", d.chip),
            icon = p.getBoolean("icon", d.icon),
            systemIcons = false,
            hook = false,
            screenOffSkip = p.getBoolean("screenOffSkip", d.screenOffSkip),
            garmin = p.getBoolean("garmin", d.garmin),
            libreview = p.getBoolean("libreview", d.libreview),
            libreviewIntervalMin = p.getInt("libreviewInterval", d.libreviewIntervalMin),
            connPriority = p.getString("connPriority", d.connPriority)!!,
        )
    }

    @Synchronized fun update(f: (FeatureState) -> FeatureState): FeatureState {
        val s = f(_state.value).let {
            it.copy(
                systemIcons = false, hook = false,   // not bundled (see FeatureState)
                nostrIntervalMin = it.nostrIntervalMin.coerceIn(0, 240),
                libreviewIntervalMin = it.libreviewIntervalMin.coerceIn(1, 240),
                source = if (it.source == "direct") "direct" else "juggluco",
                connPriority = it.connPriority.takeIf { p -> p in PRIORITIES } ?: "default",
            )
        }
        prefs?.edit()?.apply {
            putString("source", s.source); putBoolean("nostr", s.nostr); putInt("nostrInterval", s.nostrIntervalMin)
            putBoolean("bleFollowers", s.bleFollowers); putBoolean("macBle", s.macBle)
            putBoolean("chip", s.chip); putBoolean("icon", s.icon); putBoolean("screenOffSkip", s.screenOffSkip)
            putBoolean("garmin", s.garmin); putBoolean("libreview", s.libreview)
            putInt("libreviewInterval", s.libreviewIntervalMin); putString("connPriority", s.connPriority)
        }?.apply()
        _state.value = s
        return s
    }

    val PRIORITIES = listOf("default", "low", "balanced", "high")
}
