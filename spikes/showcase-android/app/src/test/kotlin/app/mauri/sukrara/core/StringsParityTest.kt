// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Catches translation drift: every translatable string and plural of `values/strings.xml` exists in every
 * translation (`values-es`) with the same format arguments, and no translation keeps a key English dropped. Also
 * checks that [Words]' built-in English (what the JVM tests and galleries draw) is the English of the resources.
 */
class StringsParityTest {
    private val res = File("src/main/res")
    private val translations = listOf("values-es")

    /** name (plurals: name#quantity) → text, translatable entries only. */
    private fun load(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val out = LinkedHashMap<String, String>()
        val items = doc.documentElement.childNodes
        for (i in 0 until items.length) {
            val e = items.item(i) as? Element ?: continue
            if (e.getAttribute("translatable") == "false") continue
            when (e.tagName) {
                // formatted="false": a literal % (no arguments), compared as plain text
                "string" -> out[e.getAttribute("name")] = unescape(e.textContent)
                    .let { if (e.getAttribute("formatted") == "false") it.replace("%", "%%") else it }
                "plurals" -> {
                    val q = e.getElementsByTagName("item")
                    for (j in 0 until q.length) (q.item(j) as Element).let { out["${e.getAttribute("name")}#${it.getAttribute("quantity")}"] = unescape(it.textContent) }
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")

    /** The format arguments of [s], sorted ("%1\$s", "%2\$d", "%d"); "%%" is a literal. */
    private fun args(s: String) = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]").findAll(s)
        .map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test fun everyStringIsTranslatedWithTheSameArguments() {
        val en = load("values")
        assertTrue("no strings found", en.size > 100)
        for (dir in translations) {
            val tr = load(dir)
            val plural = { k: String -> k.substringBefore('#') }
            val enPlurals = en.keys.filter { '#' in it }.map(plural).toSet()
            val missing = en.keys.filter { it !in tr && !('#' in it && plural(it) in tr.keys.map(plural)) }
            assertEquals("$dir: missing keys", emptyList<String>(), missing)
            val extra = tr.keys.filter { k -> k !in en && !('#' in k && plural(k) in enPlurals) }
            assertEquals("$dir: keys English no longer has", emptyList<String>(), extra)
            for ((k, v) in tr) {
                // a plural's quantities may differ per language: compare with the English "other" form
                val ref = en[k] ?: en["${plural(k)}#other"]!!
                val want = args(ref).filter { !('#' in k && it == "%d") }
                val got = args(v).filter { !('#' in k && it == "%d") }
                assertEquals("$dir/$k: format arguments", want, got)
            }
        }
    }

    @Test fun wordsDefaultsAreTheEnglishResources() {
        val en = load("values")
        val w = Words()
        val pairs = mapOf(
            "badge_eat_carbs" to w.eatCarbs, "badge_carbs_short" to w.carbsShort, "badge_take_insulin" to w.takeInsulin,
            "badge_insulin_short" to w.insulinShort,
            "badge_time_left#one" to w.timeLeft.one, "badge_time_left#other" to w.timeLeft.other,
            "card_sensor_left#one" to w.sensorLeft.one, "card_sensor_left#other" to w.sensorLeft.other,
            "badge_muted_short" to w.muted, "badge_muted_for" to w.mutedFor,
            "duration_now" to w.durNow, "duration_minutes" to w.durMinutes, "duration_hours" to w.durHours,
            "duration_hours_minutes" to w.durHoursMinutes, "duration_days" to w.durDays,
            "duration_text_minutes" to w.durTextMinutes, "duration_text_hours" to w.durTextHours,
            "duration_text_hours_minutes" to w.durTextHoursMinutes, "duration_ago" to w.ago,
            "period_days" to w.periodDays, "period_hours" to w.periodHours,
            "slot_average_short" to w.slotAverage, "slot_gmi_short" to w.slotGmi, "slot_cv_short" to w.slotCv,
            "slot_tir_in_range" to w.tirInRange, "slot_tir_very_good" to w.tirVeryGood,
            "card_no_readings_yet" to w.noReadingsYet,
            "card_no_reading_since" to w.noReadingSince,
            "trend_unknown" to w.trendUnknown, "trend_steady" to w.trendSteady, "trend_rising" to w.trendRising,
            "trend_rising_fast" to w.trendRisingFast, "trend_falling" to w.trendFalling, "trend_falling_fast" to w.trendFallingFast,
        ) + w.kinds.map { (k, v) -> "alert_kind_$k" to v }
        for ((key, value) in pairs) assertEquals(key, en[key], value)
    }

    /**
     * Round f33 (a screen reader said "quedan 1 h"): Spanish "queda" / "quedan" agrees with the time after it, so every
     * text that puts a duration after it is a plural (Durations.*Quantity picks the form), never a plain string.
     */
    @Test fun spanishTimeLeftIsAPlural() {
        for (dir in translations) {
            val plain = load(dir).filterKeys { '#' !in it }.filterValues { Regex("""\bquedan? %""").containsMatchIn(it) }
            assertEquals("$dir: time left in a plain string (use <plurals>)", emptyMap<String, String>(), plain)
            val forms = load(dir).filterKeys { '#' in it }.filterValues { Regex("""\bquedan? %""").containsMatchIn(it) }
            for ((k, v) in forms) assertTrue("$dir/$k: $v", if (k.endsWith("#one")) "queda %" in v else "quedan %" in v)
        }
    }

    /**
     * Round f33: the choice that opens a field for the user's own value ends in an ellipsis, the one character "…"
     * (like the Mac's Custom…, after Apple's HIG on menus: an ellipsis says the choice needs more input), in every language.
     */
    @Test fun customChoiceEndsInAnEllipsis() {
        for (dir in listOf("values") + translations) {
            val v = load(dir).getValue("settings_custom_value")
            assertTrue("$dir/settings_custom_value: '$v'", v.endsWith("\u2026") && !v.endsWith("..."))
        }
    }
}
