// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treatment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertStatusTest {
    private val s = AlertSettings()
    private val t0 = 1_760_000_000L

    private fun tr(k: String, t: Long, del: Boolean = false) = Treatment(id = "$k$t", k = k, a = 10.0, t = t, u = t * 1000, del = del, o = "phone")
    private fun status(sgv: Int, vararg trs: Treatment, set: AlertSettings = s, now: Long = t0) =
        AlertStatus.of(Reading(t0, sgv, 0.0), set, trs.toList(), now)

    @Test fun levelsMatchTheEngine() {
        assertEquals(AlertStatus.None, status(70))
        assertEquals(AlertStatus.Needed(Kind.LOW), status(69))
        assertEquals(AlertStatus.Needed(Kind.LOW), status(55))
        assertEquals(AlertStatus.Needed(Kind.VERY_LOW), status(54))
        assertEquals(AlertStatus.None, status(179))
        assertEquals(AlertStatus.Needed(Kind.HIGH), status(180))
        assertEquals(AlertStatus.Needed(Kind.EXTREMELY_HIGH), status(300))
        assertEquals(AlertStatus.None, AlertStatus.of(Reading(t0 - 16 * 60, 40, 0.0), s, emptyList(), t0))
        assertEquals(AlertStatus.None, status(40, set = s.copy(on = false)))
    }

    @Test fun carbsCoverLowAndVeryLow() {
        val c = tr("carbs", t0 - 60)
        assertEquals(AlertStatus.Taken(Kind.LOW, c, c.t + 15 * 60), status(60, c))
        assertEquals(AlertStatus.Taken(Kind.VERY_LOW, c, c.t + 15 * 60), status(50, c))
        assertEquals(AlertStatus.Needed(Kind.HIGH), status(200, c))
        assertEquals(AlertStatus.Needed(Kind.VERY_LOW), status(50, c, set = s.copy(muteCarbs = false)))
        assertEquals(AlertStatus.Needed(Kind.LOW), status(60, tr("carbs", t0 - 60, del = true)))
        // the window ends exactly at t + 15 min, like the engine's `now - t < min`
        assertEquals(AlertStatus.Needed(Kind.LOW), status(60, tr("carbs", t0 - 15 * 60)))
        assertTrue(status(60, tr("carbs", t0 - 15 * 60 + 1)) is AlertStatus.Taken)
        // logged for later: not yet
        assertEquals(AlertStatus.Needed(Kind.LOW), status(60, tr("carbs", t0 + 60)))
    }

    @Test fun insulinCoversHighsButNeverExtremelyHigh() {
        val r = tr("rapid", t0 - 600)
        assertEquals(AlertStatus.Taken(Kind.HIGH, r, r.t + 60 * 60), status(200, r))
        assertEquals(AlertStatus.Taken(Kind.VERY_HIGH, r, r.t + 60 * 60), status(260, r))
        assertEquals(AlertStatus.Needed(Kind.EXTREMELY_HIGH), status(300, r))
        assertEquals(AlertStatus.Needed(Kind.LOW), status(60, r))
        // the newest covering entry wins
        val r2 = tr("rapid", t0 - 60)
        assertEquals(AlertStatus.Taken(Kind.HIGH, r2, r2.t + 60 * 60), status(200, r, r2))
    }

    /** Taken ⇔ the engine is quiet because of a treatment, over a grid of values and treatment times. */
    @Test fun agreesWithTheEngine() {
        for (sgv in listOf(40, 54, 55, 69, 70, 120, 179, 180, 249, 250, 299, 300, 350)) for (k in listOf("carbs", "rapid")) {
            for (ago in listOf(-60L, 0L, 60L, 14 * 60L, 15 * 60L, 59 * 60L, 60 * 60L)) {
                val t = tr(k, t0 - ago)
                val st = status(sgv, t)
                val e = AlertEngine()
                val d = AlertEngine.doses(listOf(t))
                val fired = e.reading(Reading(t0, sgv, 0.0), s, d, t0).filterIsInstance<AlertEngine.Fire>().map { it.kind }
                val muted = e.states()["level"]?.muted
                val quietByTreatment = muted != null && (muted.startsWith("carbs") || muted.startsWith("insulin"))
                assertEquals("sgv $sgv $k ${ago}s ago", st is AlertStatus.Taken, quietByTreatment)
                when (st) {
                    AlertStatus.None -> assertTrue(fired.isEmpty() && muted == null)
                    is AlertStatus.Needed -> assertEquals(listOf(st.kind), fired)
                    is AlertStatus.Taken -> assertTrue(fired.isEmpty())
                    is AlertStatus.Paused -> error("no notification mute here")
                }
            }
        }
    }
}
