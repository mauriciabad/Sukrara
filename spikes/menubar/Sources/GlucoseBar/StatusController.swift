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

/// The menu bar item: the number + trend arrow; a click opens the glance panel (Panel.swift), a right-click a short
/// menu (Glucose Report…, Settings…, Quit). Everything that configures the app lives in Settings (HIG "Settings").
@MainActor final class StatusController: NSObject {
    private let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
    let panelModel = PanelModel()
    private(set) lazy var panel = GlancePanel(model: panelModel)
    private let contextMenu = NSMenu()

    var unit: GlucoseUnit = .mgdl
    /// Investigation 16: ranges, window, rings, side (UserDefaults, `menubarHours` over `graphHours`).
    private(set) var graph = GraphConfig.load(place: "menubar")
    /// The 15-day history the card and its rings draw from (ReadingsStore).
    var history: (() -> [GraphReading])?
    /// A window's numbers (rings, gauges, the report) from the readings store.
    var metricsOf: ((TimeInterval, Bands) -> GlucoseMetrics)? {
        didSet { panelModel.metricsOf = metricsOf.map { f in { spec, b in f(spec.window, b) } } }
    }
    /// The graph settings changed (Settings › Graph): the widget feed re-publishes.
    var onGraphChange: (() -> Void)?
    /// A new reading or source state: the open Settings and Report windows follow.
    var onUpdate: (() -> Void)?
    var onFollow: (() -> Void)?
    var onSettings: (() -> Void)?
    var onReport: (() -> Void)?

    /// The protocol v2 follow state line ("Following Ana · key epoch 2 · 4 relays"), nil when not following; shown
    /// in Settings › Following only.
    private(set) var followText: String?
    private(set) var followImage: NSImage?
    var onFollowChange: (() -> Void)?

    // Treatments (spikes/libreview/SPEC.md).
    var treatments: (() -> [Treatment])?
    /// The alert rules behind the card's badge, mute lines and last-treatment slot (AlertRules.swift).
    var alertSettings: (() -> AlertSettings)?
    var onLogTreatment: ((Treatment) -> Void)?
    var onDeleteTreatment: ((String) -> Void)?
    /// Which paths a new entry takes and whether this Mac may log at all (v2 writer gating, NEXT §1 item 1).
    var treatmentPaths: (() -> TreatmentPaths)?
    /// Per-entry delivery status ("v2 accepted ✓") and its long form (Glucose Report › Treatments).
    var treatmentStatus: ((Treatment) -> String)?
    var treatmentDetails: ((Treatment) -> [String])?

    private(set) var snapshot: Snapshot?
    private(set) var lastError: String?
    private var tick: Timer?, freshTimer: Timer?, endTimer: Timer?

    override init() {
        super.init()
        guard let button = item.button else { return }
        button.imagePosition = .imageTrailing
        button.title = "…"
        button.toolTip = "Sukrara"
        button.target = self
        button.action = #selector(clicked)
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        contextMenu.addItem(withTitle: "Glucose Report…", action: #selector(report), keyEquivalent: "").target = self
        contextMenu.addItem(withTitle: "Settings…", action: #selector(settings), keyEquivalent: ",").target = self
        contextMenu.addItem(.separator())
        contextMenu.addItem(withTitle: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")

        panelModel.onLog = { [weak self] t in self?.onLogTreatment?(t) }
        panelModel.onUndo = { [weak self] id in self?.onDeleteTreatment?(id) }
        panelModel.onFollow = { [weak self] in self?.panel.close(); self?.onFollow?() }
        panelModel.onReport = { [weak self] in self?.panel.close(); self?.onReport?() }
        panelModel.onSettings = { [weak self] in self?.panel.close(); self?.onSettings?() }
        panel.onVisible = { [weak self] visible in
            guard let self else { return }
            self.item.button?.highlight(visible)
            self.tick?.invalidate(); self.tick = nil
            guard visible else { return }
            self.render()
            // The card redraws while open (its graph moves with the clock).
            let t = Timer(timeInterval: 15, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.render() } }
            RunLoop.main.add(t, forMode: .common)
            self.tick = t
        }
        // Increase Contrast switches the card's ink at once.
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.accessibilityDisplayOptionsDidChangeNotification,
                                                          object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.panelModel.tick += 1 }
        }
        render()
    }

    @objc private func clicked() {
        guard let button = item.button else { return }
        if NSApp.currentEvent?.type == .rightMouseUp || NSApp.currentEvent?.modifierFlags.contains(.control) == true {
            panel.close()
            item.menu = contextMenu                 // shown by the next performClick, then detached again
            button.performClick(nil)
            item.menu = nil
        } else {
            panel.toggle(below: button)
        }
    }

