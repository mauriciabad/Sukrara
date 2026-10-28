// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import CryptoKit
import Foundation
import SukraraCore

/// Juggluco's web server (Juggluco › Settings › Web server, "Local only" off) as this Mac reads it on the home network.
/// Pure: requests to build and answers to parse; `JugglucoLanSource` does the I/O.
///
/// - `GET /api/v1/status.json` → `"name": "juggluco"` identifies the server (Juggluco has no Bonjour).
/// - `GET /api/v1/entries.json?count=N&interval=0` → the history, every minute (Nightscout entries; `delta` is the
///   sensor's rate × 5, `direction` "" = no rate).
/// - `GET /x/live` → Server-Sent Events, one `glucose` event per new reading (`timestamp` s, `glucoseMgDl`,
///   `rateMgDlPerMinute`).
/// - The API secret travels as `api-secret: sha1hex(secret)`.
public enum JugglucoApi {
    /// Juggluco's web server port.
    public static let port = 17580
    /// The history asked at the first connection (a day of minutes) and after a reconnect.
    public static let firstCount = 1600
    public static let againCount = 180

    public static func sha1hex(_ secret: String) -> String {
        Insecure.SHA1.hash(data: Data(secret.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The headers of every request: the hashed secret when one is set.
    public static func headers(secret: String) -> [String: String] {
        secret.isEmpty ? [:] : ["api-secret": sha1hex(secret)]
    }

    /// `http://<host>:<port>` for an address in a private range; nil for anything else (a public address, a name).
    public static func base(_ address: LanAddress, port: Int = port) -> URL? {
        guard address.isPrivate else { return nil }
        return URL(string: "http://\(address):\(port)")
    }

    public static func statusURL(_ base: URL) -> URL { base.appending(path: "api/v1/status.json") }

    public static func entriesURL(_ base: URL, count: Int) -> URL {
        base.appending(path: "api/v1/entries.json").appending(queryItems: [
            URLQueryItem(name: "count", value: String(count)), URLQueryItem(name: "interval", value: "0"),
        ])
    }

    public static func liveURL(_ base: URL) -> URL { base.appending(path: "x/live") }

    /// Whether a status answer is Juggluco's.
    public static func isJuggluco(status data: Data) -> Bool {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return (obj["name"] as? String)?.lowercased() == "juggluco"
    }

    /// Nightscout entries → readings, oldest first. Throws on an answer that isn't a list of entries, or that has
    /// entries written by Sukrara (that server isn't Juggluco: never read our own values back as new).
    public static func entries(_ data: Data, now: Date) throws -> [Reading] {
        guard let list = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            throw JugglucoError.badAnswer
        }
        if list.contains(where: { ($0["device"] as? String) == "Sukrara" }) { throw JugglucoError.notJuggluco }
        return list.compactMap { e -> Reading? in
            guard let ms = (e["date"] as? NSNumber)?.doubleValue, let sgv = (e["sgv"] as? NSNumber)?.doubleValue
            else { return nil }
            let direction = (e["direction"] as? String) ?? ""
            let rate = direction.isEmpty ? nil : (e["delta"] as? NSNumber).map { $0.doubleValue / 5 }
            return reading(t: ms / 1000, mgdl: sgv, rate: rate, now: now)
        }
        .sorted { $0.t < $1.t }
    }

    /// One SSE `glucose` event's data line (without "data:") → a reading, or nil.
    public static func live(_ data: Data, now: Date) -> Reading? {
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let t = (obj["timestamp"] as? NSNumber)?.doubleValue,
            let mgdl = (obj["glucoseMgDl"] as? NSNumber)?.doubleValue
        else { return nil }
        return reading(t: t, mgdl: mgdl, rate: (obj["rateMgDlPerMinute"] as? NSNumber)?.doubleValue, now: now)
    }

    /// A value as stored: none for a number that isn't a measurement (0, negative, NaN, absurd) or a time in the
    /// future; LO / HI as the flags (`GlucoseRange.normalised`).
    static func reading(t: Double, mgdl: Double, rate: Double?, now: Date) -> Reading? {
        guard t.isFinite, mgdl.isFinite, mgdl >= 1, mgdl <= 1000, t <= now.timeIntervalSince1970 + 60 else {
            return nil
        }
        let r = rate.flatMap { $0.isFinite ? $0 : nil }
        return GlucoseRange.normalised(
            Reading(t: Date(timeIntervalSince1970: t.rounded()), mgdl: Int(mgdl.rounded()), rate: r))
    }
}

/// Server-Sent Events, line by line: the `data:` of each `glucose` event (Juggluco sends one data line per event).
public struct SseParser: Sendable {
    private var event = "message"

    public init() {}

    /// The event data when `line` completes a `glucose` event, else nil.
    public mutating func feed(_ line: String) -> Data? {
        if line.isEmpty {
            event = "message"
            return nil
        }
        if line.hasPrefix("event:") {
            event = line.dropFirst(6).trimmingCharacters(in: .whitespaces)
            return nil
        }
        guard line.hasPrefix("data:") else { return nil }
        defer { event = "message" }
        return event == "glucose" ? Data(line.dropFirst(5).trimmingCharacters(in: .whitespaces).utf8) : nil
    }
}

public enum JugglucoError: Error, Equatable, Sendable {
    case badAnswer
    case notJuggluco
    /// 401 / 403: the API secret is missing or wrong.
    case unauthorized
    case status(Int)
}

/// An IPv4 address.
public struct LanAddress: Sendable, Hashable, CustomStringConvertible {
    public let value: UInt32

    public init(_ value: UInt32) { self.value = value }

    /// "192.168.1.23" → the address; nil for anything else (names, IPv6, ports).
    public init?(_ text: String) {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return nil }
        var v: UInt32 = 0
        for p in parts {
            guard p.count <= 3, p.allSatisfy(\.isASCII), let n = UInt32(p), n <= 255 else { return nil }
            v = v << 8 | n
        }
        value = v
    }

    public var description: String {
        "\(value >> 24).\((value >> 16) & 255).\((value >> 8) & 255).\(value & 255)"
    }

    /// The home-network ranges (RFC 1918): 10/8, 172.16/12, 192.168/16. Only these are ever contacted in plain http.
    public var isPrivate: Bool {
        value >> 24 == 10 || value >> 20 == 0xAC1 || value >> 16 == 0xC0A8
    }
}

/// One of the Mac's IPv4 interfaces.
public struct LanInterface: Sendable, Equatable {
    public var address: LanAddress
    public var netmask: LanAddress
    public var up: Bool
    public var loopback: Bool

