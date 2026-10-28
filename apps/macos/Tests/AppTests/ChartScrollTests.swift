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

    /// Two-finger double tap: the place's window ↔ 3 h, anchored at the pointer.
    @Test func smartZoomTogglesThreeHours() {
        var s = ChartScroll()
        s.smartZoom(anchor: 1, default: def, first: first, now: now)
        #expect(s.span == 3 * 3600 && s.end == nil)  // anchored at now: keeps following now
        s.smartZoom(anchor: 1, default: def, first: first, now: now)
        #expect(!s.away)
        var mid = ChartScroll()
        mid.smartZoom(anchor: 0.5, default: def, first: first, now: now)
        // the moment under the pointer (3 h ago) stays put: the 3 h window is centred on it
        #expect(mid.end == now - 1.5 * 3600)
        var short = ChartScroll()
        short.smartZoom(anchor: 1, default: 3 * 3600, first: first, now: now)
        #expect(!short.away)  // a place that already shows 3 h has nothing to switch to
    }

    /// The spring glides without overshoot, keeps its speed when retargeted and settles.
    @Test func glideIsCriticallyDamped() {
        var g = Glide(0)
        g.retarget(100, at: 10, instant: false)
        var last = 0.0
        for k in 1...60 {
            let v = g.value(at: 10 + Double(k) / 100)
            #expect(v >= last && v <= 100)  // monotonic, no bounce
            last = v
        }
        #expect(g.value(at: 10.7) == 100 && g.settled(at: 10.7))
        let speed = g.velocity(at: 10.05)
        g.retarget(200, at: 10.05, instant: false)
        #expect(abs(g.velocity(at: 10.05) - speed) < 1e-9)  // no jolt when the target moves
        g.retarget(5, at: 11, instant: true)
        #expect(g.value(at: 11) == 5 && g.settled(at: 11))
    }

    /// The y range and the Now pill ease; the first target (where the panel opens) is taken as is; reduced motion
    /// jumps.
    @Test func easeGlidesTheYRangeAndThePill() throws {
        var e = ChartEase()
        e.retarget(y: 40...300, away: false, at: 0, instant: false)
        #expect(e.view(ChartScroll(), default: def, now: now, at: 0) == nil)  // opened at now: the renderer's own
        var away = ChartScroll()
        away.by(-3600, minEnd: first + def, now: now)
        e.retarget(y: 60...200, away: true, at: 1, instant: false)
        let mid = try #require(e.view(away, default: def, now: now, at: 1.05))
        #expect(mid.now > 0 && mid.now < 1)
        #expect(mid.y!.lowerBound > 40 && mid.y!.lowerBound < 60)
        let end = try #require(e.view(away, default: def, now: now, at: 2))
        #expect(end.now == 1 && end.y == 60...200 && end.end == now - 3600)
        // back to now: the window glides there, the pill fades, then the renderer's own window again
        e.goBack(from: now - 3600, span: def, at: 3, instant: false)
        e.retarget(y: 40...300, away: false, at: 3, instant: false)
        let going = try #require(e.view(ChartScroll(), default: def, now: now, at: 3.05))
        #expect(going.end > now - 3600 && going.end < now && going.now > 0)
        #expect(e.view(ChartScroll(), default: def, now: now, at: 4) == nil)
        var reduced = ChartEase()
        reduced.retarget(y: 40...300, away: false, at: 0, instant: true)
        reduced.retarget(y: 60...200, away: true, at: 1, instant: true)
        #expect(reduced.view(away, default: def, now: now, at: 1) == ChartView(end: now - 3600, y: 60...200, now: 1))
    }
}