    /// Debug aid (`-openPanel`): open the panel as if clicked.
    func openPanel() {
        panel.open(below: item.button)
        // For `screencapture -R x,y,w,h` (points, origin at the main display's top left), margin for the shadow.
        let f = panel.frame.insetBy(dx: -24, dy: -24)
        let top = NSScreen.screens.first?.frame.maxY ?? 0
        log("panel window \(panel.windowNumber) rect \(Int(f.minX)),\(Int(top - f.maxY)),\(Int(f.width)),\(Int(f.height))")
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

    @objc private func settings() { onSettings?() }
    @objc private func report() { onReport?() }

    /// nil = not following anyone over v2.
    func setFollow(_ text: String?, image: NSImage? = nil) {
        followText = text
        followImage = image
        render()
        onFollowChange?()
    }

    /// "Bluetooth · Tartaruga" or "No data yet" (Settings › General).
    var sourceText: String { snapshot.map { $0.source } ?? "No data yet" }

    private func render() {
        renderTreatments()
        panelModel.following = followText != nil
        guard let button = item.button else { return }
        guard let snap = snapshot else {
            button.attributedTitle = NSAttributedString(string: lastError == nil ? "…" : "⚠︎")
            button.image = nil
            button.setAccessibilityLabel("Sukrara: no glucose data")
            panelModel.hasData = false
            return
        }
        let age = Date().timeIntervalSince(snap.latest.date)
        let fresh = Freshness.of(age: age)
        // Instead of polling: one timer for the next freshness step (re-armed by every new reading).
        endTimer?.invalidate()
        freshTimer?.invalidate()
        freshTimer = Freshness.nextChange(age: age).map { next in
            Timer.scheduledTimer(withTimeInterval: next - age, repeats: false) { [weak self] _ in MainActor.assumeIsolated { self?.render() } }
        }
        freshTimer?.tolerance = 5
        let rate = snap.rate
        let color = fresh.strike ? NSColor.secondaryLabelColor : graph.bands.color(for: snap.latest.mgdl)

        var attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
        ]
        if let color { attrs[.foregroundColor] = color }
        if fresh.strike { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        // Old (> 20 min): no value at all, just a dash; the panel says since when.
        button.imagePosition = .imageTrailing
        button.attributedTitle = NSAttributedString(string: fresh.showValue ? unit.format(snap.latest.mgdl) : "–", attributes: attrs)
        button.image = fresh.showValue ? TrendArrow.image(rate: fresh.strike ? nil : rate, size: 15, color: color) : nil
        button.toolTip = "Sukrara · reading at \(snap.latest.date.formatted(date: .omitted, time: .shortened))"
        let unitName = unit == .mgdl ? "mg/dL" : "mmol/L"
        button.setAccessibilityLabel(fresh.showValue ? "Glucose \(unit.format(snap.latest.mgdl)) \(unitName)" : "Glucose: no recent reading")
        // round sensor-life: no value because the sensor ended or warms up → say so (the dropdown card says it too)
        let nowT = Date().timeIntervalSince1970
        if let sl = SensorBadge.look(SensorStore.current, now: nowT, latest: snap.latest.date.timeIntervalSince1970) {
            if sl.ended {
                // as soon as it has ended (not an error): the checkered flag instead of a value; the panel has the pill
                button.attributedTitle = NSAttributedString(string: "")
                let img = NSImage(systemSymbolName: "flag.checkered", accessibilityDescription: "Sensor ended") ?? NSImage()
                img.isTemplate = true   // follows the menubar's light / dark appearance
                button.image = img.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: NSFont.systemFontSize, weight: .medium)) ?? img
                button.imagePosition = .imageOnly
                button.toolTip = "Sensor ended — replace sensor"
                button.setAccessibilityLabel("Sensor ended — replace sensor")
            } else if !fresh.showValue, let w = sl.warmup {
                attrs[.foregroundColor] = NSColor.secondaryLabelColor
                attrs.removeValue(forKey: .strikethroughStyle)
                button.attributedTitle = NSAttributedString(string: w, attributes: attrs)
                button.toolTip = "Sukrara · sensor warming up, ready at \(Freshness.timeText(sl.readyAt, now: nowT))"
                button.setAccessibilityLabel("Sensor warming up, \(w) left")
                freshTimer?.invalidate()   // no readings come during the warm-up: tick the minutes ourselves
                freshTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in MainActor.assumeIsolated { self?.render() } }
            } else if sl.end - nowT < 3600 {
                // the end comes before the next reading may: switch to "Sensor ended" right then
                endTimer?.invalidate()
                endTimer = Timer.scheduledTimer(withTimeInterval: max(1, sl.end - nowT + 1), repeats: false) { [weak self] _ in
                    MainActor.assumeIsolated { self?.render(); self?.panelModel.tick += 1 }
                }
            }
        }

        var g = graph.graphStyle()
        g.hour24 = GraphStyle.systemHour24
        var pts = history?() ?? snap.history.map { GraphReading(t: $0.date.timeIntervalSince1970, v: $0.mgdl, rate: $0.rate) }
        if !pts.isEmpty { pts[pts.count - 1].rate = rate }
        panelModel.hasData = true
        var st = graph.cardStyle(graph: g)
        st.sensor = SensorStore.current
        panelModel.style = st
        panelModel.alerts = alertSettings?()
        panelModel.readings = pts
        panelModel.tick += 1
    }

    private func marks() -> [GraphMark] {
        (treatments?() ?? []).filter { !$0.del }.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
    }

    /// Every graph setting (UserDefaults); "Reset All Graph Settings" removes them.
    static let graphKeys = ["graphHours", "menubarHours", "widgetHours", "ringCount", "slots", "ringRange", "ringOrder",
                            "bandVeryLow", "bandLow", "bandTight", "bandHigh", "bandVeryHigh", "bandExtremelyHigh", "side", "lines", "gridHours", "lastPlace", "lastElse", "countdownHours"]

    /// Settings changed (Settings › Graph or `defaults write`): redraw and re-publish to the widgets.
    func reloadGraph() {
        graph = GraphConfig.load(place: "menubar")
        render()
        onGraphChange?()
    }

    // MARK: treatments

    func treatmentsChanged() {
        renderTreatments()
        panelModel.tick += 1
    }

    /// The alert settings changed: the badge, the mute lines and the slot follow them.
    func alertsChanged() {
        panelModel.alerts = alertSettings?()
    }

    private func renderTreatments() {
        panelModel.marks = marks()
        panelModel.canLog = treatments != nil && (treatmentPaths?() ?? TreatmentPaths()).canLog
    }

    /// "14:05  2.5u rapid · 12m" (amounts lowercase without a space, the time since the entry last; delivery status
    /// comes from `treatmentStatus`).
    static func describe(_ t: Treatment, now: Date = Date()) -> String {
        let time = Date(timeIntervalSince1970: TimeInterval(t.t)).formatted(date: .omitted, time: .shortened)
        let what = TreatmentMarks.amount(k: t.k, a: t.a).map { "\($0) \(t.k)" } ?? "note"
        let note = t.n.isEmpty ? "" : (t.k == "note" ? ": " : " · ") + t.n
        return "\(time)  \(what)\(note) · \(TreatmentMarks.duration(now.timeIntervalSince1970 - TimeInterval(t.t)))"
    }
}

