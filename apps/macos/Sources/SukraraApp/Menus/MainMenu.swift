// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

/// The main menu an accessory app still needs while one of its windows is key: Settings… ⌘,, ⌘Q, ⌘W, ⌘H and the Edit
/// menu, so
/// text fields (Mute…, settings) get copy, paste, undo and select all.
enum MainMenu {
    static func make() -> NSMenu {
        let main = NSMenu()
        main.addItem(
            submenu(
                "Sukrara",
                [
                    // the standard place (Settings… ⌘, first in the app menu); target nil reaches the delegate
                    NSMenuItem(
                        title: L10n.t("Settings…"), action: #selector(AppDelegate.showSettings(_:)), keyEquivalent: ","),
                    .separator(),
                    NSMenuItem(title: "Hide Sukrara", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h"),
                    .separator(),
                    NSMenuItem(
                        title: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"),
                ]))
        main.addItem(
            submenu(
                "Edit",
                [
                    NSMenuItem(title: "Undo", action: Selector(("undo:")), keyEquivalent: "z"),
                    NSMenuItem(title: "Redo", action: Selector(("redo:")), keyEquivalent: "Z"),
                    .separator(),
                    NSMenuItem(title: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x"),
                    NSMenuItem(title: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c"),
                    NSMenuItem(title: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v"),
                    NSMenuItem(title: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"),
                ]))
        main.addItem(
            submenu(
                "Window",
                [
                    NSMenuItem(title: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w"),
                    NSMenuItem(
                        title: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m"),
                ]))
        return main
    }

    private static func submenu(_ title: String, _ items: [NSMenuItem]) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        let menu = NSMenu(title: title)
        items.forEach(menu.addItem)
        item.submenu = menu
        return item
    }
}
