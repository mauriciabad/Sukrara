// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// `Sukrara -panelselftest`: the real dropdown, opened the way a click on the menubar item opens it (a real status
/// item, `GlancePanel.toggle(below:)`), must bring Sukrara to the front and take the keyboard, because macOS sends
/// trackpad gestures only to the active app (2026-10-22: "I pinch on the trackpad and nothing happens": the
/// non-activating dropdown was key and showing and never got a `.magnify`). A pinch then goes in where the system
/// delivers it (posted to this process, dispatched by the app's own event loop and `NSApp.sendEvent`) and must zoom the card's chart. Closing it with the item gives the front back to the
/// app that had it; a row that opens a Sukrara window keeps Sukrara in front. Offline: no store, network or alerts,
/// only a status item for the few seconds of the test. Needs a logged-in GUI session (CI's macOS runners have one).
@MainActor enum PanelSelfTest {
    static func run() -> Bool {
        var failures = 0
        func check(_ what: String, _ ok: Bool) { if !ok { print("FAIL panel \(what)"); failures += 1 } }
        /// Runs the app's event loop (activation and key changes arrive as events) until `done` or `timeout` seconds.
        func wait(_ timeout: Double, until done: () -> Bool) {
            let end = Date().addingTimeInterval(timeout)
            while !done() && Date() < end {
                if let e = NSApp.nextEvent(matching: .any, until: Date().addingTimeInterval(0.05), inMode: .default, dequeue: true) {
                    NSApp.sendEvent(e)
                }
            }
        }

        /// The app the system has in front gets the gestures; `NSApp.isActive` isn't it (it read true for the
        /// non-activating panel while the pinches went elsewhere, the 2026-10-22 diagnostics).
        func inFront() -> Bool { GlancePanel.inFront }
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        app.finishLaunching()
        let before = NSWorkspace.shared.frontmostApplication
        let other = before?.processIdentifier == ProcessInfo.processInfo.processIdentifier ? nil : before
        print("panel self-test: in front before: \(before?.localizedName ?? "nothing")")

        let now = Date().timeIntervalSince1970
        let m = PanelModel()
        m.readings = (0..<(3 * 288)).map { (i: Int) -> GraphReading in
            let t: Double = now - Double(3 * 288 - i) * 300
            let v: Double = 130 + 60 * sin(Double(i) / 40)
            return GraphReading(t: t, v: v)
        }
        m.style = StatusController.dropdownStyle(GraphConfig(), sensor: nil)
        m.alerts = AlertSettings()
        m.metricsOf = { _, b in GlucoseMetrics.of([], from: 0, to: 1, bands: b) }
        m.now = Date(timeIntervalSince1970: now)
        m.hasData = true
        let glance = GlancePanel(model: m)
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        defer { NSStatusBar.system.removeStatusItem(item) }
        guard let button = item.button else { print("FAIL panel: no status item button"); return false }
        button.title = "test"

        // 0. as when the user clicks the item: another app in front (a launch from a terminal may have activated this one)
        wait(0.5) { false }
        if let other, inFront() { app.yieldActivation(to: other); other.activate() }
        wait(3) { !inFront() }
        check("before opening, Sukrara isn't the app in front", other == nil || !inFront())

        // 1. open as the menubar item does: Sukrara in front, the dropdown key, the previous app remembered
        glance.toggle(below: button)
        wait(3) { inFront() && glance.window?.isKeyWindow == true }
        check("opening brings Sukrara to the front (gestures go to the app in front only)", inFront() && app.isActive)
        check("the dropdown is the key window", glance.window?.isKeyWindow == true)
        check("the app in front before (\(other?.localizedName ?? "none")) is remembered",
              glance.returnTo?.processIdentifier == other?.processIdentifier)

        // 2. a pinch through the real dispatch zooms the chart
        guard let w = glance.window else { print("FAIL panel: no window"); return false }
        func find(_ v: NSView) -> CardView? { (v as? CardView) ?? v.subviews.lazy.compactMap(find).first }
        if let card = w.contentView.flatMap(find) {
            // the card lays its chart out when it draws (the plot box a pinch anchors in); draw it once off screen
            if let ctx = CGContext(data: nil, width: Int(card.bounds.width), height: Int(card.bounds.height), bitsPerComponent: 8,
                                   bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                   bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                card.paint(ctx, size: card.bounds.size, GraphPalette.of(dark: false))
            }
            let span0 = card.scrollSpan
            for i in 0..<5 {
                guard let cg = CGEvent(source: nil), let type = CGEventType(rawValue: 29) else { break }
                cg.type = type                                                    // a gesture event…
                cg.setIntegerValueField(CGEventField(rawValue: 110)!, value: 8)   // …of HID type zoom
                cg.setDoubleValueField(CGEventField(rawValue: 113)!, value: 0.1)  // the magnification
                cg.setIntegerValueField(CGEventField(rawValue: 51)!, value: Int64(w.windowNumber))   // for this window
                let r = w.convertToScreen(card.convert(card.bounds, to: nil))
                cg.location = CGPoint(x: r.midX, y: (NSScreen.screens.first?.frame.maxY ?? 0) - r.midY)   // over the chart
                cg.setIntegerValueField(CGEventField(rawValue: 132)!, value: i == 0 ? 1 : i == 4 ? 4 : 2)   // began, changed…, ended
                guard let e = NSEvent(cgEvent: cg), e.type == .magnify, e.window === w else {
                    check("a magnify event for the dropdown can be built", false); break
                }
                // through the window server to this process only, then the app's own loop (nextEvent → NSApp.sendEvent
                // → the window), the way a real pinch arrives; NSApp.sendEvent drops a bare synthetic gesture
                cg.postToPid(ProcessInfo.processInfo.processIdentifier)
                wait(0.2) { false }
            }
            check("a pinch delivered through the app's event loop zooms the dropdown's chart (none arrived? posting events to this process may need Accessibility)", card.scrollSpan != span0)
        } else { check("the dropdown has a card", false) }

        // 3. a second click on the item closes it and gives the front back
        glance.toggle(below: button)
        check("a second click closes the dropdown", !glance.isVisible)
        check("closing forgets the previous app", glance.returnTo == nil)
        if let other {
            wait(3) { NSWorkspace.shared.frontmostApplication?.processIdentifier == other.processIdentifier }
            check("closing gives the front back to \(other.localizedName ?? "the previous app")",
                  NSWorkspace.shared.frontmostApplication?.processIdentifier == other.processIdentifier)
        }

        // 4. a row that opens a Sukrara window closes it with Sukrara left in front
        glance.toggle(below: button)
        wait(3) { inFront() }
        glance.close(.handOff)
        check("a row's window: closed", !glance.isVisible && glance.returnTo == nil)
        wait(0.5) { false }
        check("a row's window: Sukrara stays in front", inFront())

        // leave the user's app in front as it was
        if let other { app.yieldActivation(to: other); other.activate() }
        wait(1) { !inFront() }
        print("panel self-test: \(failures == 0 ? "OK" : "\(failures) failure(s)")")
        return failures == 0
    }
}
