// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.graph.stats.TirRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsStoreTest {
    private val backing = InMemoryStateStore(WidgetPlacements())
    private val store = WidgetSettingsStore(backing)

    @Test
    fun `each placement keeps its own settings and a removed one is forgotten`() =
        runTest {
            store.set(1, WidgetOverrides(hours = 3, rings = 0))
            store.set(2, WidgetOverrides(side = false, ringRange = TirRange.TIGHT))
            assertEquals(WidgetOverrides(hours = 3, rings = 0), store.get(1))
            assertEquals(WidgetOverrides(side = false, ringRange = TirRange.TIGHT), store.get(2))
            store.forget(intArrayOf(1))
            assertEquals("a reused id starts clean", WidgetOverrides(), store.get(1))
            assertEquals(
                setOf(2),
                backing.data
                    .first()
                    .byId.keys,
            )
        }

    @Test
    fun `all "same as the app" stores nothing`() =
        runTest {
            store.set(5, WidgetOverrides(hours = 6))
            store.set(5, WidgetOverrides())
            assertTrue(
                backing.data
                    .first()
                    .byId
                    .isEmpty(),
            )
        }

    @Test
    fun `fields are stored under the schema keys of widget overrides`() {
        val json = Json.encodeToString(WidgetOverrides.serializer(), WidgetOverrides(1, 2, TirRange.TIGHT, true, false))
        val keys = Json.parseToJsonElement(json).jsonObject.keys
        val schema =
            Json
                .parseToJsonElement(Contracts.file("settings/settings.json").readText())
                .jsonObject["settings"]
                .toString()
        val entry = Regex("\"widget\\.overrides\".*?\"key\":\\s*\"([^\"]+)\"").find(schema)?.groupValues?.get(1)
        assertEquals(entry?.split(",")?.toSet(), keys)
    }
}
