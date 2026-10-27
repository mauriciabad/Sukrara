// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// One kind's rule (SPEC §3): `threshold` is mg/dL (level kinds), mg/dL/min (rate kinds) or minutes (no data).
public struct AlertRule: Equatable, Sendable {
    public var on: Bool
    public var threshold: Double
    /// Minutes between repeats within an episode; 0 = once per episode.
    public var repeatMin: Int
    /// Ring like an alarm (Mac: Time Sensitive when entitled).
    public var alarm: Bool

    public init(on: Bool, threshold: Double, repeatMin: Int, alarm: Bool = false) {
        self.on = on
        self.threshold = threshold
        self.repeatMin = repeatMin
        self.alarm = alarm
    }

    /// SPEC §3 defaults (the owner's own settings, 2026-10-18).
    public static func defaults(_ kind: AlertKind) -> AlertRule {
        switch kind {
        case .veryLow: AlertRule(on: true, threshold: 55, repeatMin: 5, alarm: true)
        case .low: AlertRule(on: true, threshold: 70, repeatMin: 5)
        case .high: AlertRule(on: true, threshold: 180, repeatMin: 15)
        case .veryHigh: AlertRule(on: true, threshold: 250, repeatMin: 10)
        case .extremelyHigh: AlertRule(on: true, threshold: 300, repeatMin: 5, alarm: true)
        case .fallingFast, .risingFast: AlertRule(on: false, threshold: 2.0, repeatMin: 30)
        case .noData: AlertRule(on: true, threshold: 20, repeatMin: 60)
        }
    }
}

/// A treatment / falling quiet rule: on, and its length in minutes.
public struct QuietRule: Codable, Equatable, Sendable {
    public var on: Bool
    public var min: Double
    public init(on: Bool, min: Double) {
        self.on = on
        self.min = min
    }
}

/// The notification actions' numbers (SPEC §2–§3): Mute's first length, Remind less's interval, the margin.
public struct NotificationActionSettings: Codable, Equatable, Sendable {
    public var muteMin: Int
    public var relaxMin: Int
    public var backMgdl: Int
    public var backMin: Int
    public init(muteMin: Int = 30, relaxMin: Int = 30, backMgdl: Int = 10, backMin: Int = 10) {
        self.muteMin = muteMin
        self.relaxMin = relaxMin
        self.backMgdl = backMgdl
        self.backMin = backMin
    }
}

/// Sound choice: the pack every kind uses, per-kind overrides (`system` / `none` allowed), shuffle variants.
public struct AlertSoundSettings: Codable, Equatable, Sendable {
    public static let packs = ["arcade", "kart", "quest", "soft"]
    public var pack: String
    public var kinds: [String: String]
    public var shuffle: Bool
    public init(pack: String = "quest", kinds: [String: String] = [:], shuffle: Bool = true) {
        self.pack = pack
        self.kinds = kinds
        self.shuffle = shuffle
    }
}

/// Where a follower's alerts come from (SPEC §3): mirror the wearer's `alert` events, or run the rules here.
public enum AlertSource: String, Codable, Sendable {
    case wearer
    case own
}

/// The alert settings: SPEC §3 JSON, the same keys on Android and Mac, and the shape the wearer mirrors to followers.
public struct AlertSettings: Equatable, Sendable {
    public static let formatVersion = 1

    public var on = true
    public var rules: [AlertKind: AlertRule] = Dictionary(uniqueKeysWithValues: AlertKind.allCases.map { ($0, .defaults($0)) })
    public var muteAfterInsulin = QuietRule(on: true, min: 60)
    public var muteFallingHigh = QuietRule(on: true, min: 20)
    public var muteAfterCarbs = QuietRule(on: true, min: 15)
    public var sounds = AlertSoundSettings()
    public var notificationActions = NotificationActionSettings()
    public var source = AlertSource.wearer

    public init() {}