/// The graph's palette on Mac surfaces.
enum MacSurface {
    static var increaseContrast: Bool { NSWorkspace.shared.accessibilityDisplayShouldIncreaseContrast }

    /// On an opaque ground (the report window): the shared palette; with Increase Contrast the secondary ink and the
    /// grid get much stronger.
    static func palette(dark: Bool, increaseContrast hc: Bool = increaseContrast) -> GraphPalette {
        var p = GraphPalette.of(dark: dark)
        if hc {
            p.inkSoft = dark ? GraphPalette.hex(0xD8D8DC) : GraphPalette.hex(0x3C3C40)
            p.grid = dark ? GraphPalette.hex(0x7C7C82) : GraphPalette.hex(0x8A8A90)
        }
        return p
    }

    /// On the panel's material: the same, without a background colour (no halo disc or marker hole in a colour that
    /// can't match a translucent ground), and a grid one step stronger, since the material is lighter than the
    /// widget ground in dark mode and darker in light mode.
    static func materialPalette(dark: Bool, increaseContrast hc: Bool = increaseContrast) -> GraphPalette {
        var p = palette(dark: dark, increaseContrast: hc)
        p.bg = CGColor(gray: 0, alpha: 0)
        if !hc { p.grid = dark ? GraphPalette.hex(0x4A4A4F) : GraphPalette.hex(0xC8C8CE) }
        return p
    }
}

/// The glucose card (investigation 16): value, trend arrow, time-in-range rings and the graph with its side profile,
/// drawn by the shared renderer (SukraraGraph.swift) like the widgets and Sukrara for Android, here in the panel's
/// module on the menu material.
final class CardView: NSView {
    var readings: [GraphReading] = [] { didSet { needsDisplay = true } }
    var marks: [GraphMark] = [] { didSet { needsDisplay = true } }
    var style = CardStyle()
    var metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)?
    var alerts: AlertSettings? { didSet { needsDisplay = true } }
    /// Tests / previews: draw as if this were now.
    var now: Date? = nil
    var inset = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)

    override var isFlipped: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        guard let ctx = NSGraphicsContext.current?.cgContext else { return }
        let dark = effectiveAppearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
        let hc = effectiveAppearance.bestMatch(from: [.accessibilityHighContrastAqua, .accessibilityHighContrastDarkAqua]) != nil
            || MacSurface.increaseContrast
        let pal = MacSurface.materialPalette(dark: dark, increaseContrast: hc)
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
