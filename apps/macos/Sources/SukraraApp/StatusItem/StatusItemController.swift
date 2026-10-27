// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Observation
import SwiftUI

/// The status item: the live title (`MenubarTitle`, from the one renderer) and, on click, the panel; right click or
/// Control-click opens the context menu. NSStatusItem (not MenuBarExtra): the title is a custom live value, the
/// panel opens programmatically and windows stay reliable in an accessory app.
final class StatusItemController {
    private let model: AppModel
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private let menu: ContextMenu
    /// Called each time the panel opens (re-check notification permission).
    var onOpen: (() -> Void)?

    init(model: AppModel, menu: ContextMenu) {
        self.model = model
        self.menu = menu
        popover.behavior = .transient
        popover.animates = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        popover.contentViewController = NSHostingController(rootView: PanelView(model: model))
        if let button = item.button {
            button.target = self
            button.action = #selector(clicked(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
        render()
    }

    /// Redraws the title whenever what it reads changes (Observation; re-armed after each change).
    private func render() {
        let title = withObservationTracking {
            model.menubarTitle
        } onChange: { [weak self] in
            Task { @MainActor in self?.render() }
        }
        guard let button = item.button else { return }
        button.attributedTitle = Self.attributed(title)
        button.image = title.muted ? NSImage(systemSymbolName: "bell.slash", accessibilityDescription: nil) : nil
        button.imagePosition = .imageTrailing
        button.setAccessibilityLabel(title.accessibilityLabel)
    }

    static func attributed(_ title: MenubarTitle) -> NSAttributedString {
        var attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium)
        ]
        if title.strike {
            attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
            attributes[.foregroundColor] = NSColor.secondaryLabelColor
        }
        return NSAttributedString(string: title.text, attributes: attributes)
    }

    @objc private func clicked(_ sender: NSStatusBarButton) {
        let event = NSApp.currentEvent
        if event?.type == .rightMouseUp || event?.modifierFlags.contains(.control) == true {
            item.menu = menu.menu
            sender.performClick(nil)
            item.menu = nil
        } else {
            togglePanel()
        }
    }

    func togglePanel() {
        guard let button = item.button else { return }
        if popover.isShown {
            popover.performClose(nil)
        } else {
            showPanel(from: button)
        }
    }

    func showPanel() {
        guard let button = item.button, !popover.isShown else { return }
        showPanel(from: button)
    }

    private func showPanel(from button: NSStatusBarButton) {
        model.alerts?.refreshUI()
        onOpen?()
        NSApp.activate()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        popover.contentViewController?.view.window?.makeKey()
    }
}
