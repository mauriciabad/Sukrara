// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Security
import UserNotifications

// Glucose alerts (spikes/alerts/SPEC.md — the contract shared with Sukrara for Android):
//   AlertSettings  the §3 JSON, one string in UserDefaults `alertSettings` (same keys and defaults as Android)
//   AlertEngine    §1–§2 rules on readings + a 60 s timer, with an injected clock (AlertsSelfTest drives it)
//   Alerts         the Mac side: notifications + sounds, mirroring the wearer's `alert` events (§4), debug hooks
// The Mac is a follower: `source` "wearer" (default) mirrors the wearer's phone and checks only `no_data` locally;
// "own" runs every rule here. Without a v2 follow link (legacy BLE / Juggluco Wi-Fi only) there are no wearer
// events, so "wearer" falls back to this Mac's rules (and the Alerts window says so).

enum AlertKind: String, CaseIterable {
    case urgentLow = "urgent_low", low, high, veryHigh = "very_high"
    case fallingFast = "falling_fast", risingFast = "rising_fast", noData = "no_data"

    /// One notification per group: a new alert of the group replaces the previous one.
    enum Group: String { case level, rate, noData = "no_data" }
    var group: Group {
        switch self {
        case .urgentLow, .low, .high, .veryHigh: .level
        case .fallingFast, .risingFast: .rate
        case .noData: .noData
        }
    }
    var title: String {
        switch self {
        case .urgentLow: "Urgent low"
        case .low: "Low"
        case .high: "High"
        case .veryHigh: "Very high"
        case .fallingFast: "Falling fast"
        case .risingFast: "Rising fast"
        case .noData: "No glucose data"
        }
    }
    var lowSide: Bool { self == .urgentLow || self == .low }
    /// Escalation within a side: low → urgent low, high → very high.
    func moreSevere(than k: AlertKind) -> Bool { (self == .urgentLow && k == .low) || (self == .veryHigh && k == .high) }

