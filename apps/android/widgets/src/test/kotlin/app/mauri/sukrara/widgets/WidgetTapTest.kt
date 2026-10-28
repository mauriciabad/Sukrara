// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.feature.treatments.QuickSource
import app.mauri.sukrara.feature.treatments.QuickTreatmentActivity
import app.mauri.sukrara.feature.treatments.QuickTreatmentIntents
import app.mauri.sukrara.graph.GraphConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetTapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-10-27T08:00:00Z")

    private fun frame(
        mgdl: Int?,
        age: Duration = Duration.ofMinutes(1),
        role: Role? = Role.Wearer,
        firing: Set<String> = emptySet(),
        sensor: SensorState = SensorState.Active(now.plus(Duration.ofDays(5))),
    ): WidgetFrame {
        val r = mgdl?.let { Reading(now.minus(age), it, 0.0, SourceId("test")) }
        val g =
            GlucoseState.Initial.copy(
                role = role,
                latest = r,
                recent = listOfNotNull(r),
                sensor = sensor,
                alerts = AlertUiState(firingKinds = firing),
            )
        return WidgetFrame(g, emptyList(), GraphConfig(), WidgetPlacements(), now, dark = false)
    }

    @Test
    fun `a value on screen opens quick Add treatment, Carbs first while low`() {
        assertEquals(WidgetTap.QuickTreatment(focusCarbs = false), widgetTap(context, frame(120)))
        assertEquals(WidgetTap.QuickTreatment(focusCarbs = true), widgetTap(context, frame(62)))
        assertEquals(WidgetTap.QuickTreatment(focusCarbs = true), widgetTap(context, frame(75, firing = setOf("low"))))
        assertEquals(
            "struck through at 5 min is still a value",
            WidgetTap.QuickTreatment(false),
            widgetTap(context, frame(120, Duration.ofMinutes(8))),
        )
    }

    @Test
    fun `without a value the tap opens the app`() {
        assertEquals(WidgetTap.OpenApp, widgetTap(context, frame(null)))
        assertEquals(WidgetTap.OpenApp, widgetTap(context, frame(120, Duration.ofMinutes(25))))
        assertEquals(WidgetTap.OpenApp, widgetTap(context, frame(120, role = null)))
    }

    @Test
    fun `a tap with a value opens the quick Add treatment dialog, Carbs focused while low`() {
        val quick = WidgetTap.intent(context, WidgetTap.QuickTreatment(focusCarbs = true))
        assertEquals(NavTargets.ACTION_QUICK_TREATMENT, quick.action)
        assertEquals(ComponentName(context.packageName, QuickTreatmentActivity::class.java.name), quick.component)
        assertEquals(QuickSource.Widget.name, quick.getStringExtra(QuickTreatmentIntents.EXTRA_SOURCE))
        assertTrue(quick.getBooleanExtra(NavTargets.EXTRA_FOCUS_CARBS, false))
        assertFalse(WidgetTap.intent(context, WidgetTap.QuickTreatment(false)).getBooleanExtra(NavTargets.EXTRA_FOCUS_CARBS, true))
        assertFalse(WidgetTap.intent(context, WidgetTap.OpenApp).action == NavTargets.ACTION_QUICK_TREATMENT)
    }
}
