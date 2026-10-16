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
    private let chart = SparklineView(frame: NSRect(x: 0, y: 0, width: 260, height: 70))

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
    var onUploadNow: (() -> Void)?
    var libreViewStatus: (() -> String)?
    private let treatmentsHeader = NSMenuItem(), lvLine = NSMenuItem()
    private var treatmentItems: [NSMenuItem] = []

    private(set) var snapshot: Snapshot?
    private var lastError: String?
    private var tick: Timer?, staleTimer: Timer?

    override init() {
        super.init()
        item.button?.imagePosition = .imageTrailing
        item.button?.title = "…"
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
        for line in [treatmentsHeader, lvLine] { line.isEnabled = false }
        menu.addItem(withTitle: "Log treatment…", action: #selector(logTreatment), keyEquivalent: "l").target = self
        menu.addItem(treatmentsHeader)
        menu.addItem(lvLine)
        menu.addItem(withTitle: "Upload to LibreView now", action: #selector(uploadNow), keyEquivalent: "").target = self
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
        menu.addItem(withTitle: "Quit", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
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

    /// nil hides the line (not following anyone over v2).
    func setFollow(_ text: String?) {
        followLine.isHidden = text == nil
        followLine.title = text ?? ""
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

    func treatmentsChanged() { renderTreatments() }

    private func renderTreatments() {
        treatmentItems.forEach(menu.removeItem)
        treatmentItems = []
        let hidden = treatments == nil
        for i in menu.items where i.action == #selector(logTreatment) || i.action == #selector(uploadNow) { i.isHidden = hidden }
        treatmentsHeader.isHidden = hidden
        lvLine.isHidden = hidden
        guard let rows = treatments?() else { return }
        let since = Int64(Date().timeIntervalSince1970) - 24 * 3600
        let recent = rows.filter { !$0.del && $0.t >= since }.sorted { $0.t > $1.t }
        treatmentsHeader.title = recent.isEmpty ? "No treatments in the last 24 h" : "Treatments (24 h)"
        lvLine.title = libreViewStatus?() ?? ""
        var at = menu.index(of: treatmentsHeader) + 1
        for t in recent {
            let row = NSMenuItem(title: Self.describe(t), action: nil, keyEquivalent: "")
            let sub = NSMenu()
            let del = NSMenuItem(title: "Delete", action: #selector(deleteTreatment(_:)), keyEquivalent: "")
            del.target = self
            del.representedObject = t.id
            sub.addItem(del)
            row.submenu = sub
            menu.insertItem(row, at: at)
            treatmentItems.append(row)
            at += 1
        }
    }

    /// "14:05  2.5 U rapid  ✓" — ✓ on LibreView, ⇡ waiting to upload.
    static func describe(_ t: Treatment) -> String {
        let time = Date(timeIntervalSince1970: TimeInterval(t.t)).formatted(date: .omitted, time: .shortened)
        let what: String
        switch t.k {
        case "rapid", "long": what = String(format: "%.1f U %@", t.a, t.k)
        case "carbs": what = "\(Int(t.a)) g carbs"
        default: what = "note"
        }
        let note = t.n.isEmpty ? "" : (t.k == "note" ? ": " : " · ") + t.n
        return "\(time)  \(what)\(note)  \(t.needsUpload ? "⇡" : "✓")"
    }

    @objc private func uploadNow() { onUploadNow?() }

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

/// Last 3h of readings, drawn in the dropdown.
final class SparklineView: NSView {
    var readings: [Reading] = [] { didSet { needsDisplay = true } }
    var thresholds = Thresholds()
    let span: TimeInterval = 3 * 3600

    override func draw(_ dirtyRect: NSRect) {
        let r = bounds.insetBy(dx: 14, dy: 8)
        guard let end = readings.last?.date else { return }
        let pts = readings.filter { end.timeIntervalSince($0.date) <= span }
        let lo = 40.0, hi = max(250.0, (pts.map(\.mgdl).max() ?? 0) + 10)
        func y(_ v: Double) -> CGFloat { r.minY + CGFloat((v - lo) / (hi - lo)) * r.height }
        func x(_ d: Date) -> CGFloat { r.maxX - CGFloat(end.timeIntervalSince(d) / span) * r.width }

        NSColor.systemGreen.withAlphaComponent(0.12).setFill()
        NSRect(x: r.minX, y: y(70), width: r.width, height: y(180) - y(70)).fill()

        for p in pts {
            (thresholds.color(for: p.mgdl) ?? .labelColor).setFill()
            NSBezierPath(ovalIn: NSRect(x: x(p.date) - 1.25, y: y(p.mgdl) - 1.25, width: 2.5, height: 2.5)).fill()
        }
    }
}
