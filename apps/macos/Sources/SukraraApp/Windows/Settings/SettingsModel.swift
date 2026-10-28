// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraAlerts
import SukraraCore
import SukraraGraph

/// The settings window's state over the store: the panes, "Settings from", the card settings, the unit, the wearer's
/// mirrored values. Every change applies at once; the app picks it up through the defaults change.
@Observable
public final class SettingsModel {
    public enum Pane: String, CaseIterable, Sendable { case general, graph, rings, alerts, following }

    @ObservationIgnored let store: any SettingsStoring
    public let alerts: AlertSettingsStore
    public let follow: FollowModel
    /// The wearer's mirrored settings (nil while not following or none arrived yet).
    public var wearer: WearerSettings?
    /// The pane shown; the window reopens on it.
    public var pane: Pane {
        didSet { store.set(pane.rawValue, for: MacSettings.settingsPane) }
    }
    /// "Copy the wearer's settings…" and "Reset all graph settings…" are asked first.
    public var confirmingCopy = false
    public var confirmingResetAll = false
    public let version: String
    private var revision = 0

    public init(store: any SettingsStoring, follow: FollowModel, wearer: WearerSettings? = nil, version: String) {
        self.store = store
        self.alerts = AlertSettingsStore(store: store)
        self.follow = follow
        self.wearer = wearer
        self.version = version
        pane = Pane(rawValue: store.value(MacSettings.settingsPane)) ?? .general
    }

    // MARK: Settings from

    /// Rows mirror the wearer (read-only) while following in "Settings from: Wearer".
    public var mirrors: Bool { settingsFrom == .wearer && wearer != nil }
    /// The card settings are this Mac's own and editable (Custom); otherwise the panes show what's in use, read-only.
    public var editable: Bool { settingsFrom == .custom }

    public var settingsFrom: SettingsFrom {
        get { _ = revision; return store.settingsFrom }
        set {
            store.set(newValue.rawValue, for: MacSettings.settingsFrom)
            // `alerts.source` is derived from `follower.settingsFrom` (schema): wearer = mirror, else own rules.
            let source: AlertSource = newValue == .wearer ? .wearer : .own
            if alerts.settings.source != source { alerts.update { $0.source = source } }
            revision += 1
        }
    }

    /// "Use my own settings": this Mac's values, editable.
    public func useOwn() { settingsFrom = .custom }

    /// "Use the defaults": Sukrara's defaults apply; this Mac's own values stay stored for Custom.
    public func useDefaults() { settingsFrom = .default }

    /// "Copy the wearer's settings" (after the confirmation): this Mac's own card settings and alert rules become the
    /// wearer's mirrored ones, as a starting point, and Custom applies them. Sounds and what isn't mirrored stay.
    public func copyWearer() {
        guard let wearer else { return }
        let g = GraphConfig.load(from: WearerOnly(), wearer: wearer)
        writeGraph(g)
        let copied = alerts.settings.withMirroredDefaults().mirroring(wearer)
        alerts.update { $0 = copied }
        settingsFrom = .custom
    }

    /// Who the mirrored settings come from now, in one sentence (General › Settings › In use).
    public var inUse: String {
        switch settingsFrom {
        case .custom: return L10n.t("This Mac's own settings, in the Graph, Rings and Alerts panes.")
        case .default: return L10n.t("Sukrara's defaults.")
        case .wearer:
            let who = wearer?.wearer ?? L10n.t("the wearer")
            if wearer != nil { return L10n.t("\(who)'s settings from their phone.") }
            guard case .following = follow.status else {
                return L10n.t("Not following anyone, so Sukrara's defaults are used.")
            }
            return L10n.t("Waiting for \(who)'s settings. Sukrara's defaults are used until they arrive.")
        }
    }

    // MARK: card settings

    /// The card settings in use (the menu bar's; each place may show its own time).
    public var graph: GraphConfig {
        _ = revision
        return GraphConfig.load(from: store, wearer: wearer)
    }

    public var unit: GlucoseUnit {
        get { _ = revision; return store.unit }
        set { store.set(newValue.rawValue, for: MacSettings.unit); revision += 1 }
    }

    /// The shown value of a number setting: this Mac's in Custom, else the source's (`GraphSource`).
    public func number(_ n: MacSettings.Number) -> Double {
        _ = revision
        return GraphSource(store: store, wearer: wearer).number(n)
    }

    public func setNumber(_ v: Double, _ n: MacSettings.Number) {
        guard editable else { return }
        if store.setNumber(v, for: n) { revision += 1 }
    }

