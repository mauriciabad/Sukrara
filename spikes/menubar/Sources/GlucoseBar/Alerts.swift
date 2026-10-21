// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import AppKit
import Security
import UserNotifications

// Glucose alerts (spikes/alerts/SPEC.md — the contract shared with Sukrara for Android):
//   AlertSettings  the §3 JSON, one string in UserDefaults `alertSettings` (AlertRules.swift, shared with the widgets)
//   AlertEngine    §1–§2 rules on readings + a 60 s timer, with an injected clock (AlertsSelfTest drives it)
//   Alerts         the Mac side: notifications + sounds, mirroring the wearer's `alert` events (§4), debug hooks
// The Mac is a follower: Settings from: Wearer (default; `source` "wearer") mirrors the wearer's phone and checks only
// `no_data` locally; Default and Custom ("own") run every rule here, with the defaults or this Mac's values for the
// mirrored rules (WearerSettings.swift). Without a v2 follow link (legacy BLE / Juggluco Wi-Fi only) there are no
// wearer events, so "wearer" falls back to rules run here (and the Alerts window says so).

// MARK: alert event (SPEC §4 body)

struct AlertEvent: Equatable, Codable {
    var id: String
    var k: AlertKind
    var state: String               // fire | clear
    var at: Date
    var t: Date?
    var sgv: Double?
    var r: Double?
    var th: Double
    var n: Int
    /// Entries of the answering treatment in this episode (0, 1, 2 = two or more): the headline, `AlertKind.message`.
    var tr = 0

    static func newId() -> String { (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined() }

    /// The decrypted body of a wearer's kind 21079 event; nil if it isn't a §4 alert.
    init?(body o: [String: Any]) {
        func num(_ a: Any?) -> Double? { (a as? NSNumber)?.doubleValue }
        guard o["type"] as? String == "alert", let id = o["id"] as? String,
              let k = (o["k"] as? String).flatMap(AlertKind.init(rawValue:)),
              let state = o["state"] as? String, ["fire", "clear"].contains(state), let at = num(o["at"]) else { return nil }
        self.init(id: id, k: k, state: state, at: Date(timeIntervalSince1970: at), t: num(o["t"]).map { Date(timeIntervalSince1970: $0) },
                  sgv: num(o["sgv"]), r: num(o["r"]), th: num(o["th"]) ?? 0, n: (o["n"] as? NSNumber)?.intValue ?? 1, tr: (o["tr"] as? NSNumber)?.intValue ?? 0)
    }

    init(id: String, k: AlertKind, state: String, at: Date, t: Date?, sgv: Double?, r: Double?, th: Double, n: Int, tr: Int = 0) {
        self.id = id; self.k = k; self.state = state; self.at = at; self.t = t; self.sgv = sgv; self.r = r; self.th = th; self.n = n; self.tr = tr
    }
}

// MARK: mirroring the wearer (SPEC §2, §4)

/// A follower that mirrors the wearer's alerts (source "wearer"), without any UI — a 1:1 port of Android's AlertMirror:
/// which of the wearer's `alert` events show on this Mac, and this Mac's own notification actions on them. Those are
/// strictly local (user, 2026-10-21: "Mac notifications should work on their own … if someone wants to mute, it
/// shouldn't mute the other"): the wearer never hears of them, and the wearer's own mutes never reach here (it keeps
/// publishing what its rules decide).
/// - *Mute* drops the wearer's alerts of that side while it lasts (very low and extremely high always show);
/// - *Slow down* shows a repeat only once `slowMin` has passed since the last one shown here (a more severe kind of the
///   side always shows);
/// - both end with the wearer's episode (a firing with `n` = 1).
/// The same struct drives the app (Alerts) and the timeline scenarios (AlertScenarios, `mirror`).
struct AlertMirror {
    /// side → this Mac's notification actions in force.
    private var snooze: [String: AlertEngine.Snooze] = [:]
    /// group → when and which kind this Mac last showed (Slow down spaces the wearer's repeats).
    private var last: [AlertKind.Group: (Date, AlertKind)] = [:]
    /// group → id of the wearer's firing whose notification is up here.
    private var shown: [AlertKind.Group: String] = [:]
    /// group → id of the wearer's newest firing, shown here or not.
    private var newest: [AlertKind.Group: String] = [:]

    /// The wearer fired `k` (firing `id`, the episode's `n`th, at `at`): true = show it here.
    mutating func fire(_ k: AlertKind, id: String, n: Int, at: Date) -> Bool {
        let side = k.side, g = k.group
        newest[g] = id
        if n <= 1 { snooze[side] = nil }       // a new episode on the wearer
        let sn = snooze[side]
        let muted = (sn?.until.map { $0 > at } ?? false) && !k.always
        let slowed = sn.map { sn in sn.slowMin > 0 && last[g].map { (t, lk) in
            lk.side == side && k.severity <= lk.severity && at.timeIntervalSince(t) < TimeInterval(sn.slowMin * 60) } ?? false } ?? false
        if muted || slowed { return false }
        shown[g] = id
        last[g] = (at, k)
        return true
    }

