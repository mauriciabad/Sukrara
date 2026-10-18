// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Insulin / carbs / note, as in spikes/libreview/SPEC.md §1. The same JSON is stored on disk and sent over v2.
struct Treatment: Codable, Equatable {
    var id: String          // UUID v4, lowercase
    var k: String           // rapid | long | carbs | note
    var a: Double           // units (1 decimal) or grams (whole); 0 for notes
    var t: Int64            // when, epoch s
    var n: String = ""      // free text, ≤ 100 UTF-8 bytes
    var u: Int64            // last modified, epoch ms (last-writer-wins version)
    var del: Bool = false
    var o: String           // phone | mac | follower

    static let kinds = ["rapid", "long", "carbs", "note"]

    init(id: String = UUID().uuidString.lowercased(), k: String, a: Double, t: Int64, n: String = "",
         u: Int64 = Treatment.nowMs(), del: Bool = false, o: String = "mac") {
        self.id = id; self.k = k; self.a = a; self.t = t; self.n = Self.truncate(n); self.u = u
        self.del = del; self.o = o
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        k = try c.decode(String.self, forKey: .k)
        a = try c.decodeIfPresent(Double.self, forKey: .a) ?? 0
        t = try c.decode(Int64.self, forKey: .t)
        n = try c.decodeIfPresent(String.self, forKey: .n) ?? ""
        u = try c.decode(Int64.self, forKey: .u)
        del = try c.decodeIfPresent(Bool.self, forKey: .del) ?? false
        o = try c.decode(String.self, forKey: .o)
    }

    /// SPEC §1 merge: newer (u, o) wins.
    static func merge(_ cur: Treatment?, _ inc: Treatment) -> Treatment {
        guard let cur else { return inc }
        return (inc.u, inc.o) > (cur.u, cur.o) ? inc : cur
    }

    static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    static func truncate(_ s: String, bytes: Int = 100) -> String {
        var out = ""
        for ch in s where out.utf8.count + String(ch).utf8.count <= bytes { out.append(ch) }
        return out
    }

    /// The JSON dictionary form (keys exactly as in SPEC §1, without the phone's LibreView `lv`).
    var dict: [String: Any] {
        ["id": id, "k": k, "a": a, "t": t, "n": n, "u": u, "del": del, "o": o]
    }

    init?(dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let t = try? JSONDecoder().decode(Treatment.self, from: data) else { return nil }
        self = t
    }
}

/// Which key may change which entry (spec/ogs-nostr.md N§9.2, PROPOSALS P4), the same rule as Android `WriterScope`: a
/// writer creates entries and changes or deletes only the ones it created; the wearer changes anything. Receivers keep
/// the *creator* of each id (the key that signed the first version they accepted).
enum WriterScope {
    enum Verdict { case accept, ignore, reject, override }

    /// A version signed by `author` (`fromDevice`: a roster device key, the wearer's merged log), `creator` recorded
    /// for the id (nil = never seen), `storedO` the `o` of the version held. A writer: only a new id or its own; when
    /// the wearer's copy of a writer entry (`o` follower) came first, the writer's own copy adds nothing (`ignore`, not
    /// a rejection). The wearer: always merged; its own entry (`o` isn't `follower`) whose creator is a writer was
    /// forged before the real one arrived, so it replaces that version whatever its `u`.
    static func follower(author: String, fromDevice: Bool, incO: String, creator: String?, storedO: String?,
                         devices: [String]) -> Verdict {
        if !fromDevice {
            if creator == nil || creator == author { return .accept }
            if let c = creator, devices.contains(c), storedO == "follower" { return .ignore }
            return .reject
        }
        if incO != "follower", let c = creator, !devices.contains(c) { return .override }
        return .accept
    }

    static func creatorAfter(_ v: Verdict, author: String, creator: String?) -> String {
        v == .override ? author : creator ?? author
    }
}

/// How a protocol-v2 entry this Mac wrote is doing (NEXT §1 item 1): `sending` (built, no relay/phone took it yet)
/// → `sent` (a relay answered OK, or the wearer's phone acknowledged the BLE write) → `accepted` (the wearer's
/// device key republished the same id with u ≥ ours, i.e. it merged it). "Not accepted yet" is `sent` for a while.
struct V2Send: Codable, Equatable {
    enum State: String, Codable { case sending, sent, accepted }
    var u: Int64
    var state: State
    var at: Double                      // when `state` was reached (epoch s)
    var via: String = ""
}

