// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [MuteLine]s against contracts/alerts/copy.json: every case maps to a line that carries everything its text needs —
 * proven by wording it in English with a minimal renderer here. The real en + es strings live in the Android
 * resources and are checked against the same file by chunk 11's Robolectric test.
 */
class MuteCopyTest {
    private val cases = Json.parseToJsonElement(TestContracts.file("alerts/copy.json").readText()).jsonObject["cases"]!!.jsonArray

    private fun side(c: JsonObject) = Side.entries.single { it.wire == c["side"]!!.jsonPrimitive.content }

    private fun line(c: JsonObject): MuteLine = when (val st = c["state"]!!.jsonPrimitive.content) {
        "side_muted" -> MuteLine.SideMuted(side(c), c["leftMin"]!!.jsonPrimitive.long)
        "side_after" -> MuteLine.SideAfter(
            side(c),
            TreatmentKind.entries.single { it.wire == c["treatment"]!!.jsonPrimitive.content },
            c["leftMin"]!!.jsonPrimitive.long,
        )
        "side_falling" -> MuteLine.SideFalling(side(c))
        "side_less" -> MuteLine.SideLess(side(c), c["intervalMin"]!!.jsonPrimitive.int)
        "all_timed" -> MuteLine.AllTimed(c["leftMin"]!!.jsonPrimitive.long)
        "all_forever" -> MuteLine.AllForever
        "notif_title" -> MuteLine.SideMuted(side(c), null)
        "notif_text" -> MuteLine.NotifUntil(0)
        "pill" -> MuteLine.Pill(c["leftMin"]!!.jsonPrimitive.long)
        "pill_forever" -> MuteLine.PillForever
        "choice" -> MuteLine.Choice(c["min"]!!.jsonPrimitive.int)
        "choice_forever" -> MuteLine.ChoiceForever
        else -> error("unknown state $st")
    }

    @Test fun everyCaseWordsInEnglish() {
        check(cases.isNotEmpty())
        for (c in cases.map { it.jsonObject }) {
            val until = c["until"]?.jsonPrimitive?.content
            assertEquals(c.toString(), c["en"]!!.jsonPrimitive.content, English.word(line(c), until))
        }
    }

    @Test fun sidesUseTheirMainName() {
        assertEquals(MuteLine.SideMuted(Side.LOW, null), MuteCopy.sideMuted(AlertKind.VERY_LOW.side, Rules.NEVER, 0))
        assertEquals(Side.HIGH, AlertKind.EXTREMELY_HIGH.side)
    }

    @Test fun quietSidesMapToTheirLines() {
        val now = 1_000_000L
        assertEquals(MuteLine.SideAfter(Side.LOW, TreatmentKind.CARBS, 12),
            MuteCopy.of(QuietSide(AlertKind.VERY_LOW, true, QuietReason.CARBS, now + 12 * 60 - 30, 0, false), now))
        assertEquals(MuteLine.SideFalling(Side.HIGH), MuteCopy.of(QuietSide(AlertKind.HIGH, true, QuietReason.FALLING, 0, 0, true), now))
        assertEquals(MuteLine.SideLess(Side.LOW, 30), MuteCopy.of(QuietSide(AlertKind.LOW, false, null, 0, 30, false), now))
        assertEquals(MuteLine.AllForever, MuteCopy.all(Rules.NEVER, now))
        assertEquals(MuteLine.Pill(72), MuteCopy.pill(now + 72 * 60, now))
        assertEquals(MuteLine.ChoiceForever, MuteCopy.muteAllChoices().last())
    }

    @Test fun countdownTicksOnTheMinuteFromTheEnd() {
        val until = 10_000L
        assertEquals(72, MuteCopy.leftMin(until, until - 72 * 60))
        assertEquals(72, MuteCopy.leftMin(until, until - 71 * 60 - 1))
        assertEquals(1, MuteCopy.leftMin(until, until - 1))
        assertEquals(until - 71 * 60, MuteCopy.nextChangeAt(until, until - 72 * 60))
        assertEquals(until - 71 * 60, MuteCopy.nextChangeAt(until, until - 71 * 60 - 30))
        assertEquals(until, MuteCopy.nextChangeAt(until, until - 30))
        assertNull(MuteCopy.nextChangeAt(until, until))
        assertNull(MuteCopy.nextChangeAt(Rules.NEVER, 0))
        assertNull(MuteCopy.nextChangeAt(0, 100))
    }

    /** A minimal English wording of [MuteLine] (test only): durations as contracts/settings/durations.json. */
    private object English {
        fun text(min: Long) = when {
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }

        fun compact(min: Long) = when {
            min < 60 -> "${min}m"
            min % 60 == 0L -> "${min / 60}h"
            else -> "${min / 60}h ${min % 60}m"
        }

        fun side(s: Side) = when (s) {
            Side.LOW -> "Low"
            Side.HIGH -> "High"
            Side.FALLING_FAST -> "Falling fast"
            Side.RISING_FAST -> "Rising fast"
            Side.NO_DATA -> "No data"
        }

        fun word(l: MuteLine, clock: String?): String = when (l) {
            is MuteLine.SideMuted -> "${side(l.side)} alerts muted" + (l.leftMin?.let { " · ${text(it)} left" } ?: "")
            is MuteLine.SideAfter ->
                "${side(l.side)} alerts muted after ${if (l.treatment == TreatmentKind.CARBS) "carbs" else "insulin"} · ${text(l.leftMin)} left"
            is MuteLine.SideFalling -> "${side(l.side)} alerts muted while falling back into range"
            is MuteLine.SideLess -> "${side(l.side)} reminders every ${text(l.intervalMin.toLong())}"
            is MuteLine.AllTimed -> "Alerts muted · ${text(l.leftMin)} left"
            MuteLine.AllForever -> "Alerts muted until you turn it off"
            is MuteLine.NotifUntil -> "Until $clock"
            is MuteLine.Pill -> "Muted · ${compact(l.leftMin)}"
            MuteLine.PillForever -> "Muted"
            is MuteLine.Choice -> "For ${text(l.min.toLong())}"
            MuteLine.ChoiceForever -> "Until you turn it off"
        }
    }
}
