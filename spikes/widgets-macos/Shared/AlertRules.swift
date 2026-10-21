// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

// The alert rules as data (spikes/alerts/SPEC.md §3, same keys and defaults as Android's AlertSettings.kt) and the
// pure functions every surface derives from them: AlertStatus (badge, mute lines) and LastTreatment (the slot).
// Shared by the menubar app (symlink in spikes/menubar/Sources/GlucoseBar: the AlertEngine uses the same `mutedBy`,
// `muteS` and `AlertStatus.level`), the widget extension (the host writes the settings into the snapshot) and the
// Render CLI, so the chart, the badge, the slot and the alerts can't disagree.

enum AlertKind: String, CaseIterable {
    case veryLow = "very_low", low, high, veryHigh = "very_high", extremelyHigh = "extremely_high"
    case fallingFast = "falling_fast", risingFast = "rising_fast", noData = "no_data"

    /// One notification per group: a new alert of the group replaces the previous one.
    enum Group: String { case level, rate, noData = "no_data" }
    var group: Group {
        switch self {
        case .veryLow, .low, .high, .veryHigh, .extremelyHigh: .level
        case .fallingFast, .risingFast: .rate
        case .noData: .noData
        }
    }
    var title: String {
        switch self {
        case .veryLow: "Very low"
        case .low: "Low"
        case .high: "High"
        case .veryHigh: "Very high"
        case .extremelyHigh: "Extremely high"
        case .fallingFast: "Falling fast"
        case .risingFast: "Rising fast"
        case .noData: "No glucose data"
        }
    }
    /// Alerts below a threshold (strictly `<`: low 70 alerts at 69); the other levels are `>=`.
    var lowSide: Bool { self == .veryLow || self == .low }
    /// Level alerts by severity: the first that matches wins.
    static let levels: [AlertKind] = [.veryLow, .extremelyHigh, .veryHigh, .low, .high]
    /// Severity within a side (an episode, see AlertEngine): moving to a higher one alerts at once.
    var severity: Int {
        switch self { case .veryLow, .veryHigh: 2; case .extremelyHigh: 3; default: 1 }
    }
    /// One alert episode per side: lows, highs, falling fast, rising fast, no data.
    var side: String {
        switch self { case .veryLow, .low: "low"; case .high, .veryHigh, .extremelyHigh: "high"; default: rawValue }
    }
    /// The side's main kind (a Mute / Relax shown without an episode): "low" → low, "high" → high, else the side.
    static func main(_ side: String) -> AlertKind {
        switch side { case "low": .low; case "high": .high; default: AlertKind(rawValue: side) ?? .noData }
    }
    /// Every side a Mute / Relax / Mute all acts on.
    static let sides = ["low", "high", "falling_fast", "rising_fast", "no_data"]
    /// The treatment that answers it ("carbs" / "rapid"), whatever the mute settings; nil for no data.
    var treatment: String? {
        switch self {
        case .veryLow, .low, .fallingFast: "carbs"
        case .high, .veryHigh, .extremelyHigh, .risingFast: "rapid"
        case .noData: nil
        }
    }

    /// The headline. Lows and highs say when their treatment was already logged in this episode (`treated` = 1, or 2
    /// for two or more): "Low glucose · eat more" / "· eat more again". Highs never suggest more insulin (stacking
    /// doses is dangerous): "High glucose · still high after insulin" / "· after more insulin".
    func message(treated: Int = 0) -> String {
        let base = switch self {
        case .veryLow, .low, .high, .veryHigh, .extremelyHigh: "\(title) glucose"
        default: title
        }
        guard treated > 0, group == .level else { return base }
        if lowSide { return base + (treated == 1 ? " · eat more" : " · eat more again") }
        return base + (treated == 1 ? " · still high after insulin" : " · still high after more insulin")
    }

    /// The number format of this kind's threshold (spikes/settings/settings.json).
    var thresholdSpec: NumberSpec {
        switch self {
        case .veryLow: SettingsSpec.thresholdVeryLow
        case .low: SettingsSpec.thresholdLow
        case .high: SettingsSpec.thresholdHigh
        case .veryHigh: SettingsSpec.thresholdVeryHigh
        case .extremelyHigh: SettingsSpec.thresholdExtremelyHigh
        case .fallingFast, .risingFast: SettingsSpec.thresholdRate
        case .noData: SettingsSpec.thresholdNoData
        }
    }

