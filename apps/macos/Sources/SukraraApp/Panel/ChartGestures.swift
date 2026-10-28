// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// The trackpad and mouse over the panel's chart: two-finger / wheel scroll moves through time, a pinch or ⌘-scroll
/// zooms, a two-finger double tap switches between the place's window and 3 h. A local event monitor reads the events over this view's area only while it is in a window, so clicks still
/// reach the card's buttons (the view itself is never hit).
struct ChartGestures: NSViewRepresentable {
    /// Points to move the content by (positive = the content moves right = earlier), x of the pointer (0…1 of the area).
    var scroll: (CGFloat, CGFloat) -> Void
    /// Zoom factor (> 1 = in), x of the pointer (0…1 of the area).
    var zoom: (CGFloat, CGFloat) -> Void
    /// A two-finger double tap (smart zoom), x of the pointer (0…1 of the area).
    var smartZoom: (CGFloat) -> Void = { _ in }

    func makeNSView(context: Context) -> Catcher {
        let v = Catcher()
        v.handlers = (scroll, zoom, smartZoom)
        return v
    }

    func updateNSView(_ v: Catcher, context: Context) { v.handlers = (scroll, zoom, smartZoom) }

    static func dismantleNSView(_ v: Catcher, coordinator: ()) { v.stop() }

    final class Catcher: NSView {
        var handlers: ((CGFloat, CGFloat) -> Void, (CGFloat, CGFloat) -> Void, (CGFloat) -> Void)?
        private var monitor: Any?

        override func hitTest(_ point: NSPoint) -> NSView? { nil }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            stop()
            guard window != nil else { return }
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.scrollWheel, .magnify, .smartMagnify]) {
                [weak self] e in
                guard let self, let handlers = self.handlers, e.window === self.window, self.window != nil else {
                    return e
                }
                let p = self.convert(e.locationInWindow, from: nil)
                guard self.bounds.contains(p) else { return e }
                let x = self.bounds.width > 0 ? p.x / self.bounds.width : 1
                switch e.type {
                case .magnify:
                    handlers.1(1 + e.magnification, x)
                case .smartMagnify:
                    handlers.2(x)
                case .scrollWheel:
                    if e.modifierFlags.contains(.command) {
                        let dy = e.hasPreciseScrollingDeltas ? e.scrollingDeltaY : e.scrollingDeltaY * 10
                        handlers.1(exp(dy * 0.01), x)
                    } else {
                        // a mostly vertical trackpad swipe isn't a time scroll; a mouse wheel is (it has no horizontal)
                        let dx =
                            abs(e.scrollingDeltaX) > abs(e.scrollingDeltaY)
                            ? e.scrollingDeltaX : e.hasPreciseScrollingDeltas ? 0 : e.scrollingDeltaY * 10
                        if dx != 0 { handlers.0(dx, x) }
                    }
                default: return e
                }
                return nil
            }
        }

        func stop() {
            if let monitor { NSEvent.removeMonitor(monitor) }
            monitor = nil
        }
    }
}