/// The Mac's treatment log, persisted to ~/Library/Application Support/Sukrara/treatments.json: entries made here and
/// the wearer's log received over protocol v2 (SPEC §5), merged by id with §1 last-writer-wins; the menu, the chart
/// and the widgets read it. Also the v2 status of entries written here.
@MainActor final class TreatmentStore {
    private struct State: Codable {
        var rows: [Treatment] = []
        var v2: [String: V2Send]? = nil
        var creators: [String: String]? = nil       // id → key that created it (P4: writers change only their own)
    }

    private var state = State()
    private let url: URL
    /// Called after every change.
    var onChange: [() -> Void] = []

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sukrara")) {
        url = directory.appendingPathComponent("treatments.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(State.self, from: data) { state = s }
    }

    var rows: [Treatment] { state.rows }
    /// The key that signed the first accepted version of `id` (this Mac's follower key for entries made here).
    func creator(_ id: String) -> String? { state.creators?[id] }
    func row(_ id: String) -> Treatment? { state.rows.first { $0.id == id } }
    func v2Status(_ id: String) -> V2Send? { state.v2?[id] }

    /// v2 progress of an entry written here; never moves backwards for the same version.
    func setV2(_ id: String, u: Int64, _ st: V2Send.State, via: String = "") {
        let order: [V2Send.State] = [.sending, .sent, .accepted]
        if let cur = state.v2?[id], cur.u == u, order.firstIndex(of: cur.state)! >= order.firstIndex(of: st)! { return }
        if let cur = state.v2?[id], cur.u > u { return }
        var m = state.v2 ?? [:]
        m[id] = V2Send(u: u, state: st, at: Date().timeIntervalSince1970, via: via)
        state.v2 = m
        save()
        onChange.forEach { $0() }
    }

    /// A change made here (new, edited or deleted). It travels over v2 (signed as a roster writer `creator`, which
    /// becomes the creator of a new entry), or stays on this Mac when it follows no one.
    func upsertLocal(_ t: Treatment, creator: String? = nil) {
        if let creator, state.creators?[t.id] == nil { setCreator(t.id, creator) }
        put(t)
        save()
        onChange.forEach { $0() }
    }

    /// Tombstone for `id`; returns it (nil if there is nothing to delete).
    @discardableResult
    func delete(_ id: String) -> Treatment? {
        guard var t = row(id), !t.del else { return nil }
        t.del = true
        t.u = max(Treatment.nowMs(), t.u + 1)     // must beat the row's version even if our clock is behind
        upsertLocal(t)
        return t
    }

    /// A v2 treatment event. `force`: the wearer's own entry replaces a writer's forged version (P4). Returns true if
    /// it changed anything.
    @discardableResult
    func receive(_ inc: Treatment, force: Bool = false) -> Bool {
        let cur = row(inc.id)
        let merged = force ? inc : Treatment.merge(cur, inc)
        guard merged != cur else { return false }
        put(merged)
        save()
        onChange.forEach { $0() }
        return true
    }

    /// A v2 treatment event that passed FollowerV2's checks (including WriterScope, which set `creator`): §1 merge
    /// into the one log, and if the wearer's device key echoed an entry this Mac sent with a version ≥ ours, that entry
    /// is accepted. Returns true when it just was.
    @discardableResult
    func receiveV2(_ t: Treatment, fromWearer: Bool, via: String, creator: String? = nil, force: Bool = false) -> Bool {
        if let creator, state.creators?[t.id] != creator { setCreator(t.id, creator); save() }
        receive(t, force: force)
        guard fromWearer, let s = v2Status(t.id), t.u >= s.u, s.state != .accepted else { return false }
        setV2(t.id, u: s.u, .accepted, via: via)
        return true
    }

    private func setCreator(_ id: String, _ key: String) {
        var m = state.creators ?? [:]
        m[id] = key
        state.creators = m
    }

    private func put(_ t: Treatment) {
        if let i = state.rows.firstIndex(where: { $0.id == t.id }) { state.rows[i] = t } else { state.rows.append(t) }
    }

