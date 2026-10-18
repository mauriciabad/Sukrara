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
    /// The glance (HIG "The menu bar" › Menu bar extras: a menu, not a popover): the card, one status block, the
    /// 24 h treatments with "Log Treatment…", then the report, Settings… and Quit. Everything that configures the
    /// app lives in the Settings window (HIG "Settings": general, infrequently changed options; ⌘,).
    private let chartItem = NSMenuItem()
    private let chart = CardView(frame: NSRect(x: 0, y: 0, width: 368, height: 212))
    private let infoItem = NSMenuItem()
    private let info = InfoView()
    private let treatmentsHeader = NSMenuItem.sectionHeader(title: "Treatments (24 h)")
    private let logItem = NSMenuItem(title: "Log Treatment…", action: nil, keyEquivalent: "l")
    private let followItem = NSMenuItem(title: "Follow Someone…", action: nil, keyEquivalent: "")

    var unit: GlucoseUnit = .mgdl
    /// Investigation 16: ranges, window, rings, side (UserDefaults, `menubarHours` over `graphHours`).
    private(set) var graph = GraphConfig.load(place: "menubar")
    /// The 15-day history the card and its rings draw from (ReadingsStore).
    var history: (() -> [GraphReading])?
    /// A window's numbers (rings, gauges, the report) from the readings store.
    var metricsOf: ((TimeInterval, Bands) -> GlucoseMetrics)?
    /// The graph settings changed (Settings › Graph): the widget feed re-publishes.
    var onGraphChange: (() -> Void)?
    /// A new reading or source state: the open Settings and Report windows follow.
    var onUpdate: (() -> Void)?
    var onFollow: (() -> Void)?
    var onSettings: (() -> Void)?
    var onReport: (() -> Void)?

    /// The protocol v2 follow state line ("Following Ana · key epoch 2 · 4 relays"), nil when not following.
    private(set) var followText: String?
    private(set) var followImage: NSImage?
    var onFollowChange: (() -> Void)?

    // Treatments (spikes/libreview/SPEC.md). Section hidden while `treatments` is nil.
    var treatments: (() -> [Treatment])?
    /// The alert rules behind the card's badge, mute lines and last-treatment slot (AlertRules.swift).
    var alertSettings: (() -> AlertSettings)?
    var onLogTreatment: ((Treatment) -> Void)?
    var onDeleteTreatment: ((String) -> Void)?
    /// Which paths a new entry takes and whether this Mac may log at all (v2 writer gating, NEXT §1 item 1).
    var treatmentPaths: (() -> TreatmentPaths)?
    /// Per-entry delivery status ("BT ✓ · v2 accepted ✓ · LV ⇡") and its long form for the row's submenu.
    var treatmentStatus: ((Treatment) -> String)?
    var treatmentDetails: ((Treatment) -> [String])?
    private var treatmentItems: [NSMenuItem] = []
    /// The menu lists at most this many of the last 24 h's entries (HIG "Menus": be mindful of menu length).
    static let maxTreatmentRows = 6

    private(set) var snapshot: Snapshot?
    private(set) var lastError: String?
    private var tick: Timer?, freshTimer: Timer?

    override init() {
        super.init()
        item.button?.imagePosition = .imageTrailing
        item.button?.title = "…"
        item.button?.toolTip = "Sukrara"
        chartItem.view = chart
        infoItem.view = info
        menu.addItem(chartItem)
        menu.addItem(infoItem)
        menu.addItem(.separator())
        menu.addItem(treatmentsHeader)
        logItem.target = self
        logItem.action = #selector(logTreatment)
        menu.addItem(logItem)
        followItem.target = self
        followItem.action = #selector(follow)
        menu.addItem(followItem)
        menu.addItem(.separator())
        menu.addItem(withTitle: "Glucose Report…", action: #selector(report), keyEquivalent: "").target = self
        menu.addItem(withTitle: "Settings…", action: #selector(settings), keyEquivalent: ",").target = self
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        menu.autoenablesItems = false
        menu.delegate = self
        item.menu = menu
        // Increase Contrast / Reduce Transparency switch the card's palette and platter at once.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.chart.needsDisplay = true }
        }
        render()
    }

    func update(_ snap: Snapshot) {
        snapshot = snap
        lastError = nil
        render()
        onUpdate?()
    }

    func fail(_ message: String) {
        lastError = message
        render()
        onUpdate?()
    }

    @objc private func follow() { onFollow?() }
    @objc private func settings() { onSettings?() }
    @objc private func report() { onReport?() }

    /// nil = not following anyone over v2.
    func setFollow(_ text: String?, image: NSImage? = nil) {
        followText = text
        followImage = image
        render()
        onFollowChange?()
    }

    // The card redraws while the menu is open (its graph moves with the clock); closed, only the freshness timer runs.
    func menuWillOpen(_ menu: NSMenu) {
        render()
        let t = Timer(timeInterval: 15, repeats: true) { [weak self] _ in self?.render() }
        RunLoop.main.add(t, forMode: .common)     // .common: menus track in event-tracking mode
        tick = t
    }

    func menuDidClose(_ menu: NSMenu) {
        tick?.invalidate(); tick = nil
    }

    /// "Bluetooth · Tartaruga" or "No data yet"; the Settings window's General pane shows it too.
    var sourceText: String { snapshot.map { $0.source } ?? "No data yet" }

    private func render() {
        renderTreatments()
        renderInfo()
        guard let button = item.button else { return }
        // First run: nothing to show and nobody followed yet, so the way in sits in the menu itself.
        followItem.isHidden = snapshot != nil || followText != nil
        guard let snap = snapshot else {
            button.attributedTitle = NSAttributedString(string: lastError == nil ? "…" : "⚠︎")
            button.image = nil
            chartItem.isHidden = true
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
        button.setAccessibilityLabel(fresh.showValue ? "Glucose \(unit.format(snap.latest.mgdl)) \(unit == .mgdl ? "mg/dL" : "mmol/L")" : "Glucose: no recent reading")

        // The card carries the value, its time when late, the rings and the graph: no separate lines.
        chartItem.isHidden = false
        var g = graph.graphStyle()
        g.hour24 = GraphStyle.systemHour24
        chart.style = graph.cardStyle(graph: g)
        chart.alerts = alertSettings?()
        var pts = history?() ?? snap.history.map { GraphReading(t: $0.date.timeIntervalSince1970, v: $0.mgdl, rate: $0.rate) }
        if !pts.isEmpty { pts[pts.count - 1].rate = rate }
        chart.readings = pts
        chart.marks = marks()
        chart.metricsOf = metricsOf.map { f in { spec, b in f(spec.window, b) } }
        chart.needsDisplay = true
        if let path = UserDefaults.standard.string(forKey: "snapshot") {
            if let button = item.button { dump(button, to: path) }
            dump(chart, to: path.replacingOccurrences(of: ".png", with: "-chart.png"))
        }
    }

    /// Under the card: where the readings come from, the follow state, a failed poll, and why logging is off.
    private func renderInfo() {
        var lines: [(String, NSColor)] = []
        if let snap = snapshot { lines.append(("Source: \(snap.source)", .secondaryLabelColor)) }
        else { lines.append(("No data yet", .secondaryLabelColor)) }
        if let f = followText { lines.append((f, f.hasPrefix("⚠︎") ? .systemOrange : .secondaryLabelColor)) }
        if let e = lastError { lines.append(((snapshot == nil ? "Error: " : "Last poll failed: ") + e, .systemRed)) }
        if treatments != nil, let p = treatmentPaths?(), !p.canLog { lines.append((TreatmentPaths.notAllowed, .secondaryLabelColor)) }
        info.set(lines, image: followImage)
    }

    private func marks() -> [GraphMark] {
        (treatments?() ?? []).filter { !$0.del }.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
    }

    /// Every graph setting (UserDefaults); "Reset All Graph Settings" removes them.
    static let graphKeys = ["graphHours", "menubarHours", "widgetHours", "ringCount", "slots", "ringRange", "ringOrder",
                            "bandVeryLow", "bandLow", "bandTight", "bandHigh", "bandVeryHigh", "bandExtremelyHigh", "side", "lines", "gridHours", "lastPlace", "lastElse"]

    /// Settings changed (Settings › Graph or `defaults write`): redraw and re-publish to the widgets.
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

    /// The alert settings changed: the badge, the mute lines and the slot follow them.
    func alertsChanged() {
        chart.alerts = alertSettings?()
    }

    private func renderTreatments() {
        treatmentItems.forEach(menu.removeItem)
        treatmentItems = []
        let hidden = treatments == nil
        logItem.isHidden = hidden
        guard let rows = treatments?() else { treatmentsHeader.isHidden = true; return }
        // Where a new entry goes: v2 (as a roster writer), else nowhere (local when following no one); the info
        // block under the card says why the item is dimmed.
        let paths = treatmentPaths?() ?? TreatmentPaths()
        logItem.isEnabled = paths.canLog
        logItem.toolTip = paths.canLog ? paths.lines.first : TreatmentPaths.notAllowed
        let since = Int64(Date().timeIntervalSince1970) - 24 * 3600
        let recent = rows.filter { !$0.del && $0.t >= since }.sorted { $0.t > $1.t }
        treatmentsHeader.isHidden = recent.isEmpty
        var at = menu.index(of: treatmentsHeader) + 1
        for t in recent.prefix(Self.maxTreatmentRows) {
            let state = treatmentStatus?(t) ?? ""
            let row = NSMenuItem(title: Self.describe(t), action: nil, keyEquivalent: "")
            // The delivery state in the secondary label colour, so the entry itself reads first.
            let title = NSMutableAttributedString(string: Self.describe(t), attributes: [.font: NSFont.menuFont(ofSize: 0)])
            if !state.isEmpty {
                title.append(NSAttributedString(string: "   " + state, attributes: [.font: NSFont.menuFont(ofSize: 0),
                                                                                    .foregroundColor: NSColor.secondaryLabelColor]))
            }
            row.attributedTitle = title
            let sub = NSMenu()
            sub.autoenablesItems = false
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
            if !sub.items.isEmpty { row.submenu = sub } else { row.isEnabled = false }
            menu.insertItem(row, at: at)
            treatmentItems.append(row)
            at += 1
        }
    }

    /// "14:05  2.5u rapid · 12m" (amounts lowercase without a space, the time since the entry last; delivery status
    /// comes from `treatmentStatus`).
    static func describe(_ t: Treatment, now: Date = Date()) -> String {
        let time = Date(timeIntervalSince1970: TimeInterval(t.t)).formatted(date: .omitted, time: .shortened)
        let what = TreatmentMarks.amount(k: t.k, a: t.a).map { "\($0) \(t.k)" } ?? "note"
        let note = t.n.isEmpty ? "" : (t.k == "note" ? ": " : " · ") + t.n
        return "\(time)  \(what)\(note) · \(TreatmentMarks.duration(now.timeIntervalSince1970 - TimeInterval(t.t)))"
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
        amount.placeholderString = "u or g"
        let note = NSTextField(frame: NSRect(x: 74, y: 28, width: 226, height: 22))
        note.placeholderString = "optional"
        let when = NSDatePicker(frame: NSRect(x: 74, y: 0, width: 226, height: 24))
        when.datePickerElements = [.yearMonthDay, .hourMinute]
        when.dateValue = Date()
        label("Kind", 86); label("Amount", 56); label("Note", 28); label("When", 0)
        [kind, amount, note, when].forEach(view.addSubview)

        let alert = NSAlert()
        alert.messageText = "Log Treatment"
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

/// The graph on Mac surfaces that sit on a translucent material (the menubar dropdown, the report window).
///
/// The shared palette (SukraraGraph.swift) is tuned for an opaque ground — the Android app and widgets, the Mac
/// widgets — so here the card gets that ground back as a platter: HIG "Materials" (thicker, more opaque materials give
/// better contrast for fine features; the graph's range colours can't be vibrant) and "Accessibility" (WCAG AA
/// contrast; a higher-contrast variant when Increase Contrast is on). Mac-only: Android has no menu material.
enum MacSurface {
    static var increaseContrast: Bool { NSWorkspace.shared.accessibilityDisplayShouldIncreaseContrast }
    static var reduceTransparency: Bool { NSWorkspace.shared.accessibilityDisplayShouldReduceTransparency }

    /// The shared palette; with Increase Contrast the secondary ink and the grid get much stronger (≥ 7:1 and ≥ 3:1
    /// against the platter instead of ~5:1 and ~1.5:1).
    static func palette(dark: Bool, increaseContrast hc: Bool = increaseContrast) -> GraphPalette {
        var p = GraphPalette.of(dark: dark)
        if hc {
            p.inkSoft = dark ? GraphPalette.hex(0xD8D8DC) : GraphPalette.hex(0x3C3C40)
            p.grid = dark ? GraphPalette.hex(0x7C7C82) : GraphPalette.hex(0x8A8A90)
        }
        return p
    }

    /// The platter: the palette's own background (the ground every colour was checked against), 94 % opaque so
    /// the menu still reads as one surface, fully opaque with Reduce Transparency or Increase Contrast (which also
    /// gets a hairline border, like the system's own high-contrast outlines).
    static func drawPlatter(_ ctx: CGContext, _ rect: CGRect, _ pal: GraphPalette, radius: CGFloat = 10,
                            reduceTransparency rt: Bool = reduceTransparency, increaseContrast hc: Bool = increaseContrast) {
        let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
        ctx.addPath(path)
        ctx.setFillColor(pal.bg.copy(alpha: rt || hc ? 1 : 0.94) ?? pal.bg)
        ctx.fillPath()
        if hc {
            ctx.addPath(CGPath(roundedRect: rect.insetBy(dx: 0.5, dy: 0.5), cornerWidth: radius, cornerHeight: radius, transform: nil))
            ctx.setStrokeColor(pal.ink.copy(alpha: 0.45) ?? pal.ink)
            ctx.setLineWidth(1)
            ctx.strokePath()
        }
    }
}

/// The lines under the card: source, follow state, a failed poll, why logging is off. Real labels in the system's
/// label colours (vibrant on the menu material, adapted by Increase Contrast), not disabled menu items, whose dimmed
/// text is meant for unavailable commands (HIG "Menus").
final class InfoView: NSView {
    private let stack = NSStackView()
    private let icon = NSImageView()

    init() {
        super.init(frame: NSRect(x: 0, y: 0, width: 368, height: 20))
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 2
        stack.translatesAutoresizingMaskIntoConstraints = false
        addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: topAnchor, constant: 4),
        ])
    }
    required init?(coder: NSCoder) { fatalError() }

    func set(_ lines: [(String, NSColor)], image: NSImage?) {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, (text, color)) in lines.enumerated() {
            let f = NSTextField(wrappingLabelWithString: text)
            f.font = .menuFont(ofSize: NSFont.smallSystemFontSize + 1)
            f.textColor = color
            f.preferredMaxLayoutWidth = 340
            f.isSelectable = false
            // The follow line carries the wearer's avatar, like the phone's follower list.
            if i == 1, let image, !text.hasPrefix("Last poll") {
                let iv = NSImageView(image: image)
                iv.setContentHuggingPriority(.required, for: .horizontal)
                let row = NSStackView(views: [iv, f])
                row.spacing = 5
                stack.addArrangedSubview(row)
            } else {
                stack.addArrangedSubview(f)
            }
        }
        layoutSubtreeIfNeeded()
        setFrameSize(NSSize(width: 368, height: stack.fittingSize.height + 8))
    }
}

