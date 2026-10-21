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
/// - *Mute* drops the wearer's alerts of that side while it lasts (every kind of the side, very low and extremely high
///   included; round f30), *Mute all* every alert;
/// - *Relax* shows a repeat only once `relaxMin` has passed since the last one shown here (a more severe kind of the
///   side always shows);
/// - Mute and Relax end at their time or once the side is back by the margin on the readings this Mac gets
///   (`reading`, the same rule as AlertEngine), not with the wearer's episode;
/// - *Unmute*, *Stop relaxing* and *Turn alerts back on* end them on this Mac; the wearer's newest firing that's still
///   up (dropped or skipped here) then shows at once.
/// The same struct drives the app (Alerts) and the timeline scenarios (AlertScenarios, `mirror`).
struct AlertMirror {
    /// side → this Mac's notification actions in force.
    private var snooze: [String: AlertEngine.Snooze] = [:]
    /// *Mute all* on this Mac until then (nil = off, AlertEngine.never = until turned back on).
    private(set) var allUntil: Date?
    /// group → when and which kind this Mac last showed (Relax spaces the wearer's repeats).
    private var last: [AlertKind.Group: (Date, AlertKind)] = [:]
    /// group → id of the wearer's firing whose notification is up here.
    private var shown: [AlertKind.Group: String] = [:]
    /// group → id and kind of the wearer's newest firing, shown here or not.
    private var newest: [AlertKind.Group: (String, AlertKind)] = [:]
    /// groups whose newest firing the wearer has cleared.
    private var newestCleared: Set<AlertKind.Group> = []

    private func allOn(_ t: Date) -> Bool { allUntil.map { $0 > t } ?? false }

    /// The wearer fired `k` (firing `id`, the episode's `n`th, at `at`): true = show it here.
    mutating func fire(_ k: AlertKind, id: String, n: Int, at: Date) -> Bool {
        let side = k.side, g = k.group
        newest[g] = (id, k)
        newestCleared.remove(g)
        let sn = snooze[side]
        let muted = (sn?.until.map { $0 > at } ?? false) || allOn(at)
        let relaxed = sn.map { sn in sn.relaxMin > 0 && last[g].map { (t, lk) in
            lk.side == side && k.severity <= lk.severity && at.timeIntervalSince(t) < TimeInterval(sn.relaxMin * 60) } ?? false } ?? false
        if muted || relaxed { return false }
        shown[g] = id
        last[g] = (at, k)
        return true
    }

    /// The wearer cleared its firing `id` of `k`'s group: true = the notification shown here goes. That's the firing
    /// shown here, or the wearer's newest one: a repeat this Mac skipped (relaxed) left the earlier firing up, and the
    /// wearer's clear of the repeat ends it too. An older clear arriving late changes nothing.
    mutating func clear(_ k: AlertKind, id: String) -> Bool {
        let g = k.group
        if newest[g]?.0 == id { newestCleared.insert(g) }
        guard let cur = shown[g], cur == id || newest[g]?.0 == id else { return false }
        shown[g] = nil
        return true
    }

    /// A reading this Mac got at `t` (`r` nil = none recent): ends a side's Mute / Relax once it is back by the margin
    /// (`AlertEngine.backBy`); returns those sides. A Mute's time ending needs no reading.
    mutating func reading(_ r: Reading?, _ s: AlertSettings, at t: Date) -> [String] {
        var out: [String] = []
        for side in snooze.keys.sorted() {
            guard var cur = snooze[side] else { continue }
            if let u = cur.until, u <= t { cur.until = nil; put(side, cur) }
            guard var sn = snooze[side] else { continue }
            guard let r, t.timeIntervalSince(r.date) <= AlertEngine.fresh else { continue }
            if !AlertEngine.backBy(side, r, s) { sn.backSince = nil; snooze[side] = sn; continue }
            let since = sn.backSince ?? t
            if t.timeIntervalSince(since) >= TimeInterval(s.notificationActions.backMin * 60) { snooze[side] = nil; out.append(side) }
            else { sn.backSince = since; snooze[side] = sn }
        }
        return out
    }

    /// This Mac's *Mute* on `k`'s side until `until`: true = an alert of it was showing (it goes).
    mutating func mute(_ k: AlertKind, until: Date) -> Bool {
        var sn = snooze[k.side] ?? AlertEngine.Snooze()
        sn.until = until
        sn.backSince = nil
        snooze[k.side] = sn
        return shown.removeValue(forKey: k.group) != nil
    }

    /// This Mac's *Relax* on `k`'s side.
    mutating func relax(_ k: AlertKind, relaxMin: Int) {
        var sn = snooze[k.side] ?? AlertEngine.Snooze()
        sn.relaxMin = relaxMin
        sn.backSince = nil
        snooze[k.side] = sn
    }

    /// This Mac's *Mute all* until `until`: returns the groups whose alert was showing (they go).
    mutating func muteAll(until: Date) -> [AlertKind.Group] {
        allUntil = until
        let gone = Array(shown.keys)
        shown = [:]
        return gone
    }

    func relaxed(_ k: AlertKind) -> Bool { (snooze[k.side]?.relaxMin ?? 0) > 0 }
    func showing(_ k: AlertKind) -> Bool { shown[k.group] != nil }

    /// This Mac's *Unmute* on `k`'s side at `t`: the id of the wearer's newest firing to show at once (still up on the
    /// wearer and not showing here), or nil.
    mutating func unmute(_ k: AlertKind, at t: Date) -> String? {
        guard var sn = snooze[k.side], let u = sn.until, u > t else { return nil }
        sn.until = nil
        put(k.side, sn)
        return allOn(t) ? nil : again(k.group, t)
    }

    /// This Mac's *Stop relaxing* on `k`'s side: like `unmute`, a skipped repeat still up shows at once.
    mutating func unrelax(_ k: AlertKind, at t: Date) -> String? {
        guard var sn = snooze[k.side], sn.relaxMin > 0 else { return nil }
        sn.relaxMin = 0
        put(k.side, sn)
        if (sn.until.map { $0 > t } ?? false) || allOn(t) { return nil }
        return again(k.group, t)
    }

    /// *Turn alerts back on* at `t`: the ids of the wearer's newest firings still up to show at once (not muted otherwise).
    mutating func unmuteAll(at t: Date) -> [String] {
        guard allOn(t) else { return [] }
        allUntil = nil
        return [AlertKind.Group.level, .rate, .noData].compactMap { g in
            guard let (_, k) = newest[g] else { return nil }
            if snooze[k.side]?.until.map({ $0 > t }) == true { return nil }
            return again(g, t)
        }
    }