    private func save() {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        try? enc.encode(state).write(to: url, options: .atomic)
    }
}

/// Which way an entry made on this Mac travels, for the menu and the Log dialog. The wearer decides, on the phone:
///   - **protocol v2** (SPEC §5) when the wearer made this Mac a writer (its key in the roster `writers`, the
///     follower's "Can add entries" switch): signed, so the phone knows it's this Mac;
///   - otherwise no way at all: "Log treatment…" is disabled. When this Mac follows no one, entries stay on it.
struct TreatmentPaths {
    enum Route: String { case v2, local, none }
    var route: Route = .local
    var v2: String?                  // where entries go, in words (nil = not following anyone)
    var canLog: Bool { route != .none }
    var lines: [String] { [v2].compactMap { $0 } }
    static let notAllowed = "The wearer hasn't allowed this Mac to log treatments"

    @MainActor static func current(node: FollowerV2?) -> TreatmentPaths {
        var p = TreatmentPaths()
        guard let node else { return p }
        guard case .active = node.phase else { p.route = .none; return p }
        p.route = node.canWrite ? .v2 : .none
        p.v2 = node.canWrite ? "Goes to the wearer's phone" : "The wearer hasn't allowed this Mac to log treatments"
        return p
    }

    /// May this Mac change or delete `t`? Following as a writer: only the entries it created (P4: the wearer's phone
    /// refuses the rest). Following no one: every local entry.
    @MainActor static func mayChange(_ t: Treatment, store: TreatmentStore, node: FollowerV2?) -> Bool {
        guard let node else { return true }
        return store.creator(t.id) == node.followerPub
    }

    /// Technical per-entry state for the log and the self-tests: "v2 accepted ✓".
    @MainActor static func status(of t: Treatment, store: TreatmentStore, now: Date = Date()) -> String {
        guard t.o == "mac", let s = store.v2Status(t.id) else { return "" }
        return v2Text(s, now: now)
    }

    static func v2Text(_ s: V2Send, now: Date = Date()) -> String {
        switch s.state {
        case .sending: return "v2 sending…"
        case .sent: return now.timeIntervalSince1970 - s.at > 120 ? "v2 not accepted yet" : "v2 sent"
        case .accepted: return "v2 accepted ✓"
        }
    }

    /// Where an entry made on this Mac is, in the user's words (Glucose Report › Treatments); nil for entries made
    /// elsewhere or kept on this Mac.
    @MainActor static func delivery(of t: Treatment, store: TreatmentStore, now: Date = Date()) -> (Delivery, Date)? {
        guard t.o == "mac", let s = store.v2Status(t.id) else { return nil }
        let at = Date(timeIntervalSince1970: s.at)
        switch s.state {
        case .sending: return (.sending, at)
        case .sent: return (now.timeIntervalSince1970 - s.at > 120 ? .late : .sent, at)
        case .accepted: return (.delivered, at)
        }
    }

    /// Who made the entry and where it is, for the row's tooltip. `who`: the wearer's name.
    @MainActor static func details(of t: Treatment, store: TreatmentStore, who: String) -> [String] {
        var out = [t.o == "mac" ? "Logged on this Mac" : t.o == "follower" ? "Logged by someone who follows \(who)" : "Logged on \(who)'s phone"]
        if let (d, at) = delivery(of: t, store: store) { out.append(d.text(who: who, at: at)) }
        return out
    }
}

/// How an entry made on this Mac reached the wearer's phone, shown like Messages' "Delivered": a symbol in the row
/// and a sentence in its tooltip. Protocol states (v2 sending / sent / accepted) stay in the log.
enum Delivery: Equatable {
    case sending, sent, late, delivered
    var symbol: String {
        switch self {
        case .sending: "clock"
        case .sent: "paperplane"
        case .late: "exclamationmark.triangle"
        case .delivered: "checkmark.circle"
        }
    }
    func text(who: String, at: Date) -> String {
        let t = Clock.time(at)
        switch self {
        case .sending: return "Sending to \(who)'s phone…"
        case .sent: return "Sent \(t), waiting for \(who)'s phone"
        case .late: return "Not on \(who)'s phone yet (sent \(t))"
        case .delivered: return "On \(who)'s phone since \(t)"
        }
    }
}
