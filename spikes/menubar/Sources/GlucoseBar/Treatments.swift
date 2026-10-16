// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Insulin / carbs / note, as in spikes/libreview/SPEC.md §1. The same JSON is stored on disk and sent over BLE.
struct Treatment: Codable, Equatable {
    var id: String          // UUID v4, lowercase
    var k: String           // rapid | long | carbs | note
    var a: Double           // units (1 decimal) or grams (whole); 0 for notes
    var t: Int64            // when, epoch s
    var n: String = ""      // free text, ≤ 100 UTF-8 bytes
    var u: Int64            // last modified, epoch ms (last-writer-wins version)
    var del: Bool = false
    var o: String           // phone | mac
    var lv: Int64 = 0       // the `u` last confirmed uploaded to LibreView

    static let kinds = ["rapid", "long", "carbs", "note"]

    init(id: String = UUID().uuidString.lowercased(), k: String, a: Double, t: Int64, n: String = "",
         u: Int64 = Treatment.nowMs(), del: Bool = false, o: String = "mac", lv: Int64 = 0) {
        self.id = id; self.k = k; self.a = a; self.t = t; self.n = Self.truncate(n); self.u = u
        self.del = del; self.o = o; self.lv = lv
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
        lv = try c.decodeIfPresent(Int64.self, forKey: .lv) ?? 0
    }

    var needsUpload: Bool { lv < u }

    /// SPEC §1 merge: newer (u, o) wins, `lv` is the max of both sides.
    static func merge(_ cur: Treatment?, _ inc: Treatment) -> Treatment {
        guard let cur else { return inc }
        var winner = (inc.u, inc.o) > (cur.u, cur.o) ? inc : cur
        winner.lv = max(cur.lv, inc.lv)
        return winner
    }

    static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    static func truncate(_ s: String, bytes: Int = 100) -> String {
        var out = ""
        for ch in s where out.utf8.count + String(ch).utf8.count <= bytes { out.append(ch) }
        return out
    }

    /// The BLE / JSON dictionary form (keys exactly as in the spec).
    var dict: [String: Any] {
        ["id": id, "k": k, "a": a, "t": t, "n": n, "u": u, "del": del, "o": o, "lv": lv]
    }

    init?(dict: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: dict),
              let t = try? JSONDecoder().decode(Treatment.self, from: data) else { return nil }
        self = t
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

/// The Mac's treatment log, persisted to ~/Library/Application Support/Sukrara/treatments.json — one store for both
/// paths (legacy BLE sync, SPEC §2, and protocol v2, SPEC §5), merged by id with §1 last-writer-wins; the menu and
/// the chart read it.
/// Besides the rows it remembers what still has to be pushed to the phone (`unacked`), the pull cursor
/// (phone's `seq`), the LibreView DeviceId the phone shares, when foreign rows were first seen
/// (for the 2-minute upload grace period), the v2 status of entries written here, and which rows were only ever
/// learned over v2 (their `lv` isn't on the wire, so this Mac must not upload them: SPEC §5).
@MainActor final class TreatmentStore {
    private struct State: Codable {
        var rows: [Treatment] = []
        var unacked: [String] = []
        var cursor: Int64 = 0
        var dev: String?
        var firstSeen: [String: Double] = [:]
        var v2: [String: V2Send]? = nil
        var v2Only: [String]? = nil
        var legacyLinked: Bool? = nil
    }

    private var state = State()
    private let url: URL
    /// Called after every change; `local` is true for changes made on this Mac (they need a BLE push).
    var onChange: [(_ local: Bool) -> Void] = []

