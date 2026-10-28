// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.bridge

import app.mauri.sukrara.treatments.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's Nightscout read API against what the clients do with it (docs/25 §2): Nightscout's IOB and LoopFollow
 * count every `insulin` as a bolus, Trio rejects a whole batch on an unknown `direction`, and filters `dateString`.
 */
class NightscoutApiTest {
    private fun tr(k: String, a: Double, n: String = "") =
        Treatment(id = "9f0c3c39-2a43-4f7e-9d8e-2b1f3c0a0001", k = k, a = a, t = 1_792_000_000, n = n, u = 1, o = "phone")

    @Test fun longActingIsANoteWithoutInsulin() {
        val o = NightscoutApi.treatment(tr("long", 14.0, "evening"))
        assertEquals("Note", o.getString("eventType"))
        assertFalse("an insulin field would count as a bolus in IOB", o.has("insulin"))
        assertEquals("long-acting", o.getString("insulinType"))
        assertTrue(o.getString("notes").startsWith("Long-acting insulin 14u"))
        assertTrue(o.getString("notes").endsWith("evening"))
    }

    @Test fun rapidAndCarbsKeepTheirNumbers() {
        val r = NightscoutApi.treatment(tr("rapid", 4.5))
        assertEquals("Correction Bolus", r.getString("eventType")); assertEquals(4.5, r.getDouble("insulin"), 0.0)
        val c = NightscoutApi.treatment(tr("carbs", 45.0))
        assertEquals("Carb Correction", c.getString("eventType")); assertEquals(45.0, c.getDouble("carbs"), 0.0)
        assertFalse(c.has("insulin"))
    }

    @Test fun entriesUseNightscoutsEnumAndUtc() {
        val alone = Bridge.Reading(1_792_000_000, 120, null)
        val e = NightscoutApi.entry(alone, listOf(alone))
        assertEquals("NONE", e.getString("direction"))   // never "None": Trio drops the whole batch
        assertTrue(e.getString("dateString").endsWith("Z"))
        assertEquals(1_792_000_000_000L, e.getLong("date"))
        assertTrue(e.get("sgv") is Int && e.get("noise") is Int && e.opt("delta") is Number)
        val rising = Bridge.Reading(1_792_000_060, 125, 2.5)
        assertEquals("SingleUp", NightscoutApi.entry(rising, listOf(rising, alone)).getString("direction"))
    }
}