    private mutating func put(_ side: String, _ sn: AlertEngine.Snooze) {
        snooze[side] = sn.until == nil && sn.relaxMin == 0 ? nil : sn
    }

    private mutating func again(_ g: AlertKind.Group, _ t: Date) -> String? {
        guard let (id, k) = newest[g], !newestCleared.contains(g), shown[g] != id else { return nil }
        shown[g] = id
        last[g] = (t, k)
        return id
    }

    /// This Mac's actions in force on `k`'s side (the panel and Settings › Alerts).
    func snooze(_ k: AlertKind) -> AlertEngine.Snooze? { snooze[k.side] }

    /// Every side with an action in force (the panel lists the ones without an alert too).
    func snoozes(_ t: Date) -> [String: AlertEngine.Snooze] {
        snooze.filter { ($0.value.until.map { $0 > t } ?? false) || $0.value.relaxMin > 0 }
            .mapValues { var sn = $0; if let u = sn.until, u <= t { sn.until = nil }; return sn }
    }

    /// Mute all's end at `t` (nil = off).
    func allMuted(_ t: Date) -> Date? { allOn(t) ? allUntil : nil }

    /// Not mirroring any more (own rules, alerts off): nothing of the wearer's shows here.
    mutating func forgetShown() { shown = [:] }

    /// Side → muted until (the side's Mute), for the badge; Mute all is `allMuted`.
    func paused(_ t: Date) -> [String: Date] { snooze.compactMapValues { sn in sn.until.flatMap { $0 > t ? $0 : nil } } }
}

// MARK: engine (SPEC §1–§2)

extension AlertKind: Codable {}

