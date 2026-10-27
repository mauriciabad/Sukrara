// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Runs the followers' real decoders (compiled from third_party/ by run.sh) on what our uploader put in Nightscout.
//   Trio:       [BloodGlucose] with JSONCoding.decoder        (NightscoutAPI.swift:94-96)
//   LoopFollow: [ShareGlucoseData] with JSONDecoder()         (NightscoutUtils.swift:86-88)
//   LoopFollow: SAGE created_at with SAge.swift's formatter   (SAge.swift:41-46)
import Foundation

let env = ProcessInfo.processInfo.environment
let base = env["NS_URL"] ?? "http://localhost:1337"
let secretSha1 = env["NS_API_SECRET_SHA1"] ?? ""
let readToken = env["NS_READ_TOKEN"] ?? ""
var failures = 0

func fetch(_ path: String, apiSecret: Bool) -> Data {
    var req = URLRequest(url: URL(string: base + path)!)
    if apiSecret { req.addValue(secretSha1, forHTTPHeaderField: "api-secret") }
    var out = Data()
    let sem = DispatchSemaphore(value: 0)
    URLSession.shared.dataTask(with: req) { d, _, _ in out = d ?? Data(); sem.signal() }.resume()
    sem.wait()
    return out
}

func check(_ ok: Bool, _ what: String) { print("  \(ok ? "PASS" : "FAIL") \(what)"); if !ok { failures += 1 } }

let iso = ISO8601DateFormatter(); iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
let since = iso.string(from: Date().addingTimeInterval(-3 * 3600))
let sinceMs = Int(Date().addingTimeInterval(-3 * 3600).timeIntervalSince1970 * 1000)

print("Trio BloodGlucose decoder")
let trioData = fetch("/api/v1/entries/sgv.json?count=1600&find%5BdateString%5D%5B$gte%5D=\(since.addingPercentEncoding(withAllowedCharacters: .alphanumerics)!)", apiSecret: true)
do {
    let g = try JSONCoding.decoder.decode([BloodGlucose].self, from: trioData)
    check(g.count >= 175, "decoded \(g.count) readings of the last 3 h")
    check(g.allSatisfy { $0.sgv != nil && $0.direction != nil }, "every reading has sgv and a direction")
    if let f = g.first { print("    newest: \(f.sgv!) mg/dL \(f.direction!.rawValue) at \(f.dateString)") }
} catch { check(false, "decode threw: \(error)") }

print("LoopFollow ShareGlucoseData decoder")
let lfData = fetch("/api/v1/entries.json?count=200&find%5Bdate%5D%5B$gte%5D=\(sinceMs)&find%5Btype%5D%5B$ne%5D=cal&token=\(readToken)", apiSecret: false)
do {
    let g = try JSONDecoder().decode([ShareGlucoseData].self, from: lfData)
    check(g.count >= 175, "decoded \(g.count) readings")
    if let f = g.first { print("    newest: \(f.sgv) mg/dL \(f.direction ?? "-") at \(Date(timeIntervalSince1970: f.date / 1000))") }
} catch { check(false, "decode threw: \(error)") }

print("LoopFollow SAGE (Sensor Start created_at)")
let sageData = fetch("/api/v1/treatments.json?find%5BeventType%5D=Sensor%20Start&count=1&token=\(readToken)", apiSecret: false)
if let arr = try? JSONSerialization.jsonObject(with: sageData) as? [[String: Any]], let created = arr.first?["created_at"] as? String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withFullDate, .withTime, .withDashSeparatorInDate, .withColonSeparatorInTime]
    let parsed = formatter.date(from: created)
    check(parsed != nil, "SAge.swift's formatter parses '\(created)'" + (parsed == nil ? " → nil; `as! TimeInterval` would trap" : ""))
} else { check(false, "no Sensor Start treatment") }

print(failures == 0 ? "\nall passed" : "\n\(failures) failed")
exit(failures == 0 ? 0 : 1)
