// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

// The menu bar panel: what a click on the glucose number opens.
//
// A window-style panel instead of an NSMenu, like SwiftUI's `MenuBarExtra(.window)` and the system's own Control
// Center / Wi-Fi / Bluetooth panels: HIG "The menu bar" › Menu bar extras ("display a menu — not a popover — …
// unless the app functionality you want to expose is too complex for a menu"). The one frequent action, logging a
// treatment, is the *Add Treatment…* row (a menu item like the others; Return chooses it) that opens the Add Treatment window
// (TreatmentDialog.swift: rapid, long, carbs and a note at once, like Android's dialog).
// The panel is the glance and the one frequent action; everything diagnostic or configurable lives in Settings.

/// Everything the panel shows; StatusController fills it, the SwiftUI view observes it.
@MainActor final class PanelModel: ObservableObject {
    // the card
    @Published var readings: [GraphReading] = []
    @Published var marks: [GraphMark] = []
    @Published var style = CardStyle(sensorTime: true)
    @Published var alerts: AlertSettings?
    @Published var paused: [String: TimeInterval] = [:]
    @Published var hasData = false
    /// Bumped to redraw the card (the clock moves even without new data).
    @Published var tick = 0
    var metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)?
    /// Previews: draw as if this were now.
    var now: Date?

    // Add Treatment
    @Published var canLog = true
    @Published var following = false

    var onAddTreatment: (() -> Void)?
    var onFollow: (() -> Void)?
    var onReport: (() -> Void)?
    var onTreatments: (() -> Void)?
    var onSettings: (() -> Void)?
}

// MARK: - The view

struct PanelView: View {
    @ObservedObject var model: PanelModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            card
            Divider().padding(.horizontal, 4)
            VStack(spacing: 0) {
                // A menu item like the rows under it; Return chooses it (shown as ↩, the menu's key-equivalent glyph).
                if model.hasData || model.following {
                    PanelRow(title: "Add Treatment…", shortcut: "↩", enabled: model.canLog, action: { model.onAddTreatment?() })
                        .keyboardShortcut(.defaultAction)
                        .help(model.canLog ? "" : TreatmentPaths.notAllowed)
                }
                PanelRow(title: "Treatments…", action: { model.onTreatments?() })
                PanelRow(title: "Glucose Report…", action: { model.onReport?() })
                PanelRow(title: "Settings…", shortcut: "⌘,", action: { model.onSettings?() })
            }
        }
        .padding(10)
        .frame(width: 380)
    }

    /// The glance, in a recessed module (Control Center style): a little darker in dark mode, lighter in light mode,
    /// so the graph's ink keeps its contrast over any wallpaper without a bright surface in the dark.
    @ViewBuilder private var card: some View {
        Group {
            if model.hasData {
                CardRep(model: model).frame(height: 196)
            } else {
                VStack(spacing: 10) {
                    Text("No readings yet").font(.title3.weight(.semibold))
                    Text(model.following ? "Waiting for the wearer's phone." : "Follow someone to see their glucose here.")
                        .foregroundStyle(.secondary)
                    if !model.following {
                        Button("Follow Someone…") { model.onFollow?() }.controlSize(.large)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 150)
            }
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .background(ModuleBackground())
    }
}

/// Recessed module: black 18 % in dark mode, white 50 % in light mode (never a bright surface in the dark), 12 pt
/// corners like Control Center's modules. Increase Contrast adds a separator-coloured outline.
private struct ModuleBackground: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.colorSchemeContrast) private var contrast

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        shape.fill(scheme == .dark ? Color.black.opacity(0.18) : Color.white.opacity(0.5))
            .overlay { if contrast == .increased { shape.strokeBorder(Color(nsColor: .separatorColor), lineWidth: 1) } }
    }
}

/// A menu-like row: full width, highlighted on hover, Return-free (click).
private struct PanelRow: View {
    let title: String
    var shortcut: String? = nil
    var enabled = true
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title)
                Spacer()
                if let shortcut { Text(shortcut).foregroundStyle(.tertiary) }
            }
            .padding(.horizontal, 8).padding(.vertical, 4)
            .contentShape(Rectangle())
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(hover && enabled ? Color.primary.opacity(0.1) : .clear))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .foregroundStyle(enabled ? .primary : .tertiary)
        .onHover { hover = $0 }
    }
}

/// The shared card (SukraraGraph.swift) in the panel, drawn with the material ink (CardView).
private struct CardRep: NSViewRepresentable {
    @ObservedObject var model: PanelModel

