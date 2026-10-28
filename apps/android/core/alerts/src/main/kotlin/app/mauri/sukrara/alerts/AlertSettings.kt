// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

/**
 * One kind's rule. [threshold]: mg/dL for levels, mg/dL/min (positive) for rates, minutes for no data.
 * [repeatMin] 0 = once per episode. [alarm]: ring like an alarm (alarm volume / Time Sensitive), local to the device.
 */
data class KindRule(val on: Boolean, val threshold: Double, val repeatMin: Int, val alarm: Boolean = false)

/** A treatment / falling-high quiet rule (SPEC §2): on, and its window in minutes. */
data class QuietRule(val on: Boolean, val min: Int)

/** SPEC §3 `sounds`: the pack every kind uses, per-kind overrides, a random variant each time. */
data class SoundSettings(
    val pack: String = "quest",
    val perKind: Map<AlertKind, String> = emptyMap(),
    val shuffle: Boolean = true,
) {
    fun soundOf(kind: AlertKind): String = perKind[kind] ?: pack
}

/**
 * SPEC §3 `notificationActions` (per device): the first length *Mute…* offers, the interval *Remind less* sets, and
 * the margin ([backMgdl] inside the threshold for [backMin] minutes) that ends Mute and Remind less.
 */
data class NotificationActions(
    val muteMin: Int = 30,
    val relaxMin: Int = 30,
    val backMgdl: Int = 10,
    val backMin: Int = 10,
)

/** Followers: mirror the wearer's `alert` events, or run these rules on the followed data (SPEC §3 `source`). */
enum class AlertSource(val wire: String) { WEARER("wearer"), OWN("own") }

/** The alert settings of SPEC §3 (the same JSON on Android, Mac and the followers' mirror; [AlertSettingsCodec]). */
data class AlertSettings(
    val on: Boolean = true,
    val rules: Map<AlertKind, KindRule> = DEFAULT_RULES,
    val afterInsulin: QuietRule = QuietRule(on = true, min = 60),
    val fallingHigh: QuietRule = QuietRule(on = true, min = 20),
    val afterCarbs: QuietRule = QuietRule(on = true, min = 15),
    val sounds: SoundSettings = SoundSettings(),
    val actions: NotificationActions = NotificationActions(),
    val source: AlertSource = AlertSource.WEARER,
) {
    fun rule(kind: AlertKind): KindRule = rules[kind] ?: DEFAULT_RULES.getValue(kind)

    fun withRule(kind: AlertKind, rule: KindRule): AlertSettings = copy(rules = rules + (kind to rule))

    /**
     * The treatment whose window quiets [kind] now, or null: its rule is off, or [kind] is never quieted by a
     * treatment (extremely high, no data).
     */
    fun mutedBy(kind: AlertKind): TreatmentKind? = when {
        kind == AlertKind.EXTREMELY_HIGH -> null
        kind.answeredBy == TreatmentKind.CARBS -> TreatmentKind.CARBS.takeIf { afterCarbs.on }
        kind.answeredBy == TreatmentKind.RAPID -> TreatmentKind.RAPID.takeIf { afterInsulin.on }
        else -> null
    }

    /** How long a [treatment] quiets the alerts it answers, in seconds (its window, whether the rule is on or not). */
    fun quietSeconds(treatment: TreatmentKind): Long =
        (if (treatment == TreatmentKind.RAPID) afterInsulin.min else afterCarbs.min) * 60L

    /** The longest repeat of the enabled level alerts: a *Remind less* interval must be slower (SPEC §3 `relaxMin`). */
    fun relaxFloor(): Int = AlertKind.LEVELS_BY_SEVERITY.map(::rule).filter { it.on && it.repeatMin > 0 }
        .maxOfOrNull { it.repeatMin } ?: 0

    /** The *Remind less* intervals the settings offer: only ones slower than every enabled level alert's repeat. */
    fun relaxChoices(): List<Int> = AlertLimits.ACTION_RELAX.presetMinutes.filter { it > relaxFloor() }
        .ifEmpty { listOf(AlertLimits.ACTION_RELAX.max.toInt()) }

    /** The *Mute…* lengths a notification offers: [NotificationActions.muteMin] first, then the presets. */
    fun muteChoices(): List<Int> = (listOf(actions.muteMin) + AlertLimits.ACTION_MUTE.presetMinutes).distinct()

    /** *Remind less* would slow [kind] down (it repeats faster than the relaxed interval). */
    fun canRelax(kind: AlertKind): Boolean = rule(kind).repeatMin in 1 until actions.relaxMin

    companion object {
        /** The owner's own settings (2026-10-18), SPEC §3. */
        val DEFAULT_RULES: Map<AlertKind, KindRule> = mapOf(
            AlertKind.VERY_LOW to KindRule(on = true, threshold = 55.0, repeatMin = 5, alarm = true),
            AlertKind.LOW to KindRule(on = true, threshold = 70.0, repeatMin = 5),
            AlertKind.HIGH to KindRule(on = true, threshold = 180.0, repeatMin = 15),
            AlertKind.VERY_HIGH to KindRule(on = true, threshold = 250.0, repeatMin = 10),
            AlertKind.EXTREMELY_HIGH to KindRule(on = true, threshold = 300.0, repeatMin = 5, alarm = true),
            AlertKind.FALLING_FAST to KindRule(on = false, threshold = 2.0, repeatMin = 30),
            AlertKind.RISING_FAST to KindRule(on = false, threshold = 2.0, repeatMin = 30),
            AlertKind.NO_DATA to KindRule(on = true, threshold = 20.0, repeatMin = 60),
        )
    }
}
