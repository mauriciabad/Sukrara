// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.settings.DataStoreSettingsRepository
import app.mauri.sukrara.core.data.settings.ImportResult
import app.mauri.sukrara.core.data.settings.PreferenceStores
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.data.settings.SettingsTransfer
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private val graphHours = SchemaSettings.number("graph.hours")
    private val side = SchemaSettings.bool("graph.side", default = true)
    private val nostr = SchemaSettings.bool("sharing.nostr", default = true)
    private val source = SchemaSettings.text("source.source", default = "direct")
    private val alerts = SchemaSettings.text("alerts.on", default = "")
    private val confirmed = SchemaSettings.text("alerts.thresholdsConfirmedAt", default = "")

    private class Setup(val repo: DataStoreSettingsRepository, val transfer: SettingsTransfer)

    private fun setup(
        scope: CoroutineScope,
        dir: String = "a",
    ): Setup {
        val stores = PreferenceStores(tmp.newFolder(dir), CorruptionLog(), FakeClock(), scope)
        // the alert settings' codec stands in: only an object with "on" is accepted
        val transfer = SettingsTransfer(stores, "1.2.3", mapOf("alerts:alerts" to { t -> t.takeIf { "\"on\"" in it } }))
        return Setup(DataStoreSettingsRepository(stores), transfer)
    }

    @Test
    fun `every Android setting is carried or kept with a reason`() {
        val ids =
            SettingsSpec.SETTINGS
                .filter { it.android.isNotEmpty() && it.type != "state" }
                .map { it.id }
                .toSet()
        assertTrue(
            "kept ids must exist: ${SettingsTransfer.KEPT.keys - ids}",
            (
                SettingsTransfer.KEPT.keys - ids -
                    "alerts.thresholdsConfirmedAt"
            ).isEmpty(),
        )
        assertTrue(SettingsTransfer.KEPT.values.all { it.isNotBlank() })
    }

    @Test
    fun `export then import on another phone carries the options and nothing else`() =
        runTest {
            val a = setup(backgroundScope, "a")
            a.repo.set(graphHours, 6.0)
            a.repo.set(side, false)
            a.repo.set(nostr, false)
            a.repo.set(source, "juggluco")
            a.repo.set(alerts, """{"on":true,"v":1}""")
            val file = a.transfer.export()
            assertTrue(file.contains("\"kind\": \"sukrara-settings\""))
            assertFalse("sharing is kept", file.contains("relaySharing"))
            assertFalse("the source is kept", file.contains("juggluco"))

            val b = setup(backgroundScope, "b")
            b.repo.set(confirmed, "2026-10-01")
            val result = b.transfer.import(file)
            assertEquals(ImportResult.Done(3, alertsChanged = true), result)
            assertEquals(6.0, b.repo.flow(graphHours).first(), 0.0)
            assertFalse(b.repo.flow(side).first())
            assertTrue(b.repo.flow(nostr).first())
            assertEquals("direct", b.repo.flow(source).first())
            assertEquals("""{"on":true,"v":1}""", b.repo.flow(alerts).first())
            assertEquals("new alert settings: the threshold review is asked again", "", b.repo.flow(confirmed).first())
        }

    @Test
    fun `invalid values are skipped, numbers snap to their format`() =
        runTest {
            val s = setup(backgroundScope)
            s.repo.set(alerts, """{"on":false}""")
            val file =
                """{"kind":"sukrara-settings","v":1,"settings":{"graph":{"graphHours":500,"side":"yes"},"alerts":{"alerts":{"x":1}}}}"""
            assertEquals(ImportResult.Done(1), s.transfer.import(file))
            assertEquals(48.0, s.repo.flow(graphHours).first(), 0.0)
            assertTrue(s.repo.flow(side).first())
            assertEquals("""{"on":false}""", s.repo.flow(alerts).first())
        }

    @Test
    fun `a file that isn't a settings file changes nothing`() =
        runTest {
            val s = setup(backgroundScope)
            s.repo.set(graphHours, 3.0)
            assertEquals(ImportResult.NotSettings, s.transfer.import("not json"))
            assertEquals(ImportResult.NotSettings, s.transfer.import("""{"kind":"other","settings":{"graph":{"graphHours":6}}}"""))
            assertEquals(3.0, s.repo.flow(graphHours).first(), 0.0)
        }

    @Test
    fun `reset puts the options back and keeps the setup`() =
        runTest {
            val s = setup(backgroundScope)
            s.repo.set(graphHours, 3.0)
            s.repo.set(nostr, false)
            s.repo.set(source, "juggluco")
            s.repo.set(confirmed, "2026-10-01")
            s.transfer.reset()
            assertEquals(12.0, s.repo.flow(graphHours).first(), 0.0)
            assertFalse(s.repo.flow(nostr).first())
            assertEquals("juggluco", s.repo.flow(source).first())
            assertEquals("", s.repo.flow(confirmed).first())
        }
}
