// Sukrara — https://github.com/mauriciabad/Sukrara
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
    fun `0_90_0 is versionCode 90000 and a prerelease`() {
        val v = AppVersion.parse("0.90.0\n")
        assertEquals("0.90.0", v.name)
        assertEquals(90_000, v.code)
        assertTrue(v.prerelease)
    }

    @Test
    fun `codes grow with every part`() {
        assertEquals(1_002_003, AppVersion.parse("1.2.3").code)
        assertFalse(AppVersion.parse("1.0.0").prerelease)
        assertTrue(AppVersion.parse("0.90.1").code > AppVersion.parse("0.90.0").code)
        assertTrue(AppVersion.parse("0.91.0").code > AppVersion.parse("0.90.999").code)
    }

    @Test
    fun `bad formats are rejected`() {
        listOf("", "0.90", "0.90.0.1", "v0.90.0", "0.1000.0", "0.0.1000", "0.90.0-rc1", "a.b.c").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { AppVersion.parse(it) }
        }
    }
}
