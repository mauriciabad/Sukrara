// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit

struct Thresholds {
    // Defaults taken from the LibreLinkUp xbar plugin config (mg/dL).
    var low = 65.0, slightlyHigh = 145.0, high = 185.0
    var staleAfter: TimeInterval = 5 * 60

    func color(for mgdl: Double) -> NSColor? {
        if mgdl < low { return .systemRed }
        if mgdl > high { return .systemRed }
        if mgdl > slightlyHigh { return .systemOrange }
        return nil
    }
}

final class StatusController: NSObject, NSMenuDelegate {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    private let menu = NSMenu()
    private let valueLine = NSMenuItem(), timeLine = NSMenuItem(), sourceLine = NSMenuItem(), errorLine = NSMenuItem()
    private let chartItem = NSMenuItem()
    private let chart = SparklineView(frame: NSRect(x: 0, y: 0, width: 300, height: 104))

    var unit: GlucoseUnit = .mgdl
    var thresholds = Thresholds()
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
    private var tick: Timer?, staleTimer: Timer?

    override init() {
        super.init()
        item.button?.imagePosition = .imageTrailing
        item.button?.title = "…"
        item.button?.toolTip = "Sukrara"
        for line in [valueLine, timeLine, sourceLine, errorLine] { line.isEnabled = false }
        chartItem.view = chart
        menu.addItem(valueLine)
        menu.addItem(timeLine)
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

    // The "(n min ago)" line only ticks while the menu is open; closed, nothing runs between readings.
    func menuWillOpen(_ menu: NSMenu) {
        render()
        let t = Timer(timeInterval: 15, repeats: true) { [weak self] _ in self?.render() }
        RunLoop.main.add(t, forMode: .common)     // .common: menus track in event-tracking mode
        tick = t
    }

    func menuDidClose(_ menu: NSMenu) { tick?.invalidate(); tick = nil }

    private func render() {
        renderTreatments()
        guard let button = item.button else { return }
        guard let snap = snapshot else {
            button.attributedTitle = NSAttributedString(string: lastError == nil ? "…" : "⚠︎")
            button.image = nil
            valueLine.title = "No data yet"
            timeLine.isHidden = true
            chartItem.isHidden = true
            sourceLine.isHidden = true
            errorLine.isHidden = lastError == nil
            errorLine.title = "Error: " + (lastError ?? "")
            return
        }
        let age = Date().timeIntervalSince(snap.latest.date)
        let stale = age > thresholds.staleAfter
        // Instead of polling: one timer for the moment the reading goes stale (re-armed by every new reading).
        staleTimer?.invalidate()
        staleTimer = stale ? nil : Timer.scheduledTimer(withTimeInterval: thresholds.staleAfter - age + 1,
                                                        repeats: false) { [weak self] _ in self?.render() }
        staleTimer?.tolerance = 10
        let rate = snap.rate
        let color = stale ? NSColor.secondaryLabelColor : thresholds.color(for: snap.latest.mgdl)

        var attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
        ]
        if let color { attrs[.foregroundColor] = color }
        if stale { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        button.attributedTitle = NSAttributedString(string: unit.format(snap.latest.mgdl), attributes: attrs)
        button.image = TrendArrow.image(rate: stale ? nil : rate, size: 15, color: color)

        valueLine.title = "\(unit.format(snap.latest.mgdl)) \(unit == .mgdl ? "mg/dL" : "mmol/L")"
            + (rate.map { "   " + unit.formatRate($0) } ?? "   trend unknown")
        timeLine.isHidden = false
        timeLine.title = "at \(snap.latest.date.formatted(date: .omitted, time: .standard))  (\(Self.ago(age)))"
        chartItem.isHidden = snap.history.count < 2
        chart.readings = snap.history
        chart.treatments = (treatments?() ?? []).filter { !$0.del }
        chart.thresholds = thresholds
        sourceLine.isHidden = false
        sourceLine.title = "Source: \(snap.source)"
        errorLine.isHidden = lastError == nil
        errorLine.title = "Last poll failed: " + (lastError ?? "")
        if let path = UserDefaults.standard.string(forKey: "snapshot") {
            if let button = item.button { dump(button, to: path) }
            dump(chart, to: path.replacingOccurrences(of: ".png", with: "-chart.png"))
        }
    }

    // MARK: treatments

    func treatmentsChanged() {
        renderTreatments()
        chart.treatments = (treatments?() ?? []).filter { !$0.del }
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

    static func ago(_ s: TimeInterval) -> String {
        s < 90 ? "\(Int(s))s ago" : s < 5400 ? "\(Int(s / 60))m ago" : "\(Int(s / 3600))h ago"
    }
}

/// Last 3h of readings, drawn in the dropdown, with the treatments of that window (NEXT §1 item 4) in the same
/// language as the Android charts: rapid insulin = filled blue drop-triangle "4.5U", long = outlined indigo
/// triangle, both in a lane along the top; carbs = amber dot "30g" along the bottom; notes = grey tick + a faint
/// line through the chart. System colours, so the menu's light/dark appearance carries over.
final class SparklineView: NSView {
    var readings: [Reading] = [] { didSet { needsDisplay = true } }
    var treatments: [Treatment] = [] { didSet { needsDisplay = true } }
    var thresholds = Thresholds()
    let span: TimeInterval = 3 * 3600
    /// Tests / previews: draw as if this were the time of the newest reading.
    var end: Date? = nil

    static let rapid = NSColor.systemBlue, long = NSColor.systemIndigo, carbs = NSColor.systemOrange, note = NSColor.systemGray

    override func draw(_ dirtyRect: NSRect) {
        let lane: CGFloat = 15                                   // top: insulin, bottom: carbs
        let r = NSRect(x: bounds.minX + 14, y: bounds.minY + lane + 4, width: bounds.width - 28, height: bounds.height - 2 * lane - 10)
        guard let end = end ?? readings.last?.date else { return }
        let pts = readings.filter { end.timeIntervalSince($0.date) <= span && $0.date <= end }
        let lo = 40.0, hi = max(250.0, (pts.map(\.mgdl).max() ?? 0) + 10)
        func y(_ v: Double) -> CGFloat { r.minY + CGFloat((v - lo) / (hi - lo)) * r.height }
        func x(_ d: Date) -> CGFloat { r.maxX - CGFloat(end.timeIntervalSince(d) / span) * r.width }

        NSColor.systemGreen.withAlphaComponent(0.12).setFill()
        NSRect(x: r.minX, y: y(70), width: r.width, height: y(180) - y(70)).fill()

        // Treatments in the window (a few minutes past the newest reading still count: logged "now").
        let shown = treatments.filter {
            let d = Date(timeIntervalSince1970: TimeInterval($0.t))
            return end.timeIntervalSince(d) <= span && d.timeIntervalSince(end) <= 15 * 60
        }.sorted { $0.t < $1.t }
        for t in shown where t.k == "note" {                    // behind the readings
            let tx = min(r.maxX, x(Date(timeIntervalSince1970: TimeInterval(t.t))))
            Self.note.withAlphaComponent(0.35).setStroke()
            let line = NSBezierPath()
            line.move(to: NSPoint(x: tx, y: r.minY)); line.line(to: NSPoint(x: tx, y: r.maxY))
            line.lineWidth = 1
            line.setLineDash([2, 2], count: 2, phase: 0)
            line.stroke()
        }

        for p in pts {
            (thresholds.color(for: p.mgdl) ?? .labelColor).setFill()
            NSBezierPath(ovalIn: NSRect(x: x(p.date) - 1.25, y: y(p.mgdl) - 1.25, width: 2.5, height: 2.5)).fill()
        }

        // Markers + amount labels; a label that would overlap the previous one in its lane is dropped (the marker stays).
        var lastLabelEnd: [String: CGFloat] = [:]
        for t in shown {
            let tx = min(r.maxX, x(Date(timeIntervalSince1970: TimeInterval(t.t))))
            let laneKey: String, label: String, color: NSColor, my: CGFloat
            switch t.k {
            case "rapid", "long":
                laneKey = "top"; color = t.k == "rapid" ? Self.rapid : Self.long
                label = Self.amount(t.a) + "U"; my = bounds.maxY - lane / 2 - 2
                Self.triangle(at: NSPoint(x: tx, y: my), filled: t.k == "rapid", color: color)
            case "carbs":
                laneKey = "bottom"; color = Self.carbs
                label = "\(Int(t.a.rounded()))g"; my = bounds.minY + lane / 2 + 2
                color.setFill()
                NSBezierPath(ovalIn: NSRect(x: tx - 3.5, y: my - 3.5, width: 7, height: 7)).fill()
            default:
                laneKey = "top"; color = Self.note; label = ""; my = bounds.maxY - lane / 2 - 2
                color.setFill()
                NSRect(x: tx - 0.75, y: my - 5, width: 1.5, height: 10).fill()
            }
            guard !label.isEmpty else { continue }
            let s = NSAttributedString(string: label, attributes: [
                .font: NSFont.monospacedDigitSystemFont(ofSize: 9, weight: .semibold), .foregroundColor: color])
            let size = s.size()
            var lx = tx + 5
            if lx + size.width > bounds.maxX - 2 { lx = tx - 5 - size.width }      // flip left at the right edge
            if lx < (lastLabelEnd[laneKey] ?? -.infinity) + 2 { continue }
            s.draw(at: NSPoint(x: lx, y: my - size.height / 2))
            lastLabelEnd[laneKey] = lx + size.width
        }
    }

    static func amount(_ a: Double) -> String { a == a.rounded() ? String(Int(a)) : String(format: "%.1f", a) }

    /// Insulin marker: a downward triangle (a drop going in), filled for rapid, outlined for long-acting.
    static func triangle(at c: NSPoint, filled: Bool, color: NSColor) {
        let p = NSBezierPath()
        p.move(to: NSPoint(x: c.x - 4.5, y: c.y + 4))
        p.line(to: NSPoint(x: c.x + 4.5, y: c.y + 4))
        p.line(to: NSPoint(x: c.x, y: c.y - 4.5))
        p.close()
        if filled { color.setFill(); p.fill() } else { color.setStroke(); p.lineWidth = 1.5; p.stroke() }
    }
}
