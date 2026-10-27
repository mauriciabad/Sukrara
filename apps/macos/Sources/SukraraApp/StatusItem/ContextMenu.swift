// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// The status item's right-click menu: Treatments…, Report…, Settings…, Check for Updates…, Quit. The windows are
/// chunk 23's; until they register, their items are disabled rather than doing nothing.
final class ContextMenu: NSObject {
    /// What the window layer opens (chunk 23 sets these).
    var openTreatments: (() -> Void)?
    var openReport: (() -> Void)?
    var openSettings: (() -> Void)?
    var checkForUpdates: (() -> Void)?

    var menu: NSMenu {
        let menu = NSMenu()
        menu.addItem(item("Treatments…", #selector(treatments), enabled: openTreatments != nil))
        menu.addItem(item("Report…", #selector(report), enabled: openReport != nil))
        menu.addItem(.separator())
        menu.addItem(item("Settings…", #selector(settings), key: ",", enabled: openSettings != nil))
        menu.addItem(item("Check for Updates…", #selector(updates), enabled: checkForUpdates != nil))
        menu.addItem(.separator())
        menu.addItem(
            NSMenuItem(title: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        return menu
    }

    private func item(_ title: String, _ action: Selector, key: String = "", enabled: Bool) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: enabled ? action : nil, keyEquivalent: key)
        item.target = self
        item.isEnabled = enabled
        return item
    }

    @objc private func treatments() { openTreatments?() }
    @objc private func report() { openReport?() }
    @objc private func settings() { openSettings?() }
    @objc private func updates() { checkForUpdates?() }
}
