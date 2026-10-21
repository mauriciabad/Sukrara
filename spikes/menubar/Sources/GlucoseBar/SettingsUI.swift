// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import ServiceManagement
import SwiftUI
import UniformTypeIdentifiers

/// Sukrara's Settings window (⌘, from the menu). Redesigned in round f28 after the HIG
/// (developer.apple.com/design/human-interface-guidelines/settings, macOS):
/// - a preference-style toolbar of panes, not a sidebar: "Typically, a custom settings window contains a toolbar that
///   includes buttons for switching between views — called panes — that each contain a group of related settings."
///   Five panes fit a toolbar; HIG Sidebars: "A sidebar requires a large amount of vertical and horizontal space", which
///   a settings window of this size doesn't need (System Settings uses one for dozens of areas).
/// - "In your settings window, use a noncustomizable toolbar that remains visible and always indicates the active
///   toolbar button."
/// - "Update the window's title to reflect the currently visible pane."
/// - "Dim a settings window's minimize and maximize buttons" (titled + closable only); the window takes each pane's
///   size, keeping its top edge ("a settings window accommodates the size of the current pane").
/// - "Restore the most recently viewed pane." (`settingsPane`, frame autosave)
/// - "Aim to provide default settings that give the best experience to the largest number of people" and "Minimize the
///   number of settings you offer": a follower takes the wearer's settings by default (Settings from, General), and the
///   panes show only what this Mac can change in that mode, with a read-only summary of the rest.
/// Every change applies at once (no Save button), with standard controls in grouped forms (`LabeledContent`, pop-up
/// pickers, switches) and every number through `NumberSettingRow` (NumberSettingRow.swift).
/// Panes: General (login, Settings from, data source), Graph (time shown, graph, lines, ranges, sensor), Rings (beside
/// the value: rings and numbers, ring order, last treatment), Alerts (AlertsUI.swift), Following (protocol v2).
@MainActor final class SettingsWindow: NSObject, NSToolbarDelegate {
    enum Pane: String, CaseIterable {
        case general, graph, rings, alerts, following
        var title: String {
            switch self { case .general: "General"; case .graph: "Graph"; case .rings: "Rings"; case .alerts: "Alerts"; case .following: "Following" }
        }
        var symbol: String {
            switch self {
            case .general: "gearshape"; case .graph: "chart.xyaxis.line"; case .rings: "circle.circle"; case .alerts: "bell.badge"
            case .following: "person.2"
            }
        }
        var height: CGFloat {
            switch self { case .general: 520; case .graph: 720; case .rings: 680; case .alerts: 720; case .following: 340 }
        }
        var id: NSToolbarItem.Identifier { .init(rawValue) }
    }
    static let width: CGFloat = 600

    let generalModel: GeneralModel
    let graphModel: GraphModel
    let alertsModel: AlertsModel
    let followingModel: FollowingModel
    let from: SettingsFromModel
    private var window: NSWindow?
    private var views: [Pane: NSView] = [:]
    private var pane = Pane(rawValue: UserDefaults.standard.string(forKey: "settingsPane") ?? "") ?? .general

    init(general: GeneralModel, graph: GraphModel, alerts: AlertsModel, following: FollowingModel, from: SettingsFromModel) {
        generalModel = general
        graphModel = graph
        alertsModel = alerts
        followingModel = following
        self.from = from
        super.init()
        from.onOpenGeneral = { [weak self] in self?.select(.general, animate: true) }
    }

    func show(_ p: Pane? = nil) {
        let w = window ?? make()
        window = w
        reload()
        select(p ?? pane, animate: false)
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
    }

    /// Settings from changed, the wearer sent new settings, or a setting changed elsewhere.
    func reload() {
        from.refresh(); generalModel.refresh(); graphModel.reload(); alertsModel.reload(); followingModel.refresh()
    }

