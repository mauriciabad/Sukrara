// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// The status item's right-click menu: Treatments…, Glucose Report…, Settings…, Check for Updates…, Quit. An item whose window or
/// service this run doesn't have (an offline dev run has no update check) is disabled rather than doing nothing.
final class ContextMenu: NSObject {
    /// What the window layer opens (set by the window layer).
    var openTreatments: (() -> Void)?
    var openReport: (() -> Void)?
    var openSettings: (() -> Void)?
    var checkForUpdates: (() -> Void)?

    var menu: NSMenu {
        let menu = NSMenu()
        menu.addItem(item(L10n.t("Treatments…"), #selector(treatments), enabled: openTreatments != nil))
        menu.addItem(item(L10n.t("Glucose Report…"), #selector(report), enabled: openReport != nil))
        menu.addItem(.separator())
        menu.addItem(item(L10n.t("Settings…"), #selector(settings), key: ",", enabled: openSettings != nil))
        menu.addItem(item(L10n.t("Check for Updates…"), #selector(updates), enabled: checkForUpdates != nil))
        menu.addItem(.separator())
        menu.addItem(
            NSMenuItem(
                title: L10n.t("Quit Sukrara"), action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
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
