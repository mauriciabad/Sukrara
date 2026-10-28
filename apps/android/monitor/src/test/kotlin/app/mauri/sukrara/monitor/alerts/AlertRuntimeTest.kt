// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertEffect
import app.mauri.sukrara.alerts.AlertInput
import app.mauri.sukrara.alerts.AlertScenarios
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Doses
import app.mauri.sukrara.alerts.ScenarioDevice
import app.mauri.sukrara.alerts.SensorWindow
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.Instant

/**
 * Every timeline of contracts/alerts/scenarios through the Android runtime (fake shade and alarm, virtual time): the
 * runtime must apply exactly what the engine decides, and a restart is a real process death (a new runtime over the
 * same stores). Expectations never change here (CLAUDE.md).
 */
@RunWith(Parameterized::class)
class AlertRuntimeTest(private val name: String, private val sc: AlertScenarios.Scenario) {
    /** The wearer phone: monitoring has started (the scenarios begin with readings). */
    private class RuntimeDevice(settings: AlertSettings, t0: Long) : ScenarioDevice {
        private val clock = FakeClock(Instant.ofEpochSecond(t0))
        private val rig = AlertRig(clock)
        private var runtime = rig.runtime()

        init {
            runBlocking {
                rig.store.setSettings(settings)
                runtime.onState(rig.wearerState(latest = null))
            }
        }

        override fun handle(input: AlertInput, t: Long): List<AlertEffect> =
            runBlocking {
                clock.instant = Instant.ofEpochSecond(t)
                when (input) {
                    is AlertInput.Settings -> rig.store.setSettings(input.settings)
                    is AlertInput.Treatments -> rig.treatments.value = treatmentsOf(input.doses)
                    else -> Unit
                }
                runtime.process(input)
            }

        override fun restart(settings: AlertSettings, doses: Doses, sensor: SensorWindow?, t: Long): List<AlertEffect> =
            runBlocking {
                clock.instant = Instant.ofEpochSecond(t)
                // a process death: a new runtime over the same stores (settings, treatments, saved record)
                runtime = rig.runtime()
                runtime.start()
            }
    }

    @Test fun timeline() {
        val errors = AlertScenarios.run(sc, ::RuntimeDevice)
        if (errors.isNotEmpty()) fail("$name\n" + errors.joinToString("\n"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> =
            AlertScenarios
                .load()
                .also { assertTrue("no scenarios found", it.isNotEmpty()) }
                .map { arrayOf("${it.file}: ${it.title}", it) }
    }
}
