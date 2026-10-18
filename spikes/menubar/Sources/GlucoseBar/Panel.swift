// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import SwiftUI

// The menu bar panel: what a click on the glucose number opens.
//
// A window-style panel instead of an NSMenu, like SwiftUI's `MenuBarExtra(.window)` and the system's own Control
// Center / Wi-Fi / Bluetooth panels: HIG "The menu bar" › Menu bar extras ("display a menu — not a popover — …
// unless the app functionality you want to expose is too complex for a menu"). Logging a treatment needs a text
// field, which a menu can't hold, so the panel carries it inline: open → type `4.5u` → Return (HIG "Entering data":
// minimize what people supply — the unit picks the kind — and prompt in the field; "Keyboards": Return confirms,
// Escape dismisses).
// The panel is the glance and the one frequent action; everything diagnostic or configurable lives in Settings.

/// Everything the panel shows; StatusController fills it, the SwiftUI view observes it.
@MainActor final class PanelModel: ObservableObject {
    // the card
    @Published var readings: [GraphReading] = []
    @Published var marks: [GraphMark] = []
    @Published var style = CardStyle()
    @Published var alerts: AlertSettings?
    @Published var hasData = false
    /// Bumped to redraw the card (the clock moves even without new data).
    @Published var tick = 0
    var metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)?
    /// Previews: draw as if this were now.
    var now: Date?

    // quick log
    @Published var entry = ""
    @Published var kind = UserDefaults.standard.string(forKey: "quickKind").flatMap { QuickLog.kinds.map(\.k).contains($0) ? $0 : nil } ?? "rapid" {
        didSet { UserDefaults.standard.set(kind, forKey: "quickKind") }
    }
    /// Minutes before now the entry is for (0 = now).
    @Published var minutesAgo = 0
    @Published var canLog = true
    @Published var following = false
    /// "Logged 4.5u rapid" + Undo, for a few seconds after Return.
    @Published var logged: (text: String, id: String)?
    private var loggedTimer: Timer?

    var onLog: ((Treatment) -> Void)?
    var onUndo: ((String) -> Void)?
    var onFollow: (() -> Void)?
    var onReport: (() -> Void)?
    var onSettings: (() -> Void)?
    /// Return with a valid entry closes the panel (the glance is done).
    var onDone: (() -> Void)?

    var parsed: QuickLog.Entry? { QuickLog.parse(entry, kind: kind) }

    func submit() {
        guard canLog, let e = parsed else { return NSSound.beep() }
        let at = Date().addingTimeInterval(TimeInterval(-minutesAgo * 60))
        let t = Treatment(k: e.k, a: e.a, t: Int64(at.timeIntervalSince1970), n: e.n)
        onLog?(t)
        entry = ""
        minutesAgo = 0
        logged = (QuickLog.describe(e), t.id)
        loggedTimer?.invalidate()
        loggedTimer = Timer.scheduledTimer(withTimeInterval: 8, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.logged = nil }
        }
    }

    func undo() {
        guard let l = logged else { return }
        onUndo?(l.id)
        logged = nil
    }
}

/// The quick-log grammar: a number, optionally a unit (`u` insulin, `g` carbs, `long`), then an optional note;
/// anything without a number is a note. The unit wins over the selected kind; `u` keeps "long" when long is selected.
enum QuickLog {
    struct Entry: Equatable { var k: String; var a: Double; var n: String }
    static let kinds: [(k: String, label: String, symbol: String)] = [
        ("rapid", "Rapid", "syringe"), ("carbs", "Carbs", "fork.knife"), ("long", "Long", "syringe.fill"), ("note", "Note", "text.bubble"),
    ]

    private static let rx = try! NSRegularExpression(pattern: #"^(\d+(?:[.,]\d+)?)\s*(u|iu|g|gr|l|long|rapid|carbs?)?\b\s*(.*)$"#,
                                                     options: [.caseInsensitive])

