// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import ServiceManagement
import SwiftUI

/// Sukrara's Settings window (⌘, from the menu), per the HIG's macOS settings guidance
/// (developer.apple.com/design/human-interface-guidelines/settings):
/// - a non-customizable preference-style toolbar of panes that always shows the active one;
/// - the window title is the visible pane's name;
/// - minimize and zoom dimmed (titled + closable only); the window takes each pane's size, keeping its top edge;
/// - it reopens on the most recently viewed pane (`settingsPane`) at its last position (frame autosave);
/// - every change applies at once (no Save button), with standard controls in grouped forms.
/// Panes: General (login, data source), Graph (everything the old Graph submenu held, plus the ranges), Alerts
/// (AlertsUI.swift) and Following (protocol v2: follow, stop following).
@MainActor final class SettingsWindow: NSObject, NSToolbarDelegate {
    enum Pane: String, CaseIterable {
        case general, graph, alerts, following
        var title: String {
            switch self { case .general: "General"; case .graph: "Graph"; case .alerts: "Alerts"; case .following: "Following" }
        }
        var symbol: String {
            switch self { case .general: "gearshape"; case .graph: "chart.xyaxis.line"; case .alerts: "bell.badge"; case .following: "person.2" }
        }
        var height: CGFloat {
            switch self { case .general: 400; case .graph: 720; case .alerts: 720; case .following: 300 }
        }
        var id: NSToolbarItem.Identifier { .init(rawValue) }
    }
    static let width: CGFloat = 580

    let generalModel: GeneralModel
    let graphModel: GraphModel
    let alertsModel: AlertsModel
    let followingModel: FollowingModel
    private var window: NSWindow?
    private var views: [Pane: NSView] = [:]
    private var pane = Pane(rawValue: UserDefaults.standard.string(forKey: "settingsPane") ?? "") ?? .general

    init(general: GeneralModel, graph: GraphModel, alerts: AlertsModel, following: FollowingModel) {
        generalModel = general
        graphModel = graph
        alertsModel = alerts
        followingModel = following
        super.init()
    }