/// The rules, without any UI — a 1:1 port of Android's AlertEngine: feed it the newest reading (`reading`) and call
/// `tick()` every minute and at `nextCheckAt`; it reports firings, clears and margin ends through `onFire` / `onClear`
/// / `onBack`. Time comes from `now` (tests inject a clock).
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
/// - the alert notification's actions act on **one side** (lows incl. very low, highs incl. extremely high, falling
///   fast, rising fast, no data; round f30): *Mute* (`mute`) quiets it like a treatment does (alerts at once when it
///   ends), *Relax* (`relax`) repeats it at the slower interval. Neither ever touches the other side. They outlive the
///   episode and end at their time (Mute) or once the side is **back by the margin** (`backMgdl` inside the threshold
///   for `backMin` minutes: `onBack`), whichever comes first; crossing back out before that keeps them on;
/// - *Mute all* (`muteAll`) quiets every kind on this Mac until its end (`never` = until turned back on); when it ends
///   whatever still matches alerts at once;
/// - the ways out (SPEC §2 "States and exits"): *Unmute* / *Alert me anyway* (`unmute`) lifts the side's Mute, the
///   treatments logged so far and the falling-high mute for the rest of the episode, and alerts at once if it still
///   matches; *Stop relaxing* (`unrelax`) goes back to the kind's interval (an overdue repeat at once); a new `mute`
///   on a muted side changes its end; *Turn alerts back on* (`unmuteAll`) ends Mute all.
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
    /// The Mute / Relax of a side ("low", "high", "falling_fast", …) ended because glucose is back by the margin.
    var onBack: ((String) -> Void)?

    /// Readings older than this never alert (backfill, a late chunk).
    static let fresh = AlertStatus.fresh
    /// An episode lasts until nothing of its side has matched for its repeat interval, but at least this long.
    static let minHoldMin = 5
    /// `muteAll` without an end: until the user turns alerts back on (Android's NEVER).
    static let never = Date(timeIntervalSince1970: AlertStatus.never)

    /// What the UI shows per group: the matching kind, and why it's quiet if it is.
    struct State { var kind: AlertKind; var muted: String?; var firedAt: Date?; var n: Int }
    /// This device's notification actions on a side: muted until `until` (nil = not), repeats relaxed to `relaxMin`
    /// (0 = not). `backSince`: when the side was first back by the margin without crossing out again (nil = it isn't).
    struct Snooze: Equatable, Codable { var until: Date? = nil; var relaxMin = 0; var backSince: Date? = nil }
    /// An episode or side in a state the user can leave (SPEC §2 "States and exits"), for the panel and Settings ›
    /// Alerts: `quiet` why it's quiet (nil = not quiet) until `until` (nil = no fixed end), `relaxMin` > 0 = relaxed,
    /// `canRelax` = a Relax would change anything. A side muted or relaxed after its episode ended shows with `on`
    /// false and the side's main kind.
    struct Quiet: Equatable {
        enum Why: String { case notification, carbs, insulin, falling }
        var kind: AlertKind; var on: Bool; var quiet: Why?; var until: Date?; var relaxMin: Int; var canRelax: Bool
    }

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
        /// *Unmute* / *Alert me anyway* at this time: treatments logged up to then and the falling mute don't quiet it.
        var lifted: Date?
        /// Lifted since the last alert: the next match alerts at once (cleared when it does).
        var liftPending = false
        init(_ k: AlertKind, _ t: Date) { side = k.side; start = t; kind = k; lastIn = t; firedKind = k; next = t }
    }

    private var episodes: [AlertKind.Group: Episode] = [:]
    /// side → this device's Mute / Relax (`mute`, `relax`); they outlive episodes (see the class comment).
    private var snooze: [String: Snooze] = [:]
    /// *Mute all* until then (nil = off, `never` = until turned back on).
    private(set) var allUntil: Date?
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

    private struct Saved: Codable { var episodes: [String: Episode]; var snoozes: [String: Snooze]; var all: Date? }

    /// The episodes, the sides' Mute / Relax and Mute all, saved after every evaluation so a restart resumes them
    /// (Android's `AlertEngine.snapshot`): without it the next check after an update or relaunch takes an ongoing alert
    /// for a new one and rings again at once (docs/15, "Two sounds at once").
    func snapshot() -> Data {
        let s = Saved(episodes: Dictionary(uniqueKeysWithValues: episodes.map { ($0.key.rawValue, $0.value) }), snoozes: snooze, all: allUntil)
        return (try? JSONEncoder().encode(s)) ?? Data("{}".utf8)
    }

    /// Back to the `snapshot` state (episodes over by `t` are dropped). Returns the firings whose notification was up:
    /// the caller shows them again without sound. The next evaluation goes on from there: a repeat that came due while
    /// the app was down alerts, anything else waits for its time.
    @discardableResult func restore(_ data: Data, at t: Date) -> [AlertEvent] {
        episodes = [:]
        let saved = try? JSONDecoder().decode(Saved.self, from: data)
        snooze = saved?.snoozes ?? [:]
        allUntil = saved?.all.flatMap { $0 > t ? $0 : nil }
        for (g, e) in saved?.episodes ?? [:] {
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

    /// The alert notification's *Mute* for `min` minutes on `kind`'s side (every kind, very low and extremely high
    /// included; with or without an episode), then an evaluation (its alert clears). On a muted side: Change.
    func mute(_ kind: AlertKind, min: Int) {
        guard min > 0 else { return }
        var sn = snooze[kind.side] ?? Snooze()
        sn.until = now().addingTimeInterval(TimeInterval(min * 60))
        sn.backSince = nil
        snooze[kind.side] = sn
        evaluate()
    }

    /// The alert notification's *Relax* on `kind`'s side: its repeats come every `notificationActions.relaxMin` at least.
    func relax(_ kind: AlertKind) {
        var sn = snooze[kind.side] ?? Snooze()
        sn.relaxMin = settings.notificationActions.relaxMin
        sn.backSince = nil
        snooze[kind.side] = sn
        evaluate()
    }

    /// *Unmute* (a notification mute) / *Alert me anyway* (a treatment or falling-high mute) on `kind`'s side, this
    /// device only: the side's Mute ends, and whatever quiets its episode now is lifted for the rest of the episode (a
    /// treatment logged later mutes again), then an evaluation: still matching → it alerts at once; back in range →
    /// nothing. Nothing when nothing is quiet.
    func unmute(_ kind: AlertKind) {
        let t = now(), side = kind.side
        let sn = snooze[side]
        let muted = sn?.until.map { $0 > t } ?? false
        let e = episodes[kind.group].flatMap { $0.side == side ? $0 : nil }
        let quiet = e.map { $0.muted != nil || cover($0, $0.kind, settings, doses, t) != nil } ?? false
        guard muted || quiet else { return }
        if var sn { sn.until = nil; put(side, sn) }
        if let e {
            e.lifted = t
            e.liftPending = true
        }
        evaluate()
    }

    /// *Stop relaxing* on `kind`'s side: the kind's own interval again (a repeat already due alerts at once).
    func unrelax(_ kind: AlertKind) {
        guard var sn = snooze[kind.side], sn.relaxMin > 0 else { return }
        sn.relaxMin = 0
        put(kind.side, sn)
        evaluate()
    }

    /// *Mute all* until `until` (`never` = until turned back on; a later call changes it), then an evaluation.
    func muteAll(until: Date) {
        guard until > now() else { return }
        allUntil = until
        evaluate()
    }

    /// *Turn alerts back on*: Mute all ends; whatever still matches (and isn't muted otherwise) alerts at once.
    func unmuteAll() {
        guard allMuted(now()) != nil else { return }
        allUntil = nil
        evaluate()
    }

    /// Mute all's end at `t` (nil = off, `never` = until turned back on).
    func allMuted(_ t: Date) -> Date? { allUntil.flatMap { $0 > t ? $0 : nil } }

    private func put(_ side: String, _ sn: Snooze) { snooze[side] = sn.until == nil && sn.relaxMin == 0 ? nil : sn }

    /// The side's Mute in force at `t`, or nil.
    private func muteEnd(_ side: String, _ t: Date) -> Date? { snooze[side]?.until.flatMap { $0 > t ? $0 : nil } }

    /// The episodes and sides the user can act on now (quiet or relaxed): see `Quiet`. Mute all is `allMuted`.
    func quiets() -> [Quiet] {
        let s = settings, t = now()
        var out: [Quiet] = [], seen: Set<String> = []
        func canRelax(_ k: AlertKind, _ relax: Int) -> Bool { relax == 0 && s[k].repeatMin > 0 && s[k].repeatMin < s.notificationActions.relaxMin }
        for g in [AlertKind.Group.level, .rate, .noData] {
            guard let e = episodes[g] else { continue }
            seen.insert(e.side)
            let note = muteEnd(e.side, t)
            let relax = snooze[e.side]?.relaxMin ?? 0
            var why: Quiet.Why?, until: Date?
            if let by = s.mutedBy(e.kind), let tc = cover(e, e.kind, s, doses, t) {
                why = by == "rapid" ? .insulin : .carbs
                until = max(tc.addingTimeInterval(s.muteS(by)), note ?? .distantPast)
            } else if let note { why = .notification; until = note }
            // the falling-high mute (Mute all's own text is not a falling mute: it shows as Mute all)
            else if e.on, e.muted?.hasPrefix("falling") == true { why = .falling }
            guard why != nil || relax > 0 else { continue }
            out.append(Quiet(kind: e.kind, on: e.on, quiet: why, until: until, relaxMin: relax, canRelax: canRelax(e.kind, relax)))
        }
        for side in snooze.keys.sorted() where !seen.contains(side) {
            guard let sn = snooze[side] else { continue }
            let until = sn.until.flatMap { $0 > t ? $0 : nil }
            guard until != nil || sn.relaxMin > 0 else { continue }
            let k = AlertKind.main(side)
            out.append(Quiet(kind: k, on: false, quiet: until != nil ? .notification : nil, until: until, relaxMin: sn.relaxMin,
                             canRelax: canRelax(k, sn.relaxMin)))
        }
        return out
    }

    private var doses: (String) -> [Date] { let r = rapidInsulin(), c = carbs(); return { $0 == "rapid" ? r : c } }

    /// The newest treatment that quiets `k` in `e` at `t` (logged after an *Alert me anyway*), or nil.
    private func cover(_ e: Episode, _ k: AlertKind, _ s: AlertSettings, _ d: (String) -> [Date], _ t: Date) -> Date? {
        guard let tk = s.mutedBy(k) else { return nil }
        return d(tk).filter { x in x <= t && t.timeIntervalSince(x) < s.muteS(tk) && (e.lifted.map { x > $0 } ?? true) }.max()
    }

    /// This device's notification actions in force, per side ("low", "high", "falling_fast", …): the badge, the panel.
    func snoozes(_ t: Date) -> [String: Snooze] {
        snooze.filter { ($0.value.until.map { $0 > t } ?? false) || $0.value.relaxMin > 0 }
            .mapValues { var sn = $0; if let u = sn.until, u <= t { sn.until = nil }; return sn }
    }

    /// Whether `kind`'s side is relaxed already (the notification offers *Stop relaxing* instead of *Relax*).
    func relaxed(_ kind: AlertKind) -> Bool { (snooze[kind.side]?.relaxMin ?? 0) > 0 }

    /// Timer: the no-data deadline, repeats and mute windows that end while the reading stays the same.
    func tick() { evaluate() }

    /// Settings, treatments or mode changed: re-check everything now.
    func refresh() { evaluate() }

    /// The level kind `r` is in at `now`, or nil (stale reading, in range, alerts off). Lows `<`, highs `>=`.
    static func level(_ r: Reading?, _ s: AlertSettings, _ now: Date) -> AlertKind? {
        r.flatMap { AlertStatus.level(mgdl: $0.mgdl, at: $0.date.timeIntervalSince1970, s, now: now.timeIntervalSince1970) }
    }

    /// The reading `r` is back in range by the margin for `side`: lows at least `backMgdl` above the low threshold (the
    /// highest enabled low-side one), highs below the high threshold (the lowest enabled high-side one) minus that; a
    /// rate side when `r`'s rate doesn't match it. The follower's AlertMirror uses it too.
    static func backBy(_ side: String, _ r: Reading, _ s: AlertSettings) -> Bool {
        let m = Double(s.notificationActions.backMgdl)
        switch side {
        case "low":
            guard let edge = [AlertKind.veryLow, .low].filter({ s[$0].on }).map({ s[$0].threshold }).max() else { return true }
            return r.mgdl >= edge + m
        case "high":
            guard let edge = [AlertKind.high, .veryHigh, .extremelyHigh].filter({ s[$0].on }).map({ s[$0].threshold }).min() else { return true }
            return r.mgdl < edge - m
        case AlertKind.fallingFast.rawValue: return !s[.fallingFast].on || (r.rate ?? 0) > -s[.fallingFast].threshold
        case AlertKind.risingFast.rawValue: return !s[.risingFast].on || (r.rate ?? 0) < s[.risingFast].threshold
        default: return true
        }
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
        // the sides' Mute / Relax: a Mute's time is over, or the side is back by the margin for backMin minutes
        for side in snooze.keys.sorted() {
            guard var cur = snooze[side] else { continue }
            if let u = cur.until, u <= t { cur.until = nil; put(side, cur) }
            guard var sn = snooze[side] else { continue }
            if !fresh && side != AlertKind.noData.rawValue { continue }    // nothing new to judge the margin by
            let back: Bool = switch side {
            case "low", "high": r.map { Self.backBy(side, $0, s) } ?? false
            case AlertKind.noData.rawValue: !noData
            default: rate?.rawValue != side
            }
            if !back { sn.backSince = nil; snooze[side] = sn; continue }
            let since = sn.backSince ?? t
            if t.timeIntervalSince(since) >= TimeInterval(s.notificationActions.backMin * 60) { snooze[side] = nil; onBack?(side) }
            else { sn.backSince = since; snooze[side] = sn }
        }
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
        let cover = cover(e, want, s, d, t)
        if let mute = cover.map({ "\(by == "rapid" ? "insulin" : "carbs") \(Durations.text(Int(t.timeIntervalSince($0) / 60))) ago" })
            ?? (e.lifted == nil ? fallingMute(want, s) : nil) {
            hide(e, t)
            e.muted = mute
            e.next = cover.map { $0.addingTimeInterval(s.muteS(by!)) }   // the falling mute has no end of its own
            return
        }
        let all = allMuted(t)
        let quietUntil = [muteEnd(e.side, t), all].compactMap { $0 }.max()
        if let q = quietUntil {
            hide(e, t)
            e.muted = all == Self.never ? "all alerts muted"
                : "muted \(q == all ? "(all alerts)" : "from the notification"), \(Durations.text(seconds: q.timeIntervalSince(t))) left"
            e.next = q
            return
        }
        e.muted = nil
        e.next = due(e, want, s, d, t)
        guard let next = e.next, t >= next else { return }
        e.n += 1
        e.firedAt = t
        e.firedKind = want
        e.liftPending = false
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
        if e.liftPending { return t }   // an Unmute / Alert me anyway since the last alert: at once
        // a treatment, the side's Mute or Mute all after the last alert: at once when its window ends
        var ends: [Date] = []
        if let by = s.mutedBy(k), let tc = d(by).filter({ x in x >= fired && x <= t && (e.lifted.map { x > $0 } ?? true) }).max() {
            ends.append(tc.addingTimeInterval(s.muteS(by)))
        }
        if let q = snooze[e.side]?.until, q > fired { ends.append(q) }
        if let a = allUntil, a > fired, a != Self.never { ends.append(a) }
        if let end = ends.max() { return max(end, fired.addingTimeInterval(1)) }
        let base = s[k].repeatMin
        let relax = snooze[e.side]?.relaxMin ?? 0
        let rep = relax > 0 && base > 0 ? max(base, relax) : base
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
        return "falling, back in range in ~\(Durations.text(max(1, Int(((r.mgdl - high) / -v).rounded(.up)))))"
    }

    /// When the next timer check is useful (no-data deadline, a repeat, a mute window ending, the margin's time), or nil.
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
        for e in episodes.values where e.on { if let n = e.next, n < Self.never { c.append(n) } }
        // a Mute ending without an episode, the margin's time, Mute all ending
        for sn in snooze.values {
            if let u = sn.until, u > now { c.append(u) }
            if let b = sn.backSince { c.append(b.addingTimeInterval(TimeInterval(settings.notificationActions.backMin * 60))) }
        }
        if let a = allUntil, a > now, a < Self.never { c.append(a) }
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

    static func ago(_ s: TimeInterval) -> String { s < 90 ? "just now" : "\(Durations.text(Int(s / 60))) ago" }
}

// MARK: the Mac app side

/// Where the alert notifications go: Notification Center in the app, a recorder in `-alerts-selftest` (AlertsSelfTest:
/// every state's notification, its category and what each action posts or removes, without the real center).
@MainActor protocol AlertPoster: AnyObject {
    func add(_ req: UNNotificationRequest)
    func remove(_ id: String)
    func setCategories(_ c: Set<UNNotificationCategory>)
}

@MainActor final class CenterPoster: AlertPoster {
    let center: UNUserNotificationCenter
    init(_ c: UNUserNotificationCenter) { center = c }
    func add(_ req: UNNotificationRequest) {
        center.add(req) { err in if let err { log("alerts: notification failed: \(err.localizedDescription)") } }
    }
    func remove(_ id: String) { center.removeDeliveredNotifications(withIdentifiers: [id]) }
    func setCategories(_ c: Set<UNNotificationCategory>) { center.setNotificationCategories(c) }
}

/// Owns the settings, the engine and the notifications. Settings › Alerts (SettingsUI.swift, AlertsUI.swift) edits them.
@MainActor final class Alerts: NSObject, UNUserNotificationCenterDelegate {
    private let defaults: UserDefaults
    /// The clock (the self-test moves it; the engine reads it too).
    var now: () -> Date = Date.init
    /// The rules that apply (`AlertSettings.load`: this Mac's own with the mirrored ones from Settings from).
    private(set) var settings: AlertSettings
    /// What this Mac stores (`alertSettings`): Settings › Alerts edits it.
    private(set) var own: AlertSettings
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
    /// What each group's notification shows now, to post it again without sound (after Relax, a bad typed length).
    private var shownEvents: [AlertKind.Group: (AlertEvent, Bool)] = [:]
    /// Groups whose alert notification is up now (not the "muted until" one).
    private var live: Set<AlertKind.Group> = []
    /// Mirroring the wearer: which of its alerts show here, and this Mac's own clicks on them (AlertMirror).
    private var mirror = AlertMirror()
    /// Mirroring: the wearer's newest firing per group (shown here or not), for Unmute / Stop relaxing.
    private var remoteNewest: [AlertKind.Group: AlertEvent] = [:]
    /// group → the "Low muted until 10:42" notification is up until then (removed at the end, Unmute, back in range).
    private(set) var mutedNote: [AlertKind.Group: Date] = [:]
    /// Side ("low", "high", …) → until (unix s): this Mac's Mute of the side. The badge under the value shows it
    /// (AlertStatus.paused); `onPausedChange` redraws the card and the widgets.
    private(set) var paused: [String: TimeInterval] = [:]
    /// This Mac's *Mute all* until (unix s; 0 = off, AlertStatus.never = until turned back on): the muted pill.
    private(set) var allMuted: TimeInterval = 0
    var onPausedChange: (() -> Void)?
    private var timer: Timer?
    /// One-shot at `engine.nextCheckAt`: a mute window or repeat that ends between readings alerts on time.
    private var checkTimer: Timer?
    /// Set while a Mute / Mute all runs (its clear is logged as the mute).
    private var muting = false
    /// Opens Settings on the Alerts pane (main.swift).
    var onOpenWindow: (() -> Void)?
    private let center: UNUserNotificationCenter?
    private let poster: AlertPoster?
    /// An offline run (LaunchGuard.services: evidence render, dev build): the rules are shown, nothing ever alerts.
    let offline: Bool
    /// `-alerts-selftest`: a recorder instead of Notification Center, no timers, no sounds played.
    let testing: Bool
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

    /// `poster` + `defaults` (tests only): record the notifications instead of posting them, keep the state elsewhere.
    init(offline: Bool, poster testPoster: AlertPoster? = nil, defaults: UserDefaults = .standard) {
        self.offline = offline
        self.defaults = defaults
        testing = testPoster != nil
        settings = AlertSettings.load(defaults)
        own = AlertSettings.own(defaults)
        center = offline || testPoster != nil || Bundle.main.bundleIdentifier == nil ? nil : .current()
        poster = testPoster ?? center.map { CenterPoster($0) }
        engine = AlertEngine(settings: settings)
        engine.sensor = { SensorStore.current }
        super.init()
        engine.now = { [unowned self] in self.now() }
        if offline { log("alerts: offline run, the alert engine is off"); return }
        if center != nil { NotificationSounds.prune() }
        center?.delegate = self
        registerCategories()
        engine.onFire = { [weak self] ev in self?.mutedNote[ev.k.group] = nil; self?.post(ev, mirrored: false) }
        // a clear while a Mute runs is the mute (the log already says "muted for"), not the episode's end
        engine.onClear = { [weak self] ev in self?.remove(ev.k.group, self?.muting == true ? "muted" : "\(ev.k.rawValue) cleared") }
        engine.onBack = { [weak self] side in self?.backInRange(side) }
        if testing { return }
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
        guard !offline else { return }
        engine.seed(newest)
        guard let data = defaults.data(forKey: Self.episodesKey) else { return scheduleCheck() }
        let shown = engine.restore(data, at: now())
        for ev in shown { post(ev, mirrored: false, silent: true) }
        if !shown.isEmpty { log("alerts: resumed \(shown.map(\.k.title).joined(separator: ", ")) (no sound)") }
        updatePaused(force: true)
        scheduleCheck()
    }

    private func saveEpisodes(force: Bool = false) {
        defaults.set(engine.snapshot(), forKey: Self.episodesKey)
        updatePaused(force: force)
    }

    /// `force`: redraw even if no mute end changed (Stop relaxing, a lifted treatment mute: the panel's rows).
    private func updatePaused(force: Bool = false) {
        let t = now()
        var p: [String: TimeInterval] = [:]
        for (side, sn) in engine.snoozes(t) { if let u = sn.until { p[side] = u.timeIntervalSince1970 } }
        for (side, u) in mirror.paused(t) { p[side] = u.timeIntervalSince1970 }
        let all = [engine.allMuted(t), mirror.allMuted(t)].compactMap { $0?.timeIntervalSince1970 }.max() ?? 0
        guard p != paused || all != allMuted || force else { return }
        paused = p
        allMuted = all
        onPausedChange?()
    }

    func openWindow() { onOpenWindow?() }

    // MARK: the ways out (SPEC §2 "States and exits")

    /// One quiet alert on this Mac (the panel's rows and pill menu, Settings › Alerts › Active now): `quiet` why
    /// (nil = only relaxed) until `until`; `mirrored` = the wearer's alert mirrored here (only this Mac's own Mute /
    /// Relax show; the wearer's phone decides the treatment mutes).
    struct Active: Equatable {
        var kind: AlertKind; var quiet: AlertEngine.Quiet.Why?; var until: Date?; var relaxMin: Int
        var canRelax: Bool; var mirrored: Bool
        /// "Low muted until 10:42", "Low quiet after carbs until 10:42", "Low relaxed: reminders every 30 min".
        var title: String {
            let t = until.map { Clock.time($0) } ?? ""
            switch quiet {
            case .notification: return "\(kind.title) muted until \(t)"
            case .carbs: return "\(kind.title) quiet after carbs until \(t)"
            case .insulin: return "\(kind.title) quiet after insulin until \(t)"
            case .falling: return "\(kind.title) quiet: falling back into range"
            case nil: return relaxedLine
            }
        }
        /// "Low relaxed: reminders every 30 min" (also the second line of a muted + relaxed side).
        var relaxedLine: String { "\(kind.title) relaxed: reminders every \(Durations.text(relaxMin))" }
        /// The main way out: Unmute (a notification mute) or Alert Me Anyway (a treatment / falling mute here).
        var lift: String? {
            if quiet == .notification { return "Unmute" }
            return quiet != nil && !mirrored ? "Alert Me Anyway" : nil
        }
    }

    /// Everything quiet or relaxed on this Mac now (the sides; Mute all is `allMutedUntil`).
    var active: [Active] {
        let t = now()
        var out: [Active] = []
        if mode == .wearer {
            for side in [AlertKind.low, .high, .fallingFast, .risingFast] {
                guard let sn = mirror.snooze(side) else { continue }
                let k = remoteNewest[side.group].flatMap { $0.k.side == side.side ? $0.k : nil } ?? side
                let until = sn.until.flatMap { $0 > t ? $0 : nil }
                guard until != nil || sn.relaxMin > 0 else { continue }
                out.append(Active(kind: k, quiet: until != nil ? .notification : nil, until: until, relaxMin: sn.relaxMin,
                                  canRelax: sn.relaxMin == 0, mirrored: true))
            }
        }
        for q in engine.quiets() where !out.contains(where: { $0.kind.side == q.kind.side }) {
            out.append(Active(kind: q.kind, quiet: q.quiet, until: q.until, relaxMin: q.relaxMin, canRelax: q.canRelax, mirrored: false))
        }
        return out
    }

    /// *Mute all*'s end (nil = off; `AlertEngine.never` = until turned back on).
    var allMutedUntil: Date? { allMuted > now().timeIntervalSince1970 ? Date(timeIntervalSince1970: allMuted) : nil }

    /// "Alerts muted until 14:30" / "Alerts muted" (Never) / nil: the dropdown row, Settings › Alerts, the menu bar.
    var allMutedLine: String? {
        guard let u = allMutedUntil else { return nil }
        return u >= AlertEngine.never ? "Alerts muted" : "Alerts muted until \(Clock.time(u))"
    }

    /// *Unmute* / *Alert Me Anyway* on `k`'s side, this Mac only: still out of range → it alerts now; back in range →
    /// nothing. The "muted until" notification goes; the card, the widgets and the ring redraw at once.
    func unmute(_ k: AlertKind) {
        log("alerts: \(k.title) unmuted")
        let g = k.group
        if mutedNote.removeValue(forKey: g) != nil { remove(g, "unmuted") }
        if mirrors(k) {
            if let id = mirror.unmute(k, at: now()), let ev = remoteNewest[g], ev.id == id {
                mirrored[g] = ev
                post(ev, mirrored: true)
            }
            updatePaused(force: true)
        } else {
            engine.levelAndRate = mode == .own
            engine.unmute(k)
            saveEpisodes(force: true)
            scheduleCheck()
        }
        onChange?()
    }

    /// A way out (or a Mute / Relax / Mute all) chosen in the panel, the pill's menu or Settings › Alerts.
    func perform(_ op: QuietOp) {
        switch op {
        case .unmute(let k): unmute(k)
        case .unrelax(let k): unrelax(k)
        case .mute(let k, let m): action(k, relax: false, min: m)
        case .relax(let k): action(k, relax: true)
        case .muteAll(let m): muteAll(minutes: m)
        case .unmuteAll: unmuteAll()
        }
    }

    /// *Stop relaxing* on `k`'s side, this Mac only: the kind's interval again (an overdue repeat now).
    func unrelax(_ k: AlertKind) {
        log("alerts: \(k.title) back to every \(Durations.text(settings[k].repeatMin))")
        if mirrors(k) {
            if let id = mirror.unrelax(k, at: now()), let ev = remoteNewest[k.group], ev.id == id {
                mirrored[k.group] = ev
                post(ev, mirrored: true)
            } else { repost(k.group) }
            updatePaused(force: true)
        } else {
            engine.levelAndRate = mode == .own
            let before = shownEvents[k.group]?.0.id
            engine.unrelax(k)
            saveEpisodes(force: true)
            scheduleCheck()
            if shownEvents[k.group]?.0.id == before { repost(k.group) }   // an overdue repeat already posted itself
        }
        onChange?()
    }

    /// *Mute all* on this Mac for `minutes` (0 = Never, until turned back on), every kind incl. no data, very low and
    /// extremely high, this Mac's own rules and the wearer's mirrored alerts: their notifications go. A new length
    /// while on changes the end (Change).
    func muteAll(minutes: Int) {
        let t = now()
        let until = minutes <= 0 ? AlertEngine.never : t.addingTimeInterval(TimeInterval(minutes * 60))
        log("alerts: \(minutes <= 0 ? "all alerts muted until turned back on" : "all alerts muted for \(Durations.text(minutes))")")
        for g in mirror.muteAll(until: until) { mirrored[g] = nil; remove(g, "all alerts muted") }
        engine.levelAndRate = mode == .own
        muting = true
        engine.muteAll(until: until)
        muting = false
        // the "muted until" notes go too: nothing alerts until Mute all ends
        for g in mutedNote.keys { remove(g, "all alerts muted") }
        mutedNote = [:]
        saveEpisodes(force: true)
        scheduleCheck()
        onChange?()
    }

    /// *Turn alerts back on*: Mute all ends on this Mac; whatever still matches (and isn't muted otherwise) alerts at
    /// once, the wearer's newest alerts still up included.
    func unmuteAll() {
        guard allMutedUntil != nil else { return }
        log("alerts: alerts back on")
        let t = now()
        for id in mirror.unmuteAll(at: t) {
            guard let (g, ev) = remoteNewest.first(where: { $0.value.id == id }) else { continue }
            mirrored[g] = ev
            post(ev, mirrored: true)
        }
        engine.levelAndRate = mode == .own
        engine.unmuteAll()
        saveEpisodes(force: true)
        scheduleCheck()
        onChange?()
    }

    /// A side's Mute / Relax ended because glucose is back by the margin (engine or mirror): its "muted until"
    /// notification goes, the badge and the panel follow.
    private func backInRange(_ side: String) {
        let k = AlertKind.main(side)
        log("alerts: \(k.title) back in range: mute and relax ended")
        if mutedNote.removeValue(forKey: k.group) != nil { remove(k.group, "back in range") }
        updatePaused(force: true)
    }

    /// The silent "Low muted until 10:42" notification with *Unmute* and *Change…* (the alert's own went when its
    /// button was used: macOS removes a notification once an action is chosen). Passive: no sound, no banner over Focus.
    private func postMuted(_ k: AlertKind, until: Date) {
        guard poster != nil else { return }
        mutedNote[k.group] = until
        let c = UNMutableNotificationContent()
        c.title = "\(k.title) muted until \(Clock.time(until))"
        if let (ev, m) = shownEvents[k.group] { c.body = text(ev, mirrored: m).1 }
        c.threadIdentifier = "sukrara-alerts"
        c.userInfo = ["k": k.rawValue]
        c.categoryIdentifier = "muted"
        c.interruptionLevel = .passive
        live.remove(k.group)
        send(k.group, UNNotificationRequest(identifier: k.group.rawValue, content: c, trigger: nil))
    }

    /// Muted notifications whose mute is over (or whose side isn't muted any more) go.
    private func expireMutedNotes() {
        let t = now(), quiet = Set(active.filter { $0.quiet == .notification }.map(\.kind.group))
        for (g, until) in mutedNote where until <= t || !quiet.contains(g) {
            mutedNote[g] = nil
            remove(g, "mute over")
        }
    }

    private func authorize() {
        center?.requestAuthorization(options: [.alert, .sound]) { ok, err in
            log("alerts: notifications \(ok ? "allowed" : "not allowed")\(err.map { " (\($0.localizedDescription))" } ?? "")")
        }
    }

    // MARK: inputs

    /// The newest merged reading (Store.publish), with the best available rate: this Mac's rules, and the margin that
    /// ends this Mac's Mute / Relax of the wearer's mirrored alerts (AlertMirror.reading).
    func onLatest(_ r: Reading) {
        guard !offline else { return }
        engine.levelAndRate = mode == .own
        engine.reading(r)
        mirrorReading(r)
        saveEpisodes()
        scheduleCheck()
        onChange?()
    }

    private func mirrorReading(_ r: Reading?) {
        for side in mirror.reading(r, settings, at: now()) { backInRange(side) }
    }

    /// A treatment was logged, edited or deleted: mutes start (or end) at once.
    func treatmentsChanged() { tick() }

    /// The wearer's sensor changed (ended, a new one warming up): no data may stop or start counting.
    func sensorChanged() { tick() }

    private func tick() {
        engine.levelAndRate = mode == .own
        engine.tick()
        mirrorReading(engine.latest)
        expireMutedNotes()
        saveEpisodes()
        scheduleCheck()
        onChange?()
    }

    private func scheduleCheck() {
        checkTimer?.invalidate()
        checkTimer = nil
        guard !testing, let at = engine.nextCheckAt(now()) else { return }
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
        own = AlertSettings.own(defaults)
        apply(AlertSettings.load(defaults))
    }

    /// This Mac's own settings edited (Settings › Alerts): saved, then what applies is recomputed.
    func update(_ o: AlertSettings) {
        guard o != own else { return }
        own = o
        o.save(defaults)
        apply(WearerSettings.alerts(own: o, defaults))
    }

    /// The self-test's rules, as they apply (no Settings from).
    func useForTest(_ s: AlertSettings) { apply(s) }
    /// The self-test's minute timer.
    func testTick() { tick() }

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
        guard !offline else { return }
        guard let ev = AlertEvent(body: body) else { return log("alerts: wearer alert not understood") }
        // relays + BLE: one copy each
        guard seenIds.insert("\(ev.id):\(ev.state)").inserted else { return }
        guard settings.on, mode == .wearer else { return log("alerts: wearer \(ev.k.rawValue) \(ev.state) ignored (\(settings.on ? "own rules" : "alerts off"))") }
        let g = ev.k.group
        // this Mac's own Mute / Relax / Mute all decide (AlertMirror); the wearer's never reach here
        if ev.state == "fire" {
            remoteNewest[g] = ev
            if mirror.fire(ev.k, id: ev.id, n: ev.n, at: ev.at) {
                mutedNote[g] = nil
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
        let all = allMutedLine.map { [$0] } ?? []
        if settings.on, mode == .wearer {
            parts = [AlertKind.Group.level, .rate, .noData].compactMap { g in
                mirrored[g].map { "\($0.k.message(treated: $0.tr)) · from the wearer \(AlertEngine.ago(now().timeIntervalSince($0.at)))" }
            }
            if let nd = engine.active[.noData] { parts.append("\(nd.kind.title) · here") }
            return (all + (parts.isEmpty ? ["No alert (mirroring the wearer)"] : parts)).joined(separator: " · ")
        }
        return (all + [engine.stateLine]).joined(separator: " · ")
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
        case .noData: "Nothing received for \(Durations.text(Int(ev.th)))" + (ev.sgv.map { " · last \(u.format($0)) \(units)" } ?? "")
        }
        return (ev.k.message(treated: ev.tr), body)
    }

    /// Android's Trend.arrow: the text arrow for a rate (mg/dL/min).
    nonisolated static func arrow(_ r: Double?) -> String {
        guard let r else { return TrendMark.ring }   // no trend: the hollow ring (Android Trend.arrow)
        return r >= 3 ? "⇈" : r >= 2 ? "↑" : r >= 1 ? "↗" : r > -1 ? "→" : r > -2 ? "↘" : r > -3 ? "↓" : "⇊"
    }

    // MARK: notification actions (SPEC §2)

    /// The notification categories (round f30): every alert, every kind, has exactly two actions, *Mute…* (a text
    /// input: a typed length, empty = `muteMin`) and *Relax* / *Stop relaxing*; an alert whose repeats Relax wouldn't
    /// slow has Mute… alone. The muted notification: *Unmute* + *Change…* (the same input).
    nonisolated static func categories() -> Set<UNNotificationCategory> {
        let mute = UNTextInputNotificationAction(identifier: "mute", title: "Mute…", options: [],
                                                 textInputButtonTitle: "Mute", textInputPlaceholder: "30 min, 1 h, 2 h…")
        let relax = UNNotificationAction(identifier: "relax", title: "Relax")
        let unrelax = UNNotificationAction(identifier: "unrelax", title: "Stop relaxing")
        let unmute = UNNotificationAction(identifier: "unmute", title: "Unmute")
        let change = UNTextInputNotificationAction(identifier: "mute", title: "Change…", options: [],
                                                   textInputButtonTitle: "Mute", textInputPlaceholder: "30 min, 1 h, 2 h…")
        return [
            UNNotificationCategory(identifier: "alert-r", actions: [mute, relax], intentIdentifiers: []),
            UNNotificationCategory(identifier: "alert-u", actions: [mute, unrelax], intentIdentifiers: []),
            UNNotificationCategory(identifier: "alert", actions: [mute], intentIdentifiers: []),
            UNNotificationCategory(identifier: "muted", actions: [unmute, change], intentIdentifiers: []),
        ]
    }

    private func registerCategories() { poster?.setCategories(Self.categories()) }

    /// The category of `k`'s alert: Stop relaxing once relaxed, Relax when it would slow the repeats, else Mute… alone.
    func category(_ k: AlertKind, mirrored: Bool) -> String {
        if mirrored ? mirror.relaxed(k) : engine.relaxed(k) { return "alert-u" }
        let rep = settings[k].repeatMin
        return mirrored || (rep > 0 && rep < settings.notificationActions.relaxMin) ? "alert-r" : "alert"
    }

    /// The mirrored wearer's alerts are quieted on this Mac only (no data is always this Mac's own).
    private func mirrors(_ k: AlertKind) -> Bool { mode == .wearer && k != .noData }

    /// An alert notification's action: *Mute* for `min` minutes (nil = the setting) or *Relax* on `k`'s side, on this
    /// Mac only: mirroring the wearer it quiets the wearer's alerts here (AlertMirror), with own rules the engine here.
    /// Nothing is sent anywhere.
    func action(_ k: AlertKind, relax: Bool, min: Int? = nil) {
        let r = AlertActions.muteRange
        let m = Swift.min(Swift.max(min ?? settings.notificationActions.muteMin, r.lowerBound), r.upperBound)
        let until = now().addingTimeInterval(TimeInterval(m * 60))
        log("alerts: \(relax ? "\(k.title) relaxed: every \(Durations.text(settings.notificationActions.relaxMin))" : "\(k.title) muted for \(Durations.text(m))") (notification)")
        let wasMuted = paused[k.side] != nil
        if mirrors(k) {
            if relax { mirror.relax(k, relaxMin: settings.notificationActions.relaxMin); repost(k.group) }
            else if mirror.mute(k, until: until) || wasMuted || mutedNote[k.group] != nil {
                mirrored[k.group] = nil
                remove(k.group, "muted")
                postMuted(k, until: until)
            }
            updatePaused(force: true)
        } else {
            engine.levelAndRate = mode == .own
            let showing = live.contains(k.group) && shownEvents[k.group].map { $0.0.k.side == k.side } == true
            if relax { engine.relax(k) } else { muting = true; engine.mute(k, min: m); muting = false }
            saveEpisodes(force: true)
            scheduleCheck()
            if relax { repost(k.group) }
            else if showing || mutedNote[k.group] != nil { postMuted(k, until: until) }
        }
        onChange?()
    }

    /// The group's notification again, without sound and with its buttons updated.
    private func repost(_ g: AlertKind.Group) {
        guard let (ev, m) = shownEvents[g] else { return }
        if let u = mutedNote[g] { return postMuted(ev.k, until: u) }
        post(ev, mirrored: m, silent: true)
    }

    /// A notification's action (`didReceive`, and the self-test): `typed` = the Mute… / Change… text.
    func respond(_ id: String, _ k: AlertKind, typed: String?) {
        switch id {
        case "relax": action(k, relax: true)
        case "unrelax": unrelax(k)
        case "unmute": unmute(k)
        case "mute":
            // the length in the reply ("45", "1 h", "1h 30m"; Durations.parse); empty = the setting; invalid: unchanged
            let text = (typed ?? "").trimmingCharacters(in: .whitespaces)
            if text.isEmpty { action(k, relax: false) }
            else if let m = Durations.parse(text), AlertActions.muteRange.contains(m) { action(k, relax: false, min: m) }
            else if let u = mutedNote[k.group] { postMuted(k, until: u) } else { repost(k.group) }
        default: break
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler done: @escaping () -> Void) {
        let id = response.actionIdentifier
        let k = (response.notification.request.content.userInfo["k"] as? String).flatMap(AlertKind.init(rawValue:))
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                guard let k else { return }
                self.respond(id, k, typed: typed)
            }
        }
        done()
    }

    /// `silent`: an alert shown again after a restart (it already rang; see `resume`).
    func post(_ ev: AlertEvent, mirrored: Bool, test: Bool = false, silent: Bool = false) {
        guard !offline else { return log("alerts: offline run, not alerting \(ev.k.rawValue)") }
        var (title, body) = text(ev, mirrored: mirrored)
        if test { title = "Test · " + title }
        let sound = silent || testing ? .none : Self.sound(for: ev.k, settings, last: lastVariant[ev.k])
        if let v = sound.variant { lastVariant[ev.k] = v }
        log("ALERT \(title) — \(body) [\(ev.k.group.rawValue), sound \(sound.label)]")
        guard poster != nil else {           // `swift run`: no bundle, no notification center
            play(sound)
            return
        }
        if !test { shownEvents[ev.k.group] = (ev, mirrored); live.insert(ev.k.group) }
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
        live.remove(g)
        send(g, nil)
    }

    /// What each group should show, sent once at the end of this run-loop turn: only the last word per group reaches
    /// Notification Center. A kind moving within its group (high → very high) is a clear + a firing in one go, and
    /// usernoted cancels both adds when two requests with the same identifier arrive milliseconds apart, or lets a
    /// remove that arrives after the add take the new notification away (both seen in its log). The self-test's
    /// recorder gets each one at once.
    private var outbox: [AlertKind.Group: UNNotificationRequest?] = [:]
    private func send(_ g: AlertKind.Group, _ req: UNNotificationRequest?) {
        guard let poster else { return }
        if testing { if let req { poster.add(req) } else { poster.remove(g.rawValue) }; return }
        let first = outbox.isEmpty
        outbox[g] = .some(req)
        guard first else { return }
        DispatchQueue.main.async { [self] in
            for (g, req) in outbox {
                if let req { poster.add(req) } else { poster.remove(g.rawValue) }
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
