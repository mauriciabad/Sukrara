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
/// menu (Treatments…, Glucose Report…, Settings…, Quit). Everything that configures the app lives in Settings (HIG "Settings").
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
    var onTreatments: (() -> Void)?

    /// The protocol v2 follow state line ("Following Ana · key epoch 2 · 4 relays"), nil when not following; shown
    /// in Settings › Following only.
    private(set) var followText: String?
    private(set) var followImage: NSImage?
    var onFollowChange: (() -> Void)?

    // Treatments (spikes/libreview/SPEC.md).
    var treatments: (() -> [Treatment])?
    /// The alert rules behind the card's badge, mute lines and last-treatment slot (AlertRules.swift).
    var alertSettings: (() -> AlertSettings)?
    /// Muted from an alert notification (side → until): the badge under the value.
    var alertPaused: (() -> [String: TimeInterval])?
    /// This Mac's Mute all until (unix s; 0 = off, AlertStatus.never = no end): the card's muted pill, the menu bar's bell.
    var alertAllMuted: (() -> TimeInterval)?
    /// The quiet alerts (Alerts.active) and their ways out, for the panel.
    var alertActive: (() -> [Alerts.Active])?
    var onQuiet: ((QuietOp) -> Void)? { didSet { panelModel.onQuiet = onQuiet } }
    var onLogTreatment: ((Treatment) -> Void)?
    /// The panel's *Add Treatment…* (field nil) and the card's *Eat carbs* / *Take insulin* pill (field "carbs" /
    /// "rapid"): open the Add Treatment window on this screen (the menubar item's) with that field focused.
    var onAddTreatment: ((NSScreen?, String?) -> Void)?
    var onDeleteTreatment: ((String) -> Void)?
    /// Which paths a new entry takes and whether this Mac may log at all (v2 writer gating, NEXT §1 item 1).
    var treatmentPaths: (() -> TreatmentPaths)?
    /// Per-entry delivery status ("v2 accepted ✓") and its long form (the Treatments window).
    var treatmentStatus: ((Treatment) -> String)?
    var treatmentDetails: ((Treatment) -> [String])?

    private(set) var snapshot: Snapshot?
    private(set) var lastError: String?
    private var tick: Timer?, freshTimer: Timer?, endTimer: Timer?, muteTimer: Timer?
    private var clockObservers: [NSObjectProtocol] = []

    override init() {
        super.init()
        guard let button = item.button else { return }
        button.imagePosition = .imageTrailing
        button.title = "…"
        button.toolTip = "Sukrara"
        button.target = self
        button.action = #selector(clicked)
        button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        contextMenu.addItem(withTitle: "Treatments…", action: #selector(treatmentsList), keyEquivalent: "").target = self
        contextMenu.addItem(withTitle: "Glucose Report…", action: #selector(report), keyEquivalent: "").target = self
        contextMenu.addItem(withTitle: "Settings…", action: #selector(settings), keyEquivalent: ",").target = self
        contextMenu.addItem(.separator())
        contextMenu.addItem(withTitle: "Quit Sukrara", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")

        panelModel.onAddTreatment = { [weak self] field in
            self?.panel.close(.handOff)
            self?.onAddTreatment?(self?.item.button?.window?.screen, field)
        }
        panelModel.onFollow = { [weak self] in self?.panel.close(.handOff); self?.onFollow?() }
        panelModel.onReport = { [weak self] in self?.panel.close(.handOff); self?.onReport?() }
        panelModel.onTreatments = { [weak self] in self?.panel.close(.handOff); self?.onTreatments?() }
        panelModel.onSettings = { [weak self] in self?.panel.close(.handOff); self?.onSettings?() }
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
        // The 24-hour setting or the time zone changed: every time on the menubar, dropdown and widgets follows at once.
        clockObservers = Clock.observeChanges { [weak self] in MainActor.assumeIsolated { self?.reloadGraph() } }
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
            panel.close(.dismiss)                   // the menu's items bring Sukrara forward themselves
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
    @objc private func treatmentsList() { onTreatments?() }

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
        let nowT = Date().timeIntervalSince1970
        let sl = SensorBadge.look(SensorStore.current, now: nowT, latest: snap.latest.date.timeIntervalSince1970)
        let rate = snap.rate
        // what shows is the card's own decision for this reading (GlucoseCard.face): the sensor's end or warm-up first,
        // then the freshness steps
        let look = Self.titleFace(snap, now: nowT, sensor: SensorStore.current, format: { self.unit.format(Double($0)) })
        let title = MenubarTitle.of(look)
        // Instead of polling: one timer for the next freshness step (re-armed by every new reading).
        endTimer?.invalidate()
        freshTimer?.invalidate()
        freshTimer = Freshness.nextChange(age: age).map { next in
            Timer.scheduledTimer(withTimeInterval: next - age, repeats: false) { [weak self] _ in MainActor.assumeIsolated { self?.render() } }
        }
        freshTimer?.tolerance = 1
        let color = title.grey ? NSColor.secondaryLabelColor : graph.bands.color(for: snap.latest.mgdl)

        var attrs: [NSAttributedString.Key: Any] = [
            .font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
        ]
        if let color { attrs[.foregroundColor] = color }
        if title.strike { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        button.imagePosition = .imageTrailing
        button.attributedTitle = NSAttributedString(string: title.text, attributes: attrs)
        switch title.image {
        case .arrow: button.image = TrendArrow.image(rate: rate, size: 15, color: color)
        case .ring: button.image = TrendArrow.image(rate: nil, size: 15, color: color)
        case .noReadings, .flag:
            // no readings for 20 min / the sensor ended: a symbol instead of a value (template: follows light / dark)
            let img = NSImage(systemSymbolName: title.image == .flag ? "flag.checkered" : GlucoseLook.noReadingsSymbol,
                              accessibilityDescription: title.label) ?? NSImage()
            img.isTemplate = true
            button.image = img.withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: NSFont.systemFontSize, weight: .medium)) ?? img
            button.imagePosition = .imageOnly
        case .none: button.image = nil
        }
        button.toolTip = title.image == .noReadings ? "Sukrara · no reading since \(Clock.time(snap.latest.date))"
            : "Sukrara · reading at \(Clock.time(snap.latest.date))"
        button.setAccessibilityLabel(title.image == .noReadings ? "Glucose: no reading since \(Clock.time(snap.latest.date))" : title.label)
        // Mute all (round f30): the "notifications off" bell before the value, while it lasts, and in the tooltip
        let allEnd = alertAllMuted?() ?? 0
        muteTimer?.invalidate(); muteTimer = nil
        if let line = MuteCopy.allLine(until: allEnd, now: nowT) {
            button.attributedTitle = Self.withMutedBell(button.attributedTitle, line: line, attrs)
            if button.imagePosition == .imageOnly { button.imagePosition = .imageTrailing }
            button.toolTip = (button.toolTip ?? "Sukrara") + " · " + line
            button.setAccessibilityLabel(button.accessibilityLabel().map { "\($0), \(line)" } ?? line)
            // the tooltip / VoiceOver line counts down: redraw when its minutes left change (aligned to the end)
            if allEnd < AlertStatus.never {
                let next = allEnd - TimeInterval(MuteCopy.leftMin(allEnd - nowT) - 1) * 60
                let t = Timer(timeInterval: max(1, next - nowT + 0.5), repeats: false) { [weak self] _ in MainActor.assumeIsolated { self?.render() } }
                RunLoop.main.add(t, forMode: .common)
                muteTimer = t
            }
        }
        // round sensor-life: no value because the sensor ended or warms up → say so (the dropdown card says it too)
        if let sl {
            if sl.ended {
                // as soon as it has ended (not an error): the checkered flag instead of a value (above); the panel has the pill
                button.toolTip = "Sensor ended — replace sensor"
                button.setAccessibilityLabel("Sensor ended — replace sensor")
            } else if look.state == .warmingUp, let w = look.warmup {
                button.image = nil
                button.imagePosition = .imageTrailing
                attrs[.foregroundColor] = NSColor.secondaryLabelColor
                attrs.removeValue(forKey: .strikethroughStyle)
                button.attributedTitle = NSAttributedString(string: w, attributes: attrs)
                // past the warm-up's end: "Almost ready" (never "0m"), "Waiting for the first reading"
                button.toolTip = "Sukrara · \(sl.warmupLine(now: nowT))"
                button.setAccessibilityLabel(sl.waiting(now: nowT) ? "Sensor almost ready, waiting for the first reading" : "Sensor warming up, \(w) left")
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

        var pts = history?() ?? snap.history.map { GraphReading(t: $0.date.timeIntervalSince1970, v: $0.mgdl, rate: $0.rate) }
        if !pts.isEmpty { pts[pts.count - 1].rate = rate }
        panelModel.hasData = true
        panelModel.style = Self.dropdownStyle(graph, sensor: SensorStore.current)
        panelModel.alerts = alertSettings?()
        panelModel.paused = alertPaused?() ?? [:]
        panelModel.allMuted = alertAllMuted?() ?? 0
        panelModel.quiet = alertActive?() ?? []
        panelModel.readings = pts
        panelModel.tick += 1
    }

    /// The readings the dropdown's card draws from (`history`; also the scroll self-test's): the store's whole history,
    /// so the chart can scroll back through it (only the shown window's readings left nothing to scroll to: "it only
    /// scrolls 1px or so", 2026-10-21). The renderer cuts the window by binary search, so this costs no frame time.
    static func cardReadings(_ store: ReadingsStore, graph: GraphConfig) -> [GraphReading] { store.readings }

    /// The dropdown card's style (the menubar place's settings), also the layout self-test's.
    static func dropdownStyle(_ graph: GraphConfig, sensor: SensorInfo?) -> CardStyle {
        var st = graph.cardStyle(graph: graph.graphStyle())
        st.sensor = sensor
        return st
    }

    /// The status item's value decision (its one, also the self-tests'): the card's face for the snapshot's newest reading with its rate (`Snapshot.rate`, the source's own) (GlucoseCard.face),
    /// written in the user's unit (`format`).
    static func titleFace(_ snap: Snapshot, now: TimeInterval, sensor: SensorInfo?,
                          format: @escaping (Int) -> String = { "\($0)" }) -> GlucoseLook {
        let r = GraphReading(t: snap.latest.date.timeIntervalSince1970, v: snap.latest.mgdl, rate: snap.rate)
        return GlucoseCard.face(r, now: now, sensor: SensorBadge.look(sensor, now: now, latest: r.t), surface: .menubarTitle, format: format)
    }

    /// The menu bar title with the "notifications off" bell before it (Mute all; also `-menubarSnapshot`).
    static func withMutedBell(_ title: NSAttributedString, line: String, _ attrs: [NSAttributedString.Key: Any]) -> NSAttributedString {
        let bell = NSTextAttachment()
        let img = NSImage(systemSymbolName: "bell.slash", accessibilityDescription: line)?
            .withSymbolConfiguration(NSImage.SymbolConfiguration(pointSize: NSFont.systemFontSize - 1, weight: .medium))
        img?.isTemplate = true
        bell.image = img
        let t = NSMutableAttributedString(attachment: bell)
        t.addAttribute(.foregroundColor, value: NSColor.secondaryLabelColor, range: NSRange(location: 0, length: t.length))
        t.append(NSAttributedString(string: "\u{2009}", attributes: attrs))
        t.append(title)
        return t
    }


    private func marks() -> [GraphMark] {
        (treatments?() ?? []).filter { !$0.del }.map { GraphMark(t: TimeInterval($0.t), k: $0.k, a: $0.a, n: $0.n) }
    }

    /// Every graph setting (UserDefaults); the mirrored ones are `WearerSettings.graphWire`'s keys, the rest
    /// (per-place window and sensor time) are always this Mac's.
    nonisolated static let graphKeys = ["graphHours", "menubarHours", "widgetHours", "ringCount", "slots", "ringRange", "ringOrder",
                            "bandVeryLow", "bandLow", "bandTight", "bandHigh", "bandVeryHigh", "bandExtremelyHigh", "side", "columnCrown", "ringMarks", "lines", "gridHours", "lastPlace", "lastElse", "countdownHours", "marks",
                            "menubarSensorTime", "widgetSensorTime"]
    /// Every UserDefaults key a setting lives in (the schema's `defaults` store; `-settingsselftest` checks both ways).
    nonisolated static let settingsKeys = graphKeys + [WearerSettings.modeKey, "reportDays"]

    /// Settings changed (Settings › Graph or `defaults write`): redraw and re-publish to the widgets.
    func reloadGraph() {
        graph = GraphConfig.load(place: "menubar")
        render()
        onGraphChange?()
    }

    // MARK: treatments

    func treatmentsChanged() {
        renderTreatments()
        panelModel.quiet = alertActive?() ?? []   // a treatment's mute starts (or is deleted) at once
        panelModel.tick += 1
        panel.relayout()
    }

    /// The alert settings changed: the badge, the mute lines and the slot follow them.
    func alertsChanged() {
        render()   // the menu bar's Mute all bell
        panelModel.alerts = alertSettings?()
        panelModel.paused = alertPaused?() ?? [:]
        panelModel.allMuted = alertAllMuted?() ?? 0
        panelModel.quiet = alertActive?() ?? []
        panel.relayout()
    }

    private func renderTreatments() {
        panelModel.marks = marks()
        let p = treatmentPaths?() ?? TreatmentPaths()
        panelModel.canLog = treatments != nil && p.canLog
        panelModel.logBlocked = p.canLog ? nil : p.reason
    }

    /// "14:05  2.5u rapid · 12m" (amounts lowercase without a space, the time since the entry last; delivery status
    /// comes from `treatmentStatus`).
    static func describe(_ t: Treatment, now: Date = Date()) -> String {
        let time = Clock.time(Date(timeIntervalSince1970: TimeInterval(t.t)))
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
/// The card pill's VoiceOver button: VoiceOver's press (VO-Space) runs `press`.
final class PillAccessibilityElement: NSAccessibilityElement {
    let press: () -> Bool
    init(press: @escaping () -> Bool) { self.press = press; super.init() }
    override func accessibilityPerformPress() -> Bool { press() }
}

final class CardView: NSView {
    var readings: [GraphReading] = [] { didSet { needsDisplay = true } }
    var marks: [GraphMark] = [] { didSet { needsDisplay = true } }
    var style = CardStyle(sensorTime: true)
    var metricsOf: ((RingSpec, Bands) -> GlucoseMetrics)?
    var alerts: AlertSettings? { didSet { needsDisplay = true } }
    var paused: [String: TimeInterval] = [:] { didSet { needsDisplay = true } }
    var allMuted: TimeInterval = 0 { didSet { needsDisplay = true } }
    /// A click on the status pill ("Eat carbs", "45m left"): its box (view coordinates) and this view.
    var onStatus: ((CGRect, NSView) -> Void)?
    /// This Mac may log entries: *Eat carbs* / *Take insulin* then open Add Treatment (PillAction).
    var canAddTreatment = false
    private var statusBox: CGRect?

    /// The pill as a VoiceOver button (round f33): its words as the label ("Eat carbs"), what pressing does as the help
    /// ("Add Treatment"), pressing = clicking it. The card itself stays a picture; only the pill acts.
    override func accessibilityChildren() -> [Any]? {
        var children = super.accessibilityChildren() ?? []
        if let e = pillElement() { children.append(e) }
        return children
    }

    /// The accessibility element for the pill, or nil when no pill is drawn or it does nothing here.
    func pillElement() -> NSAccessibilityElement? {
        guard let statusBox, onStatus != nil, let alerts else { return nil }
        let status = AlertStatus.of(readings.last, alerts, marks, now: nowT, paused: paused, allMuted: allMuted)
        guard let name = PillAction.of(status, canAdd: canAddTreatment).actionName, let text = StatusBadge.describe(status, now: nowT)
        else { return nil }
        let box = statusBox.offsetBy(dx: inset.left, dy: inset.top)
        let e = PillAccessibilityElement { [weak self] in
            guard let self else { return false }
            self.onStatus?(box, self)
            return true
        }
        e.setAccessibilityRole(.button)
        e.setAccessibilityLabel(text)
        e.setAccessibilityHelp(name)
        e.setAccessibilityParent(self)
        e.setAccessibilityFrameInParentSpace(box)
        return e
    }
    /// Tests / previews: draw as if this were now.
    var now: Date? = nil
    var inset = NSEdgeInsets(top: 0, left: 0, bottom: 0, right: 0)

    override var isFlipped: Bool { true }

    // MARK: scrolling through time (the user, 2026-10-21)
    //
    // Two-finger horizontal swipes on a trackpad (with the system's own momentum: AppKit keeps sending the scroll
    // events of the fling, `momentumPhase`, exactly what NSScrollView follows) and a mouse wheel (or shift-wheel) move
    // the chart's window through the history, clamped to the first reading. The renderer draws the window it's given
    // (`ChartView`): line, distribution and %, y range, hour labels and treatments are all about what's visible; the y
    // range eases towards the window's own; away from now the renderer's "Now" pill shows and a click on it scrolls back.
    //
    // Pinch zoom (the user, 2026-10-21): a trackpad pinch (`magnify(with:)`) or ⌘-scroll zooms the time axis only,
    // anchored at the cursor so the moment under it stays put, within `ChartView.minSpan`…`maxSpan` (and the history).
    // The Now pill also returns to the place's "time shown"; the dropdown opens at it again.

    /// The window's end while scrolled (s; nil = live, following now).
    var scrollEnd: TimeInterval? { didSet { if scrollEnd != oldValue { tick() } } }
    /// The window's length while zoomed (s; nil = the place's "time shown").
    var scrollSpan: TimeInterval? { didSet { if scrollSpan != oldValue { tick() } } }
    /// Scrolled or zoomed: not the place's usual window up to now (the Now pill shows).
    var away: Bool { scrollEnd != nil || scrollSpan != nil }
    private var shownY: ClosedRange<Double>?
    /// The plot's bottom (pt above the chart's bottom): the layout's (`insetTarget`), eased like the y range so the
    /// treatment lane that comes and goes with the marks in the window doesn't make the plot jump.
    private var shownInset: CGFloat?
    private var insetTarget: CGFloat? { didSet { if insetTarget != oldValue, away { tick() } } }
    private var pillAlpha: CGFloat = 0
    private var backTarget = false
    private var link: CADisplayLink?
    private var lastTick: CFTimeInterval = 0
    /// What the last drawing laid out: the plot (points per second) and the Now pill (clicks).
    private var plot: CGRect?
    private var nowBox: CGRect?
    /// The axis of the current trackpad gesture, decided by its first movement (nil = not yet): a mostly vertical one
    /// isn't ours. The began event itself often carries no or a little vertical motion, so it doesn't decide.
    private var horizontalGesture: Bool?

    private var nowT: TimeInterval { (now ?? Date()).timeIntervalSince1970 }
    /// The place's "time shown", and the window's length now (zoomed or not).
    private var baseSpan: TimeInterval { style.graph?.window ?? 12 * 3600 }
    private var span: TimeInterval { scrollSpan ?? baseSpan }

    /// Zoom the time axis by `factor` (> 1 = in) keeping the moment at `anchor` (0 = the plot's left edge, 1 = its right
    /// edge) where it is; the length stays within `ChartView.minSpan`…`maxSpan` and the history, never narrower or
    /// wider than the place's own needs; a window that reaches now keeps following now (GlucoseGraph.kt `ChartScroll.zoom`).
    func zoom(by factor: Double, anchor: Double) {
        guard factor > 0, factor.isFinite else { return }
        let n = nowT, first = readings.first?.t ?? n, base = baseSpan
        let lo = min(ChartView.minSpan, base), hi = max(base, min(ChartView.maxSpan, n - first))
        let span0 = span, span1 = min(max(span0 / factor, lo), hi)
        let a = min(max(anchor, 0), 1)
        let at = (scrollEnd ?? n) - span0 * (1 - a)
        let end1 = min(max(at + span1 * (1 - a), min(first + span1, n)), n)
        backTarget = false
        scrollSpan = abs(span1 - base) < 30 ? nil : span1
        scrollEnd = end1 >= n ? nil : end1
    }

    /// The cursor's place across the plot (0…1) for an event.
    private func anchor(_ e: NSEvent) -> Double {
        guard let plot, plot.width > 0 else { return 1 }
        let p = convert(e.locationInWindow, from: nil)
        return Double((p.x - inset.left - plot.minX) / plot.width)
    }

    override func magnify(with e: NSEvent) {
        guard style.graph != nil, plot != nil else { return super.magnify(with: e) }
        zoom(by: 1 + Double(e.magnification), anchor: anchor(e))
    }

    /// A two-finger double tap (smart zoom): back and forth between the place's window and 3 h there.
    override func smartMagnify(with e: NSEvent) {
        guard style.graph != nil, plot != nil else { return super.smartMagnify(with: e) }
        zoom(by: scrollSpan == nil ? baseSpan / (3 * 3600) : span / baseSpan, anchor: anchor(e))
    }

    /// Move the window's end by `dt` s, within the history; at now it follows now again.
    func scroll(by dt: TimeInterval) {
        let n = nowT
        let minEnd = min(n, (readings.first?.t ?? n) + span)
        let next = min(max((scrollEnd ?? n) + dt, minEnd), n)
        scrollEnd = next >= n ? nil : next
    }

    override func scrollWheel(with e: NSEvent) {
        guard style.graph != nil, let plot, plot.width > 0 else { return super.scrollWheel(with: e) }
        // ⌘-scroll zooms (up / away = in), anchored at the cursor
        if e.modifierFlags.contains(.command) {
            let d = e.hasPreciseScrollingDeltas ? e.scrollingDeltaY : e.scrollingDeltaY * 10
            if d != 0 { zoom(by: exp(Double(d) * 0.01), anchor: anchor(e)) }
            return
        }
        if e.phase == .began || e.phase == .mayBegin { horizontalGesture = nil }
        if horizontalGesture == nil, e.phase != .began, e.scrollingDeltaX != 0 || e.scrollingDeltaY != 0 {
            horizontalGesture = abs(e.scrollingDeltaX) >= abs(e.scrollingDeltaY)
        }
        let px: CGFloat
        if e.hasPreciseScrollingDeltas {
            // a trackpad (or Magic Mouse): only a horizontal gesture, its fling included
            guard horizontalGesture == true, e.scrollingDeltaX != 0 else { return super.scrollWheel(with: e) }
            px = e.scrollingDeltaX
        } else {
            // a wheel: lines; a vertical wheel scrolls the chart like a horizontal-only NSScrollView does
            px = (e.scrollingDeltaX != 0 ? e.scrollingDeltaX : e.scrollingDeltaY) * 10
        }
        backTarget = false
        // content follows the fingers (natural scrolling) or the wheel's direction: positive = towards the past
        scroll(by: -TimeInterval(px) * span / TimeInterval(plot.width))
    }

    override func mouseDown(with e: NSEvent) {
        let p = convert(e.locationInWindow, from: nil)
        let q = CGPoint(x: p.x - inset.left, y: p.y - inset.top)
        // the Now pill (in the chart, only while scrolled or zoomed) and the status pill (in the value row) never overlap:
        // whichever box the click is in decides
        if away, let nowBox, nowBox.insetBy(dx: -4, dy: -4).contains(q) { backTarget = true; tick(); return }
        if let statusBox, let onStatus, statusBox.insetBy(dx: -4, dy: -4).contains(q) {
            return onStatus(statusBox.offsetBy(dx: inset.left, dy: inset.top), self)
        }
        super.mouseDown(with: e)
    }

    /// Start the display link while something eases (y range, pill, the way back to now).
    private func tick() {
        needsDisplay = true
        // offscreen (the self-tests, previews): no frames to ease over, the end state at once
        guard self.window != nil else {
            pillAlpha = away ? 1 : 0; shownY = nil; shownInset = nil
            if backTarget { backTarget = false; scrollEnd = nil; scrollSpan = nil }
            return
        }
        guard link == nil else { return }
        let l = displayLink(target: self, selector: #selector(step(_:)))
        l.add(to: .main, forMode: .common)
        link = l
        lastTick = CACurrentMediaTime()
    }

    @objc private func step(_ l: CADisplayLink) {
        let t = CACurrentMediaTime(), dt = min(max(t - lastTick, 0), 0.05)
        lastTick = t
        // critically damped easing, ~0.12 s time constant (no bounce: a scale that bounced would read as data moving)
        let k = 1 - exp(-dt / 0.12)
        var busy = false
        if backTarget {
            // back to now and the place's window together
            let n = nowT, g = 1 - exp(-dt / 0.08)
            if let e = scrollEnd { let step = max((n - e) * g, 30); scrollEnd = e + step >= n - 1 ? nil : e + step }
            if let s = scrollSpan { let next = s + (baseSpan - s) * g; scrollSpan = abs(next - baseSpan) < 60 ? nil : next }
            if !away { backTarget = false } else { busy = true }
        }
        let target = targetY()
        if let y = shownY {
            let lo = y.lowerBound + (target.lowerBound - y.lowerBound) * k, hi = y.upperBound + (target.upperBound - y.upperBound) * k
            if abs(lo - target.lowerBound) < 0.05 && abs(hi - target.upperBound) < 0.05 { shownY = target } else { shownY = lo...hi; busy = true }
        } else { shownY = target }
        if let target = insetTarget {
            if let s = shownInset, abs(s - target) > 0.1 { shownInset = s + (target - s) * CGFloat(k); busy = true } else { shownInset = target }
        }
        let want: CGFloat = away ? 1 : 0
        pillAlpha += (want - pillAlpha) * CGFloat(1 - exp(-dt / 0.06))
        if abs(pillAlpha - want) < 0.01 { pillAlpha = want } else { busy = true }
        needsDisplay = true
        if !busy { link?.invalidate(); link = nil }
    }

    private func targetY() -> ClosedRange<Double> {
        guard var g = style.graph else { return 0...1 }
        g.window = span
        return GlucoseCard.yRange(readings, end: scrollEnd ?? nowT, g)
    }

    /// The view the renderer gets: none while live and at rest (the same picture as every other surface).
    private var chartView: ChartView? {
        if !away && (link == nil || pillAlpha == 0 && shownY == targetY() && shownInset == insetTarget) { return nil }
        return ChartView(end: scrollEnd ?? nowT, y: shownY, now: pillAlpha, plotInset: shownInset, span: scrollSpan)
    }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        // the dropdown opens at now again (a glance shows the latest reading)
        if window == nil { link?.invalidate(); link = nil; scrollEnd = nil; scrollSpan = nil; pillAlpha = 0; shownY = nil; shownInset = nil; backTarget = false }
    }

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
        paint(ctx, size: r.size, pal)
        ctx.restoreGState()
    }

    /// The dropdown's card (its one drawing entry, also the layout self-test's): the whole card at `size`.
    @discardableResult
    func paint(_ ctx: CGContext, size: CGSize, _ pal: GraphPalette) -> CardLayout {
        let rec = GlucoseCard.draw(ctx, size: size, readings: readings, marks: marks, now: (now ?? Date()).timeIntervalSince1970,
                         style, pal, surface: .menubarCard, part: .all, metricsOf: metricsOf, alerts: alerts, paused: paused,
                         allMuted: allMuted, view: chartView)
        plot = rec.first("chart.plot")?.box
        nowBox = rec.first("chart.now")?.box
        statusBox = rec.first("value.status")?.box
        if let area = rec.first("chart")?.box, let natural = rec.first("chart.plot.natural")?.box { insetTarget = area.maxY - natural.maxY }
        return rec
    }

    override func viewDidChangeEffectiveAppearance() { needsDisplay = true }
}

/// The menubar title for a `look` (`Freshness.look`, the user 2026-10-21): the value in its range colour with the trend
/// arrow, or the "no trend" ring; from 5 min struck through and grey with the ring; from 20 min no value, only the "no
/// readings" symbol (`GlucoseLook.noReadingsSymbol`); the flag once the sensor has ended. Pure, so `-graphselftest`
/// runs it at every step boundary (spikes/graph-fixtures/freshness, surface `menubar-title`).
struct MenubarTitle: Equatable {
    enum Image: String { case arrow, ring, noReadings = "no_readings", flag, none }
    var text: String
    var strike: Bool
    var grey: Bool
    var image: Image
    var label: String

    static func of(_ look: GlucoseLook) -> MenubarTitle {
        switch look.state {
        case .value:
            let t = look.text ?? ""
            return MenubarTitle(text: t, strike: look.strike, grey: look.grey, image: look.trend == .arrow ? .arrow : .ring,
                                label: "Glucose \(t)" + (look.strike ? " (old)" : ""))
        case .noReadings: return MenubarTitle(text: "", strike: false, grey: false, image: .noReadings, label: "Glucose: no recent reading")
        case .ended: return MenubarTitle(text: "", strike: false, grey: false, image: .flag, label: "Sensor ended — replace sensor")
        case .none: return MenubarTitle(text: "–", strike: false, grey: false, image: .none, label: "Sukrara: no glucose data")
        // a new sensor warming up: its time left where the number goes (render writes it in the secondary colour)
        case .warmingUp: return MenubarTitle(text: look.warmup ?? "", strike: false, grey: true, image: .none, label: "Sensor warming up")
        }
    }
}

/// `-menubarSnapshot <png>` (evidence, round f30): the status item's title as `render` builds it (the card's face for
/// a synthetic reading, the arrow, Mute all's bell and its tooltip line): plain, Mute all for a time, until turned off, on a light and a
/// dark menu bar. Offline like every `-…Snapshot` run (LaunchGuard): nothing starts.
@MainActor enum MenubarSnapshot {
    static func render(to path: String) {
        let now = Date().timeIntervalSince1970
        let r = GraphReading(t: now - 30, v: 161, rate: 0.6)
        let title = MenubarTitle.of(GlucoseCard.face(r, now: now, sensor: nil, surface: .menubarTitle))
        let rows: [(String, TimeInterval)] = [("not muted", 0), ("Mute all 1 h 20 min", now + 80 * 60), ("Mute all until turned off", AlertStatus.never)]
        let cell = NSSize(width: 240, height: 24), scale: CGFloat = 3
        let size = NSSize(width: cell.width * 2, height: cell.height * CGFloat(rows.count))
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width * scale), pixelsHigh: Int(size.height * scale),
                                   bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                   colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
        rep.size = size
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        for (col, dark) in [false, true].enumerated() {
            NSAppearance(named: dark ? .darkAqua : .aqua)!.performAsCurrentDrawingAppearance {
                (dark ? NSColor(white: 0.12, alpha: 1) : NSColor(white: 0.93, alpha: 1)).setFill()
                NSRect(x: CGFloat(col) * cell.width, y: 0, width: cell.width, height: size.height).fill()
                for (i, row) in rows.enumerated() {
                    let y = size.height - CGFloat(i + 1) * cell.height
                    let color = Bands().color(for: r.v) ?? .labelColor
                    let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.monospacedDigitSystemFont(ofSize: NSFont.systemFontSize, weight: .medium),
                                                                .foregroundColor: color]
                    var t = NSAttributedString(string: title.text, attributes: attrs)
                    if let line = MuteCopy.allLine(until: row.1, now: now) { t = StatusController.withMutedBell(t, line: line, attrs) }
                    let x = CGFloat(col) * cell.width + 10
                    t.draw(at: NSPoint(x: x, y: y + 4))
                    TrendArrow.image(rate: r.rate, size: 15, color: color).draw(in: NSRect(x: x + t.size().width + 2, y: y + 4.5, width: 15, height: 15))
                    // the label: the tooltip / VoiceOver line render adds (MuteCopy.allLine), else the row's name
                    NSAttributedString(string: MuteCopy.allLine(until: row.1, now: now) ?? row.0, attributes: [.font: NSFont.systemFont(ofSize: 8), .foregroundColor: NSColor.secondaryLabelColor])
                        .draw(at: NSPoint(x: x + 72, y: y + 7))
                }
            }
        }
        NSGraphicsContext.restoreGraphicsState()
        try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
        print("wrote", path)
    }
}
