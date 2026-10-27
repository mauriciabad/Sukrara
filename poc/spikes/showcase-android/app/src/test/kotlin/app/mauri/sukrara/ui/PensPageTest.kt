// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.owner.OwnerUi
import app.mauri.sukrara.pen.PenProfiles
import app.mauri.sukrara.pen.Pens
import com.android.settingslib.spa.framework.theme.SettingsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings → Insulin pens. Which pens work is the page's footer (AOSP: rows are things you act on, information goes in
 * the footer), built from [PenProfiles.ALL] (one source of truth), the untested ones marked in words; search finds the
 * *Insulin pens* row by every model (its keywords, AOSP `settings:keywords`) in English and Spanish. The *Automatic
 * detection* page has one row per tunable rule, and [Pens.rules] reads every one of them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PensPageTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val ctx get() = compose.activity

    private fun index(route: String, page: @Composable () -> Unit): PageIndex {
        val idx = PageIndex(route)
        compose.setContent { CompositionLocalProvider(LocalSettingsIndex provides idx) { SettingsTheme { page() } } }
        compose.waitForIdle()
        return idx
    }

    private fun hits(idx: PageIndex) = idx.rows.values.map { SettingsHit(listOf("settings", idx.route), listOf(idx.title ?: ""), it, null) }

    /** The wearer's settings overview on a phone with NFC, indexed: the row that opens *Insulin pens* is there. */
    private fun overview(): List<SettingsHit> {
        App.setRole(ctx, "owner")
        Pens.fakeNfc = true
        try { return hits(index("settings") { WearerSettings(OwnerUi()) {} }) } finally { Pens.fakeNfc = false }
    }

    @Test fun footerNamesEveryPenProfile() {
        val footer = pensFooter(ctx)
        for (p in PenProfiles.ALL) {
            assertTrue(footer, footer.contains(p.name))
            assertEquals(p.name, !p.verified, footer.contains(ctx.getString(R.string.pens_footer_untested, p.name)))
        }
        assertTrue("one verified model", PenProfiles.ALL.any { it.verified })
        assertEquals("Sukrara reads NovoPen Echo Plus and NovoPen 6 (untested). Other NFC pens, and Bluetooth pens or caps like " +
            "Tempo, InPen or Mallya, aren't supported.", footer)
    }

    @Test fun noRowPerModel() {
        // information isn't a list of rows: nothing on the page is titled with a model (the user's own pens go by their name)
        val titles = index("pens") { PensSettings {} }.rows.values.map { it.title }
        assertTrue(titles.toString(), PenProfiles.ALL.none { it.name in titles })
    }

    @Test fun searchFindsInsulinPensByModel() {
        val hits = overview()
        val title = ctx.getString(R.string.pens_title)
        for (q in PenProfiles.ALL.map { it.name } + listOf("NovoPen", "echo plus", "pen"))
            assertEquals(q, title, search(hits, q).firstOrNull()?.row?.title)
        assertEquals("pens", search(hits, "NovoPen 6").first().row.to)
    }

    @Config(qualifiers = "es")
    @Test fun spanishFooterAndSearch() {
        assertEquals("Sukrara lee NovoPen Echo Plus y NovoPen 6 (sin probar). Otras plumas NFC, y las plumas o capuchones Bluetooth " +
            "como Tempo, InPen o Mallya, no son compatibles.", pensFooter(ctx))
        val hits = overview()
        val title = ctx.getString(R.string.pens_title)
        for (q in listOf("pluma", "novopen", "NovoPen 6")) assertEquals(q, title, search(hits, q).firstOrNull()?.row?.title)
    }

    @Test fun detectionPageHasEveryRule() {
        val idx = index(PEN_DETECTION_ROUTE) { PenDetectionSettings {} }
        val titles = idx.rows.values.map { it.title }
        val want = listOf(R.string.pens_prime_below, R.string.pens_prime_before, R.string.pens_cluster, R.string.pens_pending,
            R.string.pens_offer, R.string.pens_match, R.string.pens_match_units, R.string.pens_match_share, R.string.pens_detection_reset)
        assertEquals(want.map { ctx.getString(it) }, titles)
        // the summary states the rule with the current value
        assertTrue(idx.rows.values.first { it.title == ctx.getString(R.string.pens_cluster) }.summary!!.contains("10"))
        assertTrue(search(hits(idx), "injection").isNotEmpty())
    }

    @Test fun rulesReadEverySetting() {
        val f = FeatureState(penPrimeBelow = 2.5, penClusterMin = 15, penPrimeBeforeMin = 20, penPendingMin = 60, penMatchMin = 60,
            penMatchUnits = 2.0, penMatchPct = 50, penOfferH = 6)
        val r = Pens.rules(f)
        assertEquals(listOf(2.5, 900.0, 1200.0, 3600.0, 3600.0, 2.0, 0.5, 21600.0),
            listOf(r.primeBelow, r.cluster.toDouble(), r.primeBefore.toDouble(), r.pending.toDouble(), r.match.toDouble(), r.matchUnits, r.matchShare, r.offer.toDouble()))
        // the defaults are the engine's defaults
        assertEquals(app.mauri.sukrara.pen.PenRules(), Pens.rules(FeatureState()))
        // Reset to defaults: every detection setting back, the rest kept
        val reset = f.copy(penReading = "on").penDetectionDefaults()
        assertEquals(FeatureState(penReading = "on"), reset)
        assertTrue(reset.penDetectionIsDefault)
    }
}
