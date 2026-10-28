// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.status

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The root status bar's slot icons ([RootStatusBar.iconPlan]: a number drawable `sb_n…` / `sb_s…` / `sb_lo` / `sb_hi`
 * and, right of it, a trend drawable `sb_t…`). SystemUI puts its own space between the two slot icons, as between all
 * its status icons; the drawables' free sides add to it. Measured on the user's phone (Pixel 10 Pro, Android 17, root
 * system-icon path, 420 dpi, 2026-10-21): 19 px = 7.24 dp from the digits to the arrow with 1.5 dp of drawable padding
 * (0.11.34–0.11.37, "too separated"), so SystemUI's share is [SYSTEMUI_DP]; between its own icons it leaves 5.7–6.5 dp.
 * The visible gap must stay in that range: not touching (an emulator approximation without SystemUI's spacing made it
 * look that way and led to the padding), not wider than SystemUI's own spacing.
 * spikes/statusbar-android/tools/gen-slot-drawables.swift writes the drawables; this reads the geometry back.
 */
class SlotDrawablesTest {
    private val dir: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .map { File(it, "spikes/showcase-android/app/src/main/res/drawable") }.first { it.isDirectory }

    /** The ink's left and right x (dp) and the drawable's width, from its vector XML. */
    private fun ink(f: File): Triple<Double, Double, Double> {
        val x = f.readText()
        val width = Regex("""android:viewportWidth="([0-9.]+)"""").find(x)!!.groupValues[1].toDouble()
        // the group's shift applies to the paths inside it only (a struck number's bar is outside)
        val group = Regex("""<group android:translateX="(-?[0-9.]+)">(.*?)</group>""", RegexOption.DOT_MATCHES_ALL).find(x)
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (p in Regex("""<path ([^>]*)/>""").findAll(x)) {
            val attrs = p.groupValues[1]
            val data = Regex("""android:pathData="([^"]*)"""").find(attrs)!!.groupValues[1]
            val stroke = Regex("""android:strokeWidth="([0-9.]+)"""").find(attrs)?.groupValues?.get(1)?.toDouble() ?: 0.0
            val xs = if (data.contains('A')) {
                // the ring: "M x0,y A r,r 0 1,0 x1,y A …": its two ends across the middle
                Regex("""[MA][^MAZ]*?(-?[0-9.]+),(-?[0-9.]+)(?=[A-Z]|$)""").findAll(data).map { it.groupValues[1].toDouble() }.toList()
            } else Regex("""(-?[0-9.]+),(-?[0-9.]+)""").findAll(data).map { it.groupValues[1].toDouble() }.toList() +
                // the struck bar's horizontal edges
                Regex("""H(-?[0-9.]+)""").findAll(data).map { it.groupValues[1].toDouble() }.toList()
            val shift = if (group != null && p.range.first in group.range) group.groupValues[1].toDouble() else 0.0
            lo = minOf(lo, xs.min() + shift - stroke / 2); hi = maxOf(hi, xs.max() + shift + stroke / 2)
        }
        return Triple(lo, hi, width)
    }

    @Test fun numberAndTrendSitAsSystemUiSpacesIcons() {
        val files = dir.listFiles()!!.filter { it.name.startsWith("sb_") && it.name.endsWith(".xml") }
        val (trends, numbers) = files.partition { it.name.startsWith("sb_t") }
        assertTrue("no slot drawables found in $dir", trends.isNotEmpty() && numbers.isNotEmpty())
        val numberRight = numbers.associate { f -> f.name to ink(f).let { it.third - it.second } }
        val trendLeft = trends.associate { f -> f.name to ink(f).first }
        val lo = numberRight.minBy { it.value }; val hi = numberRight.maxBy { it.value }
        val tlo = trendLeft.minBy { it.value }; val thi = trendLeft.maxBy { it.value }
        val narrowest = SYSTEMUI_DP + lo.value + tlo.value
        val widest = SYSTEMUI_DP + hi.value + thi.value
        assertTrue("number → trend %.2f–%.2f dp on the phone (%s + %s … %s + %s); want %.1f–%.1f dp, SystemUI's own icon spacing"
            .format(narrowest, widest, lo.key, tlo.key, hi.key, thi.key, MIN_DP, MAX_DP), narrowest >= MIN_DP && widest <= MAX_DP)
    }

    companion object {
        /** SystemUI's space between two slot icons on the root path (7.24 dp seen − the 1.5 dp drawable padding then). */
        const val SYSTEMUI_DP = 5.74
        /** The visible gap's range: SystemUI's spacing between its own status icons, measured on the same phone. */
        const val MIN_DP = 5.0
        const val MAX_DP = 6.5
    }
}