    /// The user's own settings (2026-10-18), same as Android's DEFAULTS.
    var defaultRule: AlertRule {
        switch self {
        case .veryLow: AlertRule(on: true, mgdl: 55, repeatMin: 5, alarm: true)
        case .low: AlertRule(on: true, mgdl: 70, repeatMin: 5)
        case .high: AlertRule(on: true, mgdl: 180, repeatMin: 15)
        case .veryHigh: AlertRule(on: true, mgdl: 250, repeatMin: 10)
        case .extremelyHigh: AlertRule(on: true, mgdl: 300, repeatMin: 5, alarm: true)
        case .fallingFast, .risingFast: AlertRule(on: false, rate: 2.0, repeatMin: 30)
        case .noData: AlertRule(on: true, min: 20, repeatMin: 60)
        }
    }
}

// MARK: settings (SPEC §3)

struct AlertRule: Codable, Equatable {
    var on: Bool
    var mgdl: Double? = nil         // level kinds
    var rate: Double? = nil         // rate kinds, mg/dL/min
    var min: Double? = nil          // no_data, minutes
    var repeatMin: Int              // 0 = never repeat
    /// "Like an alarm" (SPEC §3): on the Mac = Time Sensitive (breaks through Focus), when the build is entitled to it.
    var alarm: Bool = false

    /// The number the rule compares against (mg/dL, mg/dL/min or minutes) — `th` in the §4 event.
    var threshold: Double {
        get { mgdl ?? rate ?? min ?? 0 }
        set { if mgdl != nil { mgdl = newValue } else if rate != nil { rate = newValue } else { min = newValue } }
    }
}

struct AlertMute: Codable, Equatable { var on: Bool; var min: Double }

/// The alert notification's actions (SPEC §2): the first length *Mute…* offers, the interval *Relax* repeats at, and
/// the margin that ends Mute and Relax (back by `backMgdl` inside the threshold for `backMin` minutes). Their formats
/// (range, presets) are the schema's `actionMute` / `actionRelax` / `backMgdl` / `backMin` (spikes/settings/settings.json).
struct AlertActions: Codable, Equatable {
    var muteMin: Int
    var relaxMin: Int
    var backMgdl: Int
    var backMin: Int
    /// Minutes a Mute (preset or typed) accepts.
    static var muteRange: ClosedRange<Int> { Int(SettingsSpec.actionMute.min)...Int(SettingsSpec.actionMute.max) }
    /// *Mute all*: minutes a custom length accepts, and the presets (0 = Never, until turned back on).
    static var muteAllRange: ClosedRange<Int> { Int(SettingsSpec.muteAll.min)...Int(SettingsSpec.muteAll.max) }
    static var muteAllPresets: [Int] { SettingsSpec.muteAll.presets.map { Int($0) } }
}

struct AlertSounds: Codable, Equatable {
    var pack: String                // arcade | kart | quest | soft | system | none
    var kinds: [String: String]     // per-kind override, same values
    var shuffle: Bool
}

struct AlertSettings: Codable, Equatable {
    var v = 1
    var on = true
    var alerts: [String: AlertRule] = Dictionary(uniqueKeysWithValues: AlertKind.allCases.map { ($0.rawValue, $0.defaultRule) })
    var muteAfterInsulin = AlertMute(on: true, min: 60)
    var muteFallingHigh = AlertMute(on: true, min: 20)
    var muteAfterCarbs = AlertMute(on: true, min: 15)
    var sounds = AlertSounds(pack: "quest", kinds: [:], shuffle: true)
    /// relaxMin 30: slower than every level alert's default repeat (5–15 min), so Relax shows on all of them.
    var notificationActions = AlertActions(muteMin: 30, relaxMin: 30, backMgdl: 10, backMin: 10)
    /// Derived (`AlertSettings.load`): "wearer" = mirror the wearer's alert events (Settings from: Wearer), "own" = run these rules.
    var source = "wearer"

    static let packs = ["arcade", "kart", "quest", "soft"]
    static let key = "alertSettings"

    subscript(k: AlertKind) -> AlertRule {
        get { alerts[k.rawValue] ?? k.defaultRule }
        set { alerts[k.rawValue] = newValue }
    }

