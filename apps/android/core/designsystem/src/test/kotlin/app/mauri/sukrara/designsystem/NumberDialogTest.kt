// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem

import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.dialog.NumberInput.Check
import app.mauri.sukrara.settings.NumberSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The number input's rules (what the dialog offers, what it accepts), from synthetic specs shaped like the schema's. */
class NumberDialogTest {
    private fun spec(
        unit: String = "mg/dL",
        min: Double = 50.0,
        max: Double = 120.0,
        step: Double = 1.0,
        scale: Double = 1.0,
        decimals: Int = 0,
        presets: List<Double> = listOf(70.0, 80.0),
        special: Map<Double, String> = emptyMap(),
        custom: Boolean = true,
    ) = NumberSpec("t", unit, min, max, step, step, scale, decimals, presets, presets.firstOrNull() ?: min, special, custom, 1)

    @Test fun `special values come first, then presets`() {
        val input = NumberInput(spec(min = 5.0, max = 240.0, unit = "min", presets = listOf(30.0, 60.0), special = mapOf(0.0 to "off")))
        assertEquals(listOf(0.0, 30.0, 60.0), input.options)
    }

    @Test fun `glucose limit in mg per dL accepts whole numbers in range only`() {
        val input = NumberInput(spec())
        assertEquals(Check.Ok(72.0), input.check("72"))
        assertEquals(Check.OutOfRange, input.check("49"))
        assertEquals(Check.OutOfRange, input.check("121"))
        assertEquals(Check.Empty, input.check(""))
        assertEquals("725", input.filter("7a2.5"))
    }

    @Test fun `decimal spec takes a comma or a point once and keeps the step`() {
        val input = NumberInput(spec(unit = "mg/dL/min", min = 0.5, max = 5.0, step = 0.1, decimals = 1, presets = listOf(2.0)))
        assertEquals("2.51", input.filter("2.5.1"))
        assertEquals(2.5, (input.check("2,5") as Check.Ok).value, 1e-9)
        assertEquals(Check.OutOfRange, input.check("0.4"))
        assertEquals(Check.OutOfRange, input.check("2.55"))
    }

    @Test fun `ring period stores seconds and shows days`() {
        val day = 86400.0
        val input = NumberInput(spec(unit = "d", min = day, max = 90 * day, step = day, scale = day, presets = listOf(day, 7 * day)))
        assertEquals(Check.Ok(30 * day), input.check("30"))
        assertEquals("30", input.initialText(30 * day))
        assertEquals("", input.initialText(7 * day))
        assertEquals("1", input.shownMin)
        assertEquals("90", input.shownMax)
    }

    @Test fun `customMin and reject narrow what is accepted`() {
        val input = NumberInput(spec(), customMin = 60.0, reject = { if (it == 100.0) "Already drawn" else null })
        assertEquals(Check.OutOfRange, input.check("55"))
        assertEquals(Check.Rejected("Already drawn"), input.check("100"))
        assertEquals("60", input.shownMin)
    }

    @Test fun `a spec without custom values is a plain choice`() {
        val input = NumberInput(spec(custom = false))
        assertFalse(input.hasCustom)
        assertTrue(input.isOption(70.0))
    }
}