    private func make() -> NSWindow {
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: Self.width, height: pane.height),
                         styleMask: [.titled, .closable], backing: .buffered, defer: true)   // HIG: dim minimize and zoom
        w.isReleasedWhenClosed = false
        w.contentView = NSView()
        let tb = NSToolbar(identifier: "sukrara.settings")
        tb.delegate = self
        tb.allowsUserCustomization = false
        tb.displayMode = .iconAndLabel
        w.toolbar = tb
        w.toolbarStyle = .preference
        if !w.setFrameUsingName("SukraraSettings") { w.center() }
        w.setFrameAutosaveName("SukraraSettings")
        return w
    }

    private func view(_ p: Pane) -> NSView {
        if let v = views[p] { return v }
        func host<V: View>(_ root: V) -> NSView {
            let h = NSHostingView(rootView: root)
            h.sizingOptions = []                  // the pane's size is fixed; the grouped form scrolls
            return h
        }
        let v: NSView = switch p {
        case .general: host(GeneralView(model: generalModel, from: from))
        case .graph: host(GraphSettingsView(model: graphModel, from: from))
        case .rings: host(RingsSettingsView(model: graphModel, from: from))
        case .alerts: host(AlertsView(model: alertsModel, from: from))
        case .following: host(FollowingView(model: followingModel))
        }
        views[p] = v
        return v
    }

    /// Evidence aid: the panes are built again (a snapshot state's typed text shows on appear).
    func rebuildViews() { views = [:] }

    func select(_ p: Pane, animate: Bool, height: CGFloat? = nil) {
        guard let w = window, let container = w.contentView else { return }
        pane = p
        UserDefaults.standard.set(p.rawValue, forKey: "settingsPane")
        container.subviews.forEach { $0.removeFromSuperview() }
        var f = w.frameRect(forContentRect: NSRect(x: 0, y: 0, width: Self.width, height: height ?? p.height))
        f.origin = NSPoint(x: w.frame.minX, y: w.frame.maxY - f.height)        // keep the top edge where it was
        w.setFrame(f, display: true, animate: animate && w.isVisible)
        let v = view(p)
        v.frame = container.bounds
        v.autoresizingMask = [.width, .height]
        container.addSubview(v)
        w.title = p.title
        w.toolbar?.selectedItemIdentifier = p.id
    }

    /// Evidence aid: `-settingsSnapshot <dir>` (SettingsSnapshot.swift) renders a pane (title bar + toolbar + the whole
    /// form: the window is made tall, the empty rest cropped) without screen-recording permission.
    func snapshot(_ p: Pane, to path: String) async {
        show(p)
        select(p, animate: false, height: 2600)
        try? await Task.sleep(for: .milliseconds(900))
        guard let frame = window?.contentView?.superview,
              let rep = frame.bitmapImageRepForCachingDisplay(in: frame.bounds) else { return log("settings snapshot: no window") }
        frame.cacheDisplay(in: frame.bounds, to: rep)
        try? Self.cropBottom(rep).representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
        select(p, animate: false)
        log("settings snapshot → \(path)")
    }

    /// The rows at the bottom that are only the form's background, cut off (leaving a margin).
    static func cropBottom(_ rep: NSBitmapImageRep) -> NSBitmapImageRep {
        let w = rep.pixelsWide, h = rep.pixelsHigh
        guard let bg = rep.colorAt(x: 2, y: h - 2) else { return rep }
        func same(_ c: NSColor?) -> Bool {
            guard let c = c?.usingColorSpace(.deviceRGB), let b = bg.usingColorSpace(.deviceRGB) else { return false }
            return abs(c.redComponent - b.redComponent) + abs(c.greenComponent - b.greenComponent) + abs(c.blueComponent - b.blueComponent) < 0.02
        }
        var last = h - 1
        rows: while last > 0 {
            for x in stride(from: 0, to: w, by: 3) where !same(rep.colorAt(x: x, y: last)) { break rows }
            last -= 1
        }
        let keep = min(h, last + 40)
        guard keep < h, let cg = rep.cgImage?.cropping(to: CGRect(x: 0, y: 0, width: w, height: keep)) else { return rep }
        return NSBitmapImageRep(cgImage: cg)
    }

    /// Evidence aid: the alert Details sheet for `kind`, rendered in a window of its own.
    func snapshotDetails(_ kind: AlertKind, to path: String) async {
        let host = NSHostingView(rootView: alertDetailsPreview(alertsModel, from, kind))
        host.frame = NSRect(origin: .zero, size: host.fittingSize)
        let w = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
        w.contentView = host
        w.orderFront(nil)
        try? await Task.sleep(for: .milliseconds(900))
        guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
        host.cacheDisplay(in: host.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
        w.orderOut(nil)
        log("settings snapshot → \(path)")
    }

    // MARK: NSToolbarDelegate

    func toolbar(_ toolbar: NSToolbar, itemForItemIdentifier id: NSToolbarItem.Identifier,
                 willBeInsertedIntoToolbar flag: Bool) -> NSToolbarItem? {
        guard let p = Pane(rawValue: id.rawValue) else { return nil }
        let item = NSToolbarItem(itemIdentifier: id)
        item.label = p.title
        item.image = NSImage(systemSymbolName: p.symbol, accessibilityDescription: p.title)
        item.target = self
        item.action = #selector(selectPane(_:))
        return item
    }
    @objc private func selectPane(_ sender: NSToolbarItem) {
        if let p = Pane(rawValue: sender.itemIdentifier.rawValue) { select(p, animate: true) }
    }
    func toolbarDefaultItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { Pane.allCases.map(\.id) }
    func toolbarAllowedItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { Pane.allCases.map(\.id) }
    func toolbarSelectableItemIdentifiers(_ toolbar: NSToolbar) -> [NSToolbarItem.Identifier] { Pane.allCases.map(\.id) }
}

// MARK: - Settings from

