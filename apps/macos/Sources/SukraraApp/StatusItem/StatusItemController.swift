// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Observation
import SukraraGraph
import SwiftUI

/// The status item: the live title (`MenubarTitle`, from the one renderer) and, on click, the panel; right click or
/// Control-click opens the context menu. NSStatusItem (not MenuBarExtra): the title is a custom live value, the
/// panel opens programmatically and windows stay reliable in an accessory app.
/// Closing the panel by Esc, a click outside or a second click gives the focus back to the app that had it; a row
/// that opens a window keeps Sukrara active (`closePanel(handOff:)`).
final class StatusItemController: NSObject, NSPopoverDelegate {
    private let model: AppModel
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let popover = NSPopover()
    private let menu: ContextMenu
    /// Called each time the panel opens (re-check notification permission).
    var onOpen: (() -> Void)?
    /// The app that was frontmost when the panel opened (not Sukrara): it gets the focus back on close.
    private var previousApp: NSRunningApplication?
    /// The panel is closing because one of its rows opens a window: Sukrara stays active.
    private var handingOff = false

    init(model: AppModel, menu: ContextMenu, links: PanelLinks?) {
        self.model = model
        self.menu = menu
        super.init()
        popover.delegate = self
        popover.behavior = .transient
        popover.animates = !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        popover.contentViewController = NSHostingController(rootView: PanelView(model: model, links: links))
        if let button = item.button {
            button.target = self
            button.action = #selector(clicked(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }
        render()
    }

    /// The status item's frame in screen coordinates (nil while it has no window, e.g. hidden by the notch).
    var screenFrame: NSRect? {
        guard let button = item.button, let window = button.window else { return nil }
        return window.convertToScreen(button.convert(button.bounds, to: nil))
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
        button.image = Self.image(title)
        button.imagePosition = title.value.isEmpty && !title.muted ? .imageOnly : .imageTrailing
        button.toolTip = title.tooltip
        button.setAccessibilityLabel(title.accessibilityLabel)
    }

    /// The value's colour: red below the range, orange above, secondary when stale or warming up, else the menu
    /// bar's own ink (nil: the button follows light / dark and the selected highlight).
    static func color(_ tint: MenubarTitle.Tint) -> NSColor? {
        switch tint {
        case .plain: nil
        case .below: .systemRed
        case .above: .systemOrange
        case .grey: .secondaryLabelColor
        }
    }

    /// The mute bell before the value, the value (struck when stale) in its range colour.
    static func attributed(_ title: MenubarTitle) -> NSAttributedString {
        var attributes: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium)
        ]
        if let color = color(title.tint) { attributes[.foregroundColor] = color }
        if title.strike { attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        let out = NSMutableAttributedString()
        if title.muted {
            let bell = NSTextAttachment()
            let img = NSImage(systemSymbolName: "bell.slash", accessibilityDescription: nil)?
                .withSymbolConfiguration(.init(pointSize: NSFont.systemFontSize - 1, weight: .medium))
            img?.isTemplate = true
            bell.image = img
            out.append(NSAttributedString(attachment: bell))
            out.addAttribute(
                .foregroundColor, value: NSColor.secondaryLabelColor, range: NSRange(location: 0, length: out.length))
            if !title.value.isEmpty { out.append(NSAttributedString(string: "\u{2009}", attributes: attributes)) }
        }
        out.append(NSAttributedString(string: title.value, attributes: attributes))
        return out
    }

    /// The trend arrow at its angle (the card's own, `GlucoseCard.trendImage`), the no-trend ring, or the symbol that
    /// stands for no readings / the sensor's end. Without a range colour it's a template: it follows the menu bar.
    static func image(_ title: MenubarTitle) -> NSImage? {
        let ink = color(title.tint)
        switch title.mark {
        case .arrow, .ring:
            let rate: Double? = if case .arrow(let r) = title.mark { r } else { nil }
            let size: CGFloat = 15
            guard
                let cg = GlucoseCard.trendImage(
                    rate: rate, size: size, scale: 2, color: (ink ?? .black).cgColor)
            else { return nil }
            let img = NSImage(cgImage: cg, size: NSSize(width: size, height: size))
            img.isTemplate = ink == nil
            return img
        case .symbol(let name):
            let img = NSImage(systemSymbolName: name, accessibilityDescription: nil)?
                .withSymbolConfiguration(.init(pointSize: NSFont.systemFontSize, weight: .medium))
            img?.isTemplate = true
            return img
        case .none:
            return nil
        }
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

    /// Closes the panel; `handOff` = a window opens next, so the previous app doesn't get the focus back.
    func closePanel(handOff: Bool = true) {
        guard popover.isShown else { return }
        handingOff = handOff
        popover.performClose(nil)
    }

    func popoverDidClose(_ notification: Notification) {
        defer {
            handingOff = false
            previousApp = nil
        }
        // a click on another app already moved the focus there; a Sukrara window that's key keeps it
        guard !handingOff, NSApp.isActive, let previousApp, !previousApp.isTerminated else { return }
        let panelWindow = popover.contentViewController?.view.window
        if NSApp.windows.contains(where: { $0 !== panelWindow && $0.isVisible && $0.isKeyWindow }) { return }
        previousApp.activate()
    }

    func showPanel() {
        guard let button = item.button, !popover.isShown else { return }
        showPanel(from: button)
    }

    private func showPanel(from button: NSStatusBarButton) {
        model.alerts?.refreshUI()
        onOpen?()
        let front = NSWorkspace.shared.frontmostApplication
        previousApp = front?.processIdentifier == ProcessInfo.processInfo.processIdentifier ? nil : front
        handingOff = false
        NSApp.activate()
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        popover.contentViewController?.view.window?.makeKey()
    }
}
