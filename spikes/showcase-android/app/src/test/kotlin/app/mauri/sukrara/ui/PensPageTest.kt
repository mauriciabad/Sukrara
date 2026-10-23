// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.mauri.sukrara.R
import app.mauri.sukrara.features.FeatureState
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
 * Settings → Insulin pens, composed in index mode (what search records): *Supported pens* lists exactly
 * [PenProfiles.ALL] (one source of truth), the unverified ones marked untested, and search finds them in English and
 * Spanish. The *Automatic detection* page has one row per tunable rule, and [Pens.rules] reads every one of them.
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

    @Test fun supportedPensAreThePenProfiles() {
        val idx = index("pens") { PensSettings {} }
        val rows = idx.rows.values.filter { it.group == ctx.getString(R.string.pens_supported_group) }
        assertEquals(PenProfiles.ALL.map { it.name }, rows.map { it.title })
        val untested = ctx.getString(R.string.newsensor_untested_tag)
        assertEquals(PenProfiles.ALL.map { if (it.verified) null else untested }, rows.map { it.summary })
        assertTrue("one verified model", PenProfiles.ALL.any { it.verified })
    }

    @Test fun searchFindsTheSupportedPens() {
        val hits = hits(index("pens") { PensSettings {} })
        for (p in PenProfiles.ALL) assertTrue(p.name, search(hits, p.name).any { it.row.title == p.name })
        assertTrue(search(hits, "NovoPen").map { it.row.title }.containsAll(PenProfiles.ALL.map { it.name }))
        assertTrue(search(hits, "pen").map { it.row.title }.containsAll(PenProfiles.ALL.map { it.name }))
    }

    @Config(qualifiers = "es")
    @Test fun spanishSearchFindsThem() {
        val hits = hits(index("pens") { PensSettings {} })
        assertTrue(search(hits, "pluma").map { it.row.title }.containsAll(PenProfiles.ALL.map { it.name }))
        assertTrue(search(hits, "novopen").isNotEmpty())
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