/// Settings from (schema `follower.settingsFrom`, WearerSettings.swift): Default, Wearer (the default) or Custom for
/// the mirrored settings (schema `"mirror": true`). Only Custom shows them as controls; Default and Wearer show a
/// read-only summary of what's in use (`MirroredSection`).
@MainActor final class SettingsFromModel: ObservableObject {
    @Published private(set) var mode = WearerSettings.mode()
    /// When the wearer's settings in use were made (their `u`), nil = nothing received.
    @Published private(set) var received: Date?
    /// The followed person's name; nil = not following anyone.
    @Published private(set) var wearer: String?
    var wearerName: () -> String? = { nil }
    var onOpenGeneral: (() -> Void)?
    /// Evidence aid (`-settingsSnapshot`): the followed person's name.
    static var previewWearer: String?

    func refresh() {
        mode = WearerSettings.mode()
        received = (WearerSettings.content()?["u"] as? NSNumber).map { Date(timeIntervalSince1970: $0.doubleValue) }
        wearer = Self.previewWearer ?? wearerName()
    }

    var custom: Bool { mode == "custom" }
    var modeBinding: Binding<String> {
        Binding(get: { self.mode }, set: { m in WearerSettings.setMode(m); self.mode = m })
    }
    /// "Ana", or "the wearer" without a name.
    var who: String { wearer.flatMap { $0.isEmpty ? nil : $0 } ?? "the wearer" }
    var wearerOption: String { wearer == nil ? "Wearer" : "\(who.prefix(1).uppercased() + who.dropFirst()) (wearer)" }
    var modeName: String { mode == "custom" ? "Custom" : mode == "default" ? "Default" : wearerOption }

    /// Where the mirrored settings come from now, in one sentence.
    var statusLine: String {
        switch mode {
        case "custom": return "This Mac's own settings, below and in the Graph, Rings and Alerts panes."
        case "default": return "Sukrara's defaults."
        default:
            guard wearer != nil else { return "Not following anyone, so Sukrara's defaults are used." }
            guard let r = received else { return "Waiting for \(who)'s settings. Sukrara's defaults are used until they arrive." }
            return "\(who.prefix(1).uppercased() + who.dropFirst())'s settings from their phone, changed \(RelativeDateTimeFormatter().localizedString(for: r, relativeTo: Date()))."
        }
    }

    func copyWearer() { WearerSettings.overwriteCustom(fromWearer: true) }
    func resetDefaults() { WearerSettings.overwriteCustom(fromWearer: false) }
}

/// In Default and Wearer: what's in use (read-only) and how to change it, instead of the mirrored controls.
struct MirroredSection: View {
    @ObservedObject var from: SettingsFromModel
    let rows: [(String, String)]

    var body: some View {
        Section {
            LabeledContent("Settings from", value: from.modeName)
            ForEach(rows, id: \.0) { r in LabeledContent(r.0, value: r.1) }
        } footer: {
            HStack(alignment: .firstTextBaseline) {
                Text(from.statusLine + " To change these on this Mac, choose Custom in General.")
                    .font(.callout).foregroundStyle(.secondary)
                Spacer()
                Button("Change…") { from.onOpenGeneral?() }
            }
        }
    }
}

// MARK: - General

@MainActor final class GeneralModel: ObservableObject {
    @Published private(set) var source = ""
    @Published private(set) var lastReading = ""
    @Published private(set) var problem: String?
    @Published private(set) var loginNote: String?
    @Published var openAtLogin = false {
        didSet {
            guard openAtLogin != oldValue, !loading else { return }
            do {
                if openAtLogin { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                log("open at login: \(error.localizedDescription)")
            }
            refresh()
        }
    }
    private var loading = false
    private let status: StatusController
    var onReconnect: (() -> Void)?

    init(status: StatusController) { self.status = status; refresh() }

    func refresh() {
        loading = true
        defer { loading = false }
        source = status.sourceText
        lastReading = status.snapshot.map {
            Clock.dateTime($0.latest.date) + " · "
                + RelativeDateTimeFormatter().localizedString(for: $0.latest.date, relativeTo: Date())
        } ?? "None yet"
        problem = status.lastError
        let st = SMAppService.mainApp.status
        openAtLogin = st == .enabled
        loginNote = switch st {
        case .requiresApproval: "Allow Sukrara in System Settings › General › Login Items."
        case .notFound: "Only available for the installed app."
        default: nil
        }
    }

    func reconnect() { onReconnect?(); refresh() }
    var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "development build" }
}

struct GeneralView: View {
    @ObservedObject var model: GeneralModel
    @ObservedObject var from: SettingsFromModel
    @State private var confirmCopy = false
    @State private var confirmReset = false

