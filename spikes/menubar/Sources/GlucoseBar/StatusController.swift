// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

extension Bands {
    /// Menubar number colour (investigation 16): below range (low or very low) red, above (high or very high) orange,
    /// in range the menubar's own ink.
    func color(for mgdl: Double) -> NSColor? {
        switch of(mgdl).group {
        case 0: .systemRed
        case 2: .systemOrange
        default: nil
        }
    }
}

final class StatusController: NSObject, NSMenuDelegate {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private let valueLine = NSMenuItem(), sourceLine = NSMenuItem(), errorLine = NSMenuItem()
    private let chartItem = NSMenuItem()
    private let chart = CardView(frame: NSRect(x: 0, y: 0, width: 340, height: 200))

    var unit: GlucoseUnit = .mgdl
    /// Investigation 16: ranges, window, rings, side (UserDefaults, `menubarHours` over `graphHours`).
    private(set) var graph = GraphConfig.load(place: "menubar")
    /// The 15-day history the card and its rings draw from (ReadingsStore).
    var history: (() -> [GraphReading])?
    var ringTir: ((RingSpec, Bands) -> TimeInRange)?
    /// The graph settings changed in the "Graph" submenu (the widget feed re-publishes).
    var onGraphChange: (() -> Void)?
    var onRefresh: (() -> Void)?

    // Protocol v2 follower (NostrV2.swift): "Follow someone…" / status line / "Stop following".
    var onFollow: (() -> Void)?
    var onFollowQR: (() -> Void)?
    var onUnfollow: (() -> Void)?
    private let followLine = NSMenuItem()
    private var unfollowItem: NSMenuItem?

    // Treatments (spikes/libreview/SPEC.md). Section hidden while `treatments` is nil.
    var treatments: (() -> [Treatment])?
    var onLogTreatment: ((Treatment) -> Void)?
    var onDeleteTreatment: ((String) -> Void)?
    /// Which paths a new entry takes and whether this Mac may log at all (v2 writer gating, NEXT §1 item 1).
    var treatmentPaths: (() -> TreatmentPaths)?
    /// Per-entry delivery status ("BT ✓ · v2 accepted ✓ · LV ⇡") and its long form for the row's submenu.
    var treatmentStatus: ((Treatment) -> String)?
    var treatmentDetails: ((Treatment) -> [String])?
    private let treatmentsHeader = NSMenuItem()
    private var treatmentItems: [NSMenuItem] = [], pathItems: [NSMenuItem] = []
    private var logItem: NSMenuItem?

    private(set) var snapshot: Snapshot?
    private var lastError: String?
    private var tick: Timer?, freshTimer: Timer?

