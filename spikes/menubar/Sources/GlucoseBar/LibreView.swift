// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import CryptoKit
import Security

/// LibreView upload payload, byte-for-byte as spikes/libreview/SPEC.md §3 (reference: lv.py, golden: fixture.json).
/// The Mac only uploads treatments; the glucose arrays stay empty.
enum LVPayload {
    static let culture = "en-ES"
    static let gateway = "FSLibreLink3.Android"
    static let caps = ["scheduledContinuousGlucose", "unscheduledContinuousGlucose", "currentGlucose", "insulin", "food"]
        + ["exercise", "ondemandalarm.low", "ondemandalarm.high", "ondemandalarm.projectedlow", "ondemandalarm.projectedhigh",
           "customnote", "sensorstart", "sensorEnd", "isfGlucoseAlarm", "alarmSetting", "bleDisconnect", "bleReconnect", "error"]
            .map { "generic-com.abbottdiabetescare.informatics." + $0 }
    static let types: [String: Int64] = ["rapid": 1, "long": 3, "carbs": 4, "note": 16]

    /// JSON string literal, escaped exactly like lv.py `jstr`.
    static func jstr(_ s: String) -> String {
        var out = "\""
        for c in s.unicodeScalars {
            switch c {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\t": out += "\\t"
            default:
                if c.value < 0x20 { out += String(format: "\\u%04x", c.value) } else { out.unicodeScalars.append(c) }
            }
        }
        return out + "\""
    }

    /// One LibreView record per *version* of a treatment: first 40 bits of SHA-256("id:version") << 8 | type,
    /// the same whichever device uploads it. LibreView ignores changes to an existing record number, so an edit
    /// is a delete of the uploaded version plus a new record (what Juggluco does).
    static func recordNumber(_ t: Treatment, version: Int64) -> Int64 {
        let h = Array(SHA256.hash(data: Data("\(t.id):\(version)".utf8)))
        let top = h[0..<5].reduce(Int64(0)) { $0 << 8 | Int64($1) }
        return top << 8 | (types[t.k] ?? 16)
    }

    /// Retract the uploaded version if it is stale (edited or deleted since), then add the current one.
    static func entries(_ t: Treatment, _ tz: TimeZone) -> [String] {
        var out: [String] = []
        if t.lv != 0 && (t.del || t.lv != t.u) { out.append(entry(t, recordNumber(t, version: t.lv), deleted: true, tz)) }
        if !t.del { out.append(entry(t, recordNumber(t, version: t.u), deleted: false, tz)) }
        return out
    }

    private struct Parts { var y, mo, d, h, mi, s: Int; var offset: Int }

    private static func parts(_ epoch: Int64, _ tz: TimeZone) -> Parts {
        let date = Date(timeIntervalSince1970: TimeInterval(epoch))
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        let c = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return Parts(y: c.year!, mo: c.month!, d: c.day!, h: c.hour!, mi: c.minute!, s: c.second!,
                     offset: tz.secondsFromGMT(for: date) / 60)
    }

    private static func offset(_ m: Int) -> String {
        String(format: "%@%02d:%02d", m >= 0 ? "+" : "-", abs(m) / 60, abs(m) % 60)
    }

    static func utcMin(_ e: Int64) -> String {
        let p = parts(e, TimeZone(identifier: "UTC")!)
        return String(format: "%04d-%02d-%02dT%02d:%02d:00Z", p.y, p.mo, p.d, p.h, p.mi)
    }

    static func localMin(_ e: Int64, _ tz: TimeZone) -> String {
        let p = parts(e, tz)
        return String(format: "%04d-%02d-%02dT%02d:%02d:00", p.y, p.mo, p.d, p.h, p.mi) + offset(p.offset)
    }

    static func localMs(_ e: Int64, _ tz: TimeZone) -> String {
        let p = parts(e, tz)
        return String(format: "%04d-%02d-%02dT%02d:%02d:%02d.000", p.y, p.mo, p.d, p.h, p.mi, p.s) + offset(p.offset)
    }

    /// Juggluco's rule: > 50 g is a meal named by local hour, anything else a snack.
    static func foodType(_ grams: Double, _ e: Int64, _ tz: TimeZone) -> String {
        guard grams > 50 else { return "Snack" }
        switch parts(e, tz).h {
        case 4..<11: return "Breakfast"
        case 11..<16: return "Lunch"
        case 16..<22: return "Dinner"
        default: return "Snack"
        }
    }