    var body: some View {
        Form {
            Section {
                Toggle("Open at login", isOn: $model.openAtLogin)
                    .disabled(model.loginNote != nil && !model.openAtLogin && SMAppService.mainApp.status == .notFound)
            } footer: {
                if let n = model.loginNote { Text(n).font(.callout).foregroundStyle(.secondary) }
            }
            Section {
                Picker("Settings from", selection: from.modeBinding) {
                    Text(from.wearerOption).tag("wearer")
                    Text("Default").tag("default")
                    Text("Custom").tag("custom")
                }
                LabeledContent("In use") { Text(from.statusLine).multilineTextAlignment(.trailing).foregroundStyle(.secondary) }
                if from.custom {
                    HStack {
                        Spacer()
                        Button("Copy the Wearer's Settings…") { confirmCopy = true }.disabled(from.received == nil)
                        Button("Reset to Defaults…") { confirmReset = true }
                    }
                }
            } header: { Text("Settings") } footer: {
                Text("Wearer and Default decide the ranges, the time shown, the rings, the lines, the last treatment, the sensor countdown and when to alert (thresholds, reminders, quiet times); Custom lets you set them on this Mac. Sounds, like an alarm, the no-data alert, the notification's buttons and each place's own window and sensor time are always this Mac's.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            Section("Glucose data") {
                LabeledContent("Source", value: model.source)
                LabeledContent("Last reading", value: model.lastReading)
                if let p = model.problem { LabeledContent("Last poll failed") { Text(p).foregroundStyle(.red) } }
                HStack {
                    Spacer()
                    Button("Reconnect Now") { model.reconnect() }
                        .help("Reconnect every source (Juggluco on the network, relays, Bluetooth)")
                }
            }
            Section {
                LabeledContent("Version", value: model.version)
                HStack { Spacer(); Button("Quit Sukrara") { NSApp.terminate(nil) } }
            } footer: {
                Text("Right-click the number in the menu bar for Glucose Report, Settings and Quit.")
                    .font(.callout).foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Copy \(from.who)'s settings?", isPresented: $confirmCopy) {
            Button("Copy Settings") { from.copyWearer() }
        } message: {
            Text("This Mac's ranges, time shown, rings, lines, last treatment, sensor countdown and alert rules become \(from.who)'s, as a starting point to customize. Sounds and the other settings of this Mac stay.")
        }
        .confirmationDialog("Reset to the defaults?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) { from.resetDefaults() }
        } message: {
            Text("This Mac's ranges, time shown, rings, lines, last treatment, sensor countdown and alert rules go back to Sukrara's defaults. Sounds and the other settings of this Mac stay.")
        }
    }
}

// MARK: - Graph and Rings

/// The graph settings (investigations 16 and 18) in UserDefaults (StatusController.graphKeys); the same
/// `GraphConfig.load` the card and the widgets read, so a change shows in the menu and the widgets at once.
@MainActor final class GraphModel: ObservableObject {
    /// What applies (Settings from); in Custom the same as this Mac's stored values, which these bindings write.
    @Published private(set) var c = GraphConfig.load()
    private let d = UserDefaults.standard
    var onChange: (() -> Void)?

    func reload() { c = GraphConfig.load() }
    private func set(_ key: String, _ v: Any?) {
        if let v { d.set(v, forKey: key) } else { d.removeObject(forKey: key) }
        reload()
        onChange?()
    }
    private func bind<T>(_ get: @escaping @MainActor (GraphConfig) -> T, _ set: @escaping @MainActor @Sendable (T) -> Void) -> Binding<T> {
        Binding(get: { get(self.c) }, set: set)
    }
    private func number(_ get: @escaping @MainActor (GraphConfig) -> Int, _ key: String) -> Binding<Double> {
        bind({ Double(get($0)) }) { self.set(key, Int($0)) }
    }

    var hours: Binding<Double> { number(\.hours, "graphHours") }
    /// A place's own window (`menubarHours`, `widgetHours`), 0 = same as the default: always this Mac's.
    func placeHours(_ place: String) -> Binding<Double> {
        Binding(get: { (self.d.object(forKey: "\(place)Hours") as? NSNumber)?.doubleValue ?? 0 },
                set: { v in self.set("\(place)Hours", v == 0 ? nil : Int(v)) })
    }
    var side: Binding<Bool> { bind(\.side) { self.set("side", $0) } }
    var columnCrown: Binding<Bool> { bind(\.columnCrown) { self.set("columnCrown", $0) } }
    var ringMarks: Binding<Bool> { bind(\.ringMarks) { self.set("ringMarks", $0) } }
    var gridHours: Binding<Double> { number(\.gridHours, "gridHours") }
    /// One part (point, line, amount, time) of one treatment kind on the graph (`marks`, MarkShow.text).
    func mark(_ k: String, _ part: String) -> Binding<Bool> {
        bind({ $0.marks.has(k, part) }) { v in self.set("marks", self.c.marks.with(k, part, v).text) }
    }
    var marksChanged: Bool { c.marks != .defaults }
    func resetMarks() { set("marks", nil) }
    var countdownHours: Binding<Double> { number(\.countdownHours, "countdownHours") }
    /// The sensor's time left per place: the dropdown (`menubarSensorTime`) and the widgets (`widgetSensorTime`).
    func sensorTime(_ place: String) -> Binding<Bool> {
        Binding(get: { self.d.object(forKey: "\(place)SensorTime") as? Bool ?? true },
                set: { v in self.set("\(place)SensorTime", v) })
    }
    var ringCount: Binding<Double> { number(\.ringCount, "ringCount") }
    var ringRange: Binding<TirRange> { bind(\.ringRange) { self.set("ringRange", $0.rawValue) } }
    var lastPlace: Binding<LastPlace> { bind(\.lastPlace) { self.set("lastPlace", $0.rawValue) } }

    private func setSlot(_ i: Int, _ f: (inout RingSpec) -> Void) {
        var s = c.slots; f(&s[i]); set("slots", GraphConfig.slotsText(s))
    }
    func kind(_ i: Int) -> Binding<SlotKind> { bind({ $0.slots[i].kind }) { v in self.setSlot(i) { $0.kind = v } } }
    func window(_ i: Int) -> Binding<Double> { bind({ $0.slots[i].window }) { v in self.setSlot(i) { $0.window = v } } }
    func faded(_ i: Int) -> Binding<Bool> { bind({ $0.slots[i].faded }) { v in self.setSlot(i) { $0.faded = v } } }

    /// "" = nothing, else the fallback's kind.
    var elseKind: Binding<String> {
        bind({ $0.lastElse?.kind.rawValue ?? "" }) { v in
            guard let k = SlotKind(rawValue: v) else { return self.set("lastElse", "") }
            var r = self.c.lastElse ?? GraphConfig.defaultLastElse; r.kind = k
            self.set("lastElse", GraphConfig.slotsText([r]))
        }
    }
    private func setElse(_ f: (inout RingSpec) -> Void) {
        var r = c.lastElse ?? GraphConfig.defaultLastElse; f(&r); set("lastElse", GraphConfig.slotsText([r]))
    }
    var elseWindow: Binding<Double> { bind({ $0.lastElse?.window ?? GraphConfig.defaultLastElse.window }) { v in self.setElse { $0.window = v } } }
    var elseFaded: Binding<Bool> { bind({ $0.lastElse?.faded ?? true }) { v in self.setElse { $0.faded = v } } }

    /// Ring order: the new order from the reorderable list (drag and drop, Move Up / Move Down).
    func setRingOrder(_ o: [Band]) { set("ringOrder", ringOrderText(o)) }
    func resetRingOrder() { set("ringOrder", nil) }

    func removeLine(_ l: Int) { set("lines", GraphConfig.linesText(c.lines.filter { $0 != l })) }
    func addLine(_ v: Double) { set("lines", GraphConfig.linesText(GraphConfig.parseLines(GraphConfig.linesText(c.lines + [Int(v)])))) }

    static let bandRows: [(String, String, NumberSpec, WritableKeyPath<Bands, Int>)] = [
        ("Very low below", "bandVeryLow", SettingsSpec.bandVeryLow, \.veryLow), ("Low below", "bandLow", SettingsSpec.bandLow, \.low),
        ("Very good up to", "bandTight", SettingsSpec.bandTight, \.tight), ("High above", "bandHigh", SettingsSpec.bandHigh, \.high),
        ("Very high above", "bandVeryHigh", SettingsSpec.bandVeryHigh, \.veryHigh),
        ("Extremely high from", "bandExtremelyHigh", SettingsSpec.bandExtremelyHigh, \.extremelyHigh),
    ]
    func band(_ key: String, _ kp: WritableKeyPath<Bands, Int>) -> Binding<Double> {
        bind({ Double($0.bands[keyPath: kp]) }) { v in self.set(key, Int(v)) }
    }
    var rangesChanged: Bool { Self.bandRows.contains { d.object(forKey: $0.1) != nil } }
    func resetRanges() { Self.bandRows.forEach { d.removeObject(forKey: $0.1) }; reload(); onChange?() }

    var anyChanged: Bool { StatusController.graphKeys.contains { d.object(forKey: $0) != nil } }
    func resetAll() { StatusController.graphKeys.forEach { d.removeObject(forKey: $0) }; reload(); onChange?() }

    // read-only summaries for Default / Wearer (MirroredSection)
    var graphSummary: [(String, String)] {
        let b = c.bands
        return [("Time shown", NumberFormat.label(Double(c.hours), SettingsSpec.graphHours)),
                ("Ranges", "\(b.veryLow) · \(b.low) · \(b.tight) · \(b.high) · \(b.veryHigh) · \(b.extremelyHigh) mg/dL"),
                ("Lines across the graph", c.lines.isEmpty ? "None" : c.lines.map(String.init).joined(separator: ", ") + " mg/dL"),
                ("Vertical lines", gridLabel(Double(c.gridHours))),
                ("Beside the graph", [c.side ? "time in range distribution" : nil, c.columnCrown ? "crown at 100 %" : nil].compactMap { $0 }.joined(separator: ", ").capitalizedFirst.ifEmpty("Nothing")),
                ("Treatments on the graph", MarkShow.kinds.map { k in
                    "\(markKindLabel(k)): " + MarkShow.parts(k).filter { c.marks.has(k, $0) }.map(markPartLabel).joined(separator: ", ").ifEmpty("nothing")
                }.joined(separator: "; ")),
                ("Sensor countdown", "Last \(c.countdownHours) h")]
    }
    var ringsSummary: [(String, String)] {
        let rings = c.slots.prefix(c.ringCount).map { "\($0.kind.label) \(periodLabel($0.window))" }
        return [("Rings and numbers", rings.isEmpty ? "None" : rings.joined(separator: ", ")),
                ("A ring's centre counts", c.ringRange.label),
                ("Ring order", c.ringOrder.map(\.label).joined(separator: ", ")),
                ("Rewards on the rings", c.ringMarks ? "On" : "Off"),
                ("Last treatment", c.lastPlace.label + (c.lastPlace == .off ? "" : c.lastElse.map { ", else \($0.kind.label) \(periodLabel($0.window))" } ?? ""))]
    }
}

/// A treatment kind on the graph settings ("Rapid insulin", …; Android graph_marks_kind_*).
func markKindLabel(_ k: String) -> String {
    switch k { case "rapid": "Rapid insulin"; case "long": "Long-acting insulin"; case "carbs": "Carbs"; default: "Notes" }
}
/// A part of a treatment's marks (Android graph_marks_point / line / amount / time).
func markPartLabel(_ p: String) -> String {
    switch p { case MarkShow.point: "Point"; case MarkShow.line: "Mute line"; case MarkShow.amount: "Amount label"; default: "Time label" }
}

/// "Off", "Every hour", "Every 3 hours".
func gridLabel(_ h: Double) -> String { h == 0 ? "Off" : h == 1 ? "Every hour" : "Every \(Int(h)) hours" }

extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
    func ifEmpty(_ s: String) -> String { isEmpty ? s : self }
}

struct GraphSettingsView: View {
    @ObservedObject var model: GraphModel
    @ObservedObject var from: SettingsFromModel
    @State private var confirmReset = false

