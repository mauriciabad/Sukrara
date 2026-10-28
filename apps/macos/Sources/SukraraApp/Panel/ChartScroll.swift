// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraGraph

/// Where the panel's chart looks: the end of its window (`end`, epoch s; nil = following now) and its length while
/// zoomed (`span`, s; nil = the place's *Time shown*). The same rules as Android's home chart (ChartScroll.kt): the
/// gestures, VoiceOver's actions and the tests drive this one value.
struct ChartScroll: Equatable {
    private(set) var end: TimeInterval?
    private(set) var span: TimeInterval?

    init(end: TimeInterval? = nil, span: TimeInterval? = nil) {
        self.end = end
        self.span = span
    }

    /// Scrolled or zoomed: the chart doesn't show the usual window up to now (the *Now* pill shows).
    var away: Bool { end != nil || span != nil }

    /// The window's length now; `def` = the place's *Time shown*.
    func span(_ def: TimeInterval) -> TimeInterval { span ?? def }

    /// Moves the end by `dt` s, kept within `minEnd`…`now` (at `now` the chart follows now again); returns the seconds
    /// actually moved (less at an edge).
    @discardableResult
    mutating func by(_ dt: TimeInterval, minEnd: TimeInterval, now: TimeInterval) -> TimeInterval {
        let cur = end ?? now
        let next = min(max(cur + dt, min(minEnd, now)), now)
        end = next >= now ? nil : next
        return next - cur
    }

    /// Zooms the time axis by `factor` (> 1 = in) keeping the moment at `anchor` (0 = the plot's left edge, 1 = its
    /// right edge) in place, within `ChartView.minSpan`…`maxSpan` and the history (`first` = the oldest reading); a
    /// window that reaches now keeps following now.
    mutating func zoom(
        _ factor: Double, anchor: Double, default def: TimeInterval, first: TimeInterval, now: TimeInterval
    ) {
        guard factor > 0, factor.isFinite else { return }
        let (lo, hi) = Self.spanLimits(def, first: first, now: now)
        let span0 = span ?? def
        let span1 = min(max(span0 / factor, lo), hi)
        let end0 = end ?? now
        let a = min(max(anchor, 0), 1)
        let at = end0 - span0 * (1 - a)
        let end1 = min(max(at + span1 * (1 - a), min(first + span1, now)), now)
        set(end: end1, span: span1, default: def, now: now)
    }

    /// One zoom step for VoiceOver and the keyboard: twice as long (`out`) or half as long, anchored at the end.
    mutating func step(out: Bool, default def: TimeInterval, first: TimeInterval, now: TimeInterval) {
        zoom(out ? 0.5 : 2, anchor: 1, default: def, first: first, now: now)
    }

    /// Half a window earlier or later (VoiceOver's Earlier / Later).
    mutating func page(forward: Bool, default def: TimeInterval, first: TimeInterval, now: TimeInterval) {
        let s = span(def)
        by((forward ? 1 : -1) * s / 2, minEnd: first + s, now: now)
    }

    /// Puts the window at `end` with length `span`.
    mutating func set(end: TimeInterval, span: TimeInterval, default def: TimeInterval, now: TimeInterval) {
        self.span = abs(span - def) < Self.sameSpan ? nil : span
        self.end = end >= now ? nil : end
    }

    /// The view the renderer draws, nil while following now at the place's own length.
    func view(now: TimeInterval, pill: CGFloat, y: ClosedRange<Double>?) -> ChartView? {
        guard away || pill > 0 else { return nil }
        return ChartView(end: end ?? now, y: y, now: pill, span: span)
    }

    /// A length within this of the place's own counts as not zoomed (the pill goes away).
    static let sameSpan: TimeInterval = 30

    /// The shortest and longest window for `def` (the place's own length) with history from `first` at `now` (s).
    static func spanLimits(_ def: TimeInterval, first: TimeInterval, now: TimeInterval) -> (Double, Double) {
        (min(ChartView.minSpan, def), max(def, min(ChartView.maxSpan, now - first)))
    }
}
