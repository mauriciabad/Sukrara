// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.tidepool

import app.mauri.sukrara.treatments.Treatment
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mapping to Tidepool's data model (docs/27): units, time fields across DST, dedupe keys. */
class TidepoolDataTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private fun epoch(local: String, zone: ZoneId = madrid) = LocalDateTime.parse(local).atZone(zone).toEpochSecond()
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun tr(k: String, a: Double, t: Long, n: String = "", id: String = "id-$k") = Treatment(id = id, k = k, a = a, t = t, n = n, u = 1, o = "phone")

    @Test fun cbgCarriesUnitsAndTimeFields() {
        val t = epoch("2026-07-01T12:03:20")   // summer: UTC+2
        val o = TidepoolData.cbg(TidepoolData.Glucose(t, 123, 1.24), madrid, "Sukrara-wearer-x", "0.11.22")
        assertEquals("cbg", o.s("type"))
        assertEquals("mg/dL", o.s("units"))
        assertEquals(123, o["value"]!!.jsonPrimitive.int)
        assertEquals("2026-07-01T10:03:20Z", o.s("time"))
        assertEquals("2026-07-01T12:03:20", o.s("deviceTime"))
        assertEquals(120, o["timezoneOffset"]!!.jsonPrimitive.int)
        assertEquals("Europe/Madrid", o.s("timezone"))
        assertEquals("1.2", o.s("trendRate")); assertEquals("mg/dL/minute", o.s("trendRateUnits"))
        assertEquals(300000, o["sampleInterval"]!!.jsonPrimitive.int)
        val origin = o["origin"]!!.jsonObject
        assertEquals("sukrara-cbg-${t / 300}", origin.s("id"))
        assertEquals("app.mauri.sukrara", origin.s("name")); assertEquals("application", origin.s("type"))
        assertEquals("Sukrara-wearer-x", o.s("deviceId"))
    }

    @Test fun noTrendWithoutRate() {
        val o = TidepoolData.cbg(TidepoolData.Glucose(1_800_000_000, 90, null), madrid, "d", "v")
        assertFalse("trendRate" in o); assertFalse("trendRateUnits" in o)
    }

    @Test fun repeatedHourWhenClocksGoBackStaysApart() {
        // 2026-10-25 03:00 CEST → 02:00 CET: 02:30 happens twice
        val first = epoch("2026-10-25T00:30:00", ZoneId.of("UTC"))   // 02:30 CEST
        val second = first + 3600                                       // 02:30 CET
        val a = TidepoolData.cbg(TidepoolData.Glucose(first, 100), madrid, "d", "v")
        val b = TidepoolData.cbg(TidepoolData.Glucose(second, 101), madrid, "d", "v")
        assertEquals(a.s("deviceTime"), b.s("deviceTime"))
        assertEquals("2026-10-25T02:30:00", a.s("deviceTime"))
        assertEquals(120, a["timezoneOffset"]!!.jsonPrimitive.int)
        assertEquals(60, b["timezoneOffset"]!!.jsonPrimitive.int)
        assertTrue(a.s("time") != b.s("time"))
        assertTrue(a["origin"]!!.jsonObject.s("id") != b["origin"]!!.jsonObject.s("id"))
    }

    @Test fun springForwardOffset() {
        val t = epoch("2026-03-29T03:30:00")   // after 02:00 → 03:00
        assertEquals(120, TidepoolData.times(t, madrid)["timezoneOffset"])
        assertEquals(60, TidepoolData.times(t - 3 * 3600, madrid)["timezoneOffset"])
    }

    @Test fun onePerFiveMinuteSlotTheEarliest() {
        val base = 1_800_000_000L - 1_800_000_000L % 300
        val list = (0 until 12).map { TidepoolData.Glucose(base + it * 60L, 100 + it) }   // one a minute for 12 min
        val slots = TidepoolData.perSlot(list.shuffled())
        assertEquals(listOf(base / 300, base / 300 + 1, base / 300 + 2), slots.keys.toList())
        assertEquals(listOf(100, 105, 110), slots.values.map { it.sgv })
        assertTrue(TidepoolData.perSlot(listOf(TidepoolData.Glucose(base, 0))).isEmpty())   // no value: skipped
    }

    @Test fun carbsAreFoodInGrams() {
        val o = TidepoolData.treatment(tr("carbs", 45.0, 1_800_000_000, "pasta"), madrid, "d", "v")!!
        assertEquals("food", o.s("type"))
        val c = o["nutrition"]!!.jsonObject["carbohydrate"]!!.jsonObject
        assertEquals("45.0", c.s("net")); assertEquals("grams", c.s("units"))
        assertEquals("pasta", o["notes"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("sukrara-tr-id-carbs", o["origin"]!!.jsonObject.s("id"))
    }

    @Test fun insulinIsInjectedInsulinWithActingType() {
        for (k in listOf("rapid", "long")) {
            val o = TidepoolData.treatment(tr(k, 4.5, 1_800_000_000), madrid, "d", "v")!!
            assertEquals("insulin", o.s("type"))
            assertEquals("4.5", o["dose"]!!.jsonObject.s("total")); assertEquals("Units", o["dose"]!!.jsonObject.s("units"))
            assertEquals(k, o["formulation"]!!.jsonObject["simple"]!!.jsonObject.s("actingType"))
            assertFalse("notes" in o)
        }
    }

    @Test fun notesAndEmptyAmountsAreNotData() {
        assertNull(TidepoolData.treatment(tr("note", 0.0, 1_800_000_000, "walk"), madrid, "d", "v"))
        assertNull(TidepoolData.treatment(tr("carbs", 0.0, 1_800_000_000), madrid, "d", "v"))
        val m = TidepoolData.note(tr("note", 0.0, 1_800_000_000, "walk"), "user")["message"]!!.jsonObject
        assertEquals("walk", m.s("messagetext")); assertEquals("2027-01-15T08:00:00Z", m.s("timestamp"))
        assertEquals("user", m.s("groupid"))
    }

    @Test fun dataSetIsContinuousAndDedupedByOrigin() {
        val o = TidepoolData.dataSet("Sukrara-wearer-x", "Sukrara", "0.11.22", madrid, 1_800_000_000)
        assertEquals("upload", o.s("type")); assertEquals("continuous", o.s("dataSetType"))
        assertEquals("org.tidepool.deduplicator.dataset.delete.origin", o["deduplicator"]!!.jsonObject.s("name"))
        assertEquals("app.mauri.sukrara", o["client"]!!.jsonObject.s("name"))
        assertEquals("0.11.22", o["client"]!!.jsonObject.s("version"))
        assertEquals(60, o["timezoneOffset"]!!.jsonPrimitive.int)   // January in Madrid
    }

    @Test fun selectorsByOriginId() {
        val a = TidepoolData.selectors(listOf("sukrara-tr-1"))
        assertEquals("sukrara-tr-1", a[0].jsonObject["origin"]!!.jsonObject.s("id"))
    }
}
