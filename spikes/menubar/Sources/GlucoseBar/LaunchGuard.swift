// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Keeps a test or dev run from becoming a second copy of the user's Sukrara (2026-10-19: a stale debug binary got a
/// self-test flag it didn't know, fell through to the full app, and followed the wearer with the real Keychain item
/// and data files for 10 minutes). Two rules, checked in main.swift before anything touches state:
///  1. `problem(args)`: an argument the binary doesn't understand is an error (exit 2), never "start the app":
///     a `-…selftest` flag it doesn't know, a bare `-flag` with no value, or a stray word. `-key value` pairs are
///     UserDefaults settings (main.swift's header) and pass.
///  2. `devRefusal(...)`: a dev build — the bare `swift build` executable (no bundle) or an app inside a git checkout
///     (`build/Sukrara.app`) — starts the full app only with `SUKRARA_DEV=1` in its environment (exit 3 otherwise),
///     because it would share the installed app's Keychain item, data files and defaults. Copies outside a repo
///     (`/Applications`, `~/Applications`, a release unzipped in Downloads) start as always.
/// `Sukrara -argsselftest` checks both.
enum LaunchGuard {
    /// Flags that take no value: the self-tests main.swift dispatches.
    static let bareFlags: Set<String> = ["-v2selftest", "-v2relaytest", "-bleselftest", "-qrselftest", "-treatmentsselftest",
                                         "-alerts-selftest", "-graphselftest", "-exportselftest", "-argsselftest"]

    /// Why these arguments (without the executable) must not start anything, or nil when they're fine.
    static func problem(_ args: [String]) -> String? {
        var i = 0
        while i < args.count {
            let a = args[i]
            if bareFlags.contains(a) || a.hasPrefix("-psn_") { i += 1; continue }    // -psn_…: old Finder launches
            guard a.hasPrefix("-"), a.count > 1, Double(a) == nil else { return "unexpected argument '\(a)'" }
            if a.lowercased().hasSuffix("selftest") { return "unknown self-test '\(a)' (known: \(bareFlags.sorted().joined(separator: " ")))" }
            // `-key value`: a UserDefaults setting (or -debugsend / -alert-test / -…Snapshot with their value)
            if i + 1 < args.count, !args[i + 1].hasPrefix("-") || Double(args[i + 1]) != nil { i += 2; continue }
            return "unknown flag '\(a)' (a setting needs a value: '\(a) <value>')"
        }
        return nil
    }

    /// A dev build: not an app bundle (`swift build` output), or inside a git checkout (`.git` in an ancestor).
    static func devBuild(bundlePath: String, isBundle: Bool, exists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }) -> Bool {
        if !isBundle { return true }
        var dir = URL(fileURLWithPath: bundlePath).standardizedFileURL.deletingLastPathComponent()
        while dir.path != "/" && !dir.path.isEmpty {
            if exists(dir.appendingPathComponent(".git").path) { return true }
            dir = dir.deletingLastPathComponent()
        }
        return false
    }

    /// Why the full app must not start from here, or nil.
    static func devRefusal(bundlePath: String, isBundle: Bool, env: [String: String],
                           exists: (String) -> Bool = { FileManager.default.fileExists(atPath: $0) }) -> String? {
        if !devBuild(bundlePath: bundlePath, isBundle: isBundle, exists: exists) || env["SUKRARA_DEV"] == "1" { return nil }
        return "this is a dev build (\(bundlePath)), not the installed Sukrara: starting it would use the installed "
            + "app's Keychain item, data files and settings. Run a self-test flag, or set SUKRARA_DEV=1 to start it "
            + "anyway (with -keychainService <test> -readingsDir <dir> -treatmentsDir <dir> to keep the real data out)."
    }

    static func selfTest() -> Bool {
        var ok = true
        func check(_ name: String, _ c: Bool) { print("\(c ? "PASS" : "FAIL")  \(name)"); ok = ok && c }
        check("no arguments are fine", problem([]) == nil)
        check("every known self-test passes", bareFlags.allSatisfy { problem([$0]) == nil })
        check("an unknown self-test is refused", problem(["-bleselftest2"]) != nil && problem(["-fooselftest"]) != nil)
        check("…even with something after it", problem(["-newselftest", "x"]) != nil)
        check("a bare unknown flag is refused", problem(["-bogus"]) != nil && problem(["-v2relaytestx"]) != nil)
        check("a stray word is refused", problem(["selftest"]) != nil)
        check("settings with values pass", problem(["-keychainService", "t", "-v2NoRelays", "YES", "-alert-feed", "200,-1.5"]) == nil)
        check("a negative number is a value", problem(["-offset", "-5"]) == nil)
        check("a value-taking flag without its value is refused", problem(["-settingsSnapshot"]) != nil)
        check("old Finder -psn_ argument passes", problem(["-psn_0_12345"]) == nil)
        let repo: (String) -> Bool = { $0 == "/Users/u/repo/.git" }
        check("~/Applications copy is not a dev build", !devBuild(bundlePath: "/Users/u/Applications/Sukrara.app", isBundle: true, exists: repo))
        check("a release unzipped in Downloads is not a dev build", !devBuild(bundlePath: "/Users/u/Downloads/Sukrara.app", isBundle: true, exists: repo))
        check("swift build output (no bundle) is a dev build", devBuild(bundlePath: "/Users/u/repo/spikes/menubar/.build/debug", isBundle: false, exists: repo))
        check("bundle.sh output in the repo is a dev build", devBuild(bundlePath: "/Users/u/repo/spikes/menubar/build/Sukrara.app", isBundle: true, exists: repo))
        check("dev build without SUKRARA_DEV is refused", devRefusal(bundlePath: "/x/.build/debug", isBundle: false, env: [:], exists: repo) != nil)
        check("dev build with SUKRARA_DEV=1 starts", devRefusal(bundlePath: "/x/.build/debug", isBundle: false, env: ["SUKRARA_DEV": "1"], exists: repo) == nil)
        check("installed copy starts", devRefusal(bundlePath: "/Users/u/Applications/Sukrara.app", isBundle: true, env: [:], exists: repo) == nil)
        check("this very binary counts as a dev build", devBuild(bundlePath: Bundle.main.bundlePath, isBundle: Bundle.main.bundleIdentifier != nil))
        print(ok ? "args self-test OK" : "args self-test FAILED")
        return ok
    }
}
