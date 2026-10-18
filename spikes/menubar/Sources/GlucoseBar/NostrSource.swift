// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CryptoKit
import Foundation
import NostrSDK

/// Follows an end-to-end-encrypted CGM stream on public Nostr relays — works from anywhere with internet,
/// no shared network with the phone needed. Protocol: spikes/nostr/PROTOCOL.md (PoC 3).
/// Link: `cgmnostr:1?s=<npub of source S>&k=<nsec of read key G>&r=<relay>,<relay>,…`
///   - per relay: REQ the missing hourly chunks (kind 30078, `#d` = hashed chunk ids, ≤ 12 h) + live (kind 21078)
///   - the same event arrives from every relay → deduped by event id *before* any crypto
///   - signature check + NIP-44 v2 decryption (`nip44_decrypt(G.sec, S.pub, content)`) via rust-nostr's NostrSDK
/// Battery: nothing polls. A 60 s tick (10 s tolerance) only pings relays that have been silent > 150 s — live
/// events normally arrive once a minute on every socket — and re-fetches the current hour if live readings stopped.
@MainActor final class NostrSource {
    static let liveKind: UInt16 = 21078, chunkKind: UInt16 = 30078
    static let backfillHours = 12

    var onReading: ((Reading, String) -> Void)?
    var onHistory: (([Reading], String) -> Void)?

    @MainActor private final class Relay {
        let url: URL
        var host: String { url.host() ?? url.absoluteString }
        var ws: URLSessionWebSocketTask?
        var task: Task<Void, Never>?
        var lastRx = Date()
        var frames = 0, bytes = 0        // since the last stats line
        init(url: URL) { self.url = url }
    }

    private let source: PublicKey
    private let sourceHex: String
    private let read: Keys
    private let readHex: String
    private let relays: [Relay]
    private let session = URLSession(configuration: .ephemeral)
    private var seen: [String: Date] = [:]           // verified event id → first seen
    private var newest: Date?                        // newest reading received over Nostr
    private var lastRepair = Date()
    private var tick: Task<Void, Never>?
    private var decrypts = 0

