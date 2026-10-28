// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.feature.settings.SettingsRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings search: matching (accents, case, word starts, ranking) and the index against the pages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsSearchTest {
    private val alerts = SearchTarget.Page(SettingsRoute.Alerts)
    private val graph = SearchTarget.Page(SettingsRoute.Graph)

    private fun item(
        title: String,
        summary: String? = null,
        page: String = "Alerts",
        target: SearchTarget = alerts,
    ) = SearchItem(title, summary, page, target)

    @Test
    fun foldDropsAccentsAndCase() {
        assertEquals("ultimo azucar", SettingsSearch.fold("Último Azúcar"))
        assertEquals("nino", SettingsSearch.fold("NIÑO"))
    }

    @Test
    fun matchesWordStartsIgnoringAccents() {
        val items = listOf(item("Último tratamiento"), item("Vibración"), item("Mute all"))
        assertEquals(listOf("Último tratamiento"), SettingsSearch.match("ultimo", items).map { it.title })
        assertEquals(listOf("Vibración"), SettingsSearch.match("VIBRA", items).map { it.title })
        // inside a word doesn't count: "ute" is not a word start
        assertTrue(SettingsSearch.match("ute", items).isEmpty())
        // every query word must match
        assertTrue(SettingsSearch.match("mute vibr", items).isEmpty())
        assertTrue(SettingsSearch.match("   ", items).isEmpty())
    }

    @Test
    fun titleMatchesComeBeforeSummaryAndPageMatches() {
        val items =
            listOf(
                item("Time shown", page = "Graph", target = graph),
                item("Volume", summary = "Each alert's sound"),
                item("Sound pack"),
            )
        assertEquals(listOf("Sound pack", "Volume"), SettingsSearch.match("sound", items).map { it.title })
        // "graph" is only the page of "Time shown": still found, after title matches
        val withTitle = items + item("Graph", page = "Settings", target = graph)
        assertEquals(listOf("Graph", "Time shown"), SettingsSearch.match("graph", withTitle).map { it.title })
    }

    /** Every settings page with rows of its own is searchable. */
    @Test
    fun everyPageHasEntries() {
        val pages =
            listOf(
                SettingsRoute.Alerts,
                SettingsRoute.Ranges,
                SettingsRoute.Graph,
                SettingsRoute.GraphMarks,
                SettingsRoute.LockScreen,
                SettingsRoute.Pens,
                SettingsRoute.Reliability,
                SettingsRoute.StatusBar,
                SettingsRoute.SensorEnd,
                SettingsRoute.About,
                SettingsRoute.ThresholdReview,
                SettingsRoute.Advanced,
            ) + AlertKind.entries.map(SettingsRoute::AlertKindPage)
        val targets = SettingsIndex.entries.map { it.target }.toSet()
        for (p in pages) assertTrue("$p has no search entries", SearchTarget.Page(p) in targets)
        for (o in OtherPage.entries) assertTrue("$o has no search entry", SearchTarget.Other(o) in targets)
    }

    /** The index's texts read as the pages show them: no format arguments left in (en and es). */
    @Test
    fun entriesHaveNoFormatArguments() = checkTexts()

    @Test
    @Config(qualifiers = "es")
    fun entriesHaveNoFormatArgumentsInSpanish() {
        checkTexts()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val items =
            SettingsIndex.entries.map {
                SearchItem(context.getString(it.title), it.summary?.let(context::getString), context.getString(it.page), it.target)
            }
        // an accented title found without accents
        assertFalse(SettingsSearch.match("vibracion", items).isEmpty())
    }

    private fun checkTexts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (e in SettingsIndex.entries) {
            for (id in listOfNotNull(e.title, e.summary, e.page)) {
                val text = context.getString(id)
                assertTrue("${context.resources.getResourceEntryName(id)} is empty", text.isNotBlank())
                assertFalse("${context.resources.getResourceEntryName(id)} has a format argument: $text", "%1$" in text || "%d" in text)
            }
        }
    }
}