    init(directory: URL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sukrara")) {
        url = directory.appendingPathComponent("treatments.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        if let data = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(State.self, from: data) { state = s }
    }

    var rows: [Treatment] { state.rows }
    var cursor: Int64 { get { state.cursor } set { state.cursor = newValue; save() } }
    var deviceId: String? { state.dev }
    var unacked: [Treatment] { state.unacked.compactMap { id in state.rows.first { $0.id == id } } }
    func isUnacked(_ id: String) -> Bool { state.unacked.contains(id) }
    func firstSeen(_ id: String) -> Date? { state.firstSeen[id].map { Date(timeIntervalSince1970: $0) } }

    func row(_ id: String) -> Treatment? { state.rows.first { $0.id == id } }
    func v2Status(_ id: String) -> V2Send? { state.v2?[id] }
    func isV2Only(_ id: String) -> Bool { state.v2Only?.contains(id) ?? false }
    /// The phone has answered the legacy treatment sync at least once (so the legacy path exists for this Mac).
    var legacyLinked: Bool { state.legacyLinked ?? false }
    func markLegacyLinked() {
        guard !legacyLinked else { return }
        state.legacyLinked = true
        save()
    }

    /// v2 progress of an entry written here; never moves backwards for the same version.
    func setV2(_ id: String, u: Int64, _ st: V2Send.State, via: String = "") {
        let order: [V2Send.State] = [.sending, .sent, .accepted]
        if let cur = state.v2?[id], cur.u == u, order.firstIndex(of: cur.state)! >= order.firstIndex(of: st)! { return }
        if let cur = state.v2?[id], cur.u > u { return }
        var m = state.v2 ?? [:]
        m[id] = V2Send(u: u, state: st, at: Date().timeIntervalSince1970, via: via)
        state.v2 = m
        save()
        onChange.forEach { $0(false) }
    }

    /// A change made here (new, edited, deleted, or `lv` after an upload).
    func upsertLocal(_ t: Treatment) {
        put(t)
        if !state.unacked.contains(t.id) { state.unacked.append(t.id) }
        save()
        onChange.forEach { $0(true) }
    }

    func delete(_ id: String) {
        guard var t = row(id), !t.del else { return }
        t.del = true
        t.u = max(Treatment.nowMs(), t.u + 1)     // must beat the row's version even if our clock is behind
        upsertLocal(t)
    }

    /// The phone confirmed a write of `t`; only clear it if nothing changed meanwhile.
    func acked(_ t: Treatment) {
        guard row(t.id) == t, let i = state.unacked.firstIndex(of: t.id) else { return }
        state.unacked.remove(at: i)
        save()
    }

    /// A record from the phone (legacy link) or a v2 event. Returns true if it changed anything.
    @discardableResult
    func receive(_ inc: Treatment, v2: Bool = false) -> Bool {
        let cur = row(inc.id)
        if v2, cur == nil { state.v2Only = (state.v2Only ?? []) + [inc.id] }
        if !v2, let i = state.v2Only?.firstIndex(of: inc.id) { state.v2Only?.remove(at: i); save() }
        let merged = Treatment.merge(cur, inc)
        guard merged != cur else { return false }
        if merged.o != "mac", merged.needsUpload, cur?.u != merged.u {
            state.firstSeen[merged.id] = Date().timeIntervalSince1970
        }
        put(merged)
        save()
        onChange.forEach { $0(false) }
        return true
    }

    /// A v2 treatment event that passed FollowerV2's checks: §1 merge into the one log, and if the wearer's device key
    /// echoed an entry this Mac sent with a version ≥ ours, that entry is accepted. Returns true when it just was.
    @discardableResult
    func receiveV2(_ t: Treatment, fromWearer: Bool, via: String) -> Bool {
        receive(t, v2: true)
        guard fromWearer, let s = v2Status(t.id), t.u >= s.u, s.state != .accepted else { return false }
        setV2(t.id, u: s.u, .accepted, via: via)
        return true
    }

    func setDeviceId(_ dev: String) {
        guard !dev.isEmpty, dev != state.dev else { return }
        state.dev = dev
        save()
    }

    /// After a successful LibreView upload of `sent` (rows as they were in the payload).
    func markUploaded(_ sent: [Treatment]) {
        for s in sent {
            guard var t = row(s.id), t.lv < s.u else { continue }
            t.lv = s.u
            put(t)
            if !state.unacked.contains(t.id) { state.unacked.append(t.id) }
        }
        save()
        onChange.forEach { $0(true) }
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

/// Client side of SPEC §2 over the TREATMENTS characteristic. BLEBridgeSource owns the radio and calls in here;
/// this class owns the protocol: push un-acked local changes, then pull from the cursor.
@MainActor final class TreatmentSync {
    let store: TreatmentStore
    var log: (String) -> Void = { _ in }

    /// Set while the characteristic is subscribed: writes one message (with response).
    private var write: ((Data) -> Bool)?
    private var maxWrite = 512
    private var queue: [(data: Data, rec: Treatment?)] = []
    private var inFlight: (data: Data, rec: Treatment?)?

    init(store: TreatmentStore) {
        self.store = store
        store.onChange.append { [weak self] local in if local { self?.pushUnacked() } }
    }

    func connected(maxWrite: Int, write: @escaping (Data) -> Bool) {
        self.maxWrite = maxWrite
        self.write = write
        queue.removeAll()
        inFlight = nil
        pushUnacked()
        send(["op": "pull", "since": store.cursor], rec: nil)
        log("treatments: sync started (cursor \(store.cursor), \(store.unacked.count) to push)")
    }

    func disconnected() {
        write = nil
        queue.removeAll()
        inFlight = nil
    }

    func writeCompleted(error: Error?) {
        guard let done = inFlight else { return }
        inFlight = nil
        if let error {
            log("treatments: write failed: \(error.localizedDescription)")
            queue.removeAll()           // retried on the next connect
            return
        }
        if let rec = done.rec { store.acked(rec) }
        pump()
    }

    func received(_ data: Data) {
        guard let msg = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let op = msg["op"] as? String else { return log("treatments: bad message (\(data.count) B)") }
        let seq = (msg["s"] as? NSNumber)?.int64Value
        switch op {
        case "rec":
            guard let t = Treatment(dict: msg) else { return log("treatments: bad rec") }
            store.receive(t)
            if let seq, seq > store.cursor { store.cursor = seq }
        case "end":
            store.markLegacyLinked()
            if let dev = msg["dev"] as? String { store.setDeviceId(dev) }
            guard let seq else { return }
            if seq < store.cursor {
                log("treatments: phone store was reset (s \(seq) < cursor \(store.cursor)), pulling everything")
                store.cursor = 0
                send(["op": "pull", "since": 0], rec: nil)
            } else {
                store.cursor = seq
            }
        default:
            log("treatments: unknown op \(op)")
        }
    }

    private func pushUnacked() {
        guard write != nil else { return }
        let queued = Set(queue.compactMap { $0.rec?.id } + [inFlight?.rec?.id].compactMap { $0 })
        for t in store.unacked where !queued.contains(t.id) {
            var m = t.dict
            m["op"] = "rec"
            send(m, rec: t)
        }
    }

    private func send(_ msg: [String: Any], rec: Treatment?) {
        guard let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        guard data.count <= maxWrite else { return log("treatments: message too long (\(data.count) > \(maxWrite) B), skipped") }
        queue.append((data, rec))
        pump()
    }

    private func pump() {
        guard inFlight == nil, let write, !queue.isEmpty else { return }
        let next = queue.removeFirst()
        if write(next.data) { inFlight = next } else { queue.removeAll() }
    }
}

/// Which way an entry made on this Mac travels (NEXT §1 item 1), for the menu and the Log dialog:
///   - the **legacy Bluetooth link** (SPEC §2) — the phone takes every Mac entry, no permission involved;
///   - **protocol v2** (SPEC §5) — only if the wearer made this Mac a writer (its key in the roster `writers`).
struct TreatmentPaths {
    var legacy: String?              // nil = this Mac has no legacy link to a phone
    var v2: String?                  // nil = not following anyone over v2
    var v2Allowed = false
    /// "Log treatment…" is offered unless the only way out is v2 and the wearer hasn't allowed it.
    var canLog: Bool { legacy != nil || v2Allowed || v2 == nil }
    var lines: [String] { [legacy, v2].compactMap { $0 } }

    @MainActor static func current(legacy: BLEBridgeSource?, store: TreatmentStore, node: FollowerV2?) -> TreatmentPaths {
        var p = TreatmentPaths()
        if let legacy {
            if let phone = legacy.connectedName { p.legacy = "→ \(phone) over the Bluetooth link (always allowed) · connected" }
            else if store.legacyLinked { p.legacy = "→ phone over the Bluetooth link (always allowed) · when in range" }
        }
        if let n = node, case .active = n.phase {
            p.v2Allowed = n.canWrite
            p.v2 = n.canWrite ? "→ wearer over Sukrara sharing (v2) · allowed"
                              : "✕ Sukrara sharing (v2): the wearer hasn't allowed this Mac to add entries"
        }
        return p
    }

    /// Compact per-entry status for the menu row: "BT ✓ · v2 accepted ✓ · LV ⇡".
    @MainActor static func status(of t: Treatment, store: TreatmentStore, legacyOn: Bool, lvOn: Bool,
                                  now: Date = Date()) -> String {
        var parts: [String] = []
        let v2Only = store.isV2Only(t.id) && t.o != "mac"
        if t.o == "mac" {
            if legacyOn, store.legacyLinked { parts.append(store.isUnacked(t.id) ? "BT ⇡" : "BT ✓") }
            if let s = store.v2Status(t.id) { parts.append(v2Text(s, now: now)) }
        } else if v2Only {
            parts.append("from v2")
        }
        if lvOn, !v2Only { parts.append(t.needsUpload ? "LV ⇡" : "LV ✓") }
        return parts.joined(separator: " · ")
    }

    static func v2Text(_ s: V2Send, now: Date = Date()) -> String {
        switch s.state {
        case .sending: return "v2 sending…"
        case .sent: return now.timeIntervalSince1970 - s.at > 120 ? "v2 not accepted yet" : "v2 sent"
        case .accepted: return "v2 accepted ✓"
        }
    }

    /// The same in words, for the row's submenu.
    @MainActor static func details(of t: Treatment, store: TreatmentStore, legacyOn: Bool, lvOn: Bool) -> [String] {
        var out: [String] = []
        let v2Only = store.isV2Only(t.id) && t.o != "mac"
        out.append(t.o == "mac" ? "Made on this Mac" : v2Only ? "From the wearer (Sukrara sharing, v2)" : "From the phone (Bluetooth link)")
        if t.o == "mac" {
            if legacyOn, store.legacyLinked {
                out.append(store.isUnacked(t.id) ? "Bluetooth link: waiting for the phone" : "Bluetooth link: the phone has it")
            }
            if let s = store.v2Status(t.id) {
                let at = Date(timeIntervalSince1970: s.at).formatted(date: .omitted, time: .shortened)
                switch s.state {
                case .sending: out.append("v2: sending (no relay or phone has taken it yet)")
                case .sent: out.append(v2Text(s) == "v2 sent" ? "v2: sent \(at), waiting for the wearer"
                                                              : "v2: sent \(at), not accepted by the wearer yet")
                case .accepted: out.append("v2: accepted by the wearer \(at)")
                }
            }
        }
        if lvOn, !v2Only { out.append(t.needsUpload ? "LibreView: waiting to upload" : "LibreView: uploaded") }
        return out
    }
}
