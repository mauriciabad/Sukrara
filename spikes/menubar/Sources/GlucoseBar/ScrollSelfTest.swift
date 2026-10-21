// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// The dropdown's chart scrolls like a scroll view (part of `-graphselftest`): synthetic scroll events, built the way
/// AppKit receives them (CGEvent scroll-wheel fields → NSEvent; nothing is posted to the system), go through the real
/// `CardView.scrollWheel` of a card fed by the real `StatusController.cardReadings` from a 15-day store. The chart's
/// content follows the fingers 1:1 in points (a swipe of N pt moves the window N pt's worth of time), the trackpad's
/// momentum continues it, a vertical trackpad swipe leaves it alone and a mouse wheel moves it 10 pt per line, like a
/// horizontal-only NSScrollView. (2026-10-21: the dropdown "only scrolls 1px or so": the card got only the shown
/// window's readings, so there was nothing to scroll to.) Returns the number of failures.
@MainActor enum ScrollSelfTest {
    /// A scroll event as AppKit gets it: `continuous` = a trackpad (point deltas, phases), else a wheel (lines).
    static func event(dx: Double, dy: Double, continuous: Bool, phase: Int64 = 0, momentum: Int64 = 0) -> NSEvent? {
        guard let cg = CGEvent(scrollWheelEvent2Source: nil, units: continuous ? .pixel : .line, wheelCount: 2,
                               wheel1: Int32(dy.rounded()), wheel2: Int32(dx.rounded()), wheel3: 0) else { return nil }
        cg.setIntegerValueField(.scrollWheelEventIsContinuous, value: continuous ? 1 : 0)
        if continuous {
            cg.setDoubleValueField(.scrollWheelEventPointDeltaAxis1, value: dy)
            cg.setDoubleValueField(.scrollWheelEventPointDeltaAxis2, value: dx)
            cg.setDoubleValueField(.scrollWheelEventFixedPtDeltaAxis1, value: dy)
            cg.setDoubleValueField(.scrollWheelEventFixedPtDeltaAxis2, value: dx)
        } else {
            cg.setIntegerValueField(.scrollWheelEventDeltaAxis1, value: Int64(dy))
            cg.setIntegerValueField(.scrollWheelEventDeltaAxis2, value: Int64(dx))
            cg.setDoubleValueField(.scrollWheelEventFixedPtDeltaAxis1, value: dy)
            cg.setDoubleValueField(.scrollWheelEventFixedPtDeltaAxis2, value: dx)
        }
        cg.setIntegerValueField(.scrollWheelEventScrollPhase, value: phase)
        cg.setIntegerValueField(.scrollWheelEventMomentumPhase, value: momentum)
        return NSEvent(cgEvent: cg)
    }

    static func run() -> Int {
        var failures = 0
        func fail(_ s: String) { print("FAIL scroll \(s)"); failures += 1 }
        let now = 1_800_000_000.0
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-scrolltest-\(getpid())")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = ReadingsStore(directory: dir)
        store.merge((0..<(15 * 288)).map { i in
            let x = Double(i)
            let mg: Double = 130 + 60 * sin(x / 40)
            return Reading(date: Date(timeIntervalSince1970: now - Double(15 * 288 - i) * 300), mgdl: mg, rate: 0)
        })
        let cfg = GraphConfig()
        let size = CGSize(width: 340, height: 196)
        let v = CardView(frame: NSRect(origin: .zero, size: size))
        v.readings = StatusController.cardReadings(store, graph: cfg)
        v.style = StatusController.dropdownStyle(cfg, sensor: nil)
        v.alerts = AlertSettings()
        v.now = Date(timeIntervalSince1970: now)
        v.metricsOf = { _, b in GlucoseMetrics.of([], from: 0, to: 1, bands: b) }
        guard let ctx = CGContext(data: nil, width: Int(size.width * 2), height: Int(size.height * 2), bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return 1 }
        ctx.translateBy(x: 0, y: size.height * 2); ctx.scaleBy(x: 2, y: -2)
        guard let plot = v.paint(ctx, size: size, GraphPalette.of(dark: false)).first("chart.plot")?.box else { fail("no plot"); return failures }
        let span = cfg.graphStyle().window
        let perPt = span / Double(plot.width)   // seconds per point of the plot
        func moved() -> Double { now - (v.scrollEnd ?? now) }
        func send(_ e: NSEvent?) { if let e { v.scrollWheel(with: e) } else { fail("could not build an NSEvent") } }
        func expect(_ what: String, _ pt: Double) {
            let want = pt * perPt
            if abs(moved() - want) > max(1, abs(want) * 0.01) {
                fail("\(what): the window moved \(Int(moved())) s (\(String(format: "%.1f", moved() / perPt)) pt), want \(Int(want)) s (\(pt) pt, content 1:1 with the fingers)")
            }
        }

        // a trackpad swipe to the right (into the past): began, 10 × 10 pt, ended; then the system's momentum, 5 × 8 pt.
        // The began event often carries a little vertical motion: the gesture is still horizontal.
        send(event(dx: 0, dy: 0.5, continuous: true, phase: 1))
        for _ in 0..<10 { send(event(dx: 10, dy: 0.3, continuous: true, phase: 2)) }
        send(event(dx: 0, dy: 0, continuous: true, phase: 4))
        expect("trackpad swipe 100 pt", 100)
        if v.scrollEnd == nil { fail("trackpad swipe: still at now") }
        send(event(dx: 8, dy: 0, continuous: true, momentum: 1))
        for _ in 0..<4 { send(event(dx: 8, dy: 0, continuous: true, momentum: 2)) }
        send(event(dx: 0, dy: 0, continuous: true, momentum: 3))
        expect("trackpad swipe + momentum", 140)
        // back towards now: the same distance the other way
        send(event(dx: 0, dy: 0, continuous: true, phase: 1))
        for _ in 0..<5 { send(event(dx: -8, dy: 0, continuous: true, phase: 2)) }
        send(event(dx: 0, dy: 0, continuous: true, phase: 4))
        expect("trackpad swipe back 40 pt", 100)
        // a vertical trackpad swipe isn't the chart's
        send(event(dx: 0, dy: 0, continuous: true, phase: 1))
        for _ in 0..<10 { send(event(dx: 0.5, dy: 12, continuous: true, phase: 2)) }
        send(event(dx: 0, dy: 0, continuous: true, phase: 4))
        expect("vertical trackpad swipe (ignored)", 100)
        // a mouse wheel: 3 lines up = 30 pt into the past, like a horizontal-only scroll view
        for _ in 0..<3 { send(event(dx: 0, dy: 1, continuous: false)) }
        expect("mouse wheel 3 lines", 130)
        // far into the past: stops at the first reading (its window starts there), not before
        send(event(dx: 0, dy: 0, continuous: true, phase: 1))
        for _ in 0..<2000 { send(event(dx: 50, dy: 0, continuous: true, phase: 2)) }
        send(event(dx: 0, dy: 0, continuous: true, phase: 4))
        let first = store.readings.first?.t ?? now
        if abs((v.scrollEnd ?? now) - (first + span)) > 1 {
            fail("clamp: the window ends \(Int(now - (v.scrollEnd ?? now))) s back, want \(Int(now - first - span)) s (the first reading at the left edge)")
        }
        print(failures == 0 ? "scroll self-test OK (\(String(format: "%.1f", perPt)) s per pt)" : "scroll self-test: \(failures) failure(s)")
        return failures
    }
}