    init?(link: String) {
        let q = URLComponents(string: link.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "cgmnostr:1?", with: "cgmnostr://x/?"))?.queryItems ?? []
        func value(_ name: String) -> String? { q.first { $0.name == name }?.value }
        guard let s = value("s").flatMap({ try? PublicKey.parse(publicKey: $0) }),
              let k = value("k").flatMap({ try? Keys.parse(secretKey: $0) }),
              let r = value("r") else {
            log("nostr: invalid follower link")
            return nil
        }
        source = s
        sourceHex = s.toHex()
        read = k
        readHex = k.publicKey().toHex()
        relays = r.split(separator: ",").compactMap { URL(string: String($0)) }.map(Relay.init)
    }

    func restart(_ why: String) {
        log("nostr restart: \(why) (\(relays.count) relays)")
        for r in relays {
            r.task?.cancel()
            r.ws?.cancel(with: .goingAway, reason: nil)
            r.task = Task { await run(r) }
        }
        if tick == nil { tick = Task { await watchdog() } }
    }

    // MARK: connection

    private func run(_ r: Relay) async {
        var backoff = 2.0
        while !Task.isCancelled {
            let ws = session.webSocketTask(with: r.url)
            r.ws = ws
            r.lastRx = Date()
            ws.resume()                                   // sends below are queued until the socket is open
            send(r, ["REQ", "h", chunkFilter()])
            send(r, ["REQ", "l", ["authors": [sourceHex], "kinds": [Self.liveKind],
                                  "since": Int(Date().timeIntervalSince1970) - 120] as [String: Any]])
            do {
                while true {
                    handle(try await ws.receive(), from: r)
                    backoff = 2
                }
            } catch {
                if Task.isCancelled { return }
                log("nostr \(r.host): \(error.localizedDescription) — retry in \(Int(backoff)) s")
            }
            ws.cancel()
            r.ws = nil
            try? await Task.sleep(for: .seconds(backoff))
            backoff = min(backoff * 2, 300)
        }
    }

    private func send(_ r: Relay, _ msg: [Any]) {
        guard let ws = r.ws, let data = try? JSONSerialization.data(withJSONObject: msg) else { return }
        ws.send(.string(String(decoding: data, as: UTF8.self))) { _ in }
    }

    /// Chunks from the hour of the newest reading we have up to now (all 12 h on first connect).
    private func chunkFilter() -> [String: Any] {
        let now = Int(Date().timeIntervalSince1970), hour = now - now % 3600
        let missing = newest.map { (hour - Int($0.timeIntervalSince1970)) / 3600 + 1 } ?? Self.backfillHours
        let ids = (0..<max(1, min(missing, Self.backfillHours))).map { chunkID(hour - $0 * 3600) }
        return ["authors": [sourceHex], "kinds": [Self.chunkKind], "#d": ids]
    }

    /// First 32 hex chars of sha256("cgm1:<G.pub hex>:<hour start>") — outsiders can't link chunks to hours.
    private func chunkID(_ hourStart: Int) -> String {
        SHA256.hash(data: Data("cgm1:\(readHex):\(hourStart)".utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// Every 60 s: ping silent relays (drop them if still silent after a further 2 min), repair live gaps,
    /// and log traffic every 10 min.
    private func watchdog() async {
        var n = 0
        while !Task.isCancelled {
            try? await Task.sleep(for: .seconds(60), tolerance: .seconds(10))
            n += 1
            let now = Date()
            for r in relays {
                guard let ws = r.ws else { continue }
                let silent = now.timeIntervalSince(r.lastRx)
                if silent > 270 {
                    log("nostr \(r.host): silent \(Int(silent)) s, reconnecting")
                    ws.cancel(with: .goingAway, reason: nil)
                } else if silent > 150 {
                    ws.sendPing { error in
                        if error == nil { Task { @MainActor in r.lastRx = Date() } }
                    }
                }
            }
            if now.timeIntervalSince(newest ?? .distantPast) > 150, now.timeIntervalSince(lastRepair) > 300 {
                lastRepair = now
                for r in relays where r.ws != nil { send(r, ["REQ", "h", chunkFilter()]) }
            }
            seen = seen.filter { now.timeIntervalSince($0.value) < 2 * 3600 }
            if n % 10 == 0 {
                let total = relays.reduce(0) { $0 + $1.bytes }
                log("nostr stats (10 min): \(total / 1024) KB rx, \(decrypts) decrypts, "
                    + "\(relays.filter { $0.ws != nil }.count)/\(relays.count) up — "
                    + relays.map { "\($0.host) \($0.frames)f/\($0.bytes / 1024)K" }.joined(separator: ", "))
                relays.forEach { $0.frames = 0; $0.bytes = 0 }
                decrypts = 0
            }
        }
    }

    // MARK: messages

    private struct Live: Decodable { let t: Double; let sgv: Double; let r: Double? }
    private struct Chunk: Decodable { let h: Double; let r: [[Double?]] }   // [dt, sgv, rate×100]

    private func handle(_ msg: URLSessionWebSocketTask.Message, from r: Relay) {
        guard case .string(let text) = msg else { return }
        r.lastRx = Date()
        r.frames += 1
        r.bytes += text.utf8.count
        guard let arr = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any],
              let type = arr.first as? String else { return }
        switch type {
        case "EVENT": if arr.count > 2, let ev = arr[2] as? [String: Any] { event(ev, from: r) }
        case "EOSE": if arr.count > 1, arr[1] as? String == "h" { send(r, ["CLOSE", "h"]) }
        case "CLOSED", "NOTICE": log("nostr \(r.host): \(text.prefix(120))")
        default: break
        }
    }

    private func event(_ json: [String: Any], from r: Relay) {
        guard let id = json["id"] as? String, seen[id] == nil else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: json),
              let ev = try? Event.fromJson(json: String(decoding: data, as: UTF8.self)),
              ev.author().toHex() == sourceHex, ev.verify(),       // verify() checks id hash + Schnorr signature
              let plain = try? nip44Decrypt(secretKey: read.secretKey(), publicKey: source, payload: ev.content())
        else { return log("nostr \(r.host): dropped invalid event \(id.prefix(8))") }
        seen[id] = Date()      // only after verifying, so a forged copy can't shadow the real one
        decrypts += 1
        let label = "Internet", body = Data(plain.utf8)
        switch ev.kind().asU16() {
        case Self.liveKind:
            guard let l = try? JSONDecoder().decode(Live.self, from: body) else { return }
            let reading = Reading(date: Date(timeIntervalSince1970: l.t), mgdl: l.sgv, rate: l.r)
            newest = max(newest ?? reading.date, reading.date)
            onReading?(reading, label)
        case Self.chunkKind:
            guard let c = try? JSONDecoder().decode(Chunk.self, from: body) else { return }
            let pts = c.r.compactMap { row -> Reading? in
                guard row.count >= 2, let dt = row[0], let sgv = row[1] else { return nil }
                return Reading(date: Date(timeIntervalSince1970: c.h + dt), mgdl: sgv,
                               rate: row.count > 2 ? row[2].map { $0 / 100 } : nil)
            }
            guard let first = pts.first, let last = pts.last else { return }
            log("nostr \(r.host): chunk \(pts.count) readings \(first.date.formatted(date: .omitted, time: .shortened))"
                + "–\(last.date.formatted(date: .omitted, time: .shortened)) (\(Int(first.mgdl))…\(Int(last.mgdl)) mg/dL)")
            newest = max(newest ?? last.date, last.date)
            onHistory?(pts, label)
        default: break
        }
    }
}
