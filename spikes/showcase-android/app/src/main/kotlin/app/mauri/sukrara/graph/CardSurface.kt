// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.RectF

/**
 * Every place that shows the glucose card or its value: the registry of spikes/graph-fixtures/surfaces/surfaces.json
 * (the Mac keeps its own list in the same file). "One graph everywhere" (CLAUDE.md, the user 2026-10-21) is enforced
 * from here:
 *
 * - a [Kind.CARD] surface draws card pixels only through [GlucoseCard.draw] (it chooses the size, its place's
 *   settings ([CardStyle]) and the [CardPart]s; nothing else), and every call names its surface;
 * - a [Kind.GLYPH] surface (text and icons the system lays out: status bar, notification header and chip, Live
 *   Update, the Quick Settings tile, the screen-reader words) takes the value, the arrow and the freshness steps from
 *   [GlucoseCard.face] (a [GlucoseLook]) and its glyphs from the card's painters ([ValueGlyphs]);
 * - tools/check `card-surface` fails on a file that calls the renderer and isn't the registered file of the surface
 *   it names, and `card-api` on a file outside the renderer that uses the renderer's internals;
 * - `CardLayoutTest` (and the Mac's `-graphselftest`) renders the shared scenes through every surface's real entry
 *   path and checks that what each card shows depends only on the inputs spikes/graph-fixtures/layout/rules.json
 *   allows (the surface is never one of them), and the renderer's layouts against the shared golden cases.
 */
enum class CardSurface(val id: String, val kind: Kind) {
    WIDGET_VALUE("widget-value", Kind.CARD),
    WIDGET_GRAPH("widget-graph", Kind.CARD),
    WIDGET_COMBINED("widget-combined", Kind.CARD),
    APP_PANEL("app-panel", Kind.CARD),
    FIRST_READING("first-reading", Kind.CARD),
    LOCKSCREEN("lockscreen", Kind.CARD),
    NOTIFICATION("notification", Kind.CARD),
    NOTIFICATION_TEXT("notification-text", Kind.GLYPH),
    STATUS_HOOK("status-hook", Kind.GLYPH),
    STATUS_ICONS("status-icons", Kind.GLYPH),
    QUICK_TILE("quick-tile", Kind.GLYPH),
    WIDGET_DESCRIPTION("widget-description", Kind.GLYPH),
    LOCK_DESCRIPTION("lock-widget-description", Kind.GLYPH),
    /** Picture sheets and fixtures (tests only; tools/check keeps it out of the app). */
    TEST("test", Kind.CARD);

    enum class Kind { CARD, GLYPH }
}

/**
 * What one [GlucoseCard.draw] call laid out: every element it drew, with its box (px, in the call's own coordinates)
 * and what it shows ([El.info]). The cross-surface test reads it; the app ignores it. [exact] boxes are the same on
 * both platforms (sized by the [TextModel] or by the box alone); the others depend on the font.
 */
class CardLayout(val surface: CardSurface, val part: CardPart, val style: CardStyle, val w: Float, val h: Float, val d: Float) {
    class El(val name: String, val box: RectF?, val info: String, val exact: Boolean)

    val elements = ArrayList<El>()

    fun add(name: String, box: RectF? = null, info: String = "", exact: Boolean = false) {
        elements += El(name, box?.let(::RectF), info, exact)
    }

    fun all(name: String) = elements.filter { it.name == name }
    fun first(name: String) = elements.firstOrNull { it.name == name }

    companion object {
        /** [px] in dp to the nearest 0.5, as the report writes it on both platforms ("33.5", "40"). */
        fun dp(px: Float, d: Float): String {
            val v = Math.round(px / d * 2) / 2.0
            return if (v == Math.floor(v)) "${v.toLong()}" else "$v"
        }
    }
}

/**
 * Paint-only motion of the app panel's value (the number rolls, the arrow turns, the colour fades; Motion.kt's
 * specs): [prev] = the number it rolls from (null = none), [roll] 0 → 1, [up] = the direction, [arrowDeg] = the
 * arrow's current angle, [color] = the current colour. It never changes what is laid out or where.
 */
data class CardMotion(val prev: String?, val roll: Float, val up: Boolean, val arrowDeg: Float?, val color: Int?)
