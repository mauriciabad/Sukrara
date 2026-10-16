// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Tries every transport the host publishes to and records which ones the sandbox allows.
/// The result of each attempt is logged, so `log show` answers "what works with ad-hoc signing".
enum Loader {
    /// The sandboxed process's NSHomeDirectory() is its container; the real home is from getpwuid.
    static var realHome: String {
        if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir { return String(cString: dir) }
        return NSHomeDirectory()
    }

    static func load() async -> (GlucoseSnapshot?, String, [String]) {
        var report: [String] = []
        var files: [(String, URL)] = []
        if let g = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: SharedLocation.appGroup) {
            files.append(("appgroup-api", g.appendingPathComponent(SharedLocation.fileName)))
        } else {
            report.append("appgroup-api=nil")
        }
        files.append(("appgroup-path", SharedLocation.groupFile(home: realHome)))
        files.append(("own-container", URL(fileURLWithPath: NSHomeDirectory())
            .appendingPathComponent("Library/Application Support/\(SharedLocation.fileName)")))
        files.append(("users-shared", SharedLocation.sharedFile))

        var best: (GlucoseSnapshot, String)?
        for (name, url) in files {
            do {
                let d = try Data(contentsOf: url)
                let s = try JSONDecoder.glucose.decode(GlucoseSnapshot.self, from: d)
                report.append("\(name)=ok(age \(Int(Date().timeIntervalSince(s.written)))s)")
                if best == nil || s.written > best!.0.written { best = (s, name) }
            } catch {
                report.append("\(name)=\((error as NSError).code)")
            }
        }
        do {
            var req = URLRequest(url: SharedLocation.httpURL, timeoutInterval: 3)
            req.cachePolicy = .reloadIgnoringLocalCacheData
            let (d, _) = try await URLSession.shared.data(for: req)
            let s = try JSONDecoder.glucose.decode(GlucoseSnapshot.self, from: d)
            report.append("http=ok")
            if best == nil || s.written >= best!.0.written { best = (s, "http") }
        } catch {
            report.append("http=\((error as NSError).code)")
        }
        return (best?.0, best?.1 ?? "none", report)
    }
}
