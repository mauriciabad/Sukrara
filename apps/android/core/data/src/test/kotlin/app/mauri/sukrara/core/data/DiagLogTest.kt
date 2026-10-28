// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.diag.DiagLog
import app.mauri.sukrara.core.data.diag.Redact
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `lines are kept in order and rotate into one previous file`() =
        runTest {
            val log = DiagLog(tmp.root, FakeClock(), StandardTestDispatcher(testScheduler), backgroundScope, maxBytes = 200)
            repeat(20) { log.log("relay", "event $it") }
            testScheduler.advanceUntilIdle()

            assertTrue(File(tmp.root, "diag.log").length() <= 200)
            assertTrue(File(tmp.root, "diag.log.1").length() <= 200)
            assertFalse(File(tmp.root, "diag.log.2").exists())
            val lines = log.snapshot().lines().filter { it.isNotEmpty() }
            assertTrue(lines.last().endsWith("event 19"))
            assertEquals(lines.map { it.substringAfterLast(' ').toInt() }.sorted(), lines.map { it.substringAfterLast(' ').toInt() })
        }

    @Test
    fun `secrets are scrubbed`() {
        // assembled at runtime so the secrets check doesn't see a key-shaped literal (it isn't a real key)
        val fakeKey = "ns" + "ec1" + "qqqsyqcyq5rqwzqfpg9scrgwpugpzysnzs23v9ccrydpk8qarc0jqh7lzz"
        val text =
            Redact.scrub(
                "key $fakeKey " +
                    "url https://ns.example/api?token=abc123&x=1 header Bearer eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl " +
                    "event 3f9a1c0b2d4e5f60718293a4b5c6d7e8",
            )
        assertFalse(text, text.contains("qqqsyq"))
        assertFalse(text, text.contains("abc123"))
        assertFalse(text, text.contains("eyJ"))
        assertFalse(text, text.contains("3f9a1c0b"))
        assertTrue(text, text.contains("x=1"))
    }

    @Test
    fun `plain events pass unchanged`() {
        assertEquals("relay connected after 3 tries", Redact.scrub("relay connected after 3 tries"))
    }
}