    public init(address: LanAddress, netmask: LanAddress, up: Bool = true, loopback: Bool = false) {
        self.address = address
        self.netmask = netmask
        self.up = up
        self.loopback = loopback
    }

    /// The other hosts of the networks of `interfaces` worth probing: up, not loopback, private ranges only (no
    /// link-local 169.254, nothing public), at most /22 (1022 hosts) each, never the Mac itself.
    public static func hosts(_ interfaces: [LanInterface]) -> [LanAddress] {
        var seen = Set<LanAddress>()
        var out: [LanAddress] = []
        for i in interfaces where i.up && !i.loopback && i.address.isPrivate {
            let size = ~i.netmask.value
            guard size >= 3, size <= 1023 else { continue }
            let net = i.address.value & i.netmask.value
            for h in 1..<size where net | h != i.address.value {
                let a = LanAddress(net | h)
                if seen.insert(a).inserted { out.append(a) }
            }
        }
        return out
    }

    /// The Mac's IPv4 interfaces now (`getifaddrs`).
    public static func current() -> [LanInterface] {
        var out: [LanInterface] = []
        var ifap: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifap) == 0, let first = ifap else { return [] }
        defer { freeifaddrs(ifap) }
        for ptr in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let ifa = ptr.pointee
            guard let addr = ifa.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET), let mask = ifa.ifa_netmask
            else { continue }
            func v4(_ p: UnsafeMutablePointer<sockaddr>) -> UInt32 {
                p.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { UInt32(bigEndian: $0.pointee.sin_addr.s_addr) }
            }
            out.append(
                LanInterface(
                    address: LanAddress(v4(addr)), netmask: LanAddress(v4(mask)),
                    up: ifa.ifa_flags & UInt32(IFF_UP) != 0, loopback: ifa.ifa_flags & UInt32(IFF_LOOPBACK) != 0))
        }
        return out
    }
}
