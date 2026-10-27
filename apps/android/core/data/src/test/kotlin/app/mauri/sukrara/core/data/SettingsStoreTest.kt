// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.settings.DataStoreSettingsRepository
import app.mauri.sukrara.core.data.settings.PreferenceStores
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val log = CorruptionLog()

    private fun repo(scope: CoroutineScope) = DataStoreSettingsRepository(PreferenceStores(tmp.root, log, FakeClock(), scope))

    private val graphHours = SchemaSettings.number("graph.hours")
    private val nostr = SchemaSettings.bool("sharing.nostr", default = true)
    private val source = SchemaSettings.text("source.source", default = "libre3", allowed = setOf("libre3", "juggluco"))

    @Test
    fun `unset settings read as their defaults`() = runTest {
        val repo = repo(backgroundScope)
        assertEquals(12.0, repo.flow(graphHours).first(), 0.0)
        assertTrue(repo.flow(nostr).first())
        assertEquals("libre3", repo.flow(source).first())
    }

    @Test
    fun `values persist in the schema store file under the schema key`() = runTest {
        val repo = repo(backgroundScope)
        repo.set(graphHours, 6.0)
        repo.set(nostr, false)
        assertEquals(6.0, repo.flow(graphHours).first(), 0.0)
        assertFalse(repo.flow(nostr).first())
        assertTrue(File(tmp.root, "graph.preferences_pb").exists())
        assertTrue(File(tmp.root, "sharing.preferences_pb").exists())
    }

    @Test
    fun `numbers snap to their format, unknown enum names read as the default`() = runTest {
        val repo = repo(backgroundScope)
        repo.set(graphHours, 500.0)
        assertEquals(48.0, repo.flow(graphHours).first(), 0.0)
        repo.set(source, "carrier-pigeon")
        assertEquals("libre3", repo.flow(source).first())
    }

    @Test
    fun `reset goes back to the default`() = runTest {
        val repo = repo(backgroundScope)
        repo.set(graphHours, 3.0)
        repo.reset(graphHours)
        assertEquals(12.0, repo.flow(graphHours).first(), 0.0)
    }

    @Test
    fun `a damaged settings file is copied aside and reported`() = runTest {
        File(tmp.root, "graph.preferences_pb").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val repo = repo(backgroundScope)
        assertEquals(12.0, repo.flow(graphHours).first(), 0.0)
        val report = log.files.value.single()
        assertEquals("settings:graph", report.store)
        assertEquals(7, report.quarantinedTo!!.length().toInt())
    }
}
