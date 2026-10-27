// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Every timeline of contracts/alerts/scenarios, one test each ([AlertScenarios]). Expectations never change here. */
@RunWith(Parameterized::class)
class AlertScenarioTest(private val name: String, private val sc: AlertScenarios.Scenario) {
    @Test fun timeline() {
        val errors = AlertScenarios.run(sc)
        if (errors.isNotEmpty()) fail("$name\n" + errors.joinToString("\n"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> = AlertScenarios.load().also { assertTrue("no scenarios found", it.isNotEmpty()) }
            .map { arrayOf("${it.file}: ${it.title}", it) }
    }
}
