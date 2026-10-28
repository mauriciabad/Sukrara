// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.settings.FollowerSettingsRepository
import app.mauri.sukrara.core.data.settings.MirroredGraph
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.data.settings.SettingsFrom
import app.mauri.sukrara.core.testing.InMemorySettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which value a follower's mirrored setting has per *Settings from*, and what stays this phone's own. */
class FollowerSettingsTest {
    private val low = SchemaSettings.number("graph.band.low")
    private val side = SchemaSettings.bool("graph.side", default = true)
    private val lines = SchemaSettings.text("graph.lines", "100")
    private val countdown = SchemaSettings.number("sensor.countdownHours")
    private val placeHours = SchemaSettings.numberAt("graph.hours.place", 0)
    private val nostr = SchemaSettings.bool("sharing.nostr", default = true)

    private val wearer =
        JsonObject(
            mapOf(
                "low" to JsonPrimitive(80),
                "side" to JsonPrimitive(false),
                "lines" to JsonPrimitive("90,150"),
                "countdownHours" to JsonPrimitive(4),
            ),
        )

    private suspend fun own() =
        InMemorySettingsRepository().apply {
            set(low, 65.0)
            set(side, false)
            set(lines, "120")
            set(countdown, 12.0)
            set(placeHours, 3.0)
        }

    @Test
    fun `custom reads this phone's values`() =
        runTest {
            val r = FollowerSettingsRepository(own(), MutableStateFlow(SettingsFrom.Custom), MutableStateFlow(wearer))
            assertEquals(65.0, r.flow(low).first(), 0.0)
            assertEquals("120", r.flow(lines).first())
            assertEquals(12.0, r.flow(countdown).first(), 0.0)
        }

    @Test
    fun `default reads the defaults whatever this phone or the wearer has`() =
        runTest {
            val r = FollowerSettingsRepository(own(), MutableStateFlow(SettingsFrom.Default), MutableStateFlow(wearer))
            assertEquals(70.0, r.flow(low).first(), 0.0)
            assertTrue(r.flow(side).first())
            assertEquals("100", r.flow(lines).first())
            assertEquals(8.0, r.flow(countdown).first(), 0.0)
        }

    @Test
    fun `wearer reads the wearer's values, the defaults until they arrive`() =
        runTest {
            val graph = MutableStateFlow<JsonObject?>(null)
            val r = FollowerSettingsRepository(own(), MutableStateFlow(SettingsFrom.Wearer), graph)
            assertEquals(70.0, r.flow(low).first(), 0.0)
            graph.value = wearer
            assertEquals(80.0, r.flow(low).first(), 0.0)
            assertFalse(r.flow(side).first())
            assertEquals("90,150", r.flow(lines).first())
            assertEquals(4.0, r.flow(countdown).first(), 0.0)
        }

    @Test
    fun `a wearer value out of spec is coerced, a wrong type or missing key reads as the default`() =
        runTest {
            val odd = JsonObject(mapOf("low" to JsonPrimitive(500), "side" to JsonPrimitive("no")))
            val r = FollowerSettingsRepository(own(), MutableStateFlow(SettingsFrom.Wearer), MutableStateFlow(odd))
            assertEquals(120.0, r.flow(low).first(), 0.0)
            assertTrue(r.flow(side).first())
            assertEquals("100", r.flow(lines).first())
        }

    @Test
    fun `settings that aren't mirrored are always this phone's`() =
        runTest {
            val mine = own()
            val r = FollowerSettingsRepository(mine, MutableStateFlow(SettingsFrom.Default), MutableStateFlow(wearer))
            assertEquals(3.0, r.flow(placeHours).first(), 0.0)
            assertNull(MirroredGraph.wireKey("graph.hours.place"))
            r.set(nostr, false)
            assertFalse(mine.flow(nostr).first())
        }

    @Test
    fun `copy and reset write the wearer's or the default values into this phone's own`() =
        runTest {
            val mine = own()
            val specs = listOf(low, side, lines, countdown)
            MirroredGraph.copyInto(mine, specs, SettingsFrom.Wearer, wearer)
            assertEquals(80.0, mine.flow(low).first(), 0.0)
            assertEquals("90,150", mine.flow(lines).first())
            assertEquals(4.0, mine.flow(countdown).first(), 0.0)
            MirroredGraph.copyInto(mine, specs, SettingsFrom.Default, wearer)
            assertEquals(70.0, mine.flow(low).first(), 0.0)
            assertTrue(mine.flow(side).first())
            assertEquals(8.0, mine.flow(countdown).first(), 0.0)
            assertEquals(3.0, mine.flow(placeHours).first(), 0.0)
        }
}
