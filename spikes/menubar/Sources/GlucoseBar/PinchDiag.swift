// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// TEMPORARY diagnostics (branch f32-macpinch, 2026-10-22: a trackpad pinch on the dropdown does nothing in the real
/// app): every gesture and scroll event is written to ~/Library/Logs/Sukrara.log where it passes — NSApp.sendEvent
/// ("app"), a local monitor ("monitor"), the panel's sendEvent ("panel") and the card ("card") — with its type, phases,
/// magnification, window (class, key, visible), whether Sukrara is active, and which view the window's hit test finds
/// under it. Remove once the cause is known.
enum PinchDiag {
    static let types: Set<NSEvent.EventType> = [.magnify, .smartMagnify, .gesture, .beginGesture, .endGesture, .scrollWheel, .rotate, .swipe, .pressure]
    private static var scrolls = 0
    /// Only the running app logs (install()); the self-tests and previews don't write into the user's log.
    private static var enabled = false

    static func log(_ at: String, _ e: NSEvent) {
        guard enabled, types.contains(e.type) else { return }
        // scroll events come in bursts: the first 40 and then every 20th
        if e.type == .scrollWheel { scrolls += 1; if scrolls > 40 && scrolls % 20 != 0 { return } }
        var s = "pinchdiag \(at): type \(name(e.type)) phase \(e.phase.rawValue) momentum \(e.momentumPhase.rawValue)"
        if e.type == .magnify { s += " magnification \(String(format: "%.4f", e.magnification))" }
        if e.type == .scrollWheel { s += " dx \(e.scrollingDeltaX) dy \(e.scrollingDeltaY) precise \(e.hasPreciseScrollingDeltas)" }
        if let w = e.window {
            s += " window \(type(of: w)) #\(w.windowNumber) key \(w.isKeyWindow) visible \(w.isVisible)"
            if let c = w.contentView {
                let hit = c.hitTest(c.superview?.convert(e.locationInWindow, from: nil) ?? e.locationInWindow)
                s += " hit \(hit.map { String(describing: type(of: $0)) } ?? "nil")"
            }
        } else { s += " window nil #\(e.windowNumber)" }
        let nsApp: NSApplication? = NSApp
        s += " appActive \(nsApp?.isActive ?? false) keyWindow \(nsApp?.keyWindow.map { String(describing: type(of: $0)) } ?? "nil")"
        GlucoseBar.log(s)
    }

    static func name(_ t: NSEvent.EventType) -> String {
        switch t {
        case .magnify: "magnify"; case .smartMagnify: "smartMagnify"; case .gesture: "gesture"
        case .beginGesture: "beginGesture"; case .endGesture: "endGesture"; case .scrollWheel: "scrollWheel"
        case .rotate: "rotate"; case .swipe: "swipe"; case .pressure: "pressure"
        default: "\(t.rawValue)"
        }
    }

    private static var monitor: Any?
    static func install() {
        guard monitor == nil else { return }
        enabled = true
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.magnify, .smartMagnify, .gesture, .beginGesture, .endGesture, .scrollWheel, .rotate]) { e in
            log("monitor", e)
            return e
        }
        GlucoseBar.log("pinchdiag: installed (local monitor, NSApp.sendEvent, panel, card); NSApp is \(String(describing: (NSApp as NSApplication?).map { type(of: $0) }))")
    }
}

/// TEMPORARY (PinchDiag): the application class, to see every event NSApp dispatches.
final class DiagApplication: NSApplication {
    override func sendEvent(_ e: NSEvent) {
        PinchDiag.log("app", e)
        super.sendEvent(e)
    }
}