    /// The wearer cleared its firing `id` of `k`'s group: true = the notification shown here goes. That's the firing
    /// shown here, or the wearer's newest one: a repeat this Mac skipped (slowed) left the earlier firing up, and the
    /// wearer's clear of the repeat ends it too. An older clear arriving late changes nothing.
    mutating func clear(_ k: AlertKind, id: String) -> Bool {
        let g = k.group
        guard let cur = shown[g], cur == id || newest[g] == id else { return false }
        shown[g] = nil
        return true
    }

    /// This Mac's *Mute* on `k`'s side until `until`: true = an alert of it was showing (it goes).
    mutating func mute(_ k: AlertKind, until: Date) -> Bool {
        var sn = snooze[k.side] ?? AlertEngine.Snooze(until: nil, slowMin: 0)
        sn.until = until
        snooze[k.side] = sn
        return shown.removeValue(forKey: k.group) != nil
    }

    /// This Mac's *Slow down* on `k`'s side.
    mutating func slow(_ k: AlertKind, slowMin: Int) {
        var sn = snooze[k.side] ?? AlertEngine.Snooze(until: nil, slowMin: 0)
        sn.slowMin = slowMin
        snooze[k.side] = sn
    }

    func slowed(_ k: AlertKind) -> Bool { (snooze[k.side]?.slowMin ?? 0) > 0 }

    /// Not mirroring any more (own rules, alerts off): nothing of the wearer's shows here.
    mutating func forgetShown() { shown = [:] }

    /// Side → muted until, for the badge.
    func paused(_ t: Date) -> [String: Date] { snooze.compactMapValues { sn in sn.until.flatMap { $0 > t ? $0 : nil } } }
}

// MARK: engine (SPEC §1–§2)

extension AlertKind: Codable {}

/// The rules, without any UI — a 1:1 port of Android's AlertEngine: feed it the newest reading (`reading`) and call
/// `tick()` every minute and at `nextCheckAt`; it reports firings and clears through `onFire` / `onClear`. Time comes
/// from `now` (tests inject a clock).
///
/// Three independent groups (level, rate, no data), each with at most one **episode**: a stretch of time on one side
/// (lows, highs, falling fast, rising fast, no data). An episode starts when a kind of that side matches and ends once
/// none has matched for the hold time (the kind's repeat interval, at least 5 min). So a reading back in range for a
/// moment removes the notification but doesn't start a new episode. Within an episode:
/// - the first match alerts at once; a more severe kind of the side alerts at once (low → very low);
/// - then it repeats every `repeatMin` (0 = never) counted from the last alert;
/// - a treatment that answers it (carbs for lows and falling fast, rapid insulin for highs and rising fast) quiets
///   it for the mute window and restarts the count: when the window ends and it still matches, it alerts at once;
/// - the falling-high mute only postpones: when it ends, whatever was due alerts;
/// - the alert notification's actions act on the episode: *Mute* (`mute`) quiets it like a treatment does (alerts at
///   once when it ends, never very low or extremely high), *Slow down* (`slow`) repeats it at the slower interval;
///   both end with the episode.
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

    /// Readings older than this never alert (backfill, a late chunk).
    static let fresh = AlertStatus.fresh
    /// An episode lasts until nothing of its side has matched for its repeat interval, but at least this long.
    static let minHoldMin = 5

    /// What the UI shows per group: the matching kind, and why it's quiet if it is.
    struct State { var kind: AlertKind; var muted: String?; var firedAt: Date?; var n: Int }
    /// A notification action on a side's episode: muted until `until` (nil = not), repeats slowed to `slowMin` (0 = not).
    struct Snooze: Equatable { var until: Date?; var slowMin: Int }

    private final class Episode: Codable {
        let side: String, start: Date
        var kind: AlertKind
        /// The condition held at the last evaluation, and when it last did.
        var on = true
        var lastIn: Date
        /// The firing whose notification is up (cleared when the condition goes, mutes, or the kind changes).
        var shown: AlertEvent?
        var n = 0
        var firedAt: Date?
        var firedKind: AlertKind
        var muted: String?
        /// When the next alert is due (nil = never), from the last evaluation: the timer.
        var next: Date?
        /// The notification's Mute: quiet until then.
        var quietUntil: Date?
        /// The notification's Slow down: repeats at least this many minutes apart (0 = the kind's interval).
        var slowMin = 0
        init(_ k: AlertKind, _ t: Date) { side = k.side; start = t; kind = k; lastIn = t; firedKind = k; next = t }
    }

    private var episodes: [AlertKind.Group: Episode] = [:]
    private(set) var latest: Reading?
    /// The current sensor (SensorStore; the live events' ss/se/sw): no `no_data` while it has ended or warms up (no
    /// reading is expected then, SPEC §2); a running no-data alert clears.
    var sensor: () -> SensorInfo? = { nil }

    /// No reading is expected: the sensor has ended or is warming up.
    func quiet(_ t: Date) -> Bool {
        guard let s = sensor() else { return false }
        let n = t.timeIntervalSince1970
        return SensorLife.ended(end: s.end, now: n) || SensorLife.warming(s, latest: latest?.date.timeIntervalSince1970, now: n)
    }

