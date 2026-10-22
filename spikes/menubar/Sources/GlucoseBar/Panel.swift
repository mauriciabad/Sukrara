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
    /// This Mac's Mute all until (unix s; 0 = off, AlertStatus.never = until turned back on).
    @Published var allMuted: TimeInterval = 0
    @Published var hasData = false
    /// Bumped to redraw the card (the clock moves even without new data).
    @Published var tick = 0
    var metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)?
    /// Previews: draw as if this were now.
    var now: Date?
    /// Previews: the chart scrolled this far back from `now` (s).
    var previewScrollBack: TimeInterval?
    /// Previews: the chart zoomed to this window length (s).
    var previewSpan: TimeInterval?

    // Add Treatment
    @Published var canLog = true
    /// Why Add Treatment is dimmed (TreatmentPaths.Blocked), shown under it; nil = available or no reason to give.
    @Published var logBlocked: String?
    @Published var following = false

    /// The quiet alerts on this Mac (Alerts.active) and what the user does with them (SPEC §2 "States and exits").
    @Published var quiet: [Alerts.Active] = []
    var onQuiet: ((QuietOp) -> Void)?

    var onAddTreatment: (() -> Void)?
    var onFollow: (() -> Void)?
    var onReport: (() -> Void)?
    var onTreatments: (() -> Void)?
    var onSettings: (() -> Void)?
}

/// A way out of a quiet alert (or into one, from the same list): the panel's rows, the pill's menu, Settings › Alerts.
/// `muteAll` minutes, 0 = Never (until turned back on).
enum QuietOp { case unmute(AlertKind), unrelax(AlertKind), mute(AlertKind, Int), relax(AlertKind), muteAll(Int), unmuteAll }

/// The mute lengths offered (the schema's Mute presets).
let quietMinutes: [Int] = SettingsSpec.actionMute.presets.map { Int($0) }

/// The Mute Alerts menu (round f31): "For 30 min" … "For 8 h", "Until You Turn It Off" (0), as (title, minutes).
/// Menu items are title-style (HIG "Menus"); the unit abbreviations keep their case (Apple Style Guide: "min", "h").
let muteAllChoices: [(String, Int)] =
    AlertActions.muteAllPresets.filter { $0 > 0 }.map { (MuteCopy.choice($0), $0) } + [(MuteCopy.menuForever, 0)]

/// The side Mute / Change Time lengths: "For 15 min", "For 30 min", "For 1 h", "For 2 h".
let quietChoices: [(String, Int)] = quietMinutes.map { (MuteCopy.choice($0), $0) }

/// The side menu: *Change Time* for a notification Mute, else *Mute* (a treatment quiet, Remind Less alone).
func quietMenuTitle(_ q: Alerts.Active) -> String { q.quiet == .notification ? "Change Time" : "Mute" }
/// Its VoiceOver label: "Low alerts: Change time" / "Low alerts: Mute".
func quietMenuLabel(_ q: Alerts.Active) -> String {
    "\(MuteCopy.side(q.kind.side)) alerts: \(q.quiet == .notification ? "Change time" : "Mute")"
}

/// `q`'s ways out as a menu (the card pill's click, AppKit): Unmute / Alert Me Anyway, Remind as Usual / Remind Less,
/// Change Time / Mute … and, while Mute all is on, Unmute (HIG "Menus": a menu offers the commands for the item it
/// opens on). Built when it opens, so its lines say the time left then.
@MainActor func quietMenu(_ list: [Alerts.Active], allMuted: String?, now: Date = Date(), _ act: @escaping (QuietOp) -> Void) -> NSMenu {
    let menu = NSMenu()
    if let allMuted {
        let head = NSMenuItem(title: allMuted, action: nil, keyEquivalent: "")
        head.isEnabled = false
        menu.addItem(head)
        let off = ClosureMenuItem("Unmute") { act(.unmuteAll) }
        off.setAccessibilityLabel("Alerts: Unmute")
        menu.addItem(off)
    }
    for (i, q) in list.enumerated() {
        if i > 0 || allMuted != nil { menu.addItem(.separator()) }
        let head = NSMenuItem(title: q.title(now: now), action: nil, keyEquivalent: "")
        head.isEnabled = false
        menu.addItem(head)
        if q.quiet != nil, q.relaxMin > 0 {
            let line = NSMenuItem(title: q.relaxedLine, action: nil, keyEquivalent: "")
            line.isEnabled = false
            menu.addItem(line)
        }
        if let l = q.lift { menu.addItem(ClosureMenuItem(l) { act(.unmute(q.kind)) }) }
        if q.relaxMin > 0 { menu.addItem(ClosureMenuItem("Remind as Usual") { act(.unrelax(q.kind)) }) }
        else if q.canRelax { menu.addItem(ClosureMenuItem("Remind Less") { act(.relax(q.kind)) }) }
        let sub = NSMenu()
        for (t, m) in quietChoices { sub.addItem(ClosureMenuItem(t) { act(.mute(q.kind, m)) }) }
        let item = NSMenuItem(title: quietMenuTitle(q), action: nil, keyEquivalent: "")
        item.setAccessibilityLabel(quietMenuLabel(q))
        item.submenu = sub
        menu.addItem(item)
    }
    return menu
}

