// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraCore
import SukraraGraph

/// A place that shows the card with settings of its own (schema `graph.hours.place`, `graph.sensorTime.place`).
public enum GraphPlace: String, CaseIterable, Sendable {
    case menubar, widget

    var hours: MacSettings.Number { self == .menubar ? MacSettings.menubarHours : MacSettings.widgetHours }
    var sensorTime: SettingSpec<Bool> {
        self == .menubar ? MacSettings.menubarSensorTime : MacSettings.widgetSensorTime
    }
}

/// Where the mirrored card settings come from now (Settings from): this Mac's own values (Custom), Sukrara's defaults
/// (Default, and Wearer before the wearer's settings arrive), or the wearer's values (a key they didn't send = its
/// default, PROTOCOL-v2 "Settings").
struct GraphSource {
    let store: any SettingsStoring
    /// The wearer's settings while "Settings from: Wearer" and they arrived; nil otherwise.
    let wearer: WearerSettings?
    let own: Bool

    init(store: any SettingsStoring, wearer: WearerSettings?) {
        self.store = store
        own = store.settingsFrom == .custom
        self.wearer = store.settingsFrom == .wearer ? wearer : nil
    }

    func number(_ n: MacSettings.Number) -> Double {
        if own { return store.number(n) }
        return wearer?.number(n.id) ?? n.format.default
    }

    func bool(_ id: String, _ spec: SettingSpec<Bool>) -> Bool {
        if own { return store.value(spec) }
        return wearer?.bool(id) ?? spec.defaultValue
    }

    func string(_ id: String, _ spec: SettingSpec<String>) -> String {
        if own { return store.value(spec) }
        return wearer?.string(id) ?? spec.defaultValue
    }
}

extension GraphConfig {
    /// The card's settings for `place` (nil = the defaults every place starts from). The mirrored ones follow Settings
    /// from (`GraphSource`); each place's own time shown and sensor time left are always this Mac's. Every value goes
    /// through its schema format, so whatever is stored or received loads as a value the settings accept.
    public static func load(from store: some SettingsStoring, wearer: WearerSettings?, place: GraphPlace? = nil)
        -> GraphConfig
    {
        let src = GraphSource(store: store, wearer: wearer)
        func n(_ s: MacSettings.Number) -> Int { Int(src.number(s).rounded()) }
        var c = GraphConfig()
        c.bands = Bands(
            veryLow: n(MacSettings.bandVeryLow), low: n(MacSettings.bandLow), tight: n(MacSettings.bandTight),
            high: n(MacSettings.bandHigh), veryHigh: n(MacSettings.bandVeryHigh),
            extremelyHigh: n(MacSettings.bandExtremelyHigh))
        c.hours = n(MacSettings.graphHours)
        if let place {
            let own = store.number(place.hours)
            if own > 0 { c.hours = Int(own) }
            c.sensorTime = store.value(place.sensorTime)
        }
        c.ringCount = n(MacSettings.ringCount)
        c.slots = parseSlots(src.string("graph.slots", MacSettings.slots)) ?? c.slots
        c.ringRange = TirRange(rawValue: src.string("graph.ringRange", MacSettings.ringRange)) ?? .inRange
        c.ringOrder = parseRingOrder(src.string("graph.ringOrder", MacSettings.ringOrder)) ?? c.ringOrder
        c.ringMarks = src.bool("graph.ringMarks", MacSettings.ringMarks)
        c.side = src.bool("graph.side", MacSettings.side)
        c.columnCrown = src.bool("graph.columnCrown", MacSettings.columnCrown)
        c.marks = MarkShow.parseSettings(src.string("graph.marks", MacSettings.marks))
        let lines = src.string("graph.lines", MacSettings.lines)
        c.lines = lines.trimmingCharacters(in: .whitespaces).isEmpty ? [] : parseLines(lines)
        c.gridHours = n(MacSettings.gridHours)
        c.lastPlace = LastPlace(rawValue: src.string("graph.lastPlace", MacSettings.lastPlace)) ?? .nearValue
        c.lastElse = parseSlot(src.string("graph.lastElse", MacSettings.lastElse))
        c.countdownHours = n(MacSettings.countdownHours)
        return c
    }
}