    var active: [AlertKind.Group: State] {
        episodes.filter { $0.value.on }.mapValues { State(kind: $0.kind, muted: $0.muted, firedAt: $0.firedAt, n: $0.n) }
    }

    init(settings: AlertSettings, now: @escaping () -> Date = Date.init) {
        self.settings = settings
        self.now = now
    }

    /// The newest stored reading after a restart: no data counts from it. No evaluation (no alert for old news).
    func seed(_ r: Reading?) { if let r, latest.map({ r.date > $0.date }) ?? true { latest = r } }

    /// The episodes, saved after every evaluation so a restart resumes them (Android's `AlertEngine.snapshot`): without
    /// it the next check after an update or relaunch takes an ongoing alert for a new one and rings again at once
    /// (docs/15, "Two sounds at once").
    func snapshot() -> Data {
        (try? JSONEncoder().encode(Dictionary(uniqueKeysWithValues: episodes.map { ($0.key.rawValue, $0.value) }))) ?? Data("{}".utf8)
    }

    /// Back to the `snapshot` state (episodes over by `t` are dropped). Returns the firings whose notification was up:
    /// the caller shows them again without sound. The next evaluation goes on from there: a repeat that came due while
    /// the app was down alerts, anything else waits for its time.
    @discardableResult func restore(_ data: Data, at t: Date) -> [AlertEvent] {
        episodes = [:]
        let saved = (try? JSONDecoder().decode([String: Episode].self, from: data)) ?? [:]
        for (g, e) in saved {
            guard let group = AlertKind.Group(rawValue: g), t.timeIntervalSince(e.lastIn) < hold(e, settings) else { continue }
            episodes[group] = e
        }
        return [AlertKind.Group.level, .rate, .noData].compactMap { episodes[$0]?.shown }
    }

    /// The newest reading (any transport), `rate` already the best available (Snapshot.rate). Older ones are ignored.
    func reading(_ r: Reading) {
        if latest.map({ r.date > $0.date }) ?? true { latest = r }
        evaluate()
    }

    /// The alert notification's *Mute* for `min` minutes on `kind`'s episode, then an evaluation (its alert clears).
    /// Nothing without an episode, or for very low and extremely high (`AlertKind.always`).
    func mute(_ kind: AlertKind, min: Int) {
        guard let e = episodes[kind.group], !kind.always, min > 0 else { return }
        e.quietUntil = now().addingTimeInterval(TimeInterval(min * 60))
        evaluate()
    }

    /// The alert notification's *Slow down* on `kind`'s episode: its repeats come every `notificationActions.slowMin`.
    func slow(_ kind: AlertKind) {
        guard let e = episodes[kind.group] else { return }
        e.slowMin = settings.notificationActions.slowMin
        evaluate()
    }

    /// The notification actions in force, per side ("low", "high", "falling_fast", …): the badge.
    func snoozes(_ t: Date) -> [String: Snooze] {
        var out: [String: Snooze] = [:]
        for e in episodes.values where (e.quietUntil.map { $0 > t } ?? false) || e.slowMin > 0 {
            out[e.side] = Snooze(until: e.quietUntil.flatMap { $0 > t ? $0 : nil }, slowMin: e.slowMin)
        }
        return out
    }

    /// Whether `kind`'s episode is slowed already (the notification leaves out *Slow down*).
    func slowed(_ kind: AlertKind) -> Bool { (episodes[kind.group]?.slowMin ?? 0) > 0 }

    /// Timer: the no-data deadline, repeats and mute windows that end while the reading stays the same.
    func tick() { evaluate() }

    /// Settings, treatments or mode changed: re-check everything now.
    func refresh() { evaluate() }

    /// The level kind `r` is in at `now`, or nil (stale reading, in range, alerts off). Lows `<`, highs `>=`.
    static func level(_ r: Reading?, _ s: AlertSettings, _ now: Date) -> AlertKind? {
        r.flatMap { AlertStatus.level(mgdl: $0.mgdl, at: $0.date.timeIntervalSince1970, s, now: now.timeIntervalSince1970) }
    }

    private func evaluate() {
        let s = settings, t = now()
        guard s.on else {
            for g in [AlertKind.Group.level, .rate, .noData] { if let e = episodes[g] { hide(e, t) } }
            episodes = [:]
            return
        }
        let r = latest
        let fresh = r.map { t.timeIntervalSince($0.date) <= Self.fresh } ?? false
        var rate: AlertKind?
        if levelAndRate, fresh, let v = r?.rate {
            if s[.fallingFast].on, v <= -s[.fallingFast].threshold { rate = .fallingFast }
            else if s[.risingFast].on, v >= s[.risingFast].threshold { rate = .risingFast }
        }
        let nd = s[.noData]
        let noData = (r.map { nd.on && t.timeIntervalSince($0.date) >= nd.threshold * 60 } ?? false) && !quiet(t)
        let rapid = rapidInsulin(), cb = carbs()
        let doses: (String) -> [Date] = { $0 == "rapid" ? rapid : cb }
        step(.level, levelAndRate ? Self.level(r, s, t) : nil, s, doses, t)
        step(.rate, rate, s, doses, t)
        step(.noData, noData ? .noData : nil, s, doses, t)
    }

