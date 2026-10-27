// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

/**
 * A fixed text width model, so layout decisions never depend on the font (Roboto here, SF Pro on the Mac): the graph's
 * treatment label layout and the sensor badge's room use it (the side columns measure their real labels), and only the drawing measures real text (centred in the
 * modelled box). Widths in em, at or a little above the wider of Roboto and SF Pro at bold weight and small sizes (SF
 * Pro Text Bold at 9 pt: digits up to 0.71 em, "m" 0.95; Roboto's digits 0.56), so a modelled box always holds the
 * real text on both (the Mac's `-graphselftest` checks it for every fixture label). Same table in SukraraGraph.swift
 * (`TextModel`); the label fixtures (spikes/graph-fixtures/labels/) pin the results on both platforms.
 */
object TextModel {
    fun em(c: Char): Float = when {
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

    /** Width of [s] at text size [size] (any unit: the result is in the same unit). */
    fun width(s: String, size: Float): Float = size * s.fold(0f) { w, c -> w + em(c) }
}