    var defaultRule: AlertRule {
        switch self {
        case .urgentLow: AlertRule(on: true, mgdl: 55, repeatMin: 5, alarm: true)
        case .low: AlertRule(on: true, mgdl: 65, repeatMin: 15, alarm: true)
        case .high: AlertRule(on: true, mgdl: 185, repeatMin: 30)
        case .veryHigh: AlertRule(on: true, mgdl: 250, repeatMin: 30)
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
    var sounds = AlertSounds(pack: "arcade", kinds: [:], shuffle: true)
    var source = "wearer"           // followers: wearer | own

    static let packs = ["arcade", "kart", "quest", "soft"]
    static let key = "alertSettings"

    subscript(k: AlertKind) -> AlertRule {
        get { alerts[k.rawValue] ?? k.defaultRule }
        set { alerts[k.rawValue] = newValue }
    }

    init() {}

    /// Lenient: any missing or malformed key keeps its §3 default (a file written by an older or newer app still
    /// loads), numbers may be ints or doubles.
    init(from decoder: Decoder) throws {
        struct PartialRule: Decodable { var on, alarm: Bool?; var mgdl, rate, min, repeatMin: Double? }
        struct PartialMute: Decodable { var on: Bool?; var min: Double? }
        struct PartialSounds: Decodable { var pack: String?; var kinds: [String: String]?; var shuffle: Bool? }
        enum K: String, CodingKey { case v, on, alerts, muteAfterInsulin, muteFallingHigh, muteAfterCarbs, sounds, source }
        let c = try decoder.container(keyedBy: K.self)
        if let on = try? c.decode(Bool.self, forKey: .on) { self.on = on }
        if let src = try? c.decode(String.self, forKey: .source), ["wearer", "own"].contains(src) { source = src }
        let rules = (try? c.decode([String: PartialRule].self, forKey: .alerts)) ?? [:]
        for k in AlertKind.allCases {
            guard let p = rules[k.rawValue] else { continue }
            var r = k.defaultRule
            if let on = p.on { r.on = on }
            if let x = p.mgdl, r.mgdl != nil { r.mgdl = x }
            if let x = p.rate, r.rate != nil { r.rate = x }
            if let x = p.min, r.min != nil { r.min = x }
            if let x = p.repeatMin { r.repeatMin = max(0, Int(x)) }
            if let x = p.alarm { r.alarm = x }
            self[k] = r
        }
        func mute(_ key: K, _ d: AlertMute) -> AlertMute {
            guard let p = try? c.decode(PartialMute.self, forKey: key) else { return d }
            return AlertMute(on: p.on ?? d.on, min: p.min ?? d.min)
        }
        muteAfterInsulin = mute(.muteAfterInsulin, muteAfterInsulin)
        muteFallingHigh = mute(.muteFallingHigh, muteFallingHigh)
        muteAfterCarbs = mute(.muteAfterCarbs, muteAfterCarbs)
        if let p = try? c.decode(PartialSounds.self, forKey: .sounds) {
            sounds = AlertSounds(pack: p.pack ?? sounds.pack, kinds: p.kinds ?? [:], shuffle: p.shuffle ?? sounds.shuffle)
        }
    }

    static func load() -> AlertSettings {
        guard let s = UserDefaults.standard.string(forKey: key),
              let d = try? JSONDecoder().decode(AlertSettings.self, from: Data(s.utf8)) else { return AlertSettings() }
        return d
    }

    var json: String {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return (try? e.encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    }

    func save() { UserDefaults.standard.set(json, forKey: Self.key) }

    /// Which sound a kind plays: the per-kind override, else the pack.
    func pack(for k: AlertKind) -> String { sounds.kinds[k.rawValue] ?? sounds.pack }
}

// MARK: alert event (SPEC §4 body)

struct AlertEvent: Equatable {
    var id: String
    var k: AlertKind
    var state: String               // fire | clear
    var at: Date
    var t: Date?
    var sgv: Double?
    var r: Double?
    var th: Double
    var n: Int

    static func newId() -> String { (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined() }

    /// The decrypted body of a wearer's kind 21079 event; nil if it isn't a §4 alert.
    init?(body o: [String: Any]) {
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        guard o["type"] as? String == "alert", let id = o["id"] as? String,
              let k = (o["k"] as? String).flatMap(AlertKind.init(rawValue:)),
              let state = o["state"] as? String, ["fire", "clear"].contains(state), let at = num(o["at"]) else { return nil }
        self.init(id: id, k: k, state: state, at: Date(timeIntervalSince1970: at), t: num(o["t"]).map { Date(timeIntervalSince1970: $0) },
                  sgv: num(o["sgv"]), r: num(o["r"]), th: num(o["th"]) ?? 0, n: (o["n"] as? NSNumber)?.intValue ?? 1)
    }

    init(id: String, k: AlertKind, state: String, at: Date, t: Date?, sgv: Double?, r: Double?, th: Double, n: Int) {
        self.id = id; self.k = k; self.state = state; self.at = at; self.t = t; self.sgv = sgv; self.r = r; self.th = th; self.n = n
    }
}

// MARK: engine (SPEC §1–§2)

/// The rules, without any UI: feed it the newest reading (`reading`) and call `tick()` every minute; it reports
/// firings and clears through `onFire` / `onClear`. Time comes from `now` (tests inject a clock).
final class AlertEngine {
    var settings: AlertSettings
    var now: () -> Date
    /// Times of rapid-insulin entries (not deleted) — the treatment log.
    var rapidInsulin: () -> [Date] = { [] }
    /// Times of carb entries (not deleted).
    var carbs: () -> [Date] = { [] }
    /// false = only `no_data` runs here (a follower mirroring the wearer).
    var levelAndRate = true
    var onFire: ((AlertEvent) -> Void)?
    var onClear: ((AlertEvent) -> Void)?

    struct Active {
        var kind: AlertKind
        var event: AlertEvent?       // the last firing of this episode; nil = never fired (muted, or within repeatMin)
        var muted: String?           // why it's quiet ("insulin 12 min ago")
    }
    private(set) var active: [AlertKind.Group: Active] = [:]
    private(set) var lastFired: [AlertKind: Date] = [:]
    private(set) var latest: Reading?

    init(settings: AlertSettings, now: @escaping () -> Date = Date.init) {
        self.settings = settings
        self.now = now
    }

    /// The newest reading (any transport), `rate` already the best available (Snapshot.rate).
    func reading(_ r: Reading) {
        guard latest.map({ r.date >= $0.date }) ?? true else { return }
        latest = r
        checkNoData()
        evaluate()
    }

    /// Every 60 s: the no-data timer, plus repeats and mutes that end while the reading stays the same.
    func tick() {
        checkNoData()
        evaluate()
    }

    /// Settings or mode changed: re-check everything now.
    func refresh() { tick() }

    private func evaluate() {
        guard settings.on, levelAndRate else { clear(.level); clear(.rate); return }
        guard let r = latest, now().timeIntervalSince(r.date) <= 15 * 60 else { return }   // backfill never alerts
        let s = settings
        func rule(_ k: AlertKind) -> AlertRule? { s[k].on ? s[k] : nil }
        let level: AlertKind? =
            rule(.urgentLow).map { r.mgdl <= $0.threshold } == true ? .urgentLow
            : rule(.low).map { r.mgdl <= $0.threshold } == true ? .low
            : rule(.veryHigh).map { r.mgdl >= $0.threshold } == true ? .veryHigh
            : rule(.high).map { r.mgdl >= $0.threshold } == true ? .high : nil
        update(.level, level, r)
        var rate: AlertKind?
        if let v = r.rate {
            if let f = rule(.fallingFast), v <= -f.threshold { rate = .fallingFast }
            else if let u = rule(.risingFast), v >= u.threshold { rate = .risingFast }
        }
        update(.rate, rate, r)
    }

    private func checkNoData() {
        guard let r = latest else { return }
        let rule = settings[.noData]
        let lost = settings.on && rule.on && now().timeIntervalSince(r.date) >= rule.threshold * 60
        update(.noData, lost ? .noData : nil, r)
    }

    /// §2: why an alert is quiet right now, or nil. Urgent low is never muted.
    func muteReason(_ k: AlertKind, _ r: Reading) -> String? {
        let t = now()
        func dose(_ m: AlertMute, _ times: [Date], _ what: String) -> String? {
            guard m.on, let last = times.filter({ $0 <= t && t.timeIntervalSince($0) <= m.min * 60 }).max() else { return nil }
            return "\(what) \(Int(t.timeIntervalSince(last) / 60)) min ago"
        }
        if [.low, .fallingFast].contains(k) { return dose(settings.muteAfterCarbs, carbs(), "carbs") }
        guard [.high, .veryHigh, .risingFast].contains(k) else { return nil }
        if let m = dose(settings.muteAfterInsulin, rapidInsulin(), "insulin") { return m }
        if k != .risingFast, settings.muteFallingHigh.on, let v = r.rate, v < 0 {
            let high = settings[.high].threshold, ahead = settings.muteFallingHigh.min
            if r.mgdl + v * ahead < high {
                return "falling, under \(Int(high)) in ~\(max(1, Int(((r.mgdl - high) / -v).rounded(.up)))) min"
            }
        }
        return nil
    }

    private func update(_ g: AlertKind.Group, _ kind: AlertKind?, _ r: Reading) {
        if let cur = active[g], cur.kind != kind { clear(g) }     // left it, or moved to another kind of the group
        guard let kind else { return }
        let t = now(), rule = settings[kind], every = TimeInterval(rule.repeatMin * 60)
        var a = active[g] ?? Active(kind: kind)
        a.muted = muteReason(kind, r)
        defer { active[g] = a }
        guard a.muted == nil else { return }
        let due: Bool
        if let ev = a.event {
            due = every > 0 && t.timeIntervalSince(ev.at) >= every                 // re-alert
        } else {
            due = lastFired[kind].map { t.timeIntervalSince($0) >= every } ?? true   // new (or no flapping)
        }
        guard due else { return }
        let ev = AlertEvent(id: AlertEvent.newId(), k: kind, state: "fire", at: t, t: r.date, sgv: r.mgdl, r: r.rate,
                            th: rule.threshold, n: (a.event?.n ?? 0) + 1)
        a.event = ev
        lastFired[kind] = t
        onFire?(ev)
    }

    private func clear(_ g: AlertKind.Group) {
        guard let a = active.removeValue(forKey: g) else { return }
        if var ev = a.event {                // a clear repeats the id of the firing it ends
            ev.state = "clear"
            ev.at = now()
            onClear?(ev)
        }
    }

    /// "High · muted: insulin 12 min ago" / "Low · alerted 3 min ago (2×)" / "No alert".
    var stateLine: String {
        guard settings.on else { return "Alerts are off" }
        let t = now()
        let parts = [AlertKind.Group.level, .rate, .noData].compactMap { g -> String? in
            guard let a = active[g] else { return nil }
            if let m = a.muted { return "\(a.kind.title) · muted: \(m)" }
            if let ev = a.event { return "\(a.kind.title) · alerted \(Self.ago(t.timeIntervalSince(ev.at)))\(ev.n > 1 ? " (\(ev.n)×)" : "")" }
            return "\(a.kind.title) · alerted \(lastFired[a.kind].map { Self.ago(t.timeIntervalSince($0)) } ?? "earlier"), not repeated yet"
        }
        if !parts.isEmpty { return parts.joined(separator: " · ") }
        guard let r = latest else { return "Waiting for the first reading" }
        return t.timeIntervalSince(r.date) > 15 * 60 ? "No recent reading" : "No alert"
    }

    static func ago(_ s: TimeInterval) -> String { s < 90 ? "just now" : "\(Int(s / 60)) min ago" }
}

// MARK: the Mac app side

/// Owns the settings, the engine and the notifications. `Alerts…` in the menu opens AlertsWindow (AlertsUI.swift).
@MainActor final class Alerts: NSObject, UNUserNotificationCenterDelegate {
    private(set) var settings = AlertSettings.load()
    let engine: AlertEngine
    let menuItem = NSMenuItem(title: "Alerts…", action: nil, keyEquivalent: ",")
    /// Following someone over protocol v2 (approved) — the only path that carries the wearer's `alert` events.
    var followingV2: () -> Bool = { false }
    var treatments: () -> [Treatment] = { [] } { didSet { wireTreatments() } }
    var unit: () -> GlucoseUnit = { .mgdl }
    /// UI refresh (state line, mode).
    var onChange: (() -> Void)?
    /// Wearer mode: the notification each group shows now (kind + when the wearer fired it).
    private(set) var mirrored: [AlertKind.Group: AlertEvent] = [:]
    private var seenIds: Set<String> = []
    private var timer: Timer?
    private lazy var window = AlertsWindow(alerts: self)
    private let center: UNUserNotificationCenter? = Bundle.main.bundleIdentifier == nil ? nil : .current()
    private var playing: NSSound?

    /// Time Sensitive needs the `com.apple.developer.usernotifications.time-sensitive` entitlement, which only a
    /// provisioning profile can grant (bundle.sh signs without one, and an unprovisioned com.apple.developer.*
    /// entitlement stops the app from launching). So lows go out `.active` unless the signature carries it.
    nonisolated static let timeSensitive: Bool = {
        guard let task = SecTaskCreateFromSelf(nil) else { return false }
        let v = SecTaskCopyValueForEntitlement(task, "com.apple.developer.usernotifications.time-sensitive" as CFString, nil)
        return (v as? Bool) == true
    }()

    enum Mode { case wearer, own }
    var mode: Mode { settings.source == "wearer" && followingV2() ? .wearer : .own }
    /// "wearer" chosen but nothing to mirror (no v2 follow link).
    var wearerUnavailable: Bool { settings.source == "wearer" && !followingV2() }

    override init() {
        engine = AlertEngine(settings: settings)
        super.init()
        menuItem.target = self
        menuItem.action = #selector(openWindow)
        center?.delegate = self
        engine.onFire = { [weak self] ev in self?.post(ev, mirrored: false) }
        engine.onClear = { [weak self] ev in self?.remove(ev.k.group, "\(ev.k.rawValue) cleared") }
        if settings.on { authorize() }
        let t = Timer(timeInterval: 60, repeats: true) { _ in MainActor.assumeIsolated { self.tick() } }
        t.tolerance = 5
        RunLoop.main.add(t, forMode: .common)
        timer = t
        log("alerts: \(settings.on ? "on" : "off"), source \(settings.source), time-sensitive \(Self.timeSensitive ? "entitled" : "not entitled (lows go out .active)")")
    }

    private func wireTreatments() {
        let rows = treatments
        func times(_ k: String) -> [Date] { rows().filter { $0.k == k && !$0.del }.map { Date(timeIntervalSince1970: TimeInterval($0.t)) } }
        engine.rapidInsulin = { times("rapid") }
        engine.carbs = { times("carbs") }
    }

    @objc func openWindow() { window.show() }
    func snapshot(to path: String) { window.snapshot(to: path) }

    private func authorize() {
        center?.requestAuthorization(options: [.alert, .sound]) { ok, err in
            log("alerts: notifications \(ok ? "allowed" : "not allowed")\(err.map { " (\($0.localizedDescription))" } ?? "")")
        }
    }

    // MARK: inputs

    /// The newest merged reading (Store.publish), with the best available rate.
    func onLatest(_ r: Reading) {
        engine.levelAndRate = mode == .own
        engine.reading(r)
        onChange?()
    }

    private func tick() {
        engine.levelAndRate = mode == .own
        engine.tick()
        onChange?()
    }

    /// The v2 follow state changed (approved, revoked, unfollowed): the mode may flip.
    func followChanged() {
        if mode == .own, !mirrored.isEmpty {
            for g in mirrored.keys { remove(g, "not mirroring the wearer any more") }
            mirrored = [:]
        }
        tick()
    }

    /// Settings edited in the Alerts window: saved at once (macOS settings apply immediately).
    func update(_ s: AlertSettings) {
        guard s != settings else { return }
        let wasOn = settings.on
        settings = s
        s.save()
        engine.settings = s
        if s.on, !wasOn { authorize() }
        if !s.on || mode == .own {
            for g in mirrored.keys { remove(g, "alerts off / own rules") }
            mirrored = [:]
        }
        tick()
    }

    /// A wearer's §4 event that passed FollowerV2's live checks (author ∈ roster devices, gk, NIP-44, v 2, ≤ 10 min).
    func onWearerAlert(_ body: [String: Any], via: String) {
        guard let ev = AlertEvent(body: body) else { return log("alerts: wearer alert not understood") }
        guard seenIds.insert("\(ev.id):\(ev.state)").inserted else { return }   // relays + BLE: one copy each
        guard settings.on, mode == .wearer else { return log("alerts: wearer \(ev.k.rawValue) \(ev.state) ignored (\(settings.on ? "own rules" : "alerts off"))") }
        let g = ev.k.group
        if ev.state == "fire" {
            mirrored[g] = ev
            post(ev, mirrored: true)
        } else if mirrored[g]?.k == ev.k {                // clear: the notification it ends (same kind)
            mirrored[g] = nil
            remove(g, "wearer cleared \(ev.k.rawValue)")
        }
        onChange?()
    }

    // MARK: notifications

    var stateLine: String {
        var parts: [String] = []
        if settings.on, mode == .wearer {
            parts = [AlertKind.Group.level, .rate, .noData].compactMap { g in
                mirrored[g].map { "\($0.k.title) · from the wearer \(AlertEngine.ago(Date().timeIntervalSince($0.at)))" }
            }
            if let nd = engine.active[.noData] { parts.append("\(nd.kind.title) · here") }
            return parts.isEmpty ? "No alert (mirroring the wearer)" : parts.joined(separator: " · ")
        }
        return engine.stateLine
    }

    private func text(_ ev: AlertEvent, mirrored: Bool) -> (String, String) {
        let u = unit()
        let units = u == .mgdl ? "mg/dL" : "mmol/L"
        var title = ev.k.title, body: [String] = []
        if ev.k == .noData {
            let since = ev.t.map { Int(Date().timeIntervalSince($0) / 60) }
            body.append(since.map { "Nothing received for \($0) min" } ?? "Nothing received for \(Int(ev.th)) min")
        } else {
            if let sgv = ev.sgv { title += ": \(u.format(sgv)) \(units)" }
            switch ev.k {
            case .urgentLow, .low: body.append("≤ \(u.format(ev.th))")
            case .high, .veryHigh: body.append("≥ \(u.format(ev.th))")
            default: break
            }
            if let r = ev.r { body.append(u.formatRate(r)) }
        }
        if ev.n > 1 { body.append("still (\(ev.n)×)") }
        if mirrored { body.append("from the wearer's phone") }
        return (title, body.joined(separator: " · "))
    }

    func post(_ ev: AlertEvent, mirrored: Bool, test: Bool = false) {
        var (title, body) = text(ev, mirrored: mirrored)
        if test { title = "Test · " + title }
        let sound = Self.sound(for: ev.k, settings)
        log("ALERT \(title) — \(body) [\(ev.k.group.rawValue), sound \(sound.label)]")
        guard center != nil else {           // `swift run`: no bundle, no notification center
            play(sound)
            return
        }
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.threadIdentifier = "sukrara-alerts"
        c.interruptionLevel = settings[ev.k].alarm && Self.timeSensitive ? .timeSensitive : .active
        switch sound {
        case .none: c.sound = nil
        case .system: c.sound = .default
        case .file(let name, _):
            if Bundle.main.url(forResource: name, withExtension: nil) != nil {
                c.sound = UNNotificationSound(named: UNNotificationSoundName(name))
            } else {
                log("alerts: \(name) not in the app bundle — default sound")
                c.sound = .default
            }
        }
        send(ev.k.group, UNNotificationRequest(identifier: ev.k.group.rawValue, content: c, trigger: nil))
    }

    private func remove(_ g: AlertKind.Group, _ why: String) {
        log("alerts: clear \(g.rawValue) (\(why))")
        send(g, nil)
    }

    /// What each group should show, sent once at the end of this run-loop turn: only the last word per group reaches
    /// Notification Center. A kind moving within its group (high → very high) is a clear + a firing in one go, and
    /// usernoted cancels both adds when two requests with the same identifier arrive milliseconds apart, or lets a
    /// remove that arrives after the add take the new notification away (both seen in its log).
    private var outbox: [AlertKind.Group: UNNotificationRequest?] = [:]
    private func send(_ g: AlertKind.Group, _ req: UNNotificationRequest?) {
        let first = outbox.isEmpty
        outbox[g] = .some(req)
        guard first, let center else { return }
        DispatchQueue.main.async { [self] in
            for (g, req) in outbox {
                if let req {
                    center.add(req) { err in if let err { log("alerts: notification failed: \(err.localizedDescription)") } }
                } else {
                    center.removeDeliveredNotifications(withIdentifiers: [g.rawValue])
                }
            }
            outbox = [:]
        }
    }

    // MARK: sounds

    enum Sound {
        case none, system, file(String, URL?)
        var label: String {
            switch self { case .none: "none"; case .system: "system"; case .file(let n, _): n }
        }
    }

    /// `<pack>_<kind>_<n>.caf`: a random variant (1–3) with shuffle, else 1. URL for previews / `swift run`.
    nonisolated static func sound(for k: AlertKind, _ s: AlertSettings, pack: String? = nil, variant: Int? = nil) -> Sound {
        let p = pack ?? s.pack(for: k)
        switch p {
        case "none": return .none
        case "system": return .system
        default:
            guard AlertSettings.packs.contains(p) else { return .system }
            let name = "\(p)_\(k.rawValue)_\(variant ?? (s.sounds.shuffle ? Int.random(in: 1...3) : 1)).caf"
            return .file(name, soundURL(name))
        }
    }

    /// The app bundle's Resources (bundle.sh copies them there), else the repo's generated sounds (`swift run`).
    nonisolated static func soundURL(_ name: String) -> URL? {
        if let u = Bundle.main.url(forResource: name, withExtension: nil) { return u }
        let src = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../alerts/sounds/out/\(name)").standardizedFileURL
        return FileManager.default.fileExists(atPath: src.path) ? src : nil
    }

    func play(_ s: Sound) {
        playing?.stop()
        switch s {
        case .none: break
        case .system: NSSound.beep()
        case .file(_, let url):
            guard let url, let snd = NSSound(contentsOf: url, byReference: true) else { return NSSound.beep() }
            playing = snd
            snd.play()
        }
    }

    /// ▶︎ in the Alerts window: a random variant of what this kind would play (pack: a pack/system/none override).
    func preview(_ k: AlertKind, pack: String? = nil) {
        var s = settings
        s.sounds.shuffle = true
        play(Self.sound(for: k, s, pack: pack))
    }

    // MARK: debug hooks

    /// `-alert-test <kind>`: post that alert once, with its sound, then log what Notification Center holds.
    func test(_ kind: String) {
        guard let k = AlertKind(rawValue: kind) else { return log("alert-test: unknown kind \(kind) (\(AlertKind.allCases.map(\.rawValue)))") }
        let sample: [AlertKind: (Double, Double)] = [.urgentLow: (52, -1.2), .low: (62, -0.6), .high: (201, 0.8),
                                                     .veryHigh: (262, 1.1), .fallingFast: (120, -2.6), .risingFast: (150, 2.4), .noData: (110, 0)]
        let (sgv, r) = sample[k]!
        let ev = AlertEvent(id: AlertEvent.newId(), k: k, state: "fire", at: Date(), t: Date().addingTimeInterval(k == .noData ? -25 * 60 : 0),
                            sgv: sgv, r: r, th: settings[k].threshold, n: 1)
        authorize()
        post(ev, mirrored: false, test: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self.logDelivered() }
    }

    /// `-alert-feed "200,-1.5"` (several: "200,-1.5;260,1"): readings stamped now, through this Mac's own rules.
    func feed(_ spec: String) {
        for part in spec.split(separator: ";") {
            let f = part.split(separator: ",").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard let sgv = f.first else { continue }
            engine.levelAndRate = true
            engine.reading(Reading(date: Date(), mgdl: sgv, rate: f.count > 1 ? f[1] : nil))
            log("alert-feed \(Int(sgv)) \(f.count > 1 ? String(f[1]) : "-") → \(engine.stateLine)")
        }
        onChange?()
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { self.logDelivered() }
    }

    /// Debug: take every Sukrara notification out of Notification Center (after tests).
    func clearAll() {
        center?.removeAllDeliveredNotifications()
        mirrored = [:]
        log("alerts: all delivered notifications removed")
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { self.logDelivered() }
    }

    func logDelivered() {
        guard let center else { return log("alerts: no notification center (not bundled)") }
        center.getDeliveredNotifications { list in
            let s = list.map { "\($0.request.identifier)=\"\($0.request.content.title)\"" }.joined(separator: ", ")
            log("alerts: delivered notifications: [\(s)]")
        }
        center.getNotificationSettings { st in
            log("alerts: authorization \(st.authorizationStatus.rawValue) alert \(st.alertSetting.rawValue) sound \(st.soundSetting.rawValue) "
                + "timeSensitive \(st.timeSensitiveSetting.rawValue) (2 = enabled)")
        }
    }

    // Shown even while Sukrara is the active app (the Alerts window open).
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                            withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .list, .sound])
    }
}
