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

    /// A trackpad pinch step as AppKit gets it (a gesture event of HID type zoom → NSEvent `.magnify`), at `x` (pt).
    static func magnify(_ m: Double, x: Double) -> NSEvent? {
        guard let cg = CGEvent(source: nil), let type = CGEventType(rawValue: 29) else { return nil }
        cg.type = type
        cg.location = CGPoint(x: x, y: 50)
        cg.setIntegerValueField(CGEventField(rawValue: 110)!, value: 8)      // kIOHIDEventTypeZoom
        cg.setDoubleValueField(CGEventField(rawValue: 113)!, value: m)       // the magnification
        return NSEvent(cgEvent: cg).flatMap { $0.type == .magnify ? $0 : nil }
    }

    /// The real dropdown: the panel's own window class (`KeyPanel`, borderless, non-activating, never shown) with the
    /// panel's content (material, SwiftUI hosting view, the card in it). The pinch goes in where the system delivers it,
    /// `NSWindow.sendEvent` (2026-10-22: "I pinch on the trackpad and nothing happens": the card's own `magnify(with:)`
    /// worked, the event never reached it through the hosting view).
    static func throughThePanel(store: ReadingsStore, now: Double) -> Int {
        var failures = 0
        func fail(_ s: String) { print("FAIL scroll (panel) \(s)"); failures += 1 }
        _ = NSApplication.shared   // windows get their numbers (event routing) only with an application
        let m = PanelModel()
        m.readings = store.readings
        m.style = StatusController.dropdownStyle(GraphConfig(), sensor: nil)
        m.alerts = AlertSettings()
        m.metricsOf = { _, b in GlucoseMetrics.of([], from: 0, to: 1, bands: b) }
        m.now = Date(timeIntervalSince1970: now)
        m.hasData = true
        let content = GlancePanel.content(m, blending: .withinWindow)
        content.layoutSubtreeIfNeeded()
        let size = content.fittingSize
        let w = KeyPanel(contentRect: NSRect(origin: .zero, size: size), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        w.isReleasedWhenClosed = false
        w.contentView = content
        content.frame = NSRect(origin: .zero, size: size)
        content.layoutSubtreeIfNeeded()
        defer { w.contentView = nil; w.close() }
        func find(_ v: NSView) -> CardView? { (v as? CardView) ?? v.subviews.lazy.compactMap(find).first }
        guard let card = find(content) else { fail("no CardView in the panel's content"); return failures }
        // lay the chart out as a drawing does (the plot's box, which a pinch anchors in)
        if let ctx = CGContext(data: nil, width: Int(card.bounds.width), height: Int(card.bounds.height), bitsPerComponent: 8, bytesPerRow: 0,
                               space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
            card.paint(ctx, size: card.bounds.size, GraphPalette.of(dark: false))
        }
        let span0 = card.scrollSpan
        for _ in 0..<5 {
            guard let cg = CGEvent(source: nil), let type = CGEventType(rawValue: 29) else { break }
            cg.type = type
            cg.setIntegerValueField(CGEventField(rawValue: 110)!, value: 8)      // a zoom gesture
            cg.setDoubleValueField(CGEventField(rawValue: 113)!, value: 0.1)
            cg.setIntegerValueField(CGEventField(rawValue: 51)!, value: Int64(w.windowNumber))   // for this window
            guard let e = NSEvent(cgEvent: cg), e.window === w else { fail("could not build a magnify event for the panel"); break }
            w.sendEvent(e)
        }
        if card.scrollSpan == span0 { fail("a trackpad pinch on the dropdown doesn't reach the chart (still \(card.scrollSpan.map { Int($0) } ?? 0) s)") }
        print("scroll self-test (through the panel): \(failures == 0 ? "OK" : "\(failures) failure(s)")")
        return failures
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
        var send2: ((NSEvent) -> Void)? = nil   // the zoom checks swipe on their own card
        func send(_ e: NSEvent?) { if let e { if let send2 { send2(e) } else { v.scrollWheel(with: e) } } else { fail("could not build an NSEvent") } }
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
        // ---- pinch zoom on the time axis (the user, 2026-10-21) -------------------------------------------------------
        let z = CardView(frame: NSRect(origin: .zero, size: size))
        z.readings = v.readings; z.style = v.style; z.alerts = v.alerts; z.now = v.now; z.metricsOf = v.metricsOf
        func paintZ() -> CardLayout { z.paint(ctx, size: size, GraphPalette.of(dark: false)) }
        guard let zp = paintZ().first("chart.plot")?.box else { fail("zoom: no plot"); return failures }
        let midX = Double(zp.midX)   // the card is drawn at the view's origin (no inset)
        func spanZ() -> Double { z.scrollSpan ?? span }
        func endZ() -> Double { z.scrollEnd ?? now }
        // pinch out (magnify > 0) at the plot's middle: 5 × 10 % → 1.61× in; the moment under the cursor stays put
        let under = now - span / 2
        for _ in 0..<5 { if let e = magnify(0.1, x: midX) { z.magnify(with: e) } else { fail("zoom: could not build a magnify event") } }
        _ = paintZ()
        let wantSpan = span / pow(1.1, 5)
        if abs(spanZ() - wantSpan) > wantSpan * 0.01 { fail("zoom in: the window is \(Int(spanZ())) s, want \(Int(wantSpan)) s (5 pinch steps of 10 %)") }
        if abs((endZ() - spanZ() / 2) - under) > 120 { fail("zoom in: the moment under the cursor moved \(Int((endZ() - spanZ() / 2) - under)) s") }
        if paintZ().first("chart.now") == nil { fail("zoom in: no Now pill on a zoomed chart") }
        // the pinch's end (magnification 0) and a two-finger swipe after it keep the length; the swipe pans 1:1
        let lenAfter = spanZ(), endAfter = endZ()
        if let e = magnify(0, x: midX) { z.magnify(with: e) }
        if spanZ() != lenAfter || endZ() != endAfter { fail("zoom end: the pinch's end moved the window (\(Int(spanZ() - lenAfter)) s longer, \(Int(endZ() - endAfter)) s)") }
        send2 = { z.scrollWheel(with: $0) }
        send(event(dx: 0, dy: 0, continuous: true, phase: 1))
        for _ in 0..<5 { send(event(dx: 6, dy: 0, continuous: true, phase: 2)) }
        send(event(dx: 0, dy: 0, continuous: true, phase: 4))
        send2 = nil
        let zPerPt = spanZ() / Double(zp.width)
        if spanZ() != lenAfter { fail("zoom then swipe: the length changed by \(Int(spanZ() - lenAfter)) s") }
        if abs((endAfter - endZ()) - 30 * zPerPt) > zPerPt { fail("zoom then swipe: moved \(String(format: "%.1f", (endAfter - endZ()) / zPerPt)) pt, want 30") }
        // all the way in and out: 2 h, then the whole history (the store holds 15 days; the limit is 31)
        for _ in 0..<40 { if let e = magnify(0.5, x: midX) { z.magnify(with: e); _ = paintZ() } }
        if abs(spanZ() - ChartView.minSpan) > 1 { fail("zoom in limit: \(Int(spanZ())) s, want \(Int(ChartView.minSpan)) s") }
        for _ in 0..<40 { if let e = magnify(-0.5, x: midX) { z.magnify(with: e); _ = paintZ() } }
        let history = min(ChartView.maxSpan, now - (store.readings.first?.t ?? now))
        if abs(spanZ() - history) > 1 { fail("zoom out limit: \(Int(spanZ())) s, want the history, \(Int(history)) s") }
        let out = paintZ()
        if let lbl = out.first("chart")?.info, lbl != "\(Int(history / 3600))h" { fail("zoom out: the chart draws \(lbl), want \(Int(history / 3600))h") }
        if out.first("chart.line")?.info != "columns" { fail("zoom out: 15 days of readings drawn as dots, want columns") }
        let dayLabels = out.all("time.text").map(\.info)
        // (the value's own late time, "9:55 AM", is a time.text too: only hour labels count against)
        let hourLike = dayLabels.filter { $0.range(of: "^\\d{1,2}(:00| AM| PM)$", options: .regularExpression) != nil }
        if dayLabels.filter({ $0.range(of: "^\\d{1,2} \\p{L}", options: .regularExpression) != nil }).count < 2 || !hourLike.isEmpty {
            fail("zoom out: the axis labels are \(dayLabels), want dates (a step of days)")
        }
        // ⌘-scroll zooms too: 10 pt up = e^0.1 in
        let before = spanZ()
        if let e = event(dx: 0, dy: 10, continuous: true, phase: 2).flatMap({ ev -> NSEvent? in
            guard let cg = ev.cgEvent else { return nil }; cg.flags = .maskCommand; return NSEvent(cgEvent: cg) }) { z.scrollWheel(with: e) }
        if abs(spanZ() - before / exp(0.1)) > before * 0.01 { fail("⌘-scroll zoom: \(Int(spanZ())) s, want \(Int(before / exp(0.1))) s") }
        // a click on the Now pill returns to now and the place's window
        if let pill = paintZ().first("chart.now")?.box {
            let o = z.convert(NSPoint(x: 0, y: 0), from: nil), one = z.convert(NSPoint(x: 0, y: 1), from: nil)
            let wy = (Double(pill.midY) - Double(o.y)) / Double(one.y - o.y)
            if let cg = CGEvent(mouseEventSource: nil, mouseType: .leftMouseDown, mouseCursorPosition: .zero, mouseButton: .left) {
                cg.location = CGPoint(x: Double(pill.midX), y: 0)
                if let e0 = NSEvent(cgEvent: cg), let e = NSEvent.mouseEvent(with: .leftMouseDown, location: NSPoint(x: Double(pill.midX), y: wy),
                        modifierFlags: [], timestamp: e0.timestamp, windowNumber: 0, context: nil, eventNumber: 0, clickCount: 1, pressure: 1) {
                    z.mouseDown(with: e)
                }
            }
            if z.away { fail("Now pill: a click left the chart scrolled \(Int(now - endZ())) s / zoomed to \(Int(spanZ())) s") }
        } else { fail("Now pill: none to click") }
        failures += throughThePanel(store: store, now: now)
        print(failures == 0 ? "scroll self-test OK (\(String(format: "%.1f", perPt)) s per pt)" : "scroll self-test: \(failures) failure(s)")
        return failures
    }
}