    func makeNSView(context: Context) -> CardView {
        let v = CardView(frame: NSRect(x: 0, y: 0, width: 340, height: 196))
        v.inset = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)
        return v
    }

    func updateNSView(_ v: CardView, context: Context) {
        _ = model.tick
        v.readings = model.readings
        v.marks = model.marks
        v.style = model.style
        v.alerts = model.alerts
        v.paused = model.paused
        v.metricsOf = model.metricsOf
        v.now = model.now
        v.needsDisplay = true
    }
}

// MARK: - The window

/// A borderless, non-activating panel under the status item (like MenuBarExtra's window style): the menu material,
/// rounded corners, a shadow; it can take keyboard focus without bringing Sukrara forward, closes on Escape, on a
/// click outside and when it loses focus.
@MainActor final class GlancePanel: NSObject, NSWindowDelegate {
    let model: PanelModel
    private var panel: KeyPanel?
    private var monitor: Any?
    var onVisible: ((Bool) -> Void)?
    var isVisible: Bool { panel?.isVisible == true }

    init(model: PanelModel) {
        self.model = model
        super.init()
    }

    func toggle(below button: NSStatusBarButton) {
        isVisible ? close() : open(below: button)
    }

    func open(below button: NSStatusBarButton?) {
        let p = panel ?? make()
        panel = p
        p.contentView?.layoutSubtreeIfNeeded()
        let size = p.contentView?.fittingSize ?? NSSize(width: 400, height: 400)
        if let b = button, let bw = b.window {
            let r = bw.convertToScreen(b.convert(b.bounds, to: nil))
            let screen = (bw.screen ?? NSScreen.main)?.visibleFrame ?? .zero
            var x = r.midX - size.width / 2
            x = min(max(x, screen.minX + 8), screen.maxX - size.width - 8)
            p.setFrame(NSRect(x: x, y: r.minY - size.height - 6, width: size.width, height: size.height), display: true)
        } else {
            p.setContentSize(size)
            p.center()
        }
        model.tick += 1
        p.makeKeyAndOrderFront(nil)
        onVisible?(true)
        monitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] _ in
            MainActor.assumeIsolated { self?.close() }
        }
    }

    func close() {
        guard let p = panel, p.isVisible else { return }
        p.orderOut(nil)
        if let m = monitor { NSEvent.removeMonitor(m); monitor = nil }
        onVisible?(false)
    }

    /// Size changes (logged line, no-data state): keep the top edge under the status item.
    func relayout() {
        guard let p = panel, p.isVisible, let c = p.contentView else { return }
        c.layoutSubtreeIfNeeded()
        let size = c.fittingSize
        guard abs(size.height - p.frame.height) > 0.5 else { return }
        p.setFrame(NSRect(x: p.frame.minX, y: p.frame.maxY - size.height, width: size.width, height: size.height), display: true)
    }

    func windowDidResignKey(_ notification: Notification) { close() }

    /// The panel's content on the menu material (shared with the preview harness).
    static func content(_ model: PanelModel, blending: NSVisualEffectView.BlendingMode) -> NSView {
        let fx = NSVisualEffectView()
        fx.material = .menu
        fx.state = .active
        fx.blendingMode = blending
        fx.wantsLayer = true
        fx.layer?.cornerRadius = 14
        fx.layer?.cornerCurve = .continuous
        fx.layer?.masksToBounds = true
        let host = NSHostingView(rootView: PanelView(model: model))
        host.translatesAutoresizingMaskIntoConstraints = false
        fx.addSubview(host)
        NSLayoutConstraint.activate([
            host.leadingAnchor.constraint(equalTo: fx.leadingAnchor), host.trailingAnchor.constraint(equalTo: fx.trailingAnchor),
            host.topAnchor.constraint(equalTo: fx.topAnchor), host.bottomAnchor.constraint(equalTo: fx.bottomAnchor),
        ])
        return fx
    }

    private func make() -> KeyPanel {
        let p = KeyPanel(contentRect: NSRect(x: 0, y: 0, width: 400, height: 400), styleMask: [.borderless, .nonactivatingPanel],
                         backing: .buffered, defer: true)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.level = .popUpMenu
        p.isReleasedWhenClosed = false
        p.hidesOnDeactivate = false
        p.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary, .transient]
        p.delegate = self
        p.onCancel = { [weak self] in self?.close() }
        p.contentView = Self.content(model, blending: .behindWindow)
        p.setAccessibilityLabel("Sukrara")
        return p
    }

    /// Debug aid (`-openPanel`): the panel's window number and frame for `screencapture`.
    var windowNumber: Int { panel?.windowNumber ?? 0 }
    var frame: NSRect { panel?.frame ?? .zero }
}

/// Borderless panels don't take key status by default; this one must, for Return and Escape.
final class KeyPanel: NSPanel {
    var onCancel: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override func cancelOperation(_ sender: Any?) { onCancel?() }
}
