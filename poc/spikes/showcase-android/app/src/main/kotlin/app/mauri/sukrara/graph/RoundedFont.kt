// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface

/**
 * The rounded face for the card's big numbers (the value, the rings' and slots' numbers), like SF Pro Rounded on the
 * Mac: Google Sans Flex (SIL OFL 1.1, assets/fonts/OFL.txt) with its roundness axis at full (ROND 100), instanced at
 * opsz 18 / width 100 and weights 600, 700 and 800, subset to Basic Latin plus a few signs (~33 KB each). Made from
 * github.com/google/fonts ofl/googlesansflex with fontTools; see docs/16-graph.md ("Rounded numbers").
 * Before [init] (or if a file is missing) it falls back to the system sans at that weight.
 */
object RoundedFont {
    @Volatile private var assets: AssetManager? = null
    private val cache = HashMap<Int, Typeface>()

    fun init(c: Context) { assets = c.applicationContext.assets }

    /** The face at [weight] (600, 700 or 800; other weights take the nearest). */
    fun typeface(weight: Int): Typeface = synchronized(cache) {
        val w = listOf(600, 700, 800).minBy { kotlin.math.abs(it - weight) }
        cache[w] ?: (assets?.let { a ->
            runCatching { Typeface.createFromAsset(a, "fonts/GoogleSansFlexRounded-$w.ttf") }.getOrNull()
        }?.also { cache[w] = it } ?: Typeface.create(Typeface.DEFAULT, weight, false))
    }
}