    public subscript(kind: AlertKind) -> AlertRule {
        get { rules[kind] ?? .defaults(kind) }
        set { rules[kind] = newValue }
    }

    /// The treatment whose quiet window covers `kind`, or nil: the rule is off, or `kind` is never quieted by a
    /// treatment (extremely high, no data).
    public func quietedBy(_ kind: AlertKind) -> TreatmentKind? {
        guard kind != .extremelyHigh, let t = kind.treatment else { return nil }
        return quietRule(t).on ? t : nil
    }

    public func quietRule(_ treatment: TreatmentKind) -> QuietRule {
        treatment == .rapidInsulin ? muteAfterInsulin : muteAfterCarbs
    }

    /// How long a treatment quiets the alerts it answers.
    public func quietDuration(_ treatment: TreatmentKind) -> TimeInterval { quietRule(treatment).min * 60 }

    /// The sound pack `kind` plays: the per-kind override, else the pack.
    public func soundPack(for kind: AlertKind) -> String { sounds.kinds[kind.rawValue] ?? sounds.pack }

    /// The slowest enabled level alert's repeat (minutes; 0 = none): Remind less must be slower than it.
    public var relaxFloor: Int {
        AlertKind.levelsByPriority.map { self[$0] }.filter { $0.on && $0.repeatMin > 0 }.map(\.repeatMin).max() ?? 0
    }

    /// The Remind less presets the settings offer: only ones slower than every enabled level alert's repeat.
    public var relaxChoices: [Int] {
        let c = AlertLimits.relaxPresets.filter { $0 > relaxFloor }
        return c.isEmpty ? [Int(AlertLimits.actionRelax.max)] : c
    }

    /// The Mute… lengths a notification offers: `muteMin` first, then the other presets.
    public var muteChoices: [Int] {
        var out = [notificationActions.muteMin]
        for p in AlertLimits.mutePresets where !out.contains(p) { out.append(p) }
        return out
    }

    /// Whether Remind less would slow `kind` (it repeats faster than `relaxMin`).
    public func canRelax(_ kind: AlertKind) -> Bool {
        let r = self[kind].repeatMin
        return r > 0 && r < notificationActions.relaxMin
    }
}

// MARK: - SPEC §3 JSON

extension AlertSettings: Codable {
    private enum Key: String, CodingKey {
        case v, on, alerts, muteAfterInsulin, muteFallingHigh, muteAfterCarbs, sounds, notificationActions, source
    }

