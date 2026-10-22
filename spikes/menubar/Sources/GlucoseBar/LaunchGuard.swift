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
///  3. `services(...)`: what the full app may start. An evidence render (`-…Snapshot`, `-panelPreview`, `-preview`,
///     `-openPanel`, `-openTreatments`) and every dev build run offline: no Bluetooth, no relays, no LAN feed and no
///     alert engine, so a test copy can never reach the user's phone or ring (2026-10-21: a `-settingsSnapshot` dev
///     run joined the user's phone over Bluetooth). main.swift starts each transport only through this plan.
/// `Sukrara -argsselftest` checks all three.
enum LaunchGuard {
    /// Flags that take no value: the self-tests main.swift dispatches.
    static let bareFlags: Set<String> = ["-v2selftest", "-v2relaytest", "-bleselftest", "-qrselftest", "-treatmentsselftest",
                                         "-alerts-selftest", "-graphselftest", "-exportselftest", "-argsselftest",
                                         "-identityselftest", "-settingsselftest", "-panelselftest"]

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

    /// Launch arguments that render evidence or drive a dev check (with or without a value).
    static let renderFlags: Set<String> = ["-preview", "-panelPreview", "-openPanel", "-openTreatments"]

    static func isRender(_ args: [String]) -> Bool {
        args.contains { a in a.hasPrefix("-") && (a.hasSuffix("Snapshot") || renderFlags.contains(a)) }
    }

    /// What the full app may start. `wantLan` / `wantBle` / `wantNostr`: the user's settings (`lan`, `bleV2`, `nostr`).
    struct Services: Equatable {
        var lan, ble, nostr, follow, alerts: Bool
        static let none = Services(lan: false, ble: false, nostr: false, follow: false, alerts: false)
        var offline: Bool { self == .none }
    }

    static func services(args: [String], devBuild: Bool, wantLan: Bool, wantBle: Bool, wantNostr: Bool) -> Services {
        if devBuild || isRender(args) { return .none }
        return Services(lan: wantLan, ble: wantBle, nostr: wantNostr, follow: true, alerts: true)
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
        // 3. offline runs: no evidence render and no dev build may start Bluetooth, relays, the LAN feed or alerts
        let all = Services(lan: true, ble: true, nostr: true, follow: true, alerts: true)
        check("the installed app starts what its settings ask", services(args: [], devBuild: false, wantLan: true, wantBle: true, wantNostr: true) == all)
        check("…and leaves off what they turn off", services(args: [], devBuild: false, wantLan: false, wantBle: false, wantNostr: false)
              == Services(lan: false, ble: false, nostr: false, follow: true, alerts: true))
        for flag in ["-settingsSnapshot", "-alertsSnapshot", "-reportSnapshot", "-pairSnapshot", "-identitySnapshot",
                     "-treatmentTimeSnapshot", "-panelPreview", "-preview", "-someNewSnapshot"] {
            check("\(flag) runs offline (no BLE, relays, LAN, alerts)", services(args: [flag, "/tmp/x"], devBuild: false, wantLan: true, wantBle: true, wantNostr: true).offline)
        }
        check("-openPanel / -openTreatments run offline", services(args: ["-openPanel"], devBuild: false, wantLan: true, wantBle: true, wantNostr: true).offline
              && services(args: ["-openTreatments"], devBuild: false, wantLan: true, wantBle: true, wantNostr: true).offline)
        check("a dev build runs offline even with SUKRARA_DEV=1", services(args: ["-keychainService", "t"], devBuild: true, wantLan: true, wantBle: true, wantNostr: true).offline)
        check("this very binary runs offline", services(args: Array(CommandLine.arguments.dropFirst()),
              devBuild: devBuild(bundlePath: Bundle.main.bundlePath, isBundle: Bundle.main.bundleIdentifier != nil), wantLan: true, wantBle: true, wantNostr: true).offline)
        // …and main.swift starts each of them only through the plan (read from the source next to this file)
        let main = (try? String(contentsOfFile: URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("main.swift").path, encoding: .utf8)) ?? ""
        func count(_ s: String) -> Int { main.components(separatedBy: s).count - 1 }
        check("main.swift found", !main.isEmpty)
        check("Bluetooth only through services.ble", main.contains("let bleV2On = services.ble") && count("BLELink(") == 1
              && main.contains("let bleLink = bleV2On ? BLELink() : nil"))
        check("LAN feed only through services.lan", main.contains("let lanEnabled = services.lan") && main.contains("guard services.lan else"))
        check("Nostr v1 only through services.nostr", count("NostrSource.init") == 1 && main.contains("services.nostr ? nostrLink.flatMap(NostrSource.init(link:)) : nil"))
        check("relays (v2 follow) only through services.follow", count("n.start()") == 3 && count("guard services.follow") == 4
              && main.contains("guard services.follow, node == nil"))
        check("alerts only through services.alerts", count("Alerts(") == 1 && main.contains("Alerts(offline: !services.alerts)"))
        check("this very binary counts as a dev build", devBuild(bundlePath: Bundle.main.bundlePath, isBundle: Bundle.main.bundleIdentifier != nil))
        print(ok ? "args self-test OK" : "args self-test FAILED")
        return ok
    }
}
