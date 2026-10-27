// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update check on a recorded List-releases response (no network). */
class UpdateCheckTest {
    private val releases = UpdateCheck.parse(checkNotNull(javaClass.getResource("/releases.json")).readText())

    @Test
    fun `parses releases and skips one without a tag`() {
        assertEquals(listOf("v0.90.2-rc1", "v0.90.1", "v0.11.53"), releases.map { it.tag })
        assertEquals(2, releases[1].assets.size)
        assertTrue(releases[0].draft)
    }

    @Test
    fun `a 0_x install is offered the newest prerelease but never a draft`() {
        assertEquals("v0.90.1", UpdateCheck.newer(releases, "0.90.0", prerelease = true)?.tag)
    }

    @Test
    fun `a stable install is never offered a prerelease`() {
        assertNull(UpdateCheck.newer(releases, "0.11.53", prerelease = false))
        assertEquals("v0.11.53", UpdateCheck.newer(releases, "0.11.52", prerelease = false)?.tag)
    }

    @Test
    fun `up to date keeps the latest offered release for its other-device files`() {
        val state = UpdateCheck.state(Result.success(releases), "0.90.1", prerelease = true)
        assertEquals(UpdateState.UpToDate(releases[1]), state)
    }

    @Test
    fun `a failed fetch is offline`() {
        assertEquals(UpdateState.Offline, UpdateCheck.state(Result.failure(Exception("offline")), "0.90.0", true))
    }

    @Test
    fun `versions compare number by number with suffixes older`() {
        assertTrue(UpdateCheck.compare("v0.90.10", "0.90.9") > 0)
        assertTrue(UpdateCheck.compare("v0.11.12", "0.11.13") < 0)
        assertEquals(0, UpdateCheck.compare("v1.0", "1.0.0"))
        assertTrue(UpdateCheck.compare("0.90.0-rc1", "0.90.0") < 0)
        assertTrue(UpdateCheck.compare("0.90.1-rc1", "0.90.0") > 0)
        assertTrue(UpdateCheck.compare("v0.90.0-rc2", "0.90.0-rc1") > 0)
        assertEquals(0, UpdateCheck.compare("v0.90.0-rc1", "0.90.0-rc1"))
    }
}
