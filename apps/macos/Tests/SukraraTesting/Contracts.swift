// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Locates `<repo>/contracts/` for tests: `SUKRARA_CONTRACTS` when set, else the first `contracts/` directory found
/// walking up from this source file. Test-only: the shipped app never reads the repo.
public enum Contracts {
    public static let root: URL = {
        if let env = ProcessInfo.processInfo.environment["SUKRARA_CONTRACTS"], !env.isEmpty {
            return URL(fileURLWithPath: env, isDirectory: true)
        }
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appending(path: "contracts", directoryHint: .isDirectory)
            var isDirectory: ObjCBool = false
            if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDirectory), isDirectory.boolValue
            {
                return candidate
            }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("contracts/ not found above \(#filePath); set SUKRARA_CONTRACTS")
    }()

    /// `contracts/<path>`, e.g. `Contracts.url("alerts/scenarios")`.
    public static func url(_ path: String) -> URL {
        path.isEmpty ? root : root.appending(path: path)
    }

    /// Whether `contracts/<path>` exists.
    public static func exists(_ path: String) -> Bool {
        FileManager.default.fileExists(atPath: url(path).path)
    }
}
