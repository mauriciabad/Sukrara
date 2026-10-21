// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import org.json.JSONObject
import kotlin.math.roundToInt

/** The 8 alert kinds of spikes/alerts/SPEC.md §1. */
object Kind {
    const val VERY_LOW = "very_low"
    const val LOW = "low"
    const val HIGH = "high"
    const val VERY_HIGH = "very_high"
    const val EXTREMELY_HIGH = "extremely_high"
    const val FALLING_FAST = "falling_fast"
    const val RISING_FAST = "rising_fast"
    const val NO_DATA = "no_data"

    val ALL = listOf(VERY_LOW, LOW, HIGH, VERY_HIGH, EXTREMELY_HIGH, FALLING_FAST, RISING_FAST, NO_DATA)
    /** Level alerts by severity: the first that matches wins. */
    val LEVELS = listOf(VERY_LOW, EXTREMELY_HIGH, VERY_HIGH, LOW, HIGH)
    val RATES = listOf(FALLING_FAST, RISING_FAST)
    /** What the high-side mute rules can quiet. Extremely high is never muted. */
    val HIGH_SIDE = setOf(HIGH, VERY_HIGH)
    /** Alerts below a threshold (strictly `<`: low 70 alerts at 69); the rest of the levels are `>=`. */
    val LOW_SIDE = setOf(VERY_LOW, LOW)
    /**
     * Never muted from a notification's Mute action (the safety net on each side); Slow down still works on them, and
     * a very low inside a muted low episode alerts at once.
     */
    val ALWAYS = setOf(VERY_LOW, EXTREMELY_HIGH)

    /** Severity within a side (an episode, see [AlertEngine]): moving to a higher one alerts at once. */
    fun severity(k: String) = when (k) { VERY_LOW, VERY_HIGH -> 2; EXTREMELY_HIGH -> 3; else -> 1 }

    /**
     * The side of [k]: one alert episode per side. Lows and falling fast are answered by carbs, highs and rising fast
     * by rapid insulin (see [treatment]).
     */
    fun side(k: String) = when (k) { in LOW_SIDE -> "low"; HIGH, VERY_HIGH, EXTREMELY_HIGH -> "high"; else -> k }

    /** The treatment that answers [k] ("carbs" / "rapid"), whatever the mute settings, or null (no data). */
    fun treatment(k: String) = when (k) {
        in LOW_SIDE, FALLING_FAST -> "carbs"
        HIGH, VERY_HIGH, EXTREMELY_HIGH, RISING_FAST -> "rapid"
        else -> null
    }

    fun label(k: String) = when (k) {
        VERY_LOW -> "Very low"; LOW -> "Low"; HIGH -> "High"; VERY_HIGH -> "Very high"; EXTREMELY_HIGH -> "Extremely high"
        FALLING_FAST -> "Falling fast"; RISING_FAST -> "Rising fast"; else -> "No data"
    }

    /**
     * The alert's headline. Lows and highs say when the treatment for them was already logged in this episode
     * ([treated] = 1, or 2 for two or more): "Low glucose · eat more" / "· eat more again". Highs never suggest more
     * insulin (stacking doses is dangerous): "High glucose · still high after insulin" / "· after more insulin".
     */
    fun message(k: String, treated: Int = 0): String {
        val base = when (k) {
            in LOW_SIDE, HIGH, VERY_HIGH, EXTREMELY_HIGH -> "${label(k)} glucose"
            NO_DATA -> "No glucose data"
            else -> label(k)
        }
        val more = when {
            treated <= 0 || k !in LEVELS -> null
            k in LOW_SIDE -> if (treated == 1) "eat more" else "eat more again"
            else -> if (treated == 1) "still high after insulin" else "still high after more insulin"
        }
        return if (more == null) base else "$base · $more"
    }

    /** Unit of [KindCfg.value]. */
    fun unit(k: String) = when (k) { in RATES -> "mg/dL/min"; NO_DATA -> "min"; else -> "mg/dL" }

    /** Notification group: one notification per group, a new alert replaces the previous one. */
    fun group(k: String) = when (k) { in RATES -> "rate"; NO_DATA -> NO_DATA; else -> "level" }
}

/**
 * One kind's rule. [value] = threshold in mg/dL, rate in mg/dL/min (positive) or minutes for no data.
 * [alarm]: ring like an alarm clock (alarm volume, "Alarms" in Do Not Disturb) instead of as a notification.
 */
data class KindCfg(val on: Boolean, val value: Double, val repeatMin: Int, val alarm: Boolean = false)