    private func hold(_ e: Episode, _ s: AlertSettings) -> TimeInterval { TimeInterval(max(s[e.kind].repeatMin, Self.minHoldMin) * 60) }

    private func hide(_ e: Episode, _ t: Date) {
        if var ev = e.shown {                // a clear repeats the id of the firing it ends
            ev.state = "clear"
            ev.at = t
            onClear?(ev)
        }
        e.shown = nil
    }

    private func step(_ g: AlertKind.Group, _ want: AlertKind?, _ s: AlertSettings, _ d: (String) -> [Date], _ t: Date) {
        guard let want else {
            guard let e = episodes[g] else { return }
            hide(e, t)
            e.on = false
            e.muted = nil
            if t.timeIntervalSince(e.lastIn) >= hold(e, s) { episodes[g] = nil }
            return
        }
        var found = episodes[g]
        if let e = found, e.side != want.side || (!e.on && t.timeIntervalSince(e.lastIn) >= hold(e, s)) {
            hide(e, t)
            found = nil
        }
        let e = found ?? Episode(want, t)
        episodes[g] = e
        e.on = true
        e.lastIn = t
        e.kind = want
        if let sh = e.shown, sh.k != want { hide(e, t) }
        let by = s.mutedBy(want)
        let cover = by.flatMap { tk in d(tk).filter { $0 <= t && t.timeIntervalSince($0) < s.muteS(tk) }.max() }
        if let mute = cover.map({ "\(by == "rapid" ? "insulin" : "carbs") \(Int(t.timeIntervalSince($0) / 60)) min ago" })
            ?? fallingMute(want, s) {
            hide(e, t)
            e.muted = mute
            e.next = cover.map { $0.addingTimeInterval(s.muteS(by!)) }   // the falling mute has no end of its own
            return
        }
        if let q = e.quietUntil, q > t, !want.always {
            hide(e, t)
            e.muted = "muted from the notification, \(Int((q.timeIntervalSince(t) + 59) / 60)) min left"
            e.next = q
            return
        }
        e.muted = nil
        e.next = due(e, want, s, d, t)
        guard let next = e.next, t >= next else { return }
        e.n += 1
        e.firedAt = t
        e.firedKind = want
        let r = latest
        let ev = AlertEvent(id: AlertEvent.newId(), k: want, state: "fire", at: t, t: r?.date, sgv: r?.mgdl, r: r?.rate,
                            th: s[want].threshold, n: e.n, tr: treated(e, want, s, d, t))
        e.shown = ev
        e.next = due(e, want, s, d, t)
        onFire?(ev)
    }

    /// When `e` alerts next for `k` (nil = never): see the class comment.
    private func due(_ e: Episode, _ k: AlertKind, _ s: AlertSettings, _ d: (String) -> [Date], _ t: Date) -> Date? {
        guard let fired = e.firedAt else { return e.start }
        if k.side == e.firedKind.side, k.severity > e.firedKind.severity { return t }
        // a treatment or the notification's Mute after the last alert: at once when its window ends
        var ends: [Date] = []
        if let by = s.mutedBy(k), let tc = d(by).filter({ $0 >= fired && $0 <= t }).max() { ends.append(tc.addingTimeInterval(s.muteS(by))) }
        if let q = e.quietUntil, q > fired, !k.always { ends.append(q) }
        if let end = ends.max() { return max(end, fired.addingTimeInterval(1)) }
        let base = s[k].repeatMin
        let rep = e.slowMin > 0 && base > 0 ? max(base, e.slowMin) : base
        return rep > 0 ? fired.addingTimeInterval(TimeInterval(rep * 60)) : nil
    }

    /// Entries of the treatment that answers `k` (level kinds only) logged in the episode, or within the mute length
    /// before it started (they're why it stayed quiet): 0, 1, or 2 for two or more.
    private func treated(_ e: Episode, _ k: AlertKind, _ s: AlertSettings, _ d: (String) -> [Date], _ t: Date) -> Int {
        guard k.group == .level, let tk = k.treatment else { return 0 }
        let from = e.start.addingTimeInterval(-s.muteS(tk))
        return min(2, d(tk).filter { $0 >= from && $0 <= t }.count)
    }

    /// "falling, back in range in ~N min" if the current fall brings it under the high threshold within the window.
    private func fallingMute(_ k: AlertKind, _ s: AlertSettings) -> String? {
        guard let r = latest, let v = r.rate, s.muteFallingHigh.on, k == .high || k == .veryHigh, v < 0 else { return nil }
        let high = s[.high].threshold
        guard r.mgdl + v * s.muteFallingHigh.min < high else { return nil }
        return "falling, back in range in ~\(max(1, Int(((r.mgdl - high) / -v).rounded(.up)))) min"
    }

