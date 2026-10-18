// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import org.json.JSONObject

/** The 8 alert kinds of spikes/alerts/SPEC.md §1. */
object Kind {
    const val URGENT_LOW = "urgent_low"
    const val LOW = "low"
    const val HIGH = "high"
    const val VERY_HIGH = "very_high"
    const val EXTREMELY_HIGH = "extremely_high"
    const val FALLING_FAST = "falling_fast"
    const val RISING_FAST = "rising_fast"
    const val NO_DATA = "no_data"

    val ALL = listOf(URGENT_LOW, LOW, HIGH, VERY_HIGH, EXTREMELY_HIGH, FALLING_FAST, RISING_FAST, NO_DATA)
    /** Level alerts by severity: the first that matches wins. */
    val LEVELS = listOf(URGENT_LOW, EXTREMELY_HIGH, VERY_HIGH, LOW, HIGH)
    val RATES = listOf(FALLING_FAST, RISING_FAST)
    /** What the high-side mute rules can quiet. Extremely high is never muted, like urgent low. */
    val HIGH_SIDE = setOf(HIGH, VERY_HIGH)
    /** Alerts below a threshold (strictly `<`); the rest of the levels are `>=`. */
    val LOW_SIDE = setOf(URGENT_LOW, LOW)

    fun label(k: String) = when (k) {
        URGENT_LOW -> "Urgent low"; LOW -> "Low"; HIGH -> "High"; VERY_HIGH -> "Very high"; EXTREMELY_HIGH -> "Extremely high"
        FALLING_FAST -> "Falling fast"; RISING_FAST -> "Rising fast"; else -> "No data"
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
    val pack: String = "arcade",
    val packFor: Map<String, String> = emptyMap(),
    val shuffle: Boolean = true,
    /** Followers: "wearer" = mirror the wearer's alerts, "own" = these rules. */
    val source: String = "wearer",
) {
    fun cfg(k: String): KindCfg = kinds[k] ?: DEFAULTS.getValue(k)
    fun soundOf(k: String): String = packFor[k] ?: pack

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
            .put("source", source)
    }

    companion object {
        val DEFAULTS = mapOf(
            Kind.URGENT_LOW to KindCfg(true, 55.0, 5, alarm = true),
            Kind.LOW to KindCfg(true, 65.0, 15, alarm = true),
            Kind.HIGH to KindCfg(true, 185.0, 30),
            Kind.VERY_HIGH to KindCfg(true, 250.0, 30),
            Kind.EXTREMELY_HIGH to KindCfg(true, 300.0, 15),
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
                KindCfg(o.optBoolean("on", def.on), o.optDouble(key, def.value), o.optInt("repeatMin", def.repeatMin),
                    o.optBoolean("alarm", def.alarm))
            }
            val mi = j.optJSONObject("muteAfterInsulin")
            val mf = j.optJSONObject("muteFallingHigh")
            val mc = j.optJSONObject("muteAfterCarbs")
            val s = j.optJSONObject("sounds")
            val pf = s?.optJSONObject("kinds")
            return AlertSettings(
                on = j.optBoolean("on", d.on),
                kinds = kinds,
                muteInsulin = mi?.optBoolean("on", d.muteInsulin) ?: d.muteInsulin,
                muteInsulinMin = mi?.optInt("min", d.muteInsulinMin) ?: d.muteInsulinMin,
                muteFalling = mf?.optBoolean("on", d.muteFalling) ?: d.muteFalling,
                muteFallingMin = mf?.optInt("min", d.muteFallingMin) ?: d.muteFallingMin,
                muteCarbs = mc?.optBoolean("on", d.muteCarbs) ?: d.muteCarbs,
                muteCarbsMin = mc?.optInt("min", d.muteCarbsMin) ?: d.muteCarbsMin,
                pack = s?.optString("pack", d.pack) ?: d.pack,
                packFor = pf?.keys()?.asSequence()?.filter { it in Kind.ALL }?.associateWith { pf.getString(it) } ?: emptyMap(),
                shuffle = s?.optBoolean("shuffle", d.shuffle) ?: d.shuffle,
                source = j.optString("source", d.source).takeIf { it == "own" || it == "wearer" } ?: d.source,
            )
        }
    }
}
