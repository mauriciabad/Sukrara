// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.app.Application
import androidx.glance.appwidget.AppWidgetId
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.graph.GraphConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Removing a widget forgets its placement's settings inside Glance's own deletion work ([CardWidget.onDelete]), so the
 * receiver never calls `goAsync` a second time (that returned null and crashed the process, the monitoring with it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = WidgetDeleteTest.HostApp::class)
class WidgetDeleteTest {
    class HostApp :
        Application(),
        WidgetsHost {
        val scope = CoroutineScope(SupervisorJob())
        val store = InMemoryStateStore(WidgetPlacements(mapOf(7 to WidgetOverrides(hours = 3), 8 to WidgetOverrides(rings = 1))))
        override val widgets by lazy {
            WidgetsModule(
                this,
                scope,
                FakeClock(),
                emptyFlow<GlucoseState>(),
                flowOf(emptyList()),
                flowOf(GraphConfig()),
                flowOf(emptyList()),
                emptyFlow(),
                store,
            )
        }
        var readyAwaited = false

        override suspend fun awaitReady() {
            readyAwaited = true
        }
    }

    private val app: HostApp = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = app.scope.cancel()

    @Test
    fun `a removed placement's settings are forgotten once storage is ready, the others kept`() =
        runTest {
            GraphWidget().onDelete(app, AppWidgetId(7))
            assertEquals(true, app.readyAwaited)
            assertEquals(
                mapOf(8 to WidgetOverrides(rings = 1)),
                app.store.data
                    .first()
                    .byId,
            )
        }

    @Test
    fun `the receiver leaves deletion to Glance (no second goAsync)`() {
        val overrides = CardWidgetReceiver::class.java.declaredMethods.filter { it.name == "onDeleted" }
        assertEquals("CardWidgetReceiver must not override onDeleted", emptyList<Any>(), overrides)
    }
}