    static func parse(_ raw: String, kind: String) -> Entry? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        let ns = s as NSString
        guard kind != "note", let m = rx.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)),
              let v = Double(ns.substring(with: m.range(at: 1)).replacingOccurrences(of: ",", with: ".")), v > 0 else {
            return Entry(k: "note", a: 0, n: s)
        }
        func group(_ i: Int) -> String { m.range(at: i).location == NSNotFound ? "" : ns.substring(with: m.range(at: i)) }
        let unit = group(2).lowercased()
        let k: String
        switch unit {
        case "u", "iu": k = kind == "long" ? "long" : "rapid"
        case "g", "gr", "carb", "carbs": k = "carbs"
        case "l", "long": k = "long"
        case "rapid": k = "rapid"
        default: k = kind
        }
        let a: Double = k == "carbs" ? v.rounded() : (v * 10).rounded() / 10
        guard a > 0 else { return nil }
        return Entry(k: k, a: a, n: group(3).trimmingCharacters(in: .whitespaces))
    }

    /// "4.5u rapid insulin", "30g carbs · pasta", "note: walk".
    static func describe(_ e: Entry) -> String {
        let what: String = switch e.k {
        case "rapid": "\(TreatmentMarks.amount(k: e.k, a: e.a) ?? "") rapid insulin"
        case "long": "\(TreatmentMarks.amount(k: e.k, a: e.a) ?? "") long insulin"
        case "carbs": "\(TreatmentMarks.amount(k: e.k, a: e.a) ?? "") carbs"
        default: "note"
        }
        if e.k == "note" { return "note: " + e.n }
        return e.n.isEmpty ? what : what + " · " + e.n
    }

    static func selfTest() -> Bool {
        var ok = true
        func check(_ s: String, _ kind: String, _ want: Entry?) {
            let got = parse(s, kind: kind)
            if got != want { print("FAILED: quicklog \"\(s)\" (\(kind)) → \(String(describing: got)), want \(String(describing: want))"); ok = false }
        }
        check("4.5u", "carbs", Entry(k: "rapid", a: 4.5, n: ""))
        check("4,5", "rapid", Entry(k: "rapid", a: 4.5, n: ""))
        check("12u", "long", Entry(k: "long", a: 12, n: ""))
        check("12 long", "rapid", Entry(k: "long", a: 12, n: ""))
        check("30g pasta", "rapid", Entry(k: "carbs", a: 30, n: "pasta"))
        check("30", "carbs", Entry(k: "carbs", a: 30, n: ""))
        check("walk", "rapid", Entry(k: "note", a: 0, n: "walk"))
        check("2 slices", "note", Entry(k: "note", a: 0, n: "2 slices"))
        check("  ", "rapid", nil)
        check("0u", "rapid", Entry(k: "note", a: 0, n: "0u"))
        print(ok ? "quicklog selftest OK" : "quicklog selftest FAILED")
        return ok
    }
}

// MARK: - The view

struct PanelView: View {
    @ObservedObject var model: PanelModel
    @FocusState private var fieldFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            card
            if model.hasData || model.following { quickLog }
            Divider().padding(.horizontal, 4)
            VStack(spacing: 0) {
                PanelRow(title: "Glucose Report…", action: { model.onReport?() })
                PanelRow(title: "Settings…", shortcut: "⌘,", action: { model.onSettings?() })
            }
        }
        .padding(10)
        .frame(width: 380)
        .onAppear { fieldFocused = model.canLog }
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

    @ViewBuilder private var quickLog: some View {
        VStack(alignment: .leading, spacing: 6) {
            if model.canLog {
                HStack(spacing: 6) {
                    TextField("Log a treatment", text: $model.entry, prompt: Text("Log: 4.5u, 30g, 12 long, a note…"))
                        .textFieldStyle(.roundedBorder)
                        .controlSize(.large)
                        .focused($fieldFocused)
                        .onSubmit { model.submit() }
                        .accessibilityHint("Type an amount with u for insulin or g for carbs, then press Return")
                    Menu {
                        Picker("When", selection: $model.minutesAgo) {
                            Text("Now").tag(0)
                            ForEach([5, 10, 15, 30, 45, 60, 90, 120], id: \.self) { Text("\($0) min ago").tag($0) }
                        }
                        .pickerStyle(.inline)
                    } label: {
                        Text(model.minutesAgo == 0 ? "Now" : "−\(model.minutesAgo) min")
                    }
                    .menuStyle(.borderlessButton)
                    .fixedSize()
                    .help("When it happened")
                }
                // Shows the kind the entry will get (a typed unit wins); picking sets the default for unitless numbers.
                Picker("Kind", selection: Binding(get: { model.parsed?.k ?? model.kind }, set: { model.kind = $0 })) {
                    ForEach(QuickLog.kinds, id: \.k) { Label($0.label, systemImage: $0.symbol).tag($0.k) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                hint
            } else {
                Label("The wearer hasn't allowed this Mac to log treatments.", systemImage: "lock")
                    .font(.callout).foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
            }
        }
        // ⌘1–⌘4 pick the kind without leaving the field.
        .background {
            ForEach(Array(QuickLog.kinds.enumerated()), id: \.offset) { i, k in
                Button("") { model.kind = k.k }.keyboardShortcut(KeyEquivalent(Character("\(i + 1)")), modifiers: .command).hidden()
            }
        }
    }

    @ViewBuilder private var hint: some View {
        Group {
            if let l = model.logged, model.entry.isEmpty {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    Text("Logged \(l.text)")
                    Button("Undo") { model.undo() }.buttonStyle(.link)
                }
            } else if let e = model.parsed {
                Text("↩ \(QuickLog.describe(e))" + (model.minutesAgo == 0 ? "" : ", \(model.minutesAgo) min ago"))
            } else {
                Text("⌘1–⌘4 pick the kind · ↩ logs")
            }
        }
        .font(.callout)
        .foregroundStyle(.secondary)
        .lineLimit(1)
        .padding(.horizontal, 4)
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
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(hover ? Color.primary.opacity(0.1) : .clear))
        }
        .buttonStyle(.plain)
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
        model.onDone = { [weak self] in self?.close() }
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

/// Borderless panels don't take key status by default; this one must, for the text field.
final class KeyPanel: NSPanel {
    var onCancel: (() -> Void)?
    override var canBecomeKey: Bool { true }
    override func cancelOperation(_ sender: Any?) { onCancel?() }
}