/// A menu item that runs a closure.
final class ClosureMenuItem: NSMenuItem {
    private let run: () -> Void
    init(_ title: String, _ run: @escaping () -> Void) {
        self.run = run
        super.init(title: title, action: #selector(fire), keyEquivalent: "")
        target = self
    }
    required init(coder: NSCoder) { fatalError("not used") }
    @objc private func fire() { run() }
}

// MARK: - The view

struct PanelView: View {
    @ObservedObject var model: PanelModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            card
            // the ways out of a quiet alert (round f29): one block per muted / quiet / relaxed alert, its buttons as
            // menu-like rows (VoiceOver reaches each one; nothing needs the notification)
            ForEach(model.quiet, id: \.kind) { q in QuietBlock(q: q, now: model.now) { model.onQuiet?($0) } }
            Divider().padding(.horizontal, 4)
            // Mute all (round f30): this Mac's alerts off for a while or until turned back on, always reachable here
            if model.alerts?.on ?? true {
                MuteAllBlock(allMuted: model.allMuted, now: model.now) { model.onQuiet?($0) }
                Divider().padding(.horizontal, 4)
            }
            VStack(spacing: 0) {
                // A menu item like the rows under it; Return chooses it (shown as ↩, the menu's key-equivalent glyph).
                if model.hasData || model.following {
                    // Unavailable: dimmed (HIG "Menus": show people when a menu item is unavailable) with the reason
                    // on a second line, like a macOS 14.4+ menu item subtitle, so it's seen without hovering; the
                    // tooltip repeats it (HIG "Offering help": context-sensitive tooltips).
                    PanelRow(title: "Add Treatment…", detail: model.canLog ? nil : model.logBlocked, shortcut: "↩",
                             enabled: model.canLog, action: { model.onAddTreatment?() })
                        .keyboardShortcut(.defaultAction)
                        .help(model.canLog ? "" : model.logBlocked ?? "")
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

/// Redraws `content` whenever the minutes left until `until` change: a TimelineView aligned to the end, so "12 min
/// left" turns to "11 min left" right then, while the view is on screen (round f31). No end, or an indefinite one:
/// drawn once. `now` fixes the time (the offline snapshots).
struct Countdown<Content: View>: View {
    let until: Date?
    var now: Date? = nil
    @ViewBuilder let content: (Date) -> Content

    var body: some View {
        if let now {
            content(now)
        } else if let until, until < AlertEngine.never {
            // entries every minute back from the end (2 days before it covers every mute length)
            TimelineView(.periodic(from: until.addingTimeInterval(-2 * 86_400), by: 60)) { ctx in content(ctx.date) }
        } else {
            content(Date())
        }
    }
}

/// One quiet alert in the panel: its line (ticking down), then Unmute / Alert Me Anyway, Remind as Usual and a
/// Change Time / Mute menu of the mute lengths.
private struct QuietBlock: View {
    let q: Alerts.Active
    var now: Date? = nil
    let act: (QuietOp) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Countdown(until: q.ticks ? q.until : nil, now: now) { t in
                Label(q.title(now: t), systemImage: q.quiet == nil ? "bell.badge.waveform" : "bell.slash")
                    .font(.callout).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.bottom, 2)
            }
            if q.quiet != nil, q.relaxMin > 0 {
                Label(q.relaxedLine, systemImage: "bell.badge.waveform")
                    .font(.callout).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.bottom, 2)
            }
            if let l = q.lift { PanelRow(title: l, action: { act(.unmute(q.kind)) }) }
            if q.relaxMin > 0 { PanelRow(title: "Remind as Usual", action: { act(.unrelax(q.kind)) }) }
            Menu {
                ForEach(quietChoices, id: \.1) { c in Button(c.0) { act(.mute(q.kind, c.1)) } }
            } label: {
                Text(quietMenuTitle(q)).frame(maxWidth: .infinity, alignment: .leading)
            }
            .menuStyle(.borderlessButton).menuIndicator(.visible)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .accessibilityLabel(quietMenuLabel(q))
        }
    }
}

