// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

/**
 * Every place that shows the glucose card or its value: the Android side of contracts/graph/surfaces/surfaces.json
 * ("One graph everywhere", CLAUDE.md). A [Kind.CARD] surface draws card pixels only through the renderer (it chooses
 * its size, its place's settings and the part); a [Kind.GLYPH] surface (text and icons the system lays out) takes
 * the value, trend mark and freshness from `GlucoseCard.face`. tools/check `card-surface` and SurfacesRegistryTest
 * hold this list equal to the registry.
 */
enum class CardSurface(
    val id: String,
    val kind: Kind,
) {
    WIDGET_VALUE("widget-value", Kind.CARD),
    WIDGET_GRAPH("widget-graph", Kind.CARD),
    WIDGET_COMBINED("widget-combined", Kind.CARD),
    APP_PANEL("app-panel", Kind.CARD),
    NOTIFICATION("notification", Kind.CARD),
    NOTIFICATION_TEXT("notification-text", Kind.GLYPH),
    WIDGET_DESCRIPTION("widget-description", Kind.GLYPH),
    STATUS_ICONS("status-icons", Kind.GLYPH),
    STATUS_HOOK("status-hook", Kind.GLYPH),
    QUICK_TILE("quick-tile", Kind.GLYPH),

    /** Picture sheets and fixtures (tests only; tools/check keeps it out of the app). */
    TEST("test", Kind.CARD),
    ;

    enum class Kind { CARD, GLYPH }
}
