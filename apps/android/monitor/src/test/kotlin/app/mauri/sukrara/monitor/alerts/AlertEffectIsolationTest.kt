// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertDeliveryProblem
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.storage.StateStore
import app.mauri.sukrara.core.testing.InMemoryStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One failing effect step (a refused notification, a refused alarm, a broken follower link, a store that can't write,
 * a hub that throws) never costs the others: above all the exact alarm (the only re-check with the service dead) is
 * armed and the state saved. Every failure is logged without health data and shown on home until the step works.
 */
class AlertEffectIsolationTest {
    private val rig = AlertRig()
    private val logged = mutableListOf<String>()

    /** A shade where every call can be made to fail. */
    private class FlakyPresenter(private val inner: FakePresenter) : AlertPresenter by inner {
        var failing = false

        override fun show(firing: Firing, settings: AlertSettings, silent: Boolean, who: String?, relaxedMin: Int) {
            if (failing) throw SecurityException("refused")
            inner.show(firing, settings, silent, who, relaxedMin)
        }

        override fun showMuted(group: AlertGroup, side: Side, kind: AlertKind, until: Long, settings: AlertSettings, now: Long) {
            if (failing) throw SecurityException("refused")
            inner.showMuted(group, side, kind, until, settings, now)
        }

        override fun clear(group: AlertGroup) {
            if (failing) throw SecurityException("refused")
            inner.clear(group)
        }
    }

    private class FlakyAlarm : AlertAlarm {
        var failing = false
        var at: Long? = null

        override fun set(at: Long?) {
            if (failing) throw SecurityException("exact alarms refused")
            this.at = at
        }
    }

    private class FlakyStore<T>(private val inner: InMemoryStateStore<T>) : StateStore<T> {
        var failing = false
        override val data: Flow<T> get() = inner.data

        override suspend fun update(transform: (T) -> T) {
            if (failing) throw java.io.IOException("disk full")
            inner.update(transform)
        }
    }

    private val presenter = FlakyPresenter(rig.presenter)
    private val alarm = FlakyAlarm()
    private val record = FlakyStore(rig.record)
    private var mirrorFails = false
    private var publishFails = false
    private var published = AlertUiState()

    private fun runtime() = AlertRuntime(
        AlertStateStore(rig.settingsRepo, record),
        rig.treatments,
        presenter,
        alarm,
        { if (mirrorFails) throw IllegalStateException("relay down") },
        { if (publishFails) throw IllegalStateException("hub") else published = it },
        rig.clock,
        log = { logged += it },
    )

    private val low get() = rig.wearerState(latest = rig.reading(60))

    @Test fun aRefusedNotificationStillArmsTheAlarmAndSaves() = runTest {
        presenter.failing = true
        runtime().onState(low)
        assertNotNull("the exact alarm is armed", alarm.at)
        assertNotNull("the state is saved", rig.record.data.first().machine)
        assertTrue(AlertDeliveryProblem.AlertStepFailed in published.deliveryProblems)
        assertTrue(logged.contains("notify failed: SecurityException"))
    }

    @Test fun aRefusedAlarmStillPostsTheAlertAndSaves() = runTest {
        alarm.failing = true
        runtime().onState(low)
        assertEquals("low", rig.presenter.slots[AlertKind.LOW.group])
        assertNotNull(rig.record.data.first().machine)
        assertTrue(AlertDeliveryProblem.AlertStepFailed in published.deliveryProblems)
        assertTrue(logged.contains("alarm failed: SecurityException"))
    }

    @Test fun aStoreThatCantWriteStillPostsAndArmsTheAlarm() = runTest {
        record.failing = true
        runtime().onState(low)
        assertEquals("low", rig.presenter.slots[AlertKind.LOW.group])
        assertNotNull(alarm.at)
        assertTrue(logged.contains("save failed: IOException"))
    }

    @Test fun aBrokenFollowerLinkOrHubStillArmsTheAlarmAndSaves() = runTest {
        mirrorFails = true
        publishFails = true
        runtime().onState(low)
        assertNotNull(alarm.at)
        assertNotNull(rig.record.data.first().machine)
        assertTrue(logged.contains("publish failed: IllegalStateException"))
    }

    @Test fun theProblemGoesWhenTheStepWorksAgain() = runTest {
        val runtime = runtime()
        presenter.failing = true
        runtime.onState(low)
        assertTrue(AlertDeliveryProblem.AlertStepFailed in published.deliveryProblems)
        presenter.failing = false
        rig.clock.instant = rig.clock.now().plusSeconds(60)
        runtime.onState(rig.wearerState(latest = rig.reading(58)))
        assertEquals("the refused alert is shown on the next try", "low", rig.presenter.slots[AlertKind.LOW.group])
        assertFalse(AlertDeliveryProblem.AlertStepFailed in published.deliveryProblems)
    }

    @Test fun logLinesCarryNoGlucoseValues() = runTest {
        presenter.failing = true
        alarm.failing = true
        record.failing = true
        runtime().onState(low)
        assertTrue(logged.isNotEmpty())
        logged.forEach { assertFalse("no digits in '$it'", it.any(Char::isDigit)) }
    }
}