    /// When the next timer check is useful (no-data deadline, a repeat, a mute window ending), or nil.
    func nextCheckAt(_ now: Date) -> Date? {
        guard settings.on else { return nil }
        var c: [Date] = []
        if let r = latest {
            let nd = settings[.noData]
            if nd.on, episodes[.noData]?.on != true { c.append(r.date.addingTimeInterval(nd.threshold * 60)) }
            c.append(r.date.addingTimeInterval(Self.fresh + 1))   // level/rate alerts end when the reading goes stale
        }
        // a warm-up that brings no reading: no_data is due again once it is over
        if let s = sensor(), SensorLife.warming(s, latest: latest?.date.timeIntervalSince1970, now: now.timeIntervalSince1970) {
            c.append(Date(timeIntervalSince1970: s.warmupEnd + SensorLife.warmupGrace))
        }
        for e in episodes.values where e.on { if let n = e.next { c.append(n) } }
        return c.filter { $0 > now }.min()
    }

    /// "High · muted: insulin 12 min ago" / "Low · alerted 3 min ago (2×)" / "No alert".
    var stateLine: String {
        guard settings.on else { return "Alerts are off" }
        let t = now(), act = active
        let parts = [AlertKind.Group.level, .rate, .noData].compactMap { g -> String? in
            guard let a = act[g] else { return nil }
            if let m = a.muted { return "\(a.kind.title) · muted: \(m)" }
            guard let f = a.firedAt else { return "\(a.kind.title) · not alerted yet" }
            return "\(a.kind.title) · alerted \(Self.ago(t.timeIntervalSince(f)))\(a.n > 1 ? " (\(a.n)×)" : "")"
        }
        if !parts.isEmpty { return parts.joined(separator: " · ") }
        guard let r = latest else { return "Waiting for the first reading" }
        return t.timeIntervalSince(r.date) > Self.fresh ? "No recent reading" : "No alert"
    }

    static func ago(_ s: TimeInterval) -> String { s < 90 ? "just now" : "\(Int(s / 60)) min ago" }
}

// MARK: the Mac app side

