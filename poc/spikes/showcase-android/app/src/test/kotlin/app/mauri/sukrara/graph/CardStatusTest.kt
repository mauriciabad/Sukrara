// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.LastTreatment
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentMarks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-10-18 round: units, short durations, the last-treatment slot, slot priority, the badge's words. */
class CardStatusTest {
    private val now = 1_800_000_000L
    private val s = AlertSettings()
    private fun tr(k: String, a: Double, minAgo: Int, del: Boolean = false) =
        Treatment(id = "$k$minAgo", k = k, a = a, t = now - minAgo * 60L, u = 0, o = "phone", del = del)

    @Test fun units() {
        assertEquals("4.5u", TreatmentMarks.amount(tr("rapid", 4.5, 0)))
        assertEquals("18u", TreatmentMarks.amount(tr("long", 18.0, 0)))
        assertEquals("30g", TreatmentMarks.amount(tr("carbs", 30.0, 0)))
        assertNull(TreatmentMarks.amount(tr("note", 0.0, 0)))
    }

    @Test fun durations() {
        assertEquals("now", TreatmentMarks.duration(30))
        assertEquals("12m", TreatmentMarks.duration(12 * 60 + 40))
        assertEquals("1h 5m", TreatmentMarks.duration(65 * 60))
        assertEquals("3h", TreatmentMarks.duration(3 * 3600))
        assertEquals("7h", TreatmentMarks.duration(7 * 3600 + 50 * 60))
        assertEquals("2d", TreatmentMarks.duration(50 * 3600))
        assertEquals("1m", TreatmentMarks.duration(10, up = true))   // time left: never "now" while it lasts
    }

    @Test fun lastTreatment() {
        // newest rapid / carbs; a meal at the same minute shows the insulin; long and notes never
        assertEquals("rapid", LastTreatment.of(listOf(tr("carbs", 45.0, 12), tr("rapid", 4.5, 12)), s, AlertStatus.None, now)?.tr?.k)
        assertNull(LastTreatment.of(listOf(tr("long", 18.0, 5), tr("note", 0.0, 1)), s, AlertStatus.None, now))
        // kept 4 h (rapid) / 3 min per gram, 30 min–3 h (carbs), deleted ones skipped
        assertNull(LastTreatment.of(listOf(tr("rapid", 6.0, 241), tr("carbs", 60.0, 181)), s, AlertStatus.None, now))
        assertEquals(60.0, LastTreatment.of(listOf(tr("rapid", 6.0, 200), tr("carbs", 60.0, 179)), s, AlertStatus.None, now)!!.tr.a, 0.0)
        assertEquals(6.0, LastTreatment.of(listOf(tr("rapid", 6.0, 200), tr("carbs", 20.0, 61)), s, AlertStatus.None, now)!!.tr.a, 0.0)
        assertNull(LastTreatment.of(listOf(tr("rapid", 6.0, 10, del = true)), s, AlertStatus.None, now))
        // the ring: share of the mute left (60 min insulin, 15 min carbs), 0 when over
        val i = LastTreatment.of(listOf(tr("rapid", 4.5, 12)), s, AlertStatus.None, now)!!
        assertEquals(0.8f, i.left(now), 0.001f)
        assertEquals(0f, LastTreatment.of(listOf(tr("carbs", 15.0, 20)), s, AlertStatus.None, now)!!.left(now), 0f)
        assertEquals(0f, LastTreatment.of(listOf(tr("rapid", 4.5, 12)), s.copy(muteInsulin = false), AlertStatus.None, now)!!.left(now), 0f)
        // the treatment muting the current alert wins over a newer one, except rapid insulin whose mute runs (the user,
        // 2026-10-21): then the carbs ride along in the inner ring
        val carbs = tr("carbs", 15.0, 8)
        val taken = AlertStatus.Taken("low", carbs, now + 7 * 60)
        assertEquals(carbs, LastTreatment.of(listOf(carbs, tr("carbs", 5.0, 2)), s, taken, now)!!.tr)
        val both = LastTreatment.of(listOf(carbs, tr("rapid", 1.0, 2)), s, taken, now)!!
        assertEquals("rapid", both.tr.k)
        assertEquals(carbs, both.carbs?.tr)
    }

    @Test fun badgeWords() {
        val low = AlertStatus.of(Reading(now - 30, 60, -1.0), s, emptyList(), now)
        assertEquals("Eat carbs", StatusBadge.look(low, now)?.text)
        assertEquals("Take insulin", StatusBadge.look(AlertStatus.of(Reading(now - 30, 220, 1.0), s, emptyList(), now), now)?.text)
        val muted = AlertStatus.of(Reading(now - 30, 60, -1.0), s, listOf(tr("carbs", 15.0, 6)), now)
        assertEquals(StatusBadge.Look("carbs", "9m left", "9m", true), StatusBadge.look(muted, now))
        assertNull(StatusBadge.look(AlertStatus.of(Reading(now - 30, 120, 0.0), s, emptyList(), now), now))
    }

    @Test fun slotPriority() {
        val m = GlucoseMetrics.of(emptyList(), 0, 1, Bands())
        val rings = listOf(Triple(0, RingSpec(86400), m), Triple(1, RingSpec(7 * 86400L), m), Triple(2, RingSpec(14 * 86400L), m))
        val info = LastTreatment.Info(tr("rapid", 4.5, 5), now + 55 * 60)
        val fb = RingSpec(30 * 86400L) to m
        fun names(l: List<SlotItem>) = l.map { if (it is SlotItem.Metric) "${it.spec.windowS / 86400}d" else "last" }
        // right to left; the treatment pushes the second ring out when only two fit
        assertEquals(listOf("1d", "last"), names(Slots.arrange(rings, LastPlace.NEAR_VALUE, info, fb, 2)))
        assertEquals(listOf("1d", "7d", "last"), names(Slots.arrange(rings, LastPlace.NEAR_VALUE, info, fb, 3)))
        assertEquals(listOf("last", "1d", "7d"), names(Slots.arrange(rings, LastPlace.END, info, fb, 3)))
        // the fallback only fills room the second ring leaves
        assertEquals(listOf("1d", "7d"), names(Slots.arrange(rings, LastPlace.NEAR_VALUE, null, fb, 2)))
        assertEquals(listOf("1d", "7d", "30d"), names(Slots.arrange(rings, LastPlace.NEAR_VALUE, null, fb, 3)))
        assertEquals(listOf("1d", "7d", "14d"), names(Slots.arrange(rings, LastPlace.OFF, info, fb, 3)))
        assertTrue(Slots.arrange(rings, LastPlace.NEAR_VALUE, null, null, 4).none { it is SlotItem.Last })
    }

    @Test fun settingsJson() {
        val c = GraphConfig(lastPlace = LastPlace.END, lastElse = RingSpec(14 * 86400L, kind = SlotKind.GMI, faded = false))
        assertEquals(c, GraphConfig.fromJson(JSONObject(c.toJson().toString())))
        val none = GraphConfig(lastElse = null)
        assertEquals(none, GraphConfig.fromJson(JSONObject(none.toJson().toString())))
        assertEquals(LastPlace.NEAR_VALUE, GraphConfig.fromJson(JSONObject()).lastPlace)
        assertEquals(DEFAULT_LAST_ELSE, GraphConfig.fromJson(JSONObject()).lastElse)
    }
}
