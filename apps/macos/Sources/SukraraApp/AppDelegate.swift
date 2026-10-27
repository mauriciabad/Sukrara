// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// The menu bar app shell: one status item with a menu. Services are started only through
/// `model.plan.services.start(using:)` (none yet: later chunks add their `ServiceStarting`).
public final class AppDelegate: NSObject, NSApplicationDelegate {
    public let model: AppModel
    private var statusItem: NSStatusItem?

    public init(model: AppModel) {
        self.model = model
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.title = model.statusTitle
        item.button?.setAccessibilityLabel(model.notMonitoringReason ?? "Glucose \(model.statusTitle)")
        item.menu = makeMenu()
        statusItem = item
    }

    private func makeMenu() -> NSMenu {
        let menu = NSMenu()
        if let reason = model.notMonitoringReason {
            let line = NSMenuItem(title: reason, action: nil, keyEquivalent: "")
            line.isEnabled = false
            menu.addItem(line)
            menu.addItem(.separator())
        }
        menu.addItem(
            NSMenuItem(title: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        return menu
    }
}
