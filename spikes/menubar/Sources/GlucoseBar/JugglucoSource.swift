// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import CryptoKit

/// Reads Juggluco's built-in web server (Settings → Web server). No internet involved:
///   - USB:  `adb forward tcp:17580 tcp:17580` → http://127.0.0.1:17580 (works with "Local only" ticked)
///   - LAN:  untick "Local only" in Juggluco → http://<phone-ip>:17580 (same Wi-Fi or phone hotspot)
///
/// History: `/api/v1/entries.json?interval=0` gives every 1-minute Libre 3 value (default interval thins to ~5 min).
/// Live:    `/x/live` is a Server-Sent Events stream, one `glucose` event per new minute reading,
///          carrying the sensor's own rate of change (0.01 mg/dL/min resolution).
/// Note: Nightscout `delta` from Juggluco is rate×5 (not a difference), so rate = delta / 5.
final class JugglucoSource {
    let base: URL
    let secret: String?
    private let session: URLSession
    private let streamSession: URLSession

    init(base: URL, secret: String?) {
        self.base = base
        self.secret = secret
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 5
        session = URLSession(configuration: cfg)
        let scfg = URLSessionConfiguration.ephemeral
        scfg.timeoutIntervalForRequest = 60     // server heartbeat is every 20 s
        scfg.timeoutIntervalForResource = .infinity
        streamSession = URLSession(configuration: scfg)
    }

    var name: String { "Juggluco @ \(base.host() ?? "?"):\(base.port ?? 80)" }

    private func request(_ path: String, _ query: [String: String] = [:]) -> URLRequest {
        var comps = URLComponents(url: base.appending(path: path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { comps.queryItems = query.map { URLQueryItem(name: $0.key, value: $0.value) } }
        var req = URLRequest(url: comps.url!)
        if let secret, !secret.isEmpty {
            // Juggluco accepts `api-secret: sha1hex(secret)` (exact lowercase header name).
            req.setValue(Self.sha1hex(secret), forHTTPHeaderField: "api-secret")
        }
        return req
    }

    static func sha1hex(_ s: String) -> String {
        Insecure.SHA1.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func check(_ resp: URLResponse) throws {
        if let http = resp as? HTTPURLResponse, http.statusCode != 200 {
            throw NSError(domain: "Juggluco", code: http.statusCode,
                          userInfo: [NSLocalizedDescriptionKey: "HTTP \(http.statusCode)"])
        }
    }

    // MARK: history

    private struct Entry: Decodable {
        let date: Double          // epoch ms
        let sgv: Double           // always mg/dL
        let delta: Double?        // sensor rate × 5
        let direction: String?
    }

    /// Ascending per-minute readings.
    func history(count: Int = 180) async throws -> [Reading] {
        let (data, resp) = try await session.data(for: request("api/v1/entries.json",
                                                               ["count": String(count), "interval": "0"]))
        try Self.check(resp)
        return try JSONDecoder().decode([Entry].self, from: data).map {
            Reading(date: Date(timeIntervalSince1970: $0.date / 1000), mgdl: $0.sgv,
                    // direction "" means Juggluco had no rate (NaN) and wrote delta 0.
                    rate: ($0.direction ?? "").isEmpty ? nil : $0.delta.map { $0 / 5 },
                    direction: $0.direction)
        }.sorted { $0.date < $1.date }
    }

    // MARK: live (SSE)

    private struct LiveEvent: Decodable {
        let timestamp: Double     // epoch s
        let glucoseMgDl: Double
        let rateMgDlPerMinute: Double?
        let direction: String?
    }

    /// Yields each new reading as Juggluco receives it from the sensor. Ends/throws when the connection drops.
    func live() -> AsyncThrowingStream<Reading, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    let (bytes, resp) = try await streamSession.bytes(for: request("x/live"))
                    try Self.check(resp)
                    var event = "message"
                    for try await line in bytes.lines {   // .lines drops blank lines; Juggluco sends one data line per event
                        if line.hasPrefix("event:") {
                            event = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
                        } else if line.hasPrefix("data:"), event == "glucose" {
                            let json = Data(line.dropFirst(5).utf8)
                            if let e = try? JSONDecoder().decode(LiveEvent.self, from: json) {
                                continuation.yield(Reading(date: Date(timeIntervalSince1970: e.timestamp),
                                                           mgdl: e.glucoseMgDl, rate: e.rateMgDlPerMinute,
                                                           direction: e.direction))
                            }
                            event = "message"
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