/// The dropdown's glucose card (investigation 16): value, trend arrow, time-in-range rings and the graph with its side
/// profile, drawn by the shared renderer (SukraraGraph.swift) like the widgets and Sukrara for Android.
final class CardView: NSView {
    var readings: [GraphReading] = [] { didSet { needsDisplay = true } }
    var marks: [GraphMark] = [] { didSet { needsDisplay = true } }
    var style = CardStyle()
    var metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)?
    var alerts: AlertSettings? { didSet { needsDisplay = true } }
    /// Tests / previews: draw as if this were now.
    var now: Date? = nil
    var inset = NSEdgeInsets(top: 10, left: 16, bottom: 8, right: 16)
    /// Horizontal inset of the platter from the view's edge; nil = no platter (previews of the old look).
    var platterInset: CGFloat? = 5
    /// Previews: force the accessibility display options instead of reading the system's.
    var increaseContrast: Bool?, reduceTransparency: Bool?

    override var isFlipped: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let dark = effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let hc = increaseContrast ?? MacSurface.increaseContrast
        var pal = MacSurface.palette(dark: dark, increaseContrast: hc)
        if platterInset == nil { pal = GraphPalette.of(dark: dark); pal.bg = dark ? GraphPalette.hex(0x2A2A2C) : GraphPalette.hex(0xF0F0F0) }
        // The platter sits where the menu draws its rounded selection, 5 pt in from the menu's edge.
        if let p = platterInset {
            MacSurface.drawPlatter(ctx, bounds.insetBy(dx: p, dy: 0), pal,
                                   reduceTransparency: reduceTransparency ?? MacSurface.reduceTransparency, increaseContrast: hc)
        }
        let r = NSRect(x: inset.left, y: inset.top, width: bounds.width - inset.left - inset.right,
                       height: bounds.height - inset.top - inset.bottom)
        ctx.saveGState()
        ctx.translateBy(x: r.minX, y: r.minY)
        GlucoseCard.draw(ctx, size: r.size, readings: readings, marks: marks, now: (now ?? Date()).timeIntervalSince1970,
                         style, pal, metricsOf: metricsOf, alerts: alerts)
        ctx.restoreGState()
    }

    override func viewDidChangeEffectiveAppearance() { needsDisplay = true }
}
