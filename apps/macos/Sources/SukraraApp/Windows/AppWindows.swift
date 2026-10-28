// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

/// Opens the app's windows (AppKit windows hosting SwiftUI: a SwiftUI `Settings` scene can't be opened reliably
/// from an accessory menu bar app). One window per kind, frames remembered under `app.mauri.sukrara.<kind>`; the
/// app shows in the Dock and the app switcher while one is open, so keyboard users can reach it.
public final class AppWindows: NSObject, NSWindowDelegate {
    public enum Kind: String, Sendable { case settings, setup, treatments, addTreatment, report }

    private var windows: [Kind: NSWindow] = [:]

    /// `nearMenuBar`: a new window opens under the menu bar at the top right (Add Treatment), not where it was.
    public func show(
        _ kind: Kind, title: String, nearMenuBar: Bool = false, @ViewBuilder content: () -> some View
    ) {
        if let w = windows[kind] {
            w.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }
        let w = NSWindow(contentViewController: NSHostingController(rootView: content()))
        w.title = title
        w.styleMask.insert(.closable)
        w.isReleasedWhenClosed = false
        w.delegate = self
        if nearMenuBar, let screen = NSScreen.main ?? NSScreen.screens.first {
            let area = screen.visibleFrame
            w.setFrameTopLeftPoint(NSPoint(x: area.maxX - w.frame.width - 12, y: area.maxY - 6))
        } else {
            w.setFrameAutosaveName("app.mauri.sukrara.\(kind.rawValue)")
        }
        windows[kind] = w
        NSApp.setActivationPolicy(.regular)
        w.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }

    public func close(_ kind: Kind) { windows[kind]?.close() }

    public func windowWillClose(_ notification: Notification) {
        guard let w = notification.object as? NSWindow, let kind = windows.first(where: { $0.value === w })?.key else {
            return
        }
        windows[kind] = nil
        if windows.isEmpty { NSApp.setActivationPolicy(.accessory) }
    }
}
