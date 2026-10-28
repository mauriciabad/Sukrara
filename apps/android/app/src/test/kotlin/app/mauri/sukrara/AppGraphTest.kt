// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The graph composes: every module builds from its inputs without I/O or a crash (a module that throws when built —
 * like a hand-made SettingSpec once did — stops the service, the notification and the alerts), and the surfaces that
 * must agree share one instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppGraphTest {
    private val graph = AppGraph(ApplicationProvider.getApplicationContext())

    @After fun tearDown() = graph.scope.cancel()

    @Test fun everyModuleBuilds() {
        listOf(
            graph.data,
            graph.role,
            graph.sensor,
            graph.monitor,
            graph.alerts,
            graph.settingsUi,
            graph.sensorUi,
            graph.home,
            graph.treatments,
            graph.widgets,
        ).forEach { checkNotNull(it) }
    }

    @Test fun homeAndSettingsShowTheSameAlertChecklist() {
        assertSame(graph.alerts.readiness.readiness, graph.home.readiness)
        assertSame(graph.alerts.readiness, graph.settingsUi.deps.readiness)
    }
}
