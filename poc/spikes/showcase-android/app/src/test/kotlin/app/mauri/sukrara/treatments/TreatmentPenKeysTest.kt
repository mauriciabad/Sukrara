// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import app.mauri.sukrara.pen.PdDose
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The pen keys of SPEC §1 (pr, pen, pd) survive wire, disk and merge; malformed ones reject the entry (protocol review). */
class TreatmentPenKeysTest {
    private val pen = Treatment(id = "e1", k = "rapid", a = 8.5, t = 1000, u = 5, o = "phone", pr = 1.5, pen = "FHB2N9KE",
        pd = listOf(PdDose(900, 1.0, true), PdDose(950, 0.5, true), PdDose(1000, 8.0), PdDose(1030, 0.5)))

    @Test fun wireRoundTrip() {
        val j = pen.wire()
        assertEquals(1.5, j.getDouble("pr"), 0.0)
        assertEquals(pen, Treatment.fromWire(JSONObject(j.toString())))
    }

    @Test fun handTypedHasNoPenKeys() {
        val j = Treatment(id = "h", k = "rapid", a = 8.0, t = 1000, u = 5, o = "phone").wire()
        assertFalse(j.has("pr") || j.has("pen") || j.has("pd"))
    }

    @Test fun diskRoundTripAndMergeKeepThem() {
        val back = TreatmentJson.decodeFromString(Treatment.serializer(), TreatmentJson.encodeToString(Treatment.serializer(), pen))
        assertEquals(pen, back)
        assertEquals(pen.pd, Treatment.merge(pen.copy(pd = null, u = 1), pen).pd)
    }

    private fun with(k: String, v: Any) = Treatment.fromWire(JSONObject(pen.wire().toString()).put(k, v))

    @Test fun malformedRejected() {
        assertNull("negative pr", with("pr", -1))
        assertNull("pen over 32 bytes", with("pen", "x".repeat(33)))
        assertNull("pd not an array", with("pd", "nope"))
        assertNull("pd item without t", with("pd", JSONArray().put(JSONObject().put("a", 1))))
        assertNull("negative dose", with("pd", JSONArray().put(JSONObject().put("t", 1).put("a", -1))))
        assertNull("fractional time", with("pd", JSONArray().put(JSONObject().put("t", 1.5).put("a", 1))))
        assertNull("p not a boolean", with("pd", JSONArray().put(JSONObject().put("t", 1).put("a", 1).put("p", "yes"))))
        assertNull("out of time order", with("pd", JSONArray().put(JSONObject().put("t", 2).put("a", 1)).put(JSONObject().put("t", 1).put("a", 1))))
        assertNull("over 20 doses", with("pd", JSONArray().apply { repeat(21) { put(JSONObject().put("t", it).put("a", 1)) } }))
        assertNotNull("20 doses are fine", with("pd", JSONArray().apply { repeat(20) { put(JSONObject().put("t", it).put("a", 1)) } }))
    }

    /** spec/test-vectors/ogs-draft1.json `treatment_payloads`: the pen entry is accepted, every invalid one rejected. */
    @Test fun sharedVector() {
        val v = JSONObject(java.io.File("../../../spec/test-vectors/ogs-draft1.json").readText()).getJSONObject("treatment_payloads")
        val ok = Treatment.fromWire(v.getJSONObject("pen-entry"))
        assertNotNull(ok); assertEquals(1.5, ok!!.pr!!, 0.0); assertEquals(4, ok.pd!!.size)
        val bad = v.getJSONArray("invalid")
        for (i in 0 until bad.length()) assertNull("invalid[$i]", Treatment.fromWire(bad.getJSONObject(i)))
    }

    @Test fun uploadsWaitForTheSession() {
        assertEquals(1030L + Treatment.PEN_SETTLE_S, pen.heldUntil(1100))
        assertNull(pen.heldUntil(1030 + Treatment.PEN_SETTLE_S))
        assertNull(pen.copy(pd = null).heldUntil(0))
    }
}
