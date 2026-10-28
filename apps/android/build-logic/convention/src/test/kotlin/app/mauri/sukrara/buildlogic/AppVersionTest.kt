// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun `0_12_0 is versionCode 12000 and a prerelease`() {
        val v = AppVersion.parse("0.12.0\n")
        assertEquals("0.12.0", v.name)
        assertEquals(12_000, v.code)
        assertTrue(v.prerelease)
    }

    @Test
    fun `codes grow with every part`() {
        assertEquals(1_002_003, AppVersion.parse("1.2.3").code)
        assertFalse(AppVersion.parse("1.0.0").prerelease)
        assertTrue(AppVersion.parse("0.12.1").code > AppVersion.parse("0.12.0").code)
        assertTrue(AppVersion.parse("0.13.0").code > AppVersion.parse("0.12.999").code)
    }

    @Test
    fun `bad formats are rejected`() {
        listOf("", "0.12", "0.12.0.1", "v0.12.0", "0.1000.0", "0.0.1000", "0.12.0-rc1", "a.b.c").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { AppVersion.parse(it) }
        }
    }
}