    /// A place's own time shown (0 = same as the default) and sensor time left: always this Mac's.
    public func placeHours(_ p: GraphPlace) -> Double { _ = revision; return store.number(p.hours) }
    public func setPlaceHours(_ v: Double, _ p: GraphPlace) { if store.setNumber(v, for: p.hours) { revision += 1 } }
    public func sensorTime(_ p: GraphPlace) -> Bool { _ = revision; return store.value(p.sensorTime) }
    public func setSensorTime(_ on: Bool, _ p: GraphPlace) { store.set(on, for: p.sensorTime); revision += 1 }

    /// Changes this Mac's card settings (Custom only): `change` edits the current ones, the result is stored.
    public func updateGraph(_ change: (inout GraphConfig) -> Void) {
        guard editable else { return }
        var g = graph
        change(&g)
        writeGraph(g)
    }

    public func addLine(_ mgdl: Double) {
        updateGraph { $0.lines = GraphConfig.parseLines(GraphConfig.linesText($0.lines + [Int(mgdl)])) }
    }

    public func removeLine(_ mgdl: Int) { updateGraph { $0.lines.removeAll { $0 == mgdl } } }

    public var rangesChanged: Bool { graph.bands != GraphConfig().bands }
    public func resetRanges() { updateGraph { $0.bands = GraphConfig().bands } }
    public var marksChanged: Bool { graph.marks != MarkShow.defaults }
    public func resetMarks() { updateGraph { $0.marks = .defaults } }
    public var ringOrderChanged: Bool { graph.ringOrder != DEFAULT_RING_ORDER }
    public func resetRingOrder() { updateGraph { $0.ringOrder = DEFAULT_RING_ORDER } }
    /// Any card setting differs from the defaults (the place's own ones included).
    public var graphChanged: Bool {
        graph != GraphConfig()
            || GraphPlace.allCases.contains {
                placeHours($0) != MacSettings.menubarHours.format.default || !sensorTime($0)
            }
    }

    /// "Reset all graph settings" (after the confirmation): every card setting of this Mac back to its default.
    public func resetGraph() {
        guard editable else { return }
        writeGraph(GraphConfig())
        for p in GraphPlace.allCases {
            store.set(p.hours.format.default, for: p.hours.spec)
            store.set(true, for: p.sensorTime)
        }
        revision += 1
    }

    /// Stores every mirrored card setting of `g` in its stored form.
    private func writeGraph(_ g: GraphConfig) {
        let b = g.bands
        let numbers: [(MacSettings.Number, Int)] = [
            (MacSettings.bandVeryLow, b.veryLow), (MacSettings.bandLow, b.low), (MacSettings.bandTight, b.tight),
            (MacSettings.bandHigh, b.high), (MacSettings.bandVeryHigh, b.veryHigh),
            (MacSettings.bandExtremelyHigh, b.extremelyHigh), (MacSettings.graphHours, g.hours),
            (MacSettings.ringCount, g.ringCount), (MacSettings.gridHours, g.gridHours),
            (MacSettings.countdownHours, g.countdownHours),
        ]
        for (n, v) in numbers { store.setNumber(Double(v), for: n) }
        store.set(GraphConfig.slotsText(g.slots), for: MacSettings.slots)
        store.set(g.ringRange.rawValue, for: MacSettings.ringRange)
        store.set(GraphConfig.ringOrderText(g.ringOrder), for: MacSettings.ringOrder)
        store.set(g.ringMarks, for: MacSettings.ringMarks)
        store.set(g.side, for: MacSettings.side)
        store.set(g.columnCrown, for: MacSettings.columnCrown)
        store.set(g.marks.settingsText, for: MacSettings.marks)
        store.set(GraphConfig.linesText(g.lines), for: MacSettings.lines)
        store.set(g.lastPlace.rawValue, for: MacSettings.lastPlace)
        store.set(g.lastElse.map(GraphConfig.slotText) ?? "", for: MacSettings.lastElse)
        revision += 1
    }

    // MARK: alert settings

    /// The alert rules in use as the Alerts pane shows them: the wearer's mirrored values, the defaults (Default) or
    /// this Mac's own; what isn't mirrored is always this Mac's.
    public var shownAlerts: AlertSettings {
        _ = revision
        return alerts.settings.inUse(from: settingsFrom, wearer: wearer)
    }

    /// The mirrored alert rows (thresholds, repeats, quiet times) are read-only outside Custom: they come from the
    /// wearer or the defaults.
    public var alertsMirrored: Bool { !editable }
}

/// A store with nothing in it and "Settings from: Wearer": loads only what the wearer sent (else the defaults).
private final class WearerOnly: SettingsStoring, Sendable {
    func value<Value>(_ spec: SettingSpec<Value>) -> Value {
        spec.key == MacSettings.settingsFrom.key
            ? (SettingsFrom.wearer.rawValue as? Value ?? spec.defaultValue)
            : spec.defaultValue
    }
    func set<Value>(_ value: Value, for spec: SettingSpec<Value>) {}
}
