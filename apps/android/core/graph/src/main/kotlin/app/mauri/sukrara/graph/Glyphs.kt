// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import kotlin.math.abs

/** Material Symbols drawn on the card (Apache 2.0; viewBox 0 -960 960 960). */
internal object Glyphs {
    /** `sensors_off`: the "no readings" icon (20 min without a reading), as in the status bar. */
    private const val NO_READINGS =
        "M791-55 325-521q-2 10-3.5 20t-1.5 21q0 33 12.5 62t34.5 51l-57 57q-32-33-51-76.5T240-480q0-28 6-54t17-49l-59-59" +
            "q-21 36-32.5 76.5T160-480q0 67 25 125t69 101l-57 57q-54-55-85.5-127.5T80-480q0-62 17.5-117T146-700l-91-91 57-57 " +
            "736 736-57 57Zm23-205-58-58q21-36 32.5-76.5T800-480q0-67-25-125t-69-101l57-57q54 54 85.5 126.5T880-480q0 61-17.5 " +
            "116.5T814-260ZM697-377l-62-62q2-10 3.5-20t1.5-21q0-33-12.5-62T593-593l57-57q32 32 51 75.5t19 94.5q0 28-6 54t-17 49Z"
    val noReadings: Path by lazy { PathParser.createPathFromPathData(NO_READINGS) }

    /** [path] (a 960-unit symbol), [size] px square, top-left at ([x], [y]). */
    fun drawSymbol(
        cv: Canvas,
        path: Path,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
    ) {
        cv.save()
        cv.translate(x, y)
        cv.scale(size / 960f, size / 960f)
        cv.translate(0f, 960f)
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        cv.restore()
    }
}

/**
 * A fixed text width model, so layout decisions never depend on the font (Roboto here, SF Pro on the Mac): the
 * treatment label layout and the sensor badge's room use it; only the drawing measures real text. Widths in em, at
 * or a little above the wider of Roboto and SF Pro bold. Same table on the Mac; contracts/graph/labels pins it.
 */
object TextModel {
    fun em(c: Char): Float =
        when {
            c in '0'..'9' -> 0.72f
            c == '.' || c == ',' || c == '·' || c == ':' -> 0.38f
            c == ' ' -> 0.3f
            c == 'm' -> 0.96f
            c == 'w' -> 0.88f
            c == 'i' || c == 'l' || c == 'j' -> 0.32f
            c == 'r' || c == 'f' || c == 't' -> 0.46f
            c == '%' -> 1.06f
            c in 'a'..'z' -> 0.68f
            c in 'A'..'Z' -> 0.78f
            else -> 0.75f
        }

    /** Width of [s] at text size [size] (same unit as [size]). */
    fun width(
        s: String,
        size: Float,
    ): Float = size * s.fold(0f) { w, c -> w + em(c) }
}

/**
 * The rounded face for the card's big numbers (like SF Pro Rounded on the Mac): Google Sans Flex (SIL OFL 1.1,
 * assets/fonts/OFL.txt) at full roundness, weights 600 / 700 / 800, subset to Basic Latin. Before [init] (or if a
 * file is missing) the system sans at that weight.
 */
object RoundedFont {
    @Volatile private var assets: AssetManager? = null
    private val cache = HashMap<Int, Typeface>()

    fun init(c: Context) {
        assets = c.applicationContext.assets
    }

    /** The face at [weight] (600, 700 or 800; other weights take the nearest). */
    fun typeface(weight: Int): Typeface =
        synchronized(cache) {
            val w = listOf(600, 700, 800).minBy { abs(it - weight) }
            cache[w] ?: assets
                ?.let { a -> runCatching { Typeface.createFromAsset(a, "fonts/GoogleSansFlexRounded-$w.ttf") }.getOrNull() }
                ?.also { cache[w] = it }
                ?: Typeface.create(Typeface.DEFAULT, weight, false)
        }
}