/// *Mute Alerts* (round f30, f31 copy): off → a menu of the lengths (For 30 min · … · For 8 h · Until You Turn It Off ·
/// Custom…); on → "Alerts muted · 1 h 12 min left" (ticking down) / "Alerts muted until you turn it off", *Unmute*
/// and *Change Time*. Custom… opens a field for a typed length (Durations.parse, the schema's muteAll range).
struct MuteAllBlock: View {
    let allMuted: TimeInterval
    var now: Date? = nil
    let act: (QuietOp) -> Void
    /// Evidence aid (`-panelPreview`): the custom field open.
    var startCustom = false
    @State private var custom = false
    @State private var text = ""
    @State private var invalid = false

    private var end: Date? { allMuted > 0 ? Date(timeIntervalSince1970: allMuted) : nil }
    private var range: ClosedRange<Int> { AlertActions.muteAllRange }
    private var note: String { "Enter \(Durations.text(range.lowerBound))–\(Durations.text(range.upperBound)), e.g. 1 h 30 min" }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if allMuted > (now ?? Date()).timeIntervalSince1970 {
                Countdown(until: end, now: now) { t in
                    Label(MuteCopy.allLine(until: allMuted, now: t.timeIntervalSince1970) ?? "", systemImage: "bell.slash")
                        .font(.callout).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.bottom, 2)
                        .accessibilityAddTraits(.isHeader)
                }
                PanelRow(title: "Unmute", action: { act(.unmuteAll) }).accessibilityLabel("Alerts: Unmute")
                lengths("Change Time")
            } else {
                lengths("Mute Alerts")
            }
            if custom || startCustom {
                HStack(spacing: 6) {
                    TextField("Mute alerts for", text: $text, prompt: Text("2 h 30 min"))
                        .textFieldStyle(.roundedBorder)
                        .onSubmit(commit)
                        .accessibilityHint(note)
                    Button("Mute", action: commit).disabled(text.isEmpty)
                }
                .padding(.horizontal, 8).padding(.top, 2)
                Text(note).font(.caption).foregroundStyle(invalid ? Color.red : Color.secondary)
                    .padding(.horizontal, 8).padding(.top, 2)
            }
        }
    }

    private func lengths(_ title: String) -> some View {
        Menu {
            ForEach(muteAllChoices, id: \.1) { c in Button(c.0) { act(.muteAll(c.1)) } }
            Divider()
            Button("Custom…") { custom = true }
        } label: {
            Text(title).frame(maxWidth: .infinity, alignment: .leading)
        }
        .menuStyle(.borderlessButton).menuIndicator(.visible)
        .padding(.horizontal, 8).padding(.vertical, 4)
        .accessibilityLabel(title == "Change Time" ? "Alerts: Change time" : "Mute alerts")
    }

    private func commit() {
        guard let m = Durations.parse(text), range.contains(m) else { invalid = true; return }
        invalid = false
        custom = false
        text = ""
        act(.muteAll(m))
    }
}

/// A menu-like row: full width, highlighted on hover, Return-free (click).
private struct PanelRow: View {
    let title: String
    /// A second, smaller line (the reason an unavailable item is dimmed).
    var detail: String? = nil
    var shortcut: String? = nil
    var enabled = true
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title)
                    if let detail {
                        Text(detail).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
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
        v.allMuted = model.allMuted
        // a click on the "45m left" pill opens its ways out as a menu (the same as the rows under the card)
        v.onStatus = { [weak model] box, view in
            let line = MuteCopy.allLine(until: model?.allMuted ?? 0, now: Date().timeIntervalSince1970)
            guard let model, !model.quiet.isEmpty || line != nil else { return }
            let menu = quietMenu(model.quiet, allMuted: line) { model.onQuiet?($0) }
            menu.popUp(positioning: nil, at: NSPoint(x: box.minX, y: box.maxY + 4), in: view)
        }
        v.metricsOf = model.metricsOf
        v.now = model.now
        if let back = model.previewScrollBack, let now = model.now { v.scrollEnd = back > 0 ? now.timeIntervalSince1970 - back : nil }
        if let span = model.previewSpan { v.scrollSpan = span }
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
