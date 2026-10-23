// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun hit(route: String, title: String, summary: String? = null, group: String? = null, crumbs: List<String> = emptyList()) =
        SettingsHit(listOf("settings", route), crumbs, IndexedRow(title, summary, group, null), null)

    private val all = listOf(
        hit("alerts", "High", "≥ 185 mg/dL", "Highs", listOf("Alerts")),
        hit("alerts", "After rapid insulin", "Highs and rising fast wait 60 min", "Quiet alerts", listOf("Alerts")),
        hit("apps", "Nightscout-compatible API", "For Home Assistant", "Nightscout", listOf("Share with apps")),
        hit("lockscreen", "In range in white", "Highs and lows keep their colours", null, listOf("Lock screen")),
    )

    @Test fun titleFirstWordBeatsOtherTitleWordsAndSummary() {
        // "high" starts the first word of "High" (1), only the summary of the lock screen row (7)
        assertEquals(listOf("High", "After rapid insulin", "In range in white"), search(all, "high").map { it.row.title })
    }

    @Test fun everyWordMustMatch() {
        assertEquals(listOf("After rapid insulin"), search(all, "rapid insulin").map { it.row.title })
        assertTrue(search(all, "rapid nightscout").isEmpty())
    }

    @Test fun pageAndGroupNamesFindRows() {
        assertEquals(listOf("Nightscout-compatible API"), search(all, "share apps").map { it.row.title })
    }

    @Test fun accentsAndCaseDontMatter() {
        assertEquals("high", fold("HÍGH"))
        assertEquals(listOf("High"), search(all, "HÍG").map { it.row.title }.take(1))
    }

    /** Keywords (AOSP `settings:keywords`) find a row like its summary does, and are never shown. */
    @Test fun keywordsFindTheRow() {
        val pens = SettingsHit(listOf("settings", "settings"), emptyList(),
            IndexedRow("Insulin pens", "Off", null, null, to = "pens", keywords = "NovoPen Echo Plus NovoPen 6"), null)
        assertEquals(listOf("Insulin pens"), search(all + pens, "novopen 6").map { it.row.title })
        assertEquals(listOf("High", "After rapid insulin", "In range in white"), search(all + pens, "high").map { it.row.title })
    }

    @Test fun blankFindsNothing() { assertTrue(search(all, "  ").isEmpty()) }

    /** The index holds the pages' resolved strings, so in Spanish it finds the Spanish rows, accents optional. */
    @Test fun spanishRowsAreFoundWithoutAccents() {
        val es = listOf(
            hit("alerts", "Alta", "≥ 185 mg/dL", "Subidas", listOf("Alertas")),
            hit("alerts", "Tras insulina rápida", "Las altas y las subidas rápidas esperan 60 min", "Silenciar alertas", listOf("Alertas")),
            hit("graph", "Glucosa media", null, "Junto al número", listOf("Gráfica y tiempo en rango")),
        )
        assertEquals(listOf("Tras insulina rápida"), search(es, "insulina rapida").map { it.row.title })
        assertEquals(listOf("Glucosa media"), search(es, "grafica numero").map { it.row.title })
        assertEquals("Alta", search(es, "alta").first().row.title)
    }
}

class ReleaseVersionTest {
    @Test fun onlyANewerReleaseIsAnUpdate() {
        assertTrue(newer("v0.11.14", "0.11.13"))
        assertTrue(newer("v0.12.0", "0.11.13"))
        assertTrue(newer("v1.0", "0.11.13"))
        assertEquals(false, newer("v0.11.12", "0.11.13"))   // the installed test build is ahead of the release
        assertEquals(false, newer("v0.11.13", "0.11.13"))
        assertEquals(false, newer("v0.11.9", "0.11.10"))     // 9 < 10, not "9" > "1"
    }
}