/// Owns the settings, the engine and the notifications. Settings › Alerts (SettingsUI.swift, AlertsUI.swift) edits them.
@MainActor final class Alerts: NSObject, UNUserNotificationCenterDelegate {
    /// The rules that apply (`AlertSettings.load`: this Mac's own with the mirrored ones from Settings from).
    private(set) var settings = AlertSettings.load()
    /// What this Mac stores (`alertSettings`): Settings › Alerts edits it.
    private(set) var own = AlertSettings.own()
    let engine: AlertEngine
    /// Following someone over protocol v2 (approved) — the only path that carries the wearer's `alert` events.
    var followingV2: () -> Bool = { false }
    var treatments: () -> [Treatment] = { [] } { didSet { wireTreatments() } }
    var unit: () -> GlucoseUnit = { .mgdl }
    /// UI refresh (state line, mode).
    var onChange: (() -> Void)?
    /// Wearer mode: the notification each group shows now (kind + when the wearer fired it).
    private(set) var mirrored: [AlertKind.Group: AlertEvent] = [:]
    private var seenIds: Set<String> = []
    /// What each group's notification shows now, to post it again without sound (after Slow down, a bad typed time).
    private var shownEvents: [AlertKind.Group: (AlertEvent, Bool)] = [:]
    /// Mirroring the wearer: which of its alerts show here, and this Mac's own clicks on them (AlertMirror).
    private var mirror = AlertMirror()
    /// Side ("low", "high", …) → until (unix s): muted from this Mac's alert notification. The badge under the value
    /// shows it (AlertStatus.paused); `onPausedChange` redraws the card and the widgets.
    private(set) var paused: [String: TimeInterval] = [:]
    var onPausedChange: (() -> Void)?
    private var timer: Timer?
    /// One-shot at `engine.nextCheckAt`: a mute window or repeat that ends between readings alerts on time.
    private var checkTimer: Timer?
    /// Set while a notification's Mute runs (its clear is logged as the mute).
    private var muting = false
    /// Opens Settings on the Alerts pane (main.swift).
    var onOpenWindow: (() -> Void)?
    private let center: UNUserNotificationCenter? = Bundle.main.bundleIdentifier == nil ? nil : .current()
    private var playing: NSSound?
    /// The engine's episodes (AlertEngine.snapshot), saved after every evaluation; read back once by `resume`.
    private static let episodesKey = "alertEpisodes"

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
        engine.sensor = { SensorStore.current }
        super.init()
        if center != nil { NotificationSounds.prune() }
        center?.delegate = self
        registerCategories()
        engine.onFire = { [weak self] ev in self?.post(ev, mirrored: false) }
        // a clear while a notification's Mute runs is the mute (the log already says "muted for"), not the episode's end
        engine.onClear = { [weak self] ev in self?.remove(ev.k.group, self?.muting == true ? "muted from the notification" : "\(ev.k.rawValue) cleared") }
        if settings.on { authorize() }
        let t = Timer(timeInterval: 60, repeats: true) { _ in MainActor.assumeIsolated { self.tick() } }
        t.tolerance = 5
        RunLoop.main.add(t, forMode: .common)
        timer = t
        scheduleCheck()
        log("alerts: \(settings.on ? "on" : "off"), source \(settings.source), time-sensitive \(Self.timeSensitive ? "entitled" : "not entitled (lows go out .active)")")
    }

    private func wireTreatments() {
        let rows = treatments
        func times(_ k: String) -> [Date] { rows().filter { $0.k == k && !$0.del }.map { Date(timeIntervalSince1970: TimeInterval($0.t)) } }
        engine.rapidInsulin = { times("rapid") }
        engine.carbs = { times("carbs") }
    }

    /// At launch, before any reading or tick: the episodes go on where they were and the alerts that were showing come
    /// back without sound (same identifier); only a repeat that's due rings (Android's Alerts.resume, docs/15).
    /// `newest`: the last stored reading, so no data still counts from it.
    func resume(newest: Reading?) {
        engine.seed(newest)
        guard let data = UserDefaults.standard.data(forKey: Self.episodesKey) else { return scheduleCheck() }
        let shown = engine.restore(data, at: Date())
        for ev in shown { post(ev, mirrored: false, silent: true) }
        if !shown.isEmpty { log("alerts: resumed \(shown.map(\.k.title).joined(separator: ", ")) (no sound)") }
        scheduleCheck()
    }

    private func saveEpisodes() {
        UserDefaults.standard.set(engine.snapshot(), forKey: Self.episodesKey)
        updatePaused()
    }

    private func updatePaused() {
        let t = Date()
        var p: [String: TimeInterval] = [:]
        for (side, sn) in engine.snoozes(t) { if let u = sn.until { p[side] = u.timeIntervalSince1970 } }
        for (side, u) in mirror.paused(t) { p[side] = u.timeIntervalSince1970 }
        guard p != paused else { return }
        paused = p
        onPausedChange?()
    }

    func openWindow() { onOpenWindow?() }

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
        saveEpisodes()
        scheduleCheck()
        onChange?()
    }

    /// A treatment was logged, edited or deleted: mutes start (or end) at once.
    func treatmentsChanged() { tick() }

    /// The wearer's sensor changed (ended, a new one warming up): no data may stop or start counting.
    func sensorChanged() { tick() }

    private func tick() {
        engine.levelAndRate = mode == .own
        engine.tick()
        saveEpisodes()
        scheduleCheck()
        onChange?()
    }

    private func scheduleCheck() {
        checkTimer?.invalidate()
        checkTimer = nil
        guard let at = engine.nextCheckAt(Date()) else { return }
        let t = Timer(fire: at, interval: 0, repeats: false) { _ in MainActor.assumeIsolated { self.tick() } }
        t.tolerance = 1
        RunLoop.main.add(t, forMode: .common)
        checkTimer = t
    }

    /// The v2 follow state changed (approved, revoked, unfollowed): the mode may flip.
    func followChanged() {
        if mode == .own, !mirrored.isEmpty {
            for g in mirrored.keys { remove(g, "not mirroring the wearer any more") }
            mirrored = [:]
            mirror.forgetShown()
        }
        tick()
    }

    /// Settings edited in the Alerts window: saved at once (macOS settings apply immediately).
    /// After `update`: the card and the widgets redraw (badge, mute lines, slot).
    var onSettingsChange: (() -> Void)?

    /// Settings from changed, or the wearer sent new settings: the rules that apply follow.
    func reloadSettings() {
        own = AlertSettings.own()
        apply(AlertSettings.load())
    }

    /// This Mac's own settings edited (Settings › Alerts): saved, then what applies is recomputed.
    func update(_ o: AlertSettings) {
        guard o != own else { return }
        own = o
        o.save()
        apply(WearerSettings.alerts(own: o))
    }

    private func apply(_ s: AlertSettings) {
        guard s != settings else { return }
        let old = settings
        let wasOn = settings.on
        settings = s
        engine.settings = s
        if s.notificationActions != old.notificationActions { registerCategories() }
        onSettingsChange?()
        if s.on, !wasOn { authorize() }
        if !s.on || mode == .own {
            for g in mirrored.keys { remove(g, "alerts off / own rules") }
            mirrored = [:]
            mirror.forgetShown()
        }
        tick()
    }

    /// A wearer's §4 event that passed FollowerV2's live checks (author ∈ roster devices, gk, NIP-44, v 2, ≤ 10 min).
    func onWearerAlert(_ body: [String: Any], via: String) {
        guard let ev = AlertEvent(body: body) else { return log("alerts: wearer alert not understood") }
        // relays + BLE: one copy each
        guard seenIds.insert("\(ev.id):\(ev.state)").inserted else { return }
        guard settings.on, mode == .wearer else { return log("alerts: wearer \(ev.k.rawValue) \(ev.state) ignored (\(settings.on ? "own rules" : "alerts off"))") }
        let g = ev.k.group
        // this Mac's own Mute / Slow down decide (AlertMirror); the wearer's never reach here
        if ev.state == "fire" {
            if mirror.fire(ev.k, id: ev.id, n: ev.n, at: ev.at) {
                mirrored[g] = ev
                post(ev, mirrored: true)
            }
            updatePaused()
        } else if mirror.clear(ev.k, id: ev.id) {
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
                mirrored[g].map { "\($0.k.message(treated: $0.tr)) · from the wearer \(AlertEngine.ago(Date().timeIntervalSince($0.at)))" }
            }
            if let nd = engine.active[.noData] { parts.append("\(nd.kind.title) · here") }
            return parts.isEmpty ? "No alert (mirroring the wearer)" : parts.joined(separator: " · ")
        }
        return engine.stateLine
    }

    /// Same as Android's AlertNotifier: headline = `AlertKind.message`, body "69 mg/dL ↘ · < 70 mg/dL".
    private func text(_ ev: AlertEvent, mirrored: Bool) -> (String, String) {
        let u = unit()
        let units = u == .mgdl ? "mg/dL" : "mmol/L"
        let value = "\(ev.sgv.map { u.format($0) } ?? "?") \(units) \(Self.arrow(ev.r))"
        func rate(_ x: Double) -> String { u == .mgdl ? String(format: "%.1f", x) : String(format: "%.2f", x / 18.0182) }
        // The value and arrow only: the title already names the alert (no threshold, no "from the wearer's phone").
        let body = switch ev.k {
        case .veryLow, .low, .high, .veryHigh, .extremelyHigh: value
        case .fallingFast: "\(value) · falling \(rate(-(ev.r ?? 0))) \(units)/min"
        case .risingFast: "\(value) · rising \(rate(ev.r ?? 0)) \(units)/min"
        case .noData: "Nothing received for \(Int(ev.th)) min" + (ev.sgv.map { " · last \(u.format($0)) \(units)" } ?? "")
        }
        return (ev.k.message(treated: ev.tr), body)
    }

    /// Android's Trend.arrow: the text arrow for a rate (mg/dL/min).
    nonisolated static func arrow(_ r: Double?) -> String {
        guard let r else { return TrendMark.ring }   // no trend: the hollow ring (Android Trend.arrow)
        return r >= 3 ? "⇈" : r >= 2 ? "↑" : r >= 1 ? "↗" : r > -1 ? "→" : r > -2 ? "↘" : r > -3 ? "↓" : "⇊"
    }

    // MARK: notification actions (SPEC §2)

    /// Categories by which buttons apply: Mute <n> min + Mute for… unless very low / extremely high, Slow down unless
    /// already slowed. Registered again when the minutes change (the titles carry them).
    private func registerCategories() {
        guard let center else { return }
        let a = settings.notificationActions
        let mute = UNNotificationAction(identifier: "mute", title: "Mute \(a.muteMin) min")
        let slow = UNNotificationAction(identifier: "slow", title: "Slow to \(a.slowMin) min")
        let muteFor = UNTextInputNotificationAction(identifier: "muteFor", title: "Mute for…", options: [],
                                                    textInputButtonTitle: "Mute", textInputPlaceholder: "Minutes")
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "alert-ms", actions: [mute, slow, muteFor], intentIdentifiers: []),
            UNNotificationCategory(identifier: "alert-m", actions: [mute, muteFor], intentIdentifiers: []),
            UNNotificationCategory(identifier: "alert-s", actions: [slow], intentIdentifiers: []),
        ])
    }

    private func category(_ k: AlertKind, mirrored: Bool) -> String {
        let slowed = mirrored ? mirror.slowed(k) : engine.slowed(k)
        let rep = settings[k].repeatMin
        let slow = !slowed && (mirrored || (rep > 0 && rep < settings.notificationActions.slowMin))
        switch (!k.always, slow) {
        case (true, true): return "alert-ms"
        case (true, false): return "alert-m"
        case (false, true): return "alert-s"
        case (false, false): return ""
        }
    }

    /// The mirrored wearer's alerts are quieted on this Mac only (no data is always this Mac's own).
    private func mirrors(_ k: AlertKind) -> Bool { mode == .wearer && k != .noData }

    /// An alert notification's button: *Mute* for `min` minutes (nil = the setting) or *Slow down* on `k`'s episode, on
    /// this Mac only: mirroring the wearer it quiets the wearer's alerts here (AlertMirror), with own rules the engine
    /// here. Nothing is sent anywhere.
    func action(_ k: AlertKind, slow: Bool, min: Int? = nil) {
        let r = AlertActions.muteRange
        let m = Swift.min(Swift.max(min ?? settings.notificationActions.muteMin, r.lowerBound), r.upperBound)
        guard slow || !k.always else { return repost(k.group) }
        log("alerts: \(k.title) \(slow ? "slowed to every \(settings.notificationActions.slowMin) min" : "muted for \(m) min") (notification)")
        if mirrors(k) {
            if slow { mirror.slow(k, slowMin: settings.notificationActions.slowMin); repost(k.group) }
            else if mirror.mute(k, until: Date().addingTimeInterval(TimeInterval(m * 60))) {
                mirrored[k.group] = nil
                remove(k.group, "muted from the notification")
            }
            updatePaused()
        } else {
            engine.levelAndRate = mode == .own
            if slow { engine.slow(k) } else { muting = true; engine.mute(k, min: m); muting = false }
            saveEpisodes()
            scheduleCheck()
            if slow { repost(k.group) }
        }
        onChange?()
    }

    /// The group's notification again, without sound and with its buttons updated.
    private func repost(_ g: AlertKind.Group) {
        guard let (ev, m) = shownEvents[g] else { return }
        post(ev, mirrored: m, silent: true)
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler done: @escaping () -> Void) {
        let id = response.actionIdentifier
        let k = (response.notification.request.content.userInfo["k"] as? String).flatMap(AlertKind.init(rawValue:))
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                guard let k else { return }
                switch id {
                case "mute": self.action(k, slow: false)
                case "slow": self.action(k, slow: true)
                case "muteFor":
                    // the minutes in the reply ("20", "20 min"); none or out of range: nothing changes
                    if let m = typed.flatMap({ t in t.range(of: "\\d{1,4}", options: .regularExpression).flatMap { Int(t[$0]) } }), AlertActions.muteRange.contains(m) {
                        self.action(k, slow: false, min: m)
                    } else { self.repost(k.group) }
                default: break
                }
            }
        }
        done()
    }

    /// `silent`: an alert shown again after a restart (it already rang; see `resume`).
    func post(_ ev: AlertEvent, mirrored: Bool, test: Bool = false, silent: Bool = false) {
        var (title, body) = text(ev, mirrored: mirrored)
        if test { title = "Test · " + title }
        let sound = silent ? .none : Self.sound(for: ev.k, settings, last: lastVariant[ev.k])
        if let v = sound.variant { lastVariant[ev.k] = v }
        log("ALERT \(title) — \(body) [\(ev.k.group.rawValue), sound \(sound.label)]")
        guard center != nil else {           // `swift run`: no bundle, no notification center
            play(sound)
            return
        }
        if !test { shownEvents[ev.k.group] = (ev, mirrored) }
        let c = Self.content(title: title, body: body, k: ev.k, category: category(ev.k, mirrored: mirrored),
                             alarm: settings[ev.k].alarm, sound: sound)
        send(ev.k.group, UNNotificationRequest(identifier: ev.k.group.rawValue, content: c, trigger: nil))
    }

    /// The notification for an alert, the same for a mirrored alert and one from this Mac's own rules. The sound is
    /// a copy in ~/Library/Sounds (NotificationSounds: the bundle copy wasn't found by Notification Center).
    nonisolated static func content(title: String, body: String, k: AlertKind, category: String, alarm: Bool, sound: Sound)
        -> UNMutableNotificationContent {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        c.threadIdentifier = "sukrara-alerts"
        c.userInfo = ["k": k.rawValue]
        c.categoryIdentifier = category
        c.interruptionLevel = alarm && timeSensitive ? .timeSensitive : .active
        c.sound = NotificationSounds.content(sound)
        return c
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
        /// The pack variant (1–3) of a file sound.
        var variant: Int? {
            guard case .file(let n, _) = self else { return nil }
            return Int(n.dropLast(4).split(separator: "_").last ?? "")
        }
    }
    nonisolated static let variants = 3
    /// The variant each kind played last, so shuffle never plays the same one twice in a row.
    private var lastVariant: [AlertKind: Int] = [:]

    /// `<pack>_<kind>_<n>.caf`: with shuffle a random variant (1–3) other than `last` (the one this kind played
    /// before, like Android's AlertSounds.pick), else 1. URL for previews / `swift run`.
    nonisolated static func sound(for k: AlertKind, _ s: AlertSettings, pack: String? = nil, variant: Int? = nil, last: Int? = nil) -> Sound {
        let p = pack ?? s.pack(for: k)
        switch p {
        case "none": return .none
        case "system": return .system
        default:
            guard AlertSettings.packs.contains(p) else { return .system }
            let n = variant ?? (s.sounds.shuffle ? (1...variants).filter { $0 != last }.randomElement()! : 1)
            let name = "\(p)_\(k.rawValue)_\(n).caf"
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

    /// ▶︎ in the Alerts window: a random variant of what this kind would play (pack: a pack/system/none override),
    /// not the one the last ▶︎ played, so pressing it again walks through the variants.
    private var lastPreview: Int?
    func preview(_ k: AlertKind, pack: String? = nil) {
        var s = settings
        s.sounds.shuffle = true
        let snd = Self.sound(for: k, s, pack: pack, last: lastPreview)
        lastPreview = snd.variant
        play(snd)
    }

    // MARK: debug hooks

    /// `-alert-test <kind>`: post that alert once, with its sound, then log what Notification Center holds.
    func test(_ kind: String) {
        guard let k = AlertKind(rawValue: kind) else { return log("alert-test: unknown kind \(kind) (\(AlertKind.allCases.map(\.rawValue)))") }
        let sample: [AlertKind: (Double, Double)] = [.veryLow: (52, -1.2), .low: (62, -0.6), .high: (201, 0.8),
                                                     .veryHigh: (262, 1.1), .extremelyHigh: (312, 1.4), .fallingFast: (120, -2.6), .risingFast: (150, 2.4), .noData: (110, 0)]
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
            saveEpisodes()
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