    override init() {
        super.init()
        item.button?.imagePosition = .imageTrailing
        item.button?.title = "…"
        item.button?.toolTip = "Sukrara"
        for line in [valueLine, sourceLine, errorLine] { line.isEnabled = false }
        chartItem.view = chart
        menu.addItem(valueLine)
        menu.addItem(chartItem)
        menu.addItem(sourceLine)
        menu.addItem(errorLine)
        followLine.isEnabled = false
        followLine.isHidden = true
        menu.addItem(followLine)
        menu.addItem(.separator())
        treatmentsHeader.isEnabled = false
        let log = menu.addItem(withTitle: "Log treatment…", action: #selector(logTreatment), keyEquivalent: "l")
        log.target = self
        logItem = log
        menu.addItem(treatmentsHeader)
        menu.addItem(.separator())
        menu.addItem(withTitle: "Follow someone…", action: #selector(follow), keyEquivalent: "f").target = self
        menu.addItem(withTitle: "Follow from QR image…", action: #selector(followQR), keyEquivalent: "").target = self
        let stop = NSMenuItem(title: "Stop following", action: #selector(unfollow), keyEquivalent: "")
        stop.target = self
        stop.isHidden = true
        menu.addItem(stop)
        unfollowItem = stop
        menu.addItem(.separator())
        let graphItem = NSMenuItem(title: "Graph", action: nil, keyEquivalent: "")
        graphItem.submenu = graphMenu
        menu.addItem(graphItem)
        menu.addItem(withTitle: "Refresh now", action: #selector(refresh), keyEquivalent: "r").target = self
        menu.addItem(withTitle: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.delegate = self
        item.menu = menu
        render()
    }

    func update(_ snap: Snapshot) {
        snapshot = snap
        lastError = nil
        render()
    }

    func fail(_ message: String) {
        lastError = message
        render()
    }

    @objc private func refresh() { onRefresh?() }
    @objc private func follow() { onFollow?() }
    @objc private func followQR() { onFollowQR?() }
    @objc private func unfollow() { onUnfollow?() }

    /// An extra item ("Alerts…") right after the follow section.
    func addItem(_ item: NSMenuItem) {
        guard let stop = unfollowItem, let i = menu.items.firstIndex(of: stop) else { return menu.addItem(item) }
        menu.insertItem(item, at: i + 1)
    }

    /// nil hides the line (not following anyone over v2).
    func setFollow(_ text: String?, image: NSImage? = nil) {
        followLine.isHidden = text == nil
        followLine.title = text ?? ""
        followLine.image = image
        unfollowItem?.isHidden = text == nil
    }

    // The card redraws while the menu is open (its graph moves with the clock); closed, only the freshness timer runs.
    func menuWillOpen(_ menu: NSMenu) {
        guard menu === self.menu else { return }
        render()
        let t = Timer(timeInterval: 15, repeats: true) { [weak self] _ in self?.render() }
        RunLoop.main.add(t, forMode: .common)     // .common: menus track in event-tracking mode
        tick = t
    }

    func menuDidClose(_ menu: NSMenu) {
        guard menu === self.menu else { return }
        tick?.invalidate(); tick = nil
    }

    private func render() {
        renderTreatments()
        guard let button = item.button else { return }
        guard let snap = snapshot else {
            button.attributedTitle = NSAttributedString(string: lastError == nil ? "…" : "⚠︎")
            button.image = nil
            valueLine.isHidden = false
            valueLine.title = "No data yet"
            chartItem.isHidden = true
            sourceLine.isHidden = true
            errorLine.isHidden = lastError == nil
            errorLine.title = "Error: " + (lastError ?? "")
            return
        }
        let age = Date().timeIntervalSince(snap.latest.date)
        let fresh = Freshness.of(age: age)
        // Instead of polling: one timer for the next freshness step (re-armed by every new reading).
        freshTimer?.invalidate()
        freshTimer = Freshness.nextChange(age: age).map { next in
            Timer.scheduledTimer(withTimeInterval: next - age, repeats: false) { [weak self] _ in self?.render() }
        }
        freshTimer?.tolerance = 5
        let rate = snap.rate
        let color = fresh.strike ? NSColor.secondaryLabelColor : graph.bands.color(for: snap.latest.mgdl)

        var attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
        ]
        if let color { attrs[.foregroundColor] = color }
        if fresh.strike { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        // Old (> 20 min): no value at all, just a dash; the dropdown says since when.
        button.attributedTitle = NSAttributedString(string: fresh.showValue ? unit.format(snap.latest.mgdl) : "–", attributes: attrs)
        button.image = fresh.showValue ? TrendArrow.image(rate: fresh.strike ? nil : rate, size: 15, color: color) : nil
        button.toolTip = "Sukrara · reading at \(snap.latest.date.formatted(date: .omitted, time: .shortened))"

        // The card carries the value, its time when late, the rings and the graph: no separate lines.
        valueLine.isHidden = true
        chartItem.isHidden = false
        var g = graph.graphStyle()
        g.hour24 = GraphStyle.systemHour24
        chart.style = CardStyle(graph: g, rings: graph.rings, value: true)
        var pts = history?() ?? snap.history.map { GraphReading(t: $0.date.timeIntervalSince1970, v: $0.mgdl, rate: $0.rate) }
        if !pts.isEmpty { pts[pts.count - 1].rate = rate }
        chart.readings = pts
        chart.marks = marks()
        chart.tirOf = ringTir
        chart.needsDisplay = true
        sourceLine.isHidden = false
        sourceLine.title = "Source: \(snap.source)"
        errorLine.isHidden = lastError == nil
        errorLine.title = "Last poll failed: " + (lastError ?? "")
        if let path = UserDefaults.standard.string(forKey: "snapshot") {
            if let button = item.button { dump(button, to: path) }
            dump(chart, to: path.replacingOccurrences(of: ".png", with: "-chart.png"))
        }
    }

    private func marks() -> [GraphMark] {
        (treatments?() ?? []).filter { !$0.del }.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
    }

    // MARK: graph settings (investigation 16)

    private lazy var graphMenu: NSMenu = {
        let m = NSMenu()
        m.delegate = self
        return m
    }()

    func menuNeedsUpdate(_ menu: NSMenu) {
        guard menu === graphMenu else { return }
        menu.removeAllItems()
        func header(_ t: String) { let i = menu.addItem(withTitle: t, action: nil, keyEquivalent: ""); i.isEnabled = false }
        func option(_ t: String, _ on: Bool, _ key: String, _ value: Any) {
            let i = menu.addItem(withTitle: t, action: #selector(setGraph(_:)), keyEquivalent: "")
            i.target = self
            i.state = on ? .on : .off
            i.indentationLevel = 1
            i.representedObject = [key, value]
        }
        header("Graph window")
        for h in GraphConfig.windowHours { option("\(h) h", graph.hours == h, "graphHours", h) }
        header("Time in range rings")
        for n in 0...3 { option(n == 0 ? "None" : "\(n): " + graph.ringWindows.prefix(n).map(periodLabel).joined(separator: " + "), graph.ringCount == n, "ringCount", n) }
        for r in TirRange.allCases { option("Number: \(r.label)", graph.ringRange == r, "ringRange", r.rawValue) }
        menu.addItem(.separator())
        option("Time in range beside the graph", graph.side, "side", !graph.side)
        let b = graph.bands
        let note = menu.addItem(withTitle: "Ranges \(b.veryLow) · \(b.low) · \(b.tight) · \(b.high) · \(b.veryHigh) (defaults write band…)",
                                action: nil, keyEquivalent: "")
        note.isEnabled = false
    }

    @objc private func setGraph(_ sender: NSMenuItem) {
        guard let kv = sender.representedObject as? [Any], let key = kv.first as? String else { return }
        UserDefaults.standard.set(kv[1], forKey: key)
        if key == "graphHours" { UserDefaults.standard.removeObject(forKey: "menubarHours") }
        reloadGraph()
    }

    /// Settings changed (submenu or `defaults write`): redraw and re-publish to the widgets.
    func reloadGraph() {
        graph = GraphConfig.load(place: "menubar")
        render()
        onGraphChange?()
    }

    // MARK: treatments

    func treatmentsChanged() {
        renderTreatments()
        chart.marks = marks()
    }

    private func renderTreatments() {
        treatmentItems.forEach(menu.removeItem)
        treatmentItems = []
        pathItems.forEach(menu.removeItem)
        pathItems = []
        let hidden = treatments == nil
        for i in menu.items where i.action == #selector(logTreatment) { i.isHidden = hidden }
        treatmentsHeader.isHidden = hidden
        guard let rows = treatments?() else { return }
        // Where a new entry goes: v2 (as a roster writer), else nowhere (local when following no one).
        let paths = treatmentPaths?() ?? TreatmentPaths()
        logItem?.isEnabled = paths.canLog
        logItem?.title = "Log treatment…"
        if let logItem {
            var at = menu.index(of: logItem) + 1
            for line in (paths.canLog ? [] : [TreatmentPaths.notAllowed]) + paths.lines {
                let item = NSMenuItem(title: line, action: nil, keyEquivalent: "")
                item.isEnabled = false
                item.indentationLevel = 1
                menu.insertItem(item, at: at)
                pathItems.append(item)
                at += 1
            }
        }
        let since = Int64(Date().timeIntervalSince1970) - 24 * 3600
        let recent = rows.filter { !$0.del && $0.t >= since }.sorted { $0.t > $1.t }
        treatmentsHeader.title = recent.isEmpty ? "No treatments in the last 24 h" : "Treatments (24 h)"
        var at = menu.index(of: treatmentsHeader) + 1
        for t in recent {
            let state = treatmentStatus?(t) ?? ""
            let row = NSMenuItem(title: Self.describe(t) + (state.isEmpty ? "" : "   " + state), action: nil, keyEquivalent: "")
            let sub = NSMenu()
            for line in treatmentDetails?(t) ?? [] {
                let item = NSMenuItem(title: line, action: nil, keyEquivalent: "")
                item.isEnabled = false
                sub.addItem(item)
            }
            if paths.canLog {
                if !sub.items.isEmpty { sub.addItem(.separator()) }
                let del = NSMenuItem(title: "Delete", action: #selector(deleteTreatment(_:)), keyEquivalent: "")
                del.target = self
                del.representedObject = t.id
                sub.addItem(del)
            }
            row.submenu = sub
            menu.insertItem(row, at: at)
            treatmentItems.append(row)
            at += 1
        }
    }

    /// "14:05  2.5 U rapid" (delivery status comes from `treatmentStatus`).
    static func describe(_ t: Treatment) -> String {
        let time = Date(timeIntervalSince1970: TimeInterval(t.t)).formatted(date: .omitted, time: .shortened)
        let what: String
        switch t.k {
        case "rapid", "long": what = String(format: "%.1f U %@", t.a, t.k)
        case "carbs": what = "\(Int(t.a)) g carbs"
        default: what = "note"
        }
        let note = t.n.isEmpty ? "" : (t.k == "note" ? ": " : " · ") + t.n
        return "\(time)  \(what)\(note)"
    }


    @objc private func deleteTreatment(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        onDeleteTreatment?(id)
    }

    @objc private func logTreatment() {
        let kinds = [("Rapid insulin", "rapid"), ("Long insulin", "long"), ("Carbs", "carbs"), ("Note", "note")]
        let view = NSView(frame: NSRect(x: 0, y: 0, width: 300, height: 112))
        func label(_ s: String, _ y: CGFloat) {
            let l = NSTextField(labelWithString: s)
            l.frame = NSRect(x: 0, y: y + 3, width: 70, height: 17)
            view.addSubview(l)
        }
        let kind = NSPopUpButton(frame: NSRect(x: 74, y: 84, width: 226, height: 26))
        kind.addItems(withTitles: kinds.map(\.0))
        let amount = NSTextField(frame: NSRect(x: 74, y: 56, width: 80, height: 22))
        amount.placeholderString = "U or g"
        let note = NSTextField(frame: NSRect(x: 74, y: 28, width: 226, height: 22))
        note.placeholderString = "optional"
        let when = NSDatePicker(frame: NSRect(x: 74, y: 0, width: 226, height: 24))
        when.datePickerElements = [.yearMonthDay, .hourMinute]
        when.dateValue = Date()
        label("Kind", 86); label("Amount", 56); label("Note", 28); label("When", 0)
        [kind, amount, note, when].forEach(view.addSubview)

        let alert = NSAlert()
        alert.messageText = "Log treatment"
        let paths = treatmentPaths?() ?? TreatmentPaths()
        guard paths.canLog else { return }               // the menu item is disabled then; a shortcut could still land here
        alert.informativeText = paths.lines.isEmpty ? "Kept on this Mac (no phone linked)." : paths.lines.joined(separator: "\n")
        alert.accessoryView = view
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")
        alert.window.initialFirstResponder = amount
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return }

        let k = kinds[kind.indexOfSelectedItem].1
        let raw = Double(amount.stringValue.replacingOccurrences(of: ",", with: ".")) ?? 0
        let a = k == "note" ? 0 : k == "carbs" ? raw.rounded() : (raw * 10).rounded() / 10
        guard k == "note" ? !note.stringValue.isEmpty : a > 0 else { return }
        onLogTreatment?(Treatment(k: k, a: a, t: Int64(when.dateValue.timeIntervalSince1970), n: note.stringValue))
    }

    /// Debug aid: render a view to a PNG (no screen-recording permission needed).
    private func dump(_ view: NSView, to path: String) {
        view.layoutSubtreeIfNeeded()
        guard let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return }
        view.cacheDisplay(in: view.bounds, to: rep)
        try? rep.representation(using: .png, properties: [:])?.write(to: URL(fileURLWithPath: path))
    }
}

/// The dropdown's glucose card (investigation 16): value, trend arrow, time-in-range rings and the graph with its side
/// profile, drawn by the shared renderer (SukraraGraph.swift) like the widgets and Sukrara for Android.
final class CardView: NSView {
    var readings: [GraphReading] = [] { didSet { needsDisplay = true } }
    var marks: [GraphMark] = [] { didSet { needsDisplay = true } }
    var style = CardStyle()
    var tirOf: ((RingSpec, Bands) -> TimeInRange)?
    /// Tests / previews: draw as if this were now.
    var now: Date? = nil
    var inset = NSEdgeInsets(top: 6, left: 14, bottom: 4, right: 14)

    override var isFlipped: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let dark = effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let r = NSRect(x: inset.left, y: inset.top, width: bounds.width - inset.left - inset.right,
                       height: bounds.height - inset.top - inset.bottom)
        var pal = GraphPalette.of(dark: dark)
        pal.bg = dark ? GraphPalette.hex(0x2A2A2C) : GraphPalette.hex(0xF0F0F0)   // the menu's material, for the last dot's halo
        ctx.saveGState()
        ctx.translateBy(x: r.minX, y: r.minY)
        GlucoseCard.draw(ctx, size: r.size, readings: readings, marks: marks, now: (now ?? Date()).timeIntervalSince1970,
                         style, pal, tirOf: tirOf)
        ctx.restoreGState()
    }
}
