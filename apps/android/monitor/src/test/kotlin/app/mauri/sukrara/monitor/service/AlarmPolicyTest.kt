// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which AlarmManager call each wake-up uses, with exact alarms allowed and refused. */
class AlarmPolicyTest {
    @Test fun checksThatProtectThePersonAreExactAndRingInDoze() {
        assertEquals(AlarmCall.ExactWhileIdle, AlarmPolicy.call(Wakeup.AlertCheck, canScheduleExact = true))
        assertEquals(AlarmCall.ExactWhileIdle, AlarmPolicy.call(Wakeup.HealthCheck, canScheduleExact = true))
    }

    @Test fun refusedExactAlarmsFallBackToWhileIdleNotToNothing() {
        assertEquals(AlarmCall.InexactWhileIdle, AlarmPolicy.call(Wakeup.AlertCheck, canScheduleExact = false))
        assertEquals(AlarmCall.InexactWhileIdle, AlarmPolicy.call(Wakeup.HealthCheck, canScheduleExact = false))
    }

    @Test fun theFreshnessStepNeverWakesThePhone() {
        assertEquals(AlarmCall.ExactRtc, AlarmPolicy.call(Wakeup.FreshnessStep, canScheduleExact = true))
        assertEquals(AlarmCall.WindowRtc, AlarmPolicy.call(Wakeup.FreshnessStep, canScheduleExact = false))
    }

    @Test fun everyWakeupHasACallEitherWay() {
        for (w in Wakeup.entries) for (exact in listOf(true, false)) AlarmPolicy.call(w, exact)
    }
}