    private var c: GraphConfig { model.c }
    /// The places with a window of their own (schema graph.hours.place, mac: menubarHours, widgetHours).
    static let places = [("menubar", "Menu bar dropdown"), ("widget", "Widgets")]

    var body: some View {
        Form {
            if !from.custom { MirroredSection(from: from, rows: model.graphSummary) }
            Section {
                if from.custom { NumberSettingRow(title: "Default", spec: SettingsSpec.graphHours, value: model.hours) }
                // per place (Android: App / Notification / Widgets): always this Mac's
                ForEach(Self.places, id: \.0) { p in
                    NumberSettingRow(title: p.1, spec: SettingsSpec.placeHours, value: model.placeHours(p.0), label: { v in
                        v == 0 ? "Same as default (\(NumberFormat.label(Double(c.hours), SettingsSpec.graphHours)))" : NumberFormat.label(v, SettingsSpec.placeHours)
                    })
                }
            } header: { Text("Time shown") } footer: {
                note("Each place can show a window of its own; \"Same as default\" follows the default.")
            }
            if from.custom {
                Section("Graph") {
                    Toggle("Time in range distribution", isOn: model.side)
                    Toggle("Crown at 100 % beside the graph", isOn: model.columnCrown)
                    NumberSettingRow(title: "Vertical lines", spec: SettingsSpec.gridHours, value: model.gridHours, label: gridLabel)
                }
                // Treatments on the graph: a row per kind, a checkbox per part it has (HIG Toggles, macOS: checkboxes for
                // independent options in a group; developer.apple.com/design/human-interface-guidelines/toggles). Every
                // graph (the dropdown, the widgets) uses these; mirrored (Settings from).
                Section {
                    Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                        GridRow {
                            Text("")
                            ForEach(MarkShow.allParts, id: \.self) { p in Text(markPartLabel(p)).foregroundStyle(.secondary).font(.caption) }
                        }
                        ForEach(MarkShow.kinds, id: \.self) { k in
                            GridRow {
                                Text(markKindLabel(k))
                                ForEach(MarkShow.allParts, id: \.self) { p in
                                    if MarkShow.parts(k).contains(p) {
                                        Toggle(markPartLabel(p), isOn: model.mark(k, p)).toggleStyle(.checkbox).labelsHidden()
                                            .accessibilityLabel("\(markKindLabel(k)): \(markPartLabel(p))")
                                    } else { Text("—").foregroundStyle(.tertiary).accessibilityHidden(true) }
                                }
                            }
                        }
                    }
                    HStack { Spacer(); Button("Reset Treatments") { model.resetMarks() }.disabled(!model.marksChanged) }
                } header: { Text("Treatments on the graph") } footer: {
                    note("Close treatments share one label, like 4u · 40g 1h 4m. Hidden amounts leave it, and the time shows once, since the newest treatment that shows its time.")
                }
                Section {
                    ForEach(c.lines, id: \.self) { l in
                        LabeledContent("\(l) mg/dL") {
                            Button(role: .destructive) { model.removeLine(l) } label: { Image(systemName: "minus.circle") }
                                .buttonStyle(.borderless)
                                .help("Remove this line")
                                .accessibilityLabel("Remove the line at \(l) mg/dL")
                        }
                    }
                    NumberAddRow(title: "Add a line at", spec: SettingsSpec.line, count: c.lines.count) { model.addLine($0) }
                } header: { Text("Lines across the graph") } footer: { note("Dashed lines at these levels.") }
                Section {
                    ForEach(GraphModel.bandRows, id: \.1) { row in
                        NumberSettingRow(title: row.0, spec: row.2, value: model.band(row.1, row.3))
                    }
                    HStack { Spacer(); Button("Reset Ranges") { model.resetRanges() }.disabled(!model.rangesChanged) }
                } header: { Text("Ranges") } footer: {
                    note("Used by the graph, the rings, the report and the menu bar colour, and kept in order.")
                }
            }
            Section {
                if from.custom {
                    // round sensor-life: the ring over the main ring in the sensor's last hours (Android: Settings › Sensor)
                    NumberSettingRow(title: "Sensor countdown", spec: SettingsSpec.countdownHours, value: model.countdownHours,
                                     label: { "Last " + NumberFormat.label($0, SettingsSpec.countdownHours) },
                                     subtitle: "A ring over the main ring counts down the sensor's last hours")
                }
                Toggle("Time left in the menu bar dropdown", isOn: model.sensorTime("menubar"))
                Toggle("Time left on widgets", isOn: model.sensorTime("widget"))
            } header: {
                Text("Sensor")
            } footer: {
                note("The time left shows at the start of the time row, under the % of time in range (a card without a graph shows just the number). The warm-up and \"Sensor ended\" show either way.")
            }
            if from.custom {
                Section {
                    HStack {
                        Spacer()
                        Button("Reset All Graph Settings…", role: .destructive) { confirmReset = true }.disabled(!model.anyChanged)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Reset all graph settings?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) { model.resetAll() }
        } message: {
            Text("The time shown, the rings, the lines, the ranges, the last-treatment slot and the sensor settings go back to their defaults.")
        }
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
}

/// Rings and numbers beside the value, the ring order and the last-treatment slot (Android: "Beside the number").
struct RingsSettingsView: View {
    @ObservedObject var model: GraphModel
    @ObservedObject var from: SettingsFromModel

    private var c: GraphConfig { model.c }
    private static let slotNames = ["Main", "Second", "Third"]

    var body: some View {
        Form {
            if !from.custom {
                MirroredSection(from: from, rows: model.ringsSummary)
            } else {
                Section {
                    NumberSettingRow(title: "Rings and numbers", spec: SettingsSpec.ringCount, value: model.ringCount)
                    ForEach(Array(c.slots.prefix(c.ringCount).enumerated()), id: \.offset) { i, _ in
                        Picker(Self.slotNames[i], selection: model.kind(i)) {
                            ForEach(SlotKind.allCases, id: \.self) { Text($0.label).tag($0) }
                        }
                        NumberSettingRow(title: "Period", spec: SettingsSpec.ringWindow, value: model.window(i)).padding(.leading, 16)
                        // Android: the main slot is always full strength; Fainter only for the second and third
                        if i > 0 { Toggle("Fainter", isOn: model.faded(i)).padding(.leading, 16) }
                    }
                    Picker("A ring's centre counts", selection: model.ringRange) {
                        ForEach(TirRange.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Toggle("Rewards on the rings (laurel, flowers, crown)", isOn: model.ringMarks)
                } header: { Text("Beside the value") }
                ringOrder
                Section("Last treatment") {
                    Picker("Position", selection: model.lastPlace) {
                        ForEach(LastPlace.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("Without a recent treatment", selection: model.elseKind) {
                        Text("Nothing").tag("")
                        ForEach(SlotKind.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
                    }
                    .disabled(c.lastPlace == .off)
                    if c.lastElse != nil, c.lastPlace != .off {
                        NumberSettingRow(title: "Period", spec: SettingsSpec.ringWindow, value: model.elseWindow).padding(.leading, 16)
                        Toggle("Fainter", isOn: model.elseFaded).padding(.leading, 16)
                    }
                }
            }
        }
        .formStyle(.grouped)
    }

    /// The ring order as a native reorderable list (RingOrderTable: NSTableView with gap feedback, like the Finder
    /// sidebar), Move Up / Move Down in each row's context menu and for VoiceOver, and Reset Order.
    private var ringOrder: some View {
        Section {
            RingOrderTable(order: c.ringOrder) { model.setRingOrder($0) }
                .frame(height: RingOrderTable.rowHeight * CGFloat(c.ringOrder.count))
            HStack { Spacer(); Button("Reset Order") { model.resetRingOrder() }.disabled(c.ringOrder == DEFAULT_RING_ORDER) }
        } header: { Text("Ring order") } footer: {
            Text("Clockwise from 12 o'clock. Drag a range to move it, or Control-click it for Move Up and Move Down.")
                .font(.callout).foregroundStyle(.secondary)
        }
    }
}

// MARK: - Following

@MainActor final class FollowingModel: ObservableObject {
    @Published private(set) var text: String?
    @Published private(set) var image: NSImage?
    @Published private(set) var treatmentsLine = ""
    /// How this Mac appears to the wearer (IdentityUI.swift); nil while not following.
    @Published private(set) var me: (name: String, avatar: [String: Any]?, wearer: String)?
    private let status: StatusController
    var identity: (() -> (name: String, avatar: [String: Any]?, wearer: String)?)?
    var onSaveIdentity: ((String, [String: Any]?) -> Void)?
    var onFollow: (() -> Void)?
    var onFollowQR: (() -> Void)?
    var onUnfollow: (() -> Void)?

    init(status: StatusController) { self.status = status; refresh() }

    func refresh() {
        text = status.followText
        image = status.followImage
        let p = status.treatmentPaths?() ?? TreatmentPaths()
        treatmentsLine = p.canLog ? (p.route == .v2 ? "Sent to the wearer" : "Kept on this Mac") : p.reason ?? "Not allowed by the wearer"
        me = identity?()
    }
}

struct FollowingView: View {
    @ObservedObject var model: FollowingModel
    @State private var confirmStop = false

    var body: some View {
        Form {
            if let text = model.text {
                Section {
                    HStack(spacing: 10) {
                        if let img = model.image { Image(nsImage: img).resizable().frame(width: 32, height: 32).clipShape(Circle()) }
                        Text(text).fixedSize(horizontal: false, vertical: true)
                    }
                    LabeledContent("Treatments logged here", value: model.treatmentsLine)
                }
                if let me = model.me {
                    Section {
                        IdentityRow(name: me.name, avatar: me.avatar, wearer: me.wearer) { model.onSaveIdentity?($0, $1) }
                    }
                }
                Section {
                    HStack {
                        Spacer()
                        Button("Stop Following…", role: .destructive) { confirmStop = true }
                    }
                } footer: {
                    Text("Deletes this Mac's keys for this person. To follow again you need a new invite.")
                        .font(.callout).foregroundStyle(.secondary)
                }
            } else {
                Section {
                    Text("Follow someone's glucose, end-to-end encrypted. In Sukrara on their phone, choose Share, then paste the invite link here or pick a screenshot of its QR code. Their phone asks them to allow this Mac.")
                        .fixedSize(horizontal: false, vertical: true)
                    HStack {
                        Spacer()
                        Button("Follow from QR Image…") { model.onFollowQR?() }
                        Button("Follow with Invite Link…") { model.onFollow?() }.keyboardShortcut(.defaultAction)
                    }
                } footer: {
                    Text("With Bluetooth on, a phone nearby with Pair Nearby open offers itself too.")
                        .font(.callout).foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Stop following?", isPresented: $confirmStop) {
            Button("Stop Following", role: .destructive) { model.onUnfollow?() }
        } message: {
            Text("This Mac stops receiving their readings, treatments and alerts.")
        }
    }
}

// MARK: - Main menu

/// A menubar-only (accessory) app shows no menu bar of its own, but its windows still route key equivalents through
/// `NSApp.mainMenu`: without one, ⌘W, ⌘Q and the Edit commands (⌘C / ⌘V in the invite-link field, ⌘Z) do nothing.
/// HIG "Keyboard" › Standard keyboard shortcuts: people expect them to work everywhere.
@MainActor enum MainMenu {
    /// The Settings… item's target (menus hold their target weakly, so it's kept here).
    private final class Target: NSObject {
        let open: () -> Void
        init(_ open: @escaping () -> Void) { self.open = open }
        @objc func settings() { open() }
    }
    private static var target: Target?

    static func install(settings: @escaping () -> Void) {
        let t = Target(settings)
        target = t
        let main = NSMenu()
        let appItem = NSMenuItem()
        let app = NSMenu(title: "Sukrara")
        app.addItem(withTitle: "Settings…", action: #selector(Target.settings), keyEquivalent: ",").target = t
        app.addItem(.separator())
        app.addItem(withTitle: "Close Window", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        app.addItem(withTitle: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = app
        main.addItem(appItem)
        let editItem = NSMenuItem()
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        main.addItem(editItem)
        NSApp.mainMenu = main
    }
}
