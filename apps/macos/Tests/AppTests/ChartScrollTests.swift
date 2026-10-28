// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CoreGraphics
import Foundation
import SukraraGraph
import Testing

@testable import SukraraApp

/// The panel chart's scroll and zoom: the same rules as Android's home chart (ChartScrollTest.kt).
@Suite struct ChartScrollTests {
    let now: TimeInterval = 1_800_000_000
    let day: TimeInterval = 86_400
    let def: TimeInterval = 6 * 3600
    var first: TimeInterval { now - 31 * day }

    @Test func scrollsBackAndStopsAtTheEdges() {
        var s = ChartScroll()
        #expect(!s.away)
        #expect(s.by(-3600, minEnd: first + def, now: now) == -3600)
        #expect(s.end == now - 3600 && s.away)
        // forward past now: only the hour back is moved, then it follows now again
        #expect(s.by(7200, minEnd: first + def, now: now) == 3600)
        #expect(s.end == nil)
        s.by(-40 * day, minEnd: first + def, now: now)
        #expect(s.end == first + def)
    }

    @Test func zoomsWithinTwoHoursAndTheHistory() {
        var s = ChartScroll()
        s.zoom(100, anchor: 1, default: def, first: first, now: now)
        #expect(s.span == ChartView.minSpan)
        s.zoom(0.0001, anchor: 1, default: def, first: first, now: now)
        #expect(s.span == 31 * day)
        var short = ChartScroll()
        short.zoom(0.0001, anchor: 1, default: def, first: now - 3 * day, now: now)
        #expect(short.span == 3 * day)
        var tiny = ChartScroll()
        tiny.zoom(0.0001, anchor: 1, default: def, first: now - 3600, now: now)
        #expect(tiny.span == nil)
    }

    @Test func zoomKeepsTheMomentUnderThePointer() {
        var s = ChartScroll(end: now - day)
        let anchor = 0.25
        let at = (now - day) - def * (1 - anchor)
        s.zoom(2, anchor: anchor, default: def, first: first, now: now)
        let span = s.span(def)
        #expect(span == def / 2)
        #expect(abs((s.end ?? now) - span * (1 - anchor) - at) < 1)
    }

    @Test func voiceOverStepsDoubleHalveAndPage() {
        var s = ChartScroll()
        s.step(out: true, default: def, first: first, now: now)
        #expect(s.span == 2 * def)
        s.step(out: false, default: def, first: first, now: now)
        #expect(s.span == nil && !s.away)
        s.page(forward: false, default: def, first: first, now: now)
        #expect(s.end == now - def / 2)
        s.page(forward: true, default: def, first: first, now: now)
        #expect(!s.away)
    }

    @Test func theViewOnlyWhileAway() {
        #expect(ChartScroll().view(now: now, pill: 0, y: nil) == nil)
        let v = ChartScroll(end: now - day, span: 7 * day).view(now: now, pill: 1, y: 60...200)
        #expect(v == ChartView(end: now - day, y: 60...200, now: 1, span: 7 * day))
    }

    /// The renderer puts the "Now" pill only on a scrolled chart, inside the plot (the panel's button goes there).
    @Test func nowPillIsInsideThePlotOnlyWhenScrolled() {
        let readings = stride(from: now - 2 * day, through: now - 60, by: 300).map {
            GraphReading(t: $0, v: 120 + 30 * sin($0 / 3600), rate: 0)
        }
        let data = CardData(readings: readings, now: now)
        let size = CGSize(width: 340, height: 196)
        let still = GlucoseCard.chartRects(
            surface: .menubarCard, size: size, config: GraphConfig(), data: data, unit: .mgdl)
        #expect(still.plot != nil && still.now == nil)
        let scrolled = GlucoseCard.chartRects(
            surface: .menubarCard, size: size, config: GraphConfig(), data: data, unit: .mgdl,
            view: ChartView(end: now - day, now: 1))
        let plot = try? #require(scrolled.plot), pill = try? #require(scrolled.now)
        #expect(plot.map { p in pill.map { p.contains($0) } ?? false } == true)
        #expect(
            GlucoseCard.chartSpan(surface: .menubarCard, config: GraphConfig())
                == TimeInterval(GraphConfig().hours * 3600))
    }
}
