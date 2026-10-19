// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import android.content.Context
import android.content.Intent
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.wearer.Live
import app.mauri.sukrara.wearer.WearerStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 2026-10-18: `--es cmd sensorEnd`, sent to the user's release build only to read the state, replaced the real sensor
 * with a made-up one ending in 10 minutes (a countdown started, calendar events were re-created). The fakes are
 * debug-only (DebugFakes.run, gated), and reading the state changes nothing. tools/check rule `debug` keeps the
 * fakes behind the gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DebugFakesTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val now = System.currentTimeMillis() / 1000
    private val real = SensorInfo("3MH0REAL", now - 5 * 86_400L, now + 10 * 86_400L)

    @Before fun setUp() {
        app.mauri.sukrara.sensorend.SensorEnd.resetForTest(kotlinx.coroutines.test.StandardTestDispatcher())   // no stale re-plan posts
        ctx.getSharedPreferences("role", Context.MODE_PRIVATE).edit().putString("role", "owner").commit()
        Live.status.value = WearerStatus()
        SensorLife.update(ctx, real)
    }

    /** Every extra any fake command reads, so each one would act if it ran. */
    private fun intent(cmd: String) = Intent().putExtra("cmd", cmd).putExtra("inMin", 10).putExtra("left", 600L)
        .putExtra("agoMin", 50).putExtra("serial", "TEST").putExtra("start", now).putExtra("end", now + 600)
        .putExtra("alertFeed", "40,-2").putExtra("packets", 1).putExtra("alertTest", "very_low").putExtra("previewSgv", 42)
        .putExtra("previewRate", -2f).putExtra("on", true).putExtra("stage", "ready").putExtra("k", "carbs").putExtra("a", 30f)

    private val nm get() = ctx.getSystemService(android.app.NotificationManager::class.java)

    /**
     * The release app never shows a glucose value, an alert, a treatment or a sensor state that isn't real (the user
     * acts on what Sukrara shows): on a release build no fake changes the sensor, Live, the notifications, the lock
     * screen widget, the New sensor screen or the treatment log, and the reply says why.
     */
    @Test fun aReleaseBuildRunsNoFake() {
        app.mauri.sukrara.treatments.Treat.init(ctx)
        val treatments = app.mauri.sukrara.treatments.Treat.store.items.value
        app.mauri.sukrara.alerts.Alerts.history.value = emptyList()
        val posted = nm.activeNotifications.map { it.id to it.postTime }.toSet()
        for (cmd in DebugFakes.COMMANDS) {
            val out = JSONObject()
            DebugFakes.run(ctx, cmd, intent(cmd), out, debug = false)
            assertEquals("$cmd replaced the sensor", real, SensorLife.current.value)
            assertTrue("$cmd says why: $out", out.optJSONArray("debugOnly")?.toString()?.contains("debug builds only")
                ?: out.optString("debugOnly").contains("debug builds only"))
        }
        assertEquals("a made-up reading", null, Live.status.value.last)
        assertEquals("a notification (test alert) posted", posted, nm.activeNotifications.map { it.id to it.postTime }.toSet())
        assertEquals("an alert fired", emptyList<Any>(), app.mauri.sukrara.alerts.Alerts.history.value)
        assertEquals("the lock screen test pattern", false, app.mauri.sukrara.widgets.LockscreenWidget.testPattern)
        assertEquals("a forced New sensor step", null, app.mauri.sukrara.ui.NewSensorFlow.preview.value)
        assertEquals("a made-up treatment", treatments, app.mauri.sukrara.treatments.Treat.store.items.value)
    }

    @Test fun readingTheSensorEndStateChangesNothing() {
        for (cmd in listOf("sensorEnd", "sensorEndState")) {
            val reply = DebugReceiver().reply(ctx, Intent().putExtra("cmd", cmd))
            assertEquals("$cmd without extras replaced the sensor", real, SensorLife.current.value)
            assertTrue(reply.has("sensorEnd"))
        }
    }

    @Test fun aDebugBuildFakesOnlyWhenAsked() {
        DebugReceiver().reply(ctx, Intent().putExtra("cmd", "sensorEnd").putExtra("countdownHours", 8))
        assertEquals(real, SensorLife.current.value)
        DebugFakes.run(ctx, "sensorEnd", intent("sensorEnd"), JSONObject(), debug = true)
        assertTrue(SensorLife.current.value!!.serial.startsWith("TEST-"))
        // the control for aReleaseBuildRunsNoFake: in a debug build the same commands do show made-up things
        val alerts = app.mauri.sukrara.alerts.Alerts
        alerts.history.value = emptyList()
        DebugFakes.run(ctx, "alertTest", intent("alertTest"), JSONObject(), debug = true)
        assertTrue("a test alert fires", alerts.history.value.isNotEmpty())
        DebugFakes.run(ctx, "locktest", intent("locktest"), JSONObject(), debug = true)
        assertTrue(app.mauri.sukrara.widgets.LockscreenWidget.testPattern)
        app.mauri.sukrara.widgets.LockscreenWidget.testPattern = false
    }
}
