// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.testing

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.storage.SettingSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class FakesTest {
    private object HighThreshold : SettingSpec<Int> {
        override val key = "alerts.high.mgdl"
        override val default = 180
    }

    @Test
    fun `settings read the default until set`() =
        runTest {
            val settings = InMemorySettingsRepository()
            assertEquals(180, settings.flow(HighThreshold).first())
            settings.set(HighThreshold, 200)
            assertEquals(200, settings.flow(HighThreshold).first())
        }

    @Test
    fun `readings upsert replaces the same time and source and keeps time order`() =
        runTest {
            val repo = InMemoryReadingsRepository()
            val t0 = Instant.parse("2026-01-15T12:00:00Z")
            repo.upsert(listOf(Reading(t0.plusSeconds(60), 110, null, SourceId.Libre3), Reading(t0, 100, null, SourceId.Libre3)))
            repo.upsert(listOf(Reading(t0, 105, null, SourceId.Libre3)))
            assertEquals(listOf(105, 110), repo.window(t0).first().map { it.mgdl })
            assertEquals(listOf(110), repo.window(t0.plusSeconds(1)).first().map { it.mgdl })
        }

    @Test
    fun `secure store copies bytes and deletes`() =
        runTest {
            val store = InMemorySecureStore()
            val secret = byteArrayOf(1, 2, 3)
            store.write("identity", secret)
            secret[0] = 9
            assertEquals(1, store.read("identity")!![0].toInt())
            store.delete("identity")
            assertNull(store.read("identity"))
        }

    @Test
    fun `fake clock moves and changes zone`() {
        val clock = FakeClock()
        clock.advance(Duration.ofMinutes(5))
        assertEquals(Instant.parse("2026-01-15T12:05:00Z"), clock.now())
        clock.zone = ZoneId.of("Europe/Madrid")
        assertEquals("Europe/Madrid", clock.zone.id)
    }

    @Test
    fun `contracts explain a missing property`() {
        val saved = System.clearProperty(Contracts.PROPERTY)
        try {
            val e = assertThrows(IllegalStateException::class.java) { Contracts.file("x.json") }
            assert("sukrara.contracts" in e.message!!)
        } finally {
            saved?.let { System.setProperty(Contracts.PROPERTY, it) }
        }
    }
}