    /// The slowest enabled level alert's repeat (minutes; 0 = none): Relax must be slower than it.
    var relaxFloor: Int { AlertKind.levels.map { self[$0] }.filter { $0.on && $0.repeatMin > 0 }.map(\.repeatMin).max() ?? 0 }
    /// The Relax presets the settings offer: only ones slower than every enabled level alert's repeat, so the
    /// button shows on all of them (Android's AlertSettings.relaxChoices); a custom value starts at `relaxFloor + 1`.
    var relaxChoices: [Int] {
        let c = SettingsSpec.actionRelax.presets.map { Int($0) }.filter { $0 > relaxFloor }
        return c.isEmpty ? [Int(SettingsSpec.actionRelax.max)] : c
    }
    /// The *Mute…* lengths a notification offers: `muteMin` first, then the other presets (Android's muteChoices).
    var muteChoices: [Int] {
        var out = [notificationActions.muteMin]
        for p in SettingsSpec.actionMute.presets.map({ Int($0) }) where !out.contains(p) { out.append(p) }
        return out
    }

    init() {}

    /// Lenient: any missing or malformed key keeps its §3 default (a file written by an older or newer app still
    /// loads), numbers may be ints or doubles. Every number is coerced to its schema format (`NumberSpec.coerce`:
    /// in range, on step), so a value the settings accept loads back unchanged and nothing else gets in. `source` is
    /// derived (`AlertSettings.load`, from Settings from), never read.
    init(from decoder: Decoder) throws {
        struct PartialRule: Decodable { var on, alarm: Bool?; var mgdl, rate, min, repeatMin: Double? }
        struct PartialMute: Decodable { var on: Bool?; var min: Double? }
        struct PartialSounds: Decodable { var pack: String?; var kinds: [String: String]?; var shuffle: Bool? }
        struct PartialActions: Decodable { var muteMin, relaxMin, backMgdl, backMin: Double? }
        enum K: String, CodingKey { case v, on, alerts, muteAfterInsulin, muteFallingHigh, muteAfterCarbs, sounds, notificationActions, source }
        let c = try decoder.container(keyedBy: K.self)
        if let on = try? c.decode(Bool.self, forKey: .on) { self.on = on }
        let rules = (try? c.decode([String: PartialRule].self, forKey: .alerts)) ?? [:]
        for k in AlertKind.allCases {
            guard let p = rules[k.rawValue] else { continue }
            var r = k.defaultRule
            if let on = p.on { r.on = on }
            let spec = k.thresholdSpec
            if let x = p.mgdl, r.mgdl != nil { r.mgdl = spec.coerce(x) }
            if let x = p.rate, r.rate != nil { r.rate = spec.coerce(x) }
            if let x = p.min, r.min != nil { r.min = spec.coerce(x) }
            if let x = p.repeatMin { r.repeatMin = Int(SettingsSpec.alertRepeat.coerce(x)) }
            if let x = p.alarm { r.alarm = x }
            self[k] = r
        }
        func mute(_ key: K, _ d: AlertMute, _ spec: NumberSpec) -> AlertMute {
            guard let p = try? c.decode(PartialMute.self, forKey: key) else { return d }
            return AlertMute(on: p.on ?? d.on, min: p.min.map(spec.coerce) ?? d.min)
        }
        muteAfterInsulin = mute(.muteAfterInsulin, muteAfterInsulin, SettingsSpec.muteInsulin)
        muteFallingHigh = mute(.muteFallingHigh, muteFallingHigh, SettingsSpec.muteFalling)
        muteAfterCarbs = mute(.muteAfterCarbs, muteAfterCarbs, SettingsSpec.muteCarbs)
        if let p = try? c.decode(PartialSounds.self, forKey: .sounds) {
            sounds = AlertSounds(pack: p.pack ?? sounds.pack, kinds: p.kinds ?? [:], shuffle: p.shuffle ?? sounds.shuffle)
        }
        if let p = try? c.decode(PartialActions.self, forKey: .notificationActions) {
            let d = notificationActions
            notificationActions = AlertActions(muteMin: p.muteMin.map { Int(SettingsSpec.actionMute.coerce($0)) } ?? d.muteMin,
                                               relaxMin: p.relaxMin.map { Int(SettingsSpec.actionRelax.coerce($0)) } ?? d.relaxMin,
                                               backMgdl: p.backMgdl.map { Int(SettingsSpec.backMgdl.coerce($0)) } ?? d.backMgdl,
                                               backMin: p.backMin.map { Int(SettingsSpec.backMin.coerce($0)) } ?? d.backMin)
        }
    }