    private static func entry(_ t: Treatment, _ rn: Int64, deleted: Bool, _ tz: TimeZone) -> String {
        let del = deleted ? "\"action\":\"deleted\"," : ""
        let tail = "\"linkedGlucoseRecordNumber\":\"0\"},\"recordNumber\":\(rn),\"timestamp\":\"\(localMin(t.t, tz))\"}"
        let fts = "\"factoryTimestamp\":\"\(utcMin(t.t))\","
        switch t.k {
        case "rapid", "long":
            return "{\"insulinType\":\"\(t.k == "rapid" ? "RapidActing" : "LongActing")\",\"units\":\(String(format: "%.1f", t.a)),"
                + "\"extendedProperties\":{\(fts)\(del)\(tail)"
        case "carbs":
            return "{\"foodType\":\"\(foodType(t.a, t.t, tz))\",\"gramsCarbs\":\(Int(t.a.rounded(.toNearestOrEven))),"
                + "\"extendedProperties\":{\(fts)\(del)\(tail)"
        default:
            return "{\"type\":\"com.abbottdiabetescare.informatics.customnote\",\"extendedProperties\":{\(fts)\"text\":\(jstr(t.n)),\(del)\(tail)"
        }
    }

    static func build(_ treatments: [Treatment], deviceId: String, token: String, now: Int64, tz: TimeZone) -> String {
        let trs = treatments.sorted { ($0.t, $0.id) < ($1.t, $1.id) }
        func list(_ kinds: Set<String>) -> String { trs.filter { kinds.contains($0.k) }.flatMap { entries($0, tz) }.joined(separator: ",") }
        return "{\"DeviceData\":{\"deviceSettings\":{\"factoryConfig\":{\"UOM\":\"mg/dL\"},\"firmwareVersion\":\"3.3.0\","
            + "\"miscellaneous\":{\"selectedLanguage\":\"\(culture)\",\"valueGlucoseTargetRangeLowInMgPerDl\":70,"
            + "\"valueGlucoseTargetRangeHighInMgPerDl\":140,\"selectedTimeFormat\":\"24hr\",\"selectedCarbType\":\"grams of carbs\"},"
            + "\"timestamp\":\"\(localMs(now, tz))\"},\"header\":{\"device\":{\"hardwareDescriptor\":\"GlucoBridge-spike\",\"hardwareName\":\"GlucoBridge\","
            + "\"modelName\":\"com.freestylelibre3.app.de\",\"osType\":\"Android\",\"osVersion\":\"36\",\"uniqueIdentifier\":\"\(deviceId)\"}},"
            + "\"measurementLog\":{\"capabilities\":[\(caps.map(jstr).joined(separator: ","))],\"currentGlucoseEntries\":[],"
            + "\"foodEntries\":[\(list(["carbs"]))],\"genericEntries\":[\(list(["note"]))],\"insulinEntries\":[\(list(["rapid", "long"]))],"
            + "\"scheduledContinuousGlucoseEntries\":[],\"unscheduledContinuousGlucoseEntries\":[]}},"
            + "\"UserToken\":\(jstr(token)),\"Domain\":\"Libreview\",\"GatewayType\":\"\(gateway)\"}"
    }
}

enum LVError: LocalizedError {
    case noCredentials(String), takeoverNeeded, rejected(Int, String), http(Int)
    var errorDescription: String? {
        switch self {
        case .noCredentials(let why): why
        case .takeoverNeeded: "another device is LibreView's active one (defaults write dev.spike.GlucoseBar lvTakeover -bool YES to take over)"
        case .rejected(let status, let reason): "LibreView status \(status): \(reason)"
        case .http(let code): "HTTP \(code)"
        }
    }
}

/// Talks to LibreView as the FreeStyle Libre 3 Android app (SPEC §3). Token kept in memory only.
final class LibreViewClient {
    private static let assets = URL(string: "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json")!
    private var base: URL?
    private var apiKey = ""
    private var token: String?