    private struct DynamicKey: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ s: String) { stringValue = s }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    /// The threshold's JSON key per kind (SPEC §3).
    private static func thresholdKey(_ kind: AlertKind) -> String {
        switch kind.group {
        case .level: "mgdl"
        case .rate: "rate"
        case .noData: "min"
        }
    }

    private struct PartialRule: Decodable {
        var on: Bool?
        var alarm: Bool?
        var mgdl: Double?
        var rate: Double?
        var min: Double?
        var repeatMin: Double?
    }

    private struct PartialQuiet: Decodable {
        var on: Bool?
        var min: Double?
    }

    private struct PartialSounds: Decodable {
        var pack: String?
        var kinds: [String: String]?
        var shuffle: Bool?
    }

    private struct PartialActions: Decodable {
        var muteMin: Double?
        var relaxMin: Double?
        var backMgdl: Double?
        var backMin: Double?
    }

    /// Lenient (a wearer's mirrored JSON, a file from another version): unknown keys are ignored, a missing or
    /// malformed key keeps its §3 default, every number is coerced to its schema format (`AlertLimits`).
    public init(from decoder: Decoder) throws {
        self.init()
        let c = try decoder.container(keyedBy: Key.self)
        if let on = try? c.decode(Bool.self, forKey: .on) { self.on = on }
        let partial = (try? c.decode([String: PartialRule].self, forKey: .alerts)) ?? [:]
        for kind in AlertKind.allCases {
            guard let p = partial[kind.rawValue] else { continue }
            var r = AlertRule.defaults(kind)
            if let on = p.on { r.on = on }
            if let alarm = p.alarm { r.alarm = alarm }
            let raw: Double? =
                switch kind.group {
                case .level: p.mgdl
                case .rate: p.rate
                case .noData: p.min
                }
            if let raw { r.threshold = AlertLimits.threshold(kind).coerce(raw) }
            if let x = p.repeatMin { r.repeatMin = Int(AlertLimits.alertRepeat.coerce(x)) }
            self[kind] = r
        }
        func quiet(_ key: Key, _ d: QuietRule, _ limits: AlertLimits.Number) -> QuietRule {
            guard let p = try? c.decode(PartialQuiet.self, forKey: key) else { return d }
            return QuietRule(on: p.on ?? d.on, min: p.min.map(limits.coerce) ?? d.min)
        }
        muteAfterInsulin = quiet(.muteAfterInsulin, muteAfterInsulin, AlertLimits.muteInsulin)
        muteFallingHigh = quiet(.muteFallingHigh, muteFallingHigh, AlertLimits.muteFalling)
        muteAfterCarbs = quiet(.muteAfterCarbs, muteAfterCarbs, AlertLimits.muteCarbs)
        if let p = try? c.decode(PartialSounds.self, forKey: .sounds) {
            sounds = AlertSoundSettings(pack: p.pack ?? sounds.pack, kinds: p.kinds ?? [:], shuffle: p.shuffle ?? sounds.shuffle)
        }
        if let p = try? c.decode(PartialActions.self, forKey: .notificationActions) {
            let d = notificationActions
            func int(_ x: Double?, _ limits: AlertLimits.Number, _ fallback: Int) -> Int { x.map { Int(limits.coerce($0)) } ?? fallback }
            notificationActions = NotificationActionSettings(
                muteMin: int(p.muteMin, AlertLimits.actionMute, d.muteMin),
                relaxMin: int(p.relaxMin, AlertLimits.actionRelax, d.relaxMin),
                backMgdl: int(p.backMgdl, AlertLimits.backMgdl, d.backMgdl),
                backMin: int(p.backMin, AlertLimits.backMin, d.backMin))
        }
        if let s = try? c.decode(AlertSource.self, forKey: .source) { source = s }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Key.self)
        try c.encode(Self.formatVersion, forKey: .v)
        try c.encode(on, forKey: .on)
        var alerts = c.nestedContainer(keyedBy: DynamicKey.self, forKey: .alerts)
        for kind in AlertKind.allCases {
            let r = self[kind]
            var rule = alerts.nestedContainer(keyedBy: DynamicKey.self, forKey: DynamicKey(kind.rawValue))
            try rule.encode(r.on, forKey: DynamicKey("on"))
            try rule.encode(r.threshold, forKey: DynamicKey(Self.thresholdKey(kind)))
            try rule.encode(r.repeatMin, forKey: DynamicKey("repeatMin"))
            try rule.encode(r.alarm, forKey: DynamicKey("alarm"))
        }
        try c.encode(muteAfterInsulin, forKey: .muteAfterInsulin)
        try c.encode(muteFallingHigh, forKey: .muteFallingHigh)
        try c.encode(muteAfterCarbs, forKey: .muteAfterCarbs)
        try c.encode(sounds, forKey: .sounds)
        try c.encode(notificationActions, forKey: .notificationActions)
        try c.encode(source, forKey: .source)
    }

    /// Lenient decode of stored / mirrored JSON; anything unreadable gives the defaults.
    public static func decode(_ data: Data) -> AlertSettings {
        (try? JSONDecoder().decode(AlertSettings.self, from: data)) ?? AlertSettings()
    }

    /// Canonical JSON (sorted keys): what is stored and mirrored.
    public func encoded() -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        // Encoding plain numbers, strings and bools can't fail.
        return (try? e.encode(self)) ?? Data("{}".utf8)
    }
}