    /// This Mac's own stored settings (`alertSettings`); what applies is `load` (WearerSettings.swift).
    static func own(_ d: UserDefaults = .standard) -> AlertSettings {
        guard let s = d.string(forKey: key),
              let a = try? JSONDecoder().decode(AlertSettings.self, from: Data(s.utf8)) else { return AlertSettings() }
        return a
    }

    var json: String {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? e.encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }

    func save(_ d: UserDefaults = .standard) { d.set(json, forKey: Self.key) }

    /// Which sound a kind plays: the per-kind override, else the pack.
    func pack(for k: AlertKind) -> String { sounds.kinds[k.rawValue] ?? sounds.pack }

    /// The treatment whose mute window quiets `k` now, or nil: the rule is off, or `k` is never muted (extremely
    /// high, no data). Carbs quiet very low, low and falling fast; rapid insulin high, very high and rising fast.
    func mutedBy(_ k: AlertKind) -> String? {
        if k == .extremelyHigh { return nil }
        if k.treatment == "carbs", muteAfterCarbs.on { return "carbs" }
        if k.treatment == "rapid", muteAfterInsulin.on { return "rapid" }
        return nil
    }

    /// How long a treatment ("carbs" / "rapid") quiets the alerts it answers, in seconds.
    func muteS(_ treatment: String) -> TimeInterval { (treatment == "rapid" ? muteAfterInsulin.min : muteAfterCarbs.min) * 60 }
}

// MARK: - Status (AlertStatus.kt)

/// What the alert rules say about the latest reading, for the badge under the value: nothing (in range, stale, alerts
/// off), a treatment is needed, or one was taken and its mute window still covers the alert.
enum AlertStatus: Equatable {
    case none
    /// Out of range and nothing logged that covers it: tell the user what to do.
    case needed(AlertKind)
    /// Out of range, but `treatment`'s mute window covers it until `until` (epoch s).
    case taken(AlertKind, treatment: GraphMark, until: TimeInterval)
    /// Out of range, muted on this device (the side's *Mute*, or *Mute all*) until `until` (epoch s; `never` = until
    /// turned back on): the same faded badge.
    case paused(AlertKind, until: TimeInterval)
    /// In range (or no current reading) while *Mute all* is on, until `until` (`never` = until turned back on).
    case muted(until: TimeInterval)

    /// *Mute all* without an end (Android's AlertEngine.NEVER): far in the future, finite so it encodes in JSON.
    static let never: TimeInterval = Date.distantFuture.timeIntervalSince1970

    /// Readings older than this never alert (backfill, a late chunk); `AlertEngine.fresh`.
    static let fresh: TimeInterval = 15 * 60

    /// The level kind a reading of `mgdl` taken at `t` is in at `now`, or nil (stale, in range, alerts off). Lows `<`,
    /// highs `>=`; the AlertEngine uses this too.
    static func level(mgdl: Double, at t: TimeInterval, _ s: AlertSettings, now: TimeInterval) -> AlertKind? {
        guard s.on, now - t <= fresh else { return nil }
        return AlertKind.levels.first { k in s[k].on && (k.lowSide ? mgdl < s[k].threshold : mgdl >= s[k].threshold) }
    }

    /// When `m`'s mute window ends (epoch s), or nil if this kind of treatment mutes nothing (rule off, long, note).
    static func muteUntil(_ m: GraphMark, _ s: AlertSettings) -> TimeInterval? {
        switch m.k {
        case "rapid" where s.muteAfterInsulin.on: m.t + s.muteS("rapid")
        case "carbs" where s.muteAfterCarbs.on: m.t + s.muteS("carbs")
        default: nil
        }
    }

