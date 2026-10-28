// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.Fixtures.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** CardSurface lists exactly contracts/graph/surfaces/surfaces.json's Android surfaces, with the same kind. */
class SurfacesRegistryTest {
    @Test fun enumMatchesRegistry() {
        val registry =
            Fixtures
                .read("surfaces/surfaces.json")["android"]!!
                .jsonObject["surfaces"]!!
                .jsonArray
                .associate { it.jsonObject["id"].str()!! to it.jsonObject["kind"].str()!! }
        val enum =
            CardSurface.entries
                .filter { it != CardSurface.TEST }
                .associate { it.id to it.kind.name.lowercase() }
        assertEquals(registry.toSortedMap(), enum.toSortedMap())
    }
}