/** spikes/alerts/SPEC.md §3, same JSON as Sukrara for Mac. */
data class AlertSettings(
    val on: Boolean = true,
    val kinds: Map<String, KindCfg> = DEFAULTS,
    val muteInsulin: Boolean = true,
    val muteInsulinMin: Int = 60,
    val muteFalling: Boolean = true,
    val muteFallingMin: Int = 20,
    val muteCarbs: Boolean = true,
    val muteCarbsMin: Int = 15,
    val pack: String = "quest",
    val packFor: Map<String, String> = emptyMap(),
    val shuffle: Boolean = true,
    /** The alert notification's actions (SPEC §2): *Mute <n> min* and the interval *Slow down* repeats at. */
    val actionMuteMin: Int = 30,
    /** 30: slower than every level alert's default repeat (5–15 min), so *Slow down* shows on all of them. */
    val slowMin: Int = 30,
    /**
     * Followers: "wearer" = mirror the wearer's alerts, "own" = these rules. Derived from the follower's Settings from
     * ([app.mauri.sukrara.settings.WearerSettings.alertSource]: "wearer" in Wearer mode, else "own"), never chosen.
     */
    val source: String = "wearer",
) {
    fun cfg(k: String): KindCfg = kinds[k] ?: DEFAULTS.getValue(k)

    /**
     * The *Slow down* intervals the settings offer: only ones slower than every level alert's repeat (the alerts that
     * repeat often enough to want it), so the button shows on all of them. An interval the alert already repeats at
     * hides the button (SPEC §2): 10 min, offered before, hid it on every default alert but very low.
     */
    fun slowChoices(): List<Int> = SLOW_PRESETS.filter { it > slowFloor() }.ifEmpty { listOf(SLOW_RANGE.last) }

    /** The longest repeat of the enabled level alerts: a *Slow down* interval must be longer (custom minutes too). */
    fun slowFloor(): Int = Kind.LEVELS.map { cfg(it) }.filter { it.on && it.repeatMin > 0 }.maxOfOrNull { it.repeatMin } ?: 0
    fun soundOf(k: String): String = packFor[k] ?: pack

    /**
     * The treatment kind whose mute window quiets [k] now, or null: the rule is off, or [k] is never muted (extremely
     * high, no data). Carbs quiet very low, low and falling fast; rapid insulin high, very high and rising fast.
     */
    fun mutedBy(k: String): String? = when {
        k == Kind.EXTREMELY_HIGH -> null
        Kind.treatment(k) == "carbs" && muteCarbs -> "carbs"
        Kind.treatment(k) == "rapid" && muteInsulin -> "rapid"
        else -> null
    }

    /** How long a [treatment] ("carbs" / "rapid") quiets the alerts it answers, in seconds. */
    fun muteS(treatment: String): Long = (if (treatment == "rapid") muteInsulinMin else muteCarbsMin) * 60L

    fun toJson(): JSONObject {
        val a = JSONObject()
        for (k in Kind.ALL) {
            val c = cfg(k)
            val key = when (k) { in Kind.RATES -> "rate"; Kind.NO_DATA -> "min"; else -> "mgdl" }
            a.put(k, JSONObject().put("on", c.on).put(key, if (key == "rate") c.value else c.value.toInt()).put("repeatMin", c.repeatMin).put("alarm", c.alarm))
        }
        val kindsJ = JSONObject()
        packFor.forEach { (k, v) -> kindsJ.put(k, v) }
        return JSONObject().put("v", 1).put("on", on).put("alerts", a)
            .put("muteAfterInsulin", JSONObject().put("on", muteInsulin).put("min", muteInsulinMin))
            .put("muteFallingHigh", JSONObject().put("on", muteFalling).put("min", muteFallingMin))
            .put("muteAfterCarbs", JSONObject().put("on", muteCarbs).put("min", muteCarbsMin))
            .put("sounds", JSONObject().put("pack", pack).put("kinds", kindsJ).put("shuffle", shuffle))
            .put("notificationActions", JSONObject().put("muteMin", actionMuteMin).put("slowMin", slowMin))
            .put("source", source)
    }

    companion object {
        /** Minutes a notification's Mute (preset or typed) and Slow down accept (SettingsSpec, spikes/settings/settings.json). */
        val MUTE_RANGE: IntRange = SettingsSpec.ACTION_MUTE.let { it.min.toInt()..it.max.toInt() }
        val SLOW_RANGE: IntRange = SettingsSpec.ACTION_SLOW.let { it.min.toInt()..it.max.toInt() }
        /** The settings' presets (minutes): Mute and Slow down, the same lists on Android and Mac. */
        val MUTE_PRESETS: List<Int> = SettingsSpec.ACTION_MUTE.presets.map { it.toInt() }
        val SLOW_PRESETS: List<Int> = SettingsSpec.ACTION_SLOW.presets.map { it.toInt() }

        /** The number format of [k]'s threshold ([KindCfg.value]). */
        fun thresholdSpec(k: String): NumberSpec = when (k) {
            Kind.VERY_LOW -> SettingsSpec.THRESHOLD_VERY_LOW; Kind.LOW -> SettingsSpec.THRESHOLD_LOW
            Kind.HIGH -> SettingsSpec.THRESHOLD_HIGH; Kind.VERY_HIGH -> SettingsSpec.THRESHOLD_VERY_HIGH
            Kind.EXTREMELY_HIGH -> SettingsSpec.THRESHOLD_EXTREMELY_HIGH; Kind.NO_DATA -> SettingsSpec.THRESHOLD_NO_DATA
            else -> SettingsSpec.THRESHOLD_RATE
        }
        private fun NumberSpec.int(v: Int): Int = coerce(v.toDouble()).roundToInt()

        /** The user's own settings (2026-10-18). */
        val DEFAULTS = mapOf(
            Kind.VERY_LOW to KindCfg(true, 55.0, 5, alarm = true),
            Kind.LOW to KindCfg(true, 70.0, 5),
            Kind.HIGH to KindCfg(true, 180.0, 15),
            Kind.VERY_HIGH to KindCfg(true, 250.0, 10),
            Kind.EXTREMELY_HIGH to KindCfg(true, 300.0, 5, alarm = true),
            Kind.FALLING_FAST to KindCfg(false, 2.0, 30),
            Kind.RISING_FAST to KindCfg(false, 2.0, 30),
            Kind.NO_DATA to KindCfg(true, 20.0, 60),
        )

        fun fromJson(j: JSONObject): AlertSettings {
            val d = AlertSettings()
            val a = j.optJSONObject("alerts") ?: JSONObject()
            val kinds = Kind.ALL.associateWith { k ->
                val def = DEFAULTS.getValue(k)
                val o = a.optJSONObject(k) ?: return@associateWith def
                val key = when (k) { in Kind.RATES -> "rate"; Kind.NO_DATA -> "min"; else -> "mgdl" }
                KindCfg(o.optBoolean("on", def.on), thresholdSpec(k).coerce(o.optDouble(key, def.value)),
                    SettingsSpec.ALERT_REPEAT.int(o.optInt("repeatMin", def.repeatMin)), o.optBoolean("alarm", def.alarm))
            }
            val mi = j.optJSONObject("muteAfterInsulin")
            val mf = j.optJSONObject("muteFallingHigh")
            val mc = j.optJSONObject("muteAfterCarbs")
            val s = j.optJSONObject("sounds")
            val na = j.optJSONObject("notificationActions")
            val pf = s?.optJSONObject("kinds")
            return AlertSettings(
                on = j.optBoolean("on", d.on),
                kinds = kinds,
                muteInsulin = mi?.optBoolean("on", d.muteInsulin) ?: d.muteInsulin,
                muteInsulinMin = SettingsSpec.MUTE_INSULIN.int(mi?.optInt("min", d.muteInsulinMin) ?: d.muteInsulinMin),
                muteFalling = mf?.optBoolean("on", d.muteFalling) ?: d.muteFalling,
                muteFallingMin = SettingsSpec.MUTE_FALLING.int(mf?.optInt("min", d.muteFallingMin) ?: d.muteFallingMin),
                muteCarbs = mc?.optBoolean("on", d.muteCarbs) ?: d.muteCarbs,
                muteCarbsMin = SettingsSpec.MUTE_CARBS.int(mc?.optInt("min", d.muteCarbsMin) ?: d.muteCarbsMin),
                pack = s?.optString("pack", d.pack) ?: d.pack,
                packFor = pf?.keys()?.asSequence()?.filter { it in Kind.ALL }?.associateWith { pf.getString(it) } ?: emptyMap(),
                shuffle = s?.optBoolean("shuffle", d.shuffle) ?: d.shuffle,
                actionMuteMin = SettingsSpec.ACTION_MUTE.int(na?.optInt("muteMin", d.actionMuteMin) ?: d.actionMuteMin),
                slowMin = SettingsSpec.ACTION_SLOW.int(na?.optInt("slowMin", d.slowMin) ?: d.slowMin),
                source = j.optString("source", d.source).takeIf { it == "own" || it == "wearer" } ?: d.source,
            )
        }
    }
}