    /// Same rules as the AlertEngine: carbs cover very low and low, rapid insulin high and very high, while their mute
    /// window lasts (`t ≤ now < t + min`); extremely high is never covered. `marks` = live entries (no deleted ones).
    /// Otherwise `paused` (side → until, this device's *Mute* of the side) or `allMuted` (*Mute all*'s end, 0 = off,
    /// `never` = no end) covers every level kind, very low and extremely high included (round f30): a side's pause is
    /// the later of the two. In range or without a current reading while Mute all is on: `muted`.
    static func of(_ r: GraphReading?, _ s: AlertSettings, _ marks: [GraphMark], now: TimeInterval,
                   paused: [String: TimeInterval] = [:], allMuted: TimeInterval = 0) -> AlertStatus {
        guard let r, let k = level(mgdl: r.v, at: r.t, s, now: now) else { return s.on && allMuted > now ? .muted(until: allMuted) : .none }
        let cover = s.mutedBy(k).flatMap { by in marks.filter { $0.k == by && $0.t <= now }
            .compactMap { m in muteUntil(m, s).map { (m, $0) } }
            .filter { $0.1 > now }
            .max { $0.0.t < $1.0.t } }
        if let cover { return .taken(k, treatment: cover.0, until: cover.1) }
        let until = max(paused[k.side] ?? 0, allMuted)
        if until > now { return .paused(k, until: until) }
        return .needed(k)
    }
}

// MARK: - Last treatment (LastTreatment.kt)

/// The entry for the last-treatment slot beside the value, and how much of its mute is left.
enum LastTreatment {
    /// How long after it was logged an entry stays in the slot: rapid insulin 4 h (still working?), carbs 3 min per
    /// gram between 30 min and 3 h (a small amount needn't stay for hours); long-acting and notes never.
    static func keepS(_ m: GraphMark) -> TimeInterval? {
        switch m.k { case "rapid": 4 * 3600; case "carbs": min(max(m.a * 180, 30 * 60), 3 * 3600); default: nil }
    }

    struct Info: Equatable {
        var tr: GraphMark
        /// Its mute's end (epoch s); nil = its kind mutes nothing in the settings.
        var until: TimeInterval?
        /// While `tr` is rapid insulin whose mute runs: the newest carbs still inside `keepS` (the slot draws both,
        /// concentric, where it is big enough: `LastSlot`).
        var carbs: Companion? = nil
        /// Share of the mute window left at `now`, 0 when over or none.
        func left(_ now: TimeInterval) -> Double {
            guard let u = until else { return 0 }
            return min(max((u - now) / max(u - tr.t, 1), 0), 1)
        }
    }

    /// Rapid insulin whose mute is still running (carbs on top of it show once it is over), else the treatment that
    /// mutes the current alert (`status` taken), else the newest rapid / carbs entry still inside `keepS` (a meal logged
    /// at the same minute shows the insulin); nil = nothing recent (the slot shows its fallback). Running insulin carries
    /// the newest carbs inside `keepS` along (`carbs`).
    static func of(_ marks: [GraphMark], _ s: AlertSettings, _ status: AlertStatus, now: TimeInterval) -> Info? {
        let tr: GraphMark
        let live = marks.filter { $0.t <= now }
        if let r = live.filter({ $0.k == "rapid" && (AlertStatus.muteUntil($0, s) ?? 0) > now }).max(by: { $0.t < $1.t }) {
            tr = r
        } else if case let .taken(_, t, _) = status { tr = t } else {
            func key(_ m: GraphMark) -> (Int, Int) { (Int((m.t / 60).rounded(.down)), m.k == "rapid" ? 1 : 0) }
            guard let t = live.filter({ m in keepS(m).map { now - m.t < $0 } == true })
                .max(by: { key($0) < key($1) }) else { return nil }
            tr = t
        }
        let until = AlertStatus.muteUntil(tr, s)
        // carbs logged while the insulin works ride along in the same slot (the user, 2026-10-21)
        var carbs: Companion? = nil
        if tr.k == "rapid", (until ?? 0) > now,
           let c = live.filter({ m in m.k == "carbs" && keepS(m).map { now - m.t < $0 } == true }).max(by: { $0.t < $1.t }) {
            carbs = Companion(tr: c, until: AlertStatus.muteUntil(c, s))
        }
        return Info(tr: tr, until: until, carbs: carbs)
    }

    /// The carbs drawn with running insulin (an `Info` without a companion of its own).
    struct Companion: Equatable {
        var tr: GraphMark
        var until: TimeInterval?
        var info: Info { Info(tr: tr, until: until) }
    }
}