    /// Abbott's CDN rejects some User-Agents; send what Android's HttpURLConnection sends, like the phone.
    private static let userAgent = "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 10 Pro Build/BP2A.250605.031)"

    private func json(_ req: URLRequest) async throws -> [String: Any] {
        var req = req
        req.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw LVError.http(code) }
        return (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    private func config() async throws -> URL {
        if let base { return base }
        let assets = try await json(URLRequest(url: Self.assets))
        guard let cfgURL = (assets["Configuration"] as? String).flatMap(URL.init(string:)) else { throw LVError.rejected(-1, "no Configuration URL") }
        let cfg = try await json(URLRequest(url: cfgURL))
        guard let url = (cfg["newYuUrl"] as? String).flatMap(URL.init(string:)) else { throw LVError.rejected(-1, "no newYuUrl") }
        apiKey = cfg["newYuApiKey"].map { "\($0)" } ?? ""
        base = url
        return url
    }

    private func request(_ path: String, body: Data, token: String = "") async throws -> URLRequest {
        var req = URLRequest(url: try await config().appendingPathComponent(path), timeoutInterval: 60)
        req.httpMethod = "POST"
        req.httpBody = body
        for (k, v) in ["Content-Type": "application/json", "Platform": "Android", "Version": "3.3.0",
                       "Abbott-ADC-App-Platform": "Android/16/FSL3/3.3.0.9092", "Accept-Language": LVPayload.culture,
                       "x-api-key": apiKey, "x-newyu-token": token] {
            req.setValue(v, forHTTPHeaderField: k)
        }
        return req
    }

    static func credentials() throws -> (email: String, password: String) {
        guard let email = UserDefaults.standard.string(forKey: "lvEmail"), !email.isEmpty else {
            throw LVError.noCredentials("set lvEmail (defaults write dev.spike.GlucoseBar lvEmail you@example.com)")
        }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.spike.libreview",
                                    kSecAttrAccount as String: email, kSecReturnData as String: true]
        var out: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &out) == errSecSuccess, let data = out as? Data,
              let pw = String(data: data, encoding: .utf8) else {
            throw LVError.noCredentials("no keychain item dev.spike.libreview for \(email)")
        }
        return (email, pw)
    }

    func authenticate(deviceId: String) async throws {
        let (email, pw) = try Self.credentials()
        let takeover = UserDefaults.standard.bool(forKey: "lvTakeover")
        let body = try JSONSerialization.data(withJSONObject: [
            "Culture": LVPayload.culture, "DeviceId": deviceId, "Password": pw, "SetDevice": takeover,
            "UserName": email, "Domain": "Libreview", "GatewayType": LVPayload.gateway] as [String: Any])
        let o = try await json(try await request("api/nisperson/getauthentication", body: body))
        let status = (o["status"] as? NSNumber)?.intValue ?? -1
        guard status == 0, let token = (o["result"] as? [String: Any])?["UserToken"] as? String else {
            let reason = "\(o["reason"] ?? "")"
            if status == 20, reason.contains("wrongDeviceForUser") { throw LVError.takeoverNeeded }
            throw LVError.rejected(status, reason)
        }
        self.token = token
    }

    /// POST /api/measurements with these treatments; re-authenticates once on `wrongDeviceInToken`.
    func upload(_ treatments: [Treatment], deviceId: String) async throws {
        for attempt in 0..<2 {
            if token == nil { try await authenticate(deviceId: deviceId) }
            let now = Int64(Date().timeIntervalSince1970)
            let body = LVPayload.build(treatments, deviceId: deviceId, token: token!, now: now, tz: .current)
            let o = try await json(try await request("api/measurements", body: Data(body.utf8), token: token!))
            let status = (o["status"] as? NSNumber)?.intValue ?? -1
            if status == 0 { return }
            let reason = "\(o["reason"] ?? "")"
            if attempt == 0, status == 20, reason.contains("wrongDeviceInToken") { token = nil; continue }
            throw LVError.rejected(status, reason)
        }
    }
}