    func show(_ p: Pane? = nil) {
        let w = window ?? make()
        window = w
        generalModel.refresh(); graphModel.reload(); alertsModel.reload(); followingModel.refresh()
        select(p ?? pane, animate: false)
        NSApp.activate(ignoringOtherApps: true)
        w.makeKeyAndOrderFront(nil)
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
        case .general: host(GeneralView(model: generalModel))
        case .graph: host(GraphSettingsView(model: graphModel))
        case .alerts: host(AlertsView(model: alertsModel))
        case .following: host(FollowingView(model: followingModel))
        }
        views[p] = v
        return v
    }

    private func select(_ p: Pane, animate: Bool) {
        guard let w = window, let container = w.contentView else { return }
        pane = p
        UserDefaults.standard.set(p.rawValue, forKey: "settingsPane")
        container.subviews.forEach { $0.removeFromSuperview() }
        var f = w.frameRect(forContentRect: NSRect(x: 0, y: 0, width: Self.width, height: p.height))
        f.origin = NSPoint(x: w.frame.minX, y: w.frame.maxY - f.height)        // keep the top edge where it was
        w.setFrame(f, display: true, animate: animate && w.isVisible)
        let v = view(p)
        v.frame = container.bounds
        v.autoresizingMask = [.width, .height]
        container.addSubview(v)
        w.title = p.title
        w.toolbar?.selectedItemIdentifier = p.id
    }

    /// Evidence aid: `-settingsSnapshot <dir>` renders every pane (title bar + toolbar + form) to `<dir>/<pane>.png`
    /// without screen-recording permission; `-alertsSnapshot out.png` the Alerts pane alone.
    func snapshot(_ p: Pane, to path: String) {
        show(p)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
            guard let frame = self?.window?.contentView?.superview,
                  let rep = frame.bitmapImageRepForCachingDisplay(in: frame.bounds) else { return log("settings snapshot: no window") }
            frame.cacheDisplay(in: frame.bounds, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
            log("settings snapshot → \(path)")
        }
    }

    /// Evidence aid: the alert Details sheet for `kind`, rendered in a window of its own.
    func snapshotDetails(_ kind: AlertKind, to path: String) {
        let host = NSHostingView(rootView: alertDetailsPreview(alertsModel, kind))
        host.frame = NSRect(x: 0, y: 0, width: 460, height: 430)
        let w = NSWindow(contentRect: host.frame, styleMask: [.titled], backing: .buffered, defer: false)
        w.contentView = host
        w.orderFront(nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
            guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { return }
            host.cacheDisplay(in: host.bounds, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
            w.orderOut(nil)
            log("settings snapshot → \(path)")
        }
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

    var body: some View {
        Form {
            Section {
                Toggle("Open at login", isOn: $model.openAtLogin)
                    .disabled(model.loginNote != nil && !model.openAtLogin && SMAppService.mainApp.status == .notFound)
            } footer: {
                if let n = model.loginNote { Text(n).font(.callout).foregroundStyle(.secondary) }
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
    }
}

// MARK: - Graph

/// The graph settings (investigations 16 and 18) in UserDefaults keys (main.swift lists them); the same
/// `GraphConfig.load` the card and the widgets read, so a change shows in the menu and the widgets at once.
@MainActor final class GraphModel: ObservableObject {
    @Published private(set) var c = GraphConfig.load(place: "menubar")
    @Published var newLine: Int?
    private let d = UserDefaults.standard
    var onChange: (() -> Void)?

    func reload() { c = GraphConfig.load(place: "menubar") }
    private func set(_ key: String, _ v: Any?) {
        if let v { d.set(v, forKey: key) } else { d.removeObject(forKey: key) }
        if key == "graphHours" { d.removeObject(forKey: "menubarHours") }
        reload()
        onChange?()
    }
    private func bind<T>(_ get: @escaping @MainActor (GraphConfig) -> T, _ set: @escaping @MainActor @Sendable (T) -> Void) -> Binding<T> {
        Binding(get: { get(self.c) }, set: set)
    }

    var hours: Binding<Int> { bind(\.hours) { self.set("graphHours", $0) } }
    var side: Binding<Bool> { bind(\.side) { self.set("side", $0) } }
    var columnCrown: Binding<Bool> { bind(\.columnCrown) { self.set("columnCrown", $0) } }
    var ringMarks: Binding<Bool> { bind(\.ringMarks) { self.set("ringMarks", $0) } }
    var gridHours: Binding<Int> { bind(\.gridHours) { self.set("gridHours", $0) } }
    var countdownHours: Binding<Int> { bind(\.countdownHours) { self.set("countdownHours", $0) } }
    /// The sensor's time left per place: the dropdown (`menubarSensorTime`) and the widgets (`widgetSensorTime`).
    func sensorTime(_ place: String) -> Binding<Bool> {
        Binding(get: { self.d.object(forKey: "\(place)SensorTime") as? Bool ?? true },
                set: { v in self.set("\(place)SensorTime", v) })
    }
    var ringCount: Binding<Int> { bind(\.ringCount) { self.set("ringCount", $0) } }
    var ringRange: Binding<TirRange> { bind(\.ringRange) { self.set("ringRange", $0.rawValue) } }
    var lastPlace: Binding<LastPlace> { bind(\.lastPlace) { self.set("lastPlace", $0.rawValue) } }

    private func setSlot(_ i: Int, _ f: (inout RingSpec) -> Void) {
        var s = c.slots; f(&s[i]); set("slots", GraphConfig.slotsText(s))
    }
    func kind(_ i: Int) -> Binding<SlotKind> { bind({ $0.slots[i].kind }) { v in self.setSlot(i) { $0.kind = v } } }
    func window(_ i: Int) -> Binding<TimeInterval> { bind({ $0.slots[i].window }) { v in self.setSlot(i) { $0.window = v } } }
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
    var elseWindow: Binding<TimeInterval> { bind({ $0.lastElse?.window ?? 0 }) { v in self.setElse { $0.window = v } } }
    var elseFaded: Binding<Bool> { bind({ $0.lastElse?.faded ?? true }) { v in self.setElse { $0.faded = v } } }

    func moveRing(_ i: Int, _ delta: Int) {
        var o = c.ringOrder
        guard o.indices.contains(i + delta) else { return }
        o.swapAt(i, i + delta)
        set("ringOrder", ringOrderText(o))
    }
    func resetRingOrder() { set("ringOrder", nil) }

    func removeLine(_ l: Int) { set("lines", GraphConfig.linesText(c.lines.filter { $0 != l })) }
    func addLine() {
        guard let v = newLine, GraphConfig.lineRange.contains(v) else { return }
        set("lines", GraphConfig.linesText(GraphConfig.parseLines(GraphConfig.linesText(c.lines + [v]))))
        newLine = nil
    }
    var canAddLine: Bool { c.lines.count < GraphConfig.maxLines && newLine.map(GraphConfig.lineRange.contains) == true }

    static let bandRows: [(String, String, WritableKeyPath<Bands, Int>)] = [
        ("Very low below", "bandVeryLow", \.veryLow), ("Low below", "bandLow", \.low), ("Very good up to", "bandTight", \.tight),
        ("High above", "bandHigh", \.high), ("Very high above", "bandVeryHigh", \.veryHigh),
        ("Extremely high from", "bandExtremelyHigh", \.extremelyHigh),
    ]
    func band(_ key: String, _ kp: WritableKeyPath<Bands, Int>) -> Binding<Int> {
        bind({ $0.bands[keyPath: kp] }) { v in self.set(key, max(GlucoseStats.minMg, min(GlucoseStats.maxMg, v))) }
    }
    var rangesChanged: Bool { Self.bandRows.contains { d.object(forKey: $0.1) != nil } }
    func resetRanges() { Self.bandRows.forEach { d.removeObject(forKey: $0.1) }; reload(); onChange?() }

    var anyChanged: Bool { StatusController.graphKeys.contains { d.object(forKey: $0) != nil } }
    func resetAll() { StatusController.graphKeys.forEach { d.removeObject(forKey: $0) }; reload(); onChange?() }

    /// The presets plus the current value when it's a custom one (Android's "Custom" days, 1–90).
    func periods(_ current: TimeInterval) -> [TimeInterval] {
        GraphConfig.ringChoices.contains(current) || current <= 0 ? GraphConfig.ringChoices : (GraphConfig.ringChoices + [current]).sorted()
    }
}

struct GraphSettingsView: View {
    @ObservedObject var model: GraphModel
    @State private var confirmReset = false

    private var c: GraphConfig { model.c }

    var body: some View {
        Form {
            Section("Graph") {
                Picker("Time shown", selection: model.hours) {
                    ForEach(GraphConfig.windowHours, id: \.self) { Text("\($0) hours").tag($0) }
                }
                Toggle("Time in range beside the graph", isOn: model.side)
                Toggle("Crown at 100 % beside the graph", isOn: model.columnCrown)
                Toggle("Rewards on the rings (laurel, flowers, crown)", isOn: model.ringMarks)
                Picker("Vertical lines", selection: model.gridHours) {
                    ForEach(GraphConfig.gridChoices, id: \.self) { h in Text(h == 0 ? "Off" : h == 1 ? "Every hour" : "Every \(h) hours").tag(h) }
                }
                // round sensor-life: the ring over the main ring in the sensor's last hours (Android: Settings › Sensor)
                Stepper(value: model.countdownHours, in: SensorLife.countdownRange) {
                    // the menubar's "sensor ended" flag, so the two read as one thing
                    LabeledContent { Text("Last \(c.countdownHours) h") } label: { Label("Sensor countdown", systemImage: "flag.checkered") }
                }
                .help("A ring over the main ring counts down the sensor's last hours")
            }
            Section {
                Toggle("In the menu bar dropdown", isOn: model.sensorTime("menubar"))
                Toggle("On widgets", isOn: model.sensorTime("widget"))
            } header: {
                Text("Sensor time left")
            } footer: {
                Text("At the start of the time row, under the % of time in range (beside the arrow where a card has no graph). The warm-up and \"Sensor ended\" show either way.")
                    .foregroundStyle(.secondary)
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
                HStack {
                    LabeledContent("Add a line at") {
                        TextField("Add a line at", value: $model.newLine, format: .number, prompt: Text("mg/dL"))
                            .labelsHidden().textFieldStyle(.roundedBorder).frame(width: 80).multilineTextAlignment(.trailing)
                            .onSubmit { model.addLine() }
                    }
                    Button("Add") { model.addLine() }.disabled(!model.canAddLine)
                }
                .disabled(c.lines.count >= GraphConfig.maxLines)
            } header: { Text("Lines across the graph") } footer: {
                note("Dashed lines at these levels, \(GraphConfig.lineRange.lowerBound)–\(GraphConfig.lineRange.upperBound) mg/dL, up to \(GraphConfig.maxLines).")
            }
            Section("Beside the value") {
                Picker("Rings and numbers", selection: model.ringCount) {
                    Text("None").tag(0)
                    ForEach(1...3, id: \.self) { Text("\($0)").tag($0) }
                }
                ForEach(0..<c.ringCount, id: \.self) { i in
                    Picker(["Main", "Second", "Third"][i], selection: model.kind(i)) {
                        ForEach(SlotKind.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Picker("Period", selection: model.window(i)) {
                        ForEach(model.periods(c.slots[i].window), id: \.self) { Text(periodLabel($0)).tag($0) }
                    }
                    .padding(.leading, 16)
                    Toggle("Fainter", isOn: model.faded(i)).padding(.leading, 16)
                }
                Picker("A ring's centre counts", selection: model.ringRange) {
                    ForEach(TirRange.allCases, id: \.self) { Text($0.label).tag($0) }
                }
            }
            Section {
                ForEach(Array(c.ringOrder.enumerated()), id: \.offset) { i, band in
                    LabeledContent("\(i + 1). \(band.label)") {
                        HStack(spacing: 2) {
                            Button { model.moveRing(i, -1) } label: { Image(systemName: "chevron.up") }
                                .disabled(i == 0).help("Move earlier").accessibilityLabel("Move \(band.label) earlier")
                            Button { model.moveRing(i, 1) } label: { Image(systemName: "chevron.down") }
                                .disabled(i == c.ringOrder.count - 1).help("Move later").accessibilityLabel("Move \(band.label) later")
                        }
                        .buttonStyle(.borderless)
                    }
                }
                HStack { Spacer(); Button("Reset Order") { model.resetRingOrder() }.disabled(c.ringOrder == DEFAULT_RING_ORDER) }
            } header: { Text("Ring order") } footer: { note("Clockwise from 12 o'clock.") }
            Section("Last treatment") {
                Picker("Position", selection: model.lastPlace) {
                    ForEach(LastPlace.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Picker("Without a recent treatment", selection: model.elseKind) {
                    Text("Nothing").tag("")
                    ForEach(SlotKind.allCases, id: \.self) { Text($0.label).tag($0.rawValue) }
                }
                .disabled(c.lastPlace == .off)
                if let e = c.lastElse, c.lastPlace != .off {
                    Picker("Period", selection: model.elseWindow) {
                        ForEach(model.periods(e.window), id: \.self) { Text(periodLabel($0)).tag($0) }
                    }
                    .padding(.leading, 16)
                    Toggle("Fainter", isOn: model.elseFaded).padding(.leading, 16)
                }
            }
            Section {
                ForEach(GraphModel.bandRows, id: \.1) { row in
                    LabeledContent(row.0) {
                        HStack(spacing: 4) {
                            TextField(row.0, value: model.band(row.1, row.2), format: .number)
                                .labelsHidden().textFieldStyle(.roundedBorder).frame(width: 56).multilineTextAlignment(.trailing)
                            Stepper(row.0, value: model.band(row.1, row.2), step: 1).labelsHidden()
                            Text("mg/dL").foregroundStyle(.secondary)
                        }
                    }
                }
                HStack { Spacer(); Button("Reset Ranges") { model.resetRanges() }.disabled(!model.rangesChanged) }
            } header: { Text("Ranges") } footer: {
                note("Used by the graph, the rings, the report and the menu bar colour, and kept in order.")
            }
            Section {
                HStack {
                    Spacer()
                    Button("Reset All Graph Settings…", role: .destructive) { confirmReset = true }.disabled(!model.anyChanged)
                }
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Reset all graph settings?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) { model.resetAll() }
        } message: {
            Text("The window, rings, lines, ranges and last-treatment slot go back to their defaults.")
        }
    }

    private func note(_ s: String) -> some View { Text(s).font(.callout).foregroundStyle(.secondary) }
}

// MARK: - Following

@MainActor final class FollowingModel: ObservableObject {
    @Published private(set) var text: String?
    @Published private(set) var image: NSImage?
    @Published private(set) var treatmentsLine = ""
    private let status: StatusController
    var onFollow: (() -> Void)?
    var onFollowQR: (() -> Void)?
    var onUnfollow: (() -> Void)?

    init(status: StatusController) { self.status = status; refresh() }

    func refresh() {
        text = status.followText
        image = status.followImage
        let p = status.treatmentPaths?() ?? TreatmentPaths()
        treatmentsLine = p.canLog ? (p.route == .v2 ? "Sent to the wearer" : "Kept on this Mac") : "Not allowed by the wearer"
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