/// Uploads rows that need it (SPEC §1 policy): Mac-created rows at once, phone rows only if still
/// pending 2 minutes after the Mac learned them. Off unless `defaults write dev.spike.GlucoseBar lvUpload -bool YES`.
@MainActor final class LibreViewUploader {
    private let store: TreatmentStore
    private let client = LibreViewClient()
    private let ledger: URL
    private var running = false
    private var halted: String?         // permanent error: stop until "Upload now"
    private var timer: Timer?
    private var debounce: Timer?
    private(set) var lastOK: Date?
    private(set) var lastError: String?
    var log: (String) -> Void = { _ in }
    var onStatus: () -> Void = {}

    var enabled: Bool { UserDefaults.standard.bool(forKey: "lvUpload") }
    static let grace: TimeInterval = 120

    init(store: TreatmentStore) {
        self.store = store
        ledger = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("GlucoseBar/lv-ledger.jsonl")
        store.onChange.append { [weak self] _ in self?.schedule(after: 2) }
        timer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.kick() }
        }
    }

    /// Rows this Mac should upload now.
    func due(now: Date = Date()) -> [Treatment] {
        store.rows.filter { t in
            guard t.needsUpload else { return false }
            if t.o == "mac" { return true }
            guard let seen = store.firstSeen(t.id) else { return true }
            return now.timeIntervalSince(seen) >= Self.grace
        }
    }

    var statusLine: String {
        guard enabled else { return "LibreView: upload off" }
        if let halted { return "LibreView: stopped — \(halted)" }
        let pending = store.rows.filter(\.needsUpload).count
        var s = "LibreView: "
        if let lastError { s += "error — \(lastError)" }
        else if let lastOK { s += "last upload \(lastOK.formatted(date: .omitted, time: .shortened)) ok" }
        else { s += "no upload yet" }
        if pending > 0 { s += " · \(pending) pending" }
        return s
    }

    /// Clears a halt and uploads now (menu "Upload to LibreView now").
    func retry() {
        halted = nil
        kick(force: true)
    }

    private func schedule(after s: TimeInterval) {
        debounce?.invalidate()
        debounce = Timer.scheduledTimer(withTimeInterval: s, repeats: false) { [weak self] _ in
            MainActor.assumeIsolated { self?.kick() }
        }
        // Phone rows become due once their grace period is over.
        if store.rows.contains(where: { $0.needsUpload && $0.o != "mac" }) {
            Timer.scheduledTimer(withTimeInterval: Self.grace + 5, repeats: false) { [weak self] _ in
                MainActor.assumeIsolated { self?.kick() }
            }
        }
    }

    func kick(force: Bool = false) {
        guard enabled, halted == nil, !running else { return }
        // Deletes always go up, even with lv == 0: the phone may have uploaded the row before its
        // lv update reached us, and retracting a row LibreView never saw is harmless.
        let rows = force ? store.rows.filter(\.needsUpload) : due()
        guard !rows.isEmpty else { return onStatus() }
        guard let dev = store.deviceId else {
            lastError = "waiting for the phone's LibreView DeviceId"
            return onStatus()
        }
        running = true
        Task { @MainActor in
            defer { running = false; onStatus() }
            do {
                try await client.upload(rows, deviceId: dev)
                lastOK = Date()
                lastError = nil
                appendLedger(rows, dev: dev)
                store.markUploaded(rows)
                log("libreview: uploaded \(rows.count) treatment(s)")
            } catch let e as LVError {
                switch e {
                case .http(let c) where c == 408 || c == 429 || c >= 500: lastError = e.localizedDescription
                default: halted = e.localizedDescription
                }
                log("libreview: \(e.localizedDescription)")
            } catch {
                lastError = error.localizedDescription    // network: retried by the 5-min timer
                log("libreview: \(error.localizedDescription)")
            }
        }
    }

    private func appendLedger(_ rows: [Treatment], dev: String) {
        let at = Int64(Date().timeIntervalSince1970)
        var lines = Data()
        for t in rows {
            let o: [String: Any] = ["rn": LVPayload.recordNumber(t, version: t.u), "u": t.u, "lv": t.lv, "id": t.id, "k": t.k, "t": t.t, "a": t.a, "n": t.n,
                                    "del": t.del, "dev": dev, "at": at]
            if let d = try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys]) { lines.append(d); lines.append(0x0A) }
        }
        if let h = try? FileHandle(forWritingTo: ledger) {
            h.seekToEndOfFile(); h.write(lines); try? h.close()
        } else {
            try? lines.write(to: ledger)
        }
    }
}
