// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import UserNotifications

/// Part of `-alerts-selftest` (round f30; the user, 2026-10-21: "it is important that we have tests for all these
/// situations, especially with notifications"): the real `Alerts` (this Mac's notifications) with a recorder in place
/// of Notification Center, an injected clock and a throwaway UserDefaults suite. One block per row of SPEC §2 "States
/// and exits": which notification is up (its category, title, actions), what each action posts or removes, Mute all
/// (own rules and the wearer's mirrored alerts, very low and extremely high included), Turn alerts back on and the
/// back-by-margin end. Nothing reaches the real notification center, the Keychain or the user's data.
@MainActor enum AlertsNotifySelfTest {
    /// What `Alerts` posted: the notification up per group, every add / remove, the registered categories.
    final class Recorder: AlertPoster {
        var up: [String: UNNotificationContent] = [:]
        var ops: [String] = []
        var categories: Set<UNNotificationCategory> = []
        func add(_ req: UNNotificationRequest) { up[req.identifier] = req.content; ops.append("add \(req.identifier) \(req.content.title)") }
        func remove(_ id: String) { up[id] = nil; ops.append("remove \(id)") }
        func setCategories(_ c: Set<UNNotificationCategory>) { categories = c }
        var level: UNNotificationContent? { up["level"] }
    }

    static func run(_ check: (String, Bool, String) -> Void) {
        let t0 = Date(timeIntervalSince1970: 1_800_000_000)
        var clock = t0
        var trs: [Treatment] = []
        var suites: [String] = []
        defer { for n in suites { UserDefaults().removePersistentDomain(forName: n) } }
        func make(source: String = "own", follow: Bool = false, edit: (inout AlertSettings) -> Void = { _ in }) -> (Alerts, Recorder) {
            let name = "sukrara-notify-selftest-\(UUID().uuidString)"
            suites.append(name)
            let rec = Recorder()
            let a = Alerts(offline: false, poster: rec, defaults: UserDefaults(suiteName: name)!)
            a.now = { clock }
            a.followingV2 = { follow }
            a.treatments = { trs }
            a.engine.sensor = { nil }   // no sensor life here (not the stored one)
            var s = AlertSettings()
            s.sounds.pack = "none"
            s.source = source
            s.notificationActions.backMin = 10
            edit(&s)
            a.useForTest(s)
            return (a, rec)
        }
        func feed(_ a: Alerts, _ mgdl: Double, rate: Double = 0) { a.onLatest(Reading(date: clock, mgdl: mgdl, rate: rate)) }
        /// `minutes` of readings at `mgdl`, one per minute (the clock moves).
        func walk(_ a: Alerts, _ mgdl: Double, minutes: Int) {
            for _ in 0..<minutes { clock += 60; feed(a, mgdl) }
        }
        func cat(_ c: UNNotificationContent?) -> String { c?.categoryIdentifier ?? "nothing" }
        func title(_ c: UNNotificationContent?) -> String { c?.title ?? "nothing" }
        func at(_ m: Int) -> String { Clock.time(clock.addingTimeInterval(TimeInterval(m * 60))) }

        // Categories: every alert has Mute… (a text input) and at most Relax / Stop relaxing; the muted one Unmute + Change….
        do {
            let (_, rec) = make()
            let c = Dictionary(uniqueKeysWithValues: rec.categories.map { ($0.identifier, $0.actions) })
            func describe(_ a: [UNNotificationAction]) -> String {
                a.map { x in (x as? UNTextInputNotificationAction).map { "\(x.identifier):\(x.title)[\($0.textInputPlaceholder)]" } ?? "\(x.identifier):\(x.title)" }
                    .joined(separator: ", ")
            }
            print("      registered categories: " + c.keys.sorted().map { "\($0) = \(describe(c[$0]!))" }.joined(separator: " | "))
            check("notify: the four categories", Set(c.keys) == ["alert-r", "alert-u", "alert", "muted"], "\(c.keys.sorted())")
            check("notify: alert-r = Mute… (text input, \"30 min, 1 h, 2 h…\") + Relax",
                  describe(c["alert-r"] ?? []) == "mute:Mute…[30 min, 1 h, 2 h…], relax:Relax", describe(c["alert-r"] ?? []))
            check("notify: alert-u = Mute… + Stop relaxing", describe(c["alert-u"] ?? []) == "mute:Mute…[30 min, 1 h, 2 h…], unrelax:Stop relaxing",
                  describe(c["alert-u"] ?? []))
            check("notify: alert (Relax wouldn't slow it) = Mute… alone", describe(c["alert"] ?? []) == "mute:Mute…[30 min, 1 h, 2 h…]", describe(c["alert"] ?? []))
            check("notify: muted = Unmute + Change… (the same input)", describe(c["muted"] ?? []) == "unmute:Unmute, mute:Change…[30 min, 1 h, 2 h…]",
                  describe(c["muted"] ?? []))
            check("notify: no alert category has more than two actions, none a one-tap Mute n min / Slow to",
                  rec.categories.allSatisfy { $0.actions.count <= 2 && $0.actions.allSatisfy { !$0.title.contains("min") && !$0.title.hasPrefix("Slow") } }, "")
        }

        // The dropdown's Mute alerts lengths (the schema's muteAll presets, 0 = Never) and its Custom… input.
        do {
            let menu = AlertActions.muteAllPresets.filter { $0 > 0 }.map(muteAllLabel) + ["Never (until I turn them back on)", "Custom…"]
            print("      Mute alerts menu: " + menu.joined(separator: " · "))
            check("mute all: the menu offers 30 min · 1 h · 2 h · 4 h · 8 h · Never · Custom…",
                  menu == ["30 min", "1 h", "2 h", "4 h", "8 h", "Never (until I turn them back on)", "Custom…"] && AlertActions.muteAllPresets.contains(0),
                  menu.joined(separator: " · "))
            check("mute all: a custom length takes 5 min – 24 h", AlertActions.muteAllRange == 5...1440, "\(AlertActions.muteAllRange)")
        }

        // Normal (alerting): every level kind gets Mute… + Relax, very low and extremely high the same.
        for (mgdl, kind) in [(60.0, "Low glucose"), (50, "Very low glucose"), (200, "High glucose"), (262, "Very high glucose"), (320, "Extremely high glucose")] {
            clock = t0
            let (a, rec) = make()
            feed(a, mgdl)
            check("normal: \(kind) posts with Mute… + Relax", title(rec.level) == kind && cat(rec.level) == "alert-r",
                  "\(title(rec.level)) \(cat(rec.level))")
        }
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 120)
            clock += 21 * 60; a.testTick()
            check("normal: no data (repeats hourly, Relax slows nothing) posts with Mute… alone",
                  title(rec.up["no_data"]) == "No glucose data" && cat(rec.up["no_data"]) == "alert", "\(title(rec.up["no_data"])) \(cat(rec.up["no_data"]))")
        }

        // Side muted: Mute… with a typed length → the passive "Low muted until …" (Unmute + Change…); Change; bad text; Unmute.
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 60)
            a.respond("mute", .low, typed: "1 h")
            check("muted: Mute… \"1 h\" → \"Low muted until \(at(60))\" (Unmute + Change…, passive)",
                  title(rec.level) == "Low muted until \(at(60))" && cat(rec.level) == "muted" && rec.level?.interruptionLevel == .passive,
                  "\(title(rec.level)) \(cat(rec.level))")
            check("muted: the badge's pause and the panel row", a.paused["low"] == clock.timeIntervalSince1970 + 3600
                  && a.active.first.map { $0.quiet == .notification && $0.lift == "Unmute" } == true, "\(a.paused) \(a.active)")
            a.respond("mute", .low, typed: "15 min")
            check("muted: Change… \"15 min\" → until \(at(15))", title(rec.level) == "Low muted until \(at(15))", title(rec.level))
            a.respond("mute", .low, typed: "soon")
            check("muted: a reply that isn't a length changes nothing", title(rec.level) == "Low muted until \(at(15))" && cat(rec.level) == "muted",
                  title(rec.level))
            a.respond("mute", .low, typed: "")
            check("muted: an empty reply = the setting (30 min)", title(rec.level) == "Low muted until \(at(30))", title(rec.level))
            walk(a, 50, minutes: 2)
            check("muted: a low's Mute covers very low (no alert while it lasts)", cat(rec.level) == "muted", "\(title(rec.level)) \(cat(rec.level))")
            a.respond("unmute", .low, typed: nil)
            check("muted: Unmute → the alert at once (Mute… + Relax)", title(rec.level) == "Very low glucose" && cat(rec.level) == "alert-r",
                  "\(title(rec.level)) \(cat(rec.level))")
        }
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 60)
            a.respond("mute", .low, typed: "20")
            walk(a, 60, minutes: 19)
            check("muted: still muted a minute before its end", cat(rec.level) == "muted", cat(rec.level))
            walk(a, 60, minutes: 1)
            check("muted: ends at its time → the alert again", title(rec.level) == "Low glucose" && cat(rec.level) == "alert-r",
                  "\(title(rec.level)) \(cat(rec.level))")
        }
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 200)
            a.respond("mute", .high, typed: "2 h")
            walk(a, 120, minutes: 5)
            walk(a, 60, minutes: 1)
            check("muted: a high's Mute never silences a low", title(rec.level) == "Low glucose" && cat(rec.level) == "alert-r",
                  "\(title(rec.level)) \(cat(rec.level))")
        }
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 60)
            a.perform(.mute(.low, 30))
            check("muted: the panel / pill menu's Mute For 30 min posts the muted notification too", title(rec.level) == "Low muted until \(at(30))",
                  title(rec.level))
        }

        // Back by the margin: low + 10 mg/dL for 10 min ends Mute and Relax; its notification goes.
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 60)
            a.respond("relax", .low, typed: nil)
            a.respond("mute", .low, typed: "2 h")
            check("muted + relaxed: both on the side", a.active.first.map { $0.quiet == .notification && $0.relaxMin == 30 } == true, "\(a.active)")
            walk(a, 85, minutes: 5)
            walk(a, 75, minutes: 1)     // back under low + 10: the count starts again
            walk(a, 85, minutes: 10)
            check("back: not yet (crossing back under the margin restarted the count)", cat(rec.level) == "muted" && a.paused["low"] != nil,
                  "\(cat(rec.level)) \(a.paused)")
            walk(a, 85, minutes: 1)
            check("back: 10 min at ≥ 80 mg/dL → the muted notification goes, Mute and Relax end", rec.level == nil && a.paused.isEmpty && a.active.isEmpty,
                  "\(title(rec.level)) \(a.paused) \(a.active)")
            walk(a, 60, minutes: 1)
            check("back: the next low alerts at once with Relax again", title(rec.level) == "Low glucose" && cat(rec.level) == "alert-r",
                  "\(title(rec.level)) \(cat(rec.level))")
        }

        // Side relaxed: Relax → Stop relaxing in its place; Stop relaxing → Relax again.
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 60)
            a.respond("relax", .low, typed: nil)
            check("relaxed: the notification comes back silently with Stop relaxing", cat(rec.level) == "alert-u" && rec.level?.sound == nil,
                  cat(rec.level))
            check("relaxed: \"Low relaxed: reminders every 30 min\" in the panel", a.active.first?.title == "Low relaxed: reminders every 30 min",
                  a.active.first?.title ?? "none")
            let adds = rec.ops.filter { $0.hasPrefix("add level") }.count
            walk(a, 60, minutes: 10)
            check("relaxed: no repeat at the low's 5 min", rec.ops.filter { $0.hasPrefix("add level") }.count == adds, "\(rec.ops.suffix(3))")
            a.respond("unrelax", .low, typed: nil)
            check("relaxed: Stop relaxing → the overdue repeat at once, with Relax", cat(rec.level) == "alert-r"
                  && rec.ops.filter { $0.hasPrefix("add level") }.count == adds + 1, "\(cat(rec.level)) \(rec.ops.suffix(3))")
        }

        // Quiet after carbs: no alert; Alert Me Anyway (the panel) brings it.
        do {
            clock = t0
            trs = [Treatment(k: "carbs", a: 15, t: Int64(clock.timeIntervalSince1970) - 60)]
            let (a, rec) = make()
            feed(a, 60)
            check("carbs: quiet after carbs, Alert Me Anyway offered", rec.level == nil && a.active.first.map { $0.quiet == .carbs && $0.lift == "Alert Me Anyway" } == true,
                  "\(title(rec.level)) \(a.active)")
            a.perform(.unmute(.low))
            check("carbs: Alert Me Anyway → the alert at once (\"· eat more\")", title(rec.level) == "Low glucose · eat more", title(rec.level))
            trs = []
        }

        // Mute all: every kind quiet (very low, extremely high, no data), the line, Change, Turn alerts back on, its end.
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 60)
            a.perform(.muteAll(60))
            check("mute all: the alert up goes", rec.level == nil, title(rec.level))
            check("mute all: the quiet episode isn't listed as a falling mute (no Alert Me Anyway; Android 3d8c8e9b)",
                  a.active.isEmpty && a.engine.quiets().isEmpty, "\(a.active)")
            check("mute all: \"Alerts muted until \(at(60))\" and the pill's end", a.allMutedLine == "Alerts muted until \(at(60))"
                  && a.allMuted == clock.timeIntervalSince1970 + 3600, a.allMutedLine ?? "nil")
            walk(a, 50, minutes: 2)
            walk(a, 320, minutes: 2)
            clock += 25 * 60; a.testTick()
            check("mute all: very low, extremely high and no data post nothing", rec.up.isEmpty, "\(rec.up.keys.sorted())")
            a.perform(.muteAll(0))
            check("mute all: Change to Never → \"Alerts muted\"", a.allMutedLine == "Alerts muted" && a.allMuted == AlertStatus.never, a.allMutedLine ?? "nil")
            feed(a, 320)
            a.perform(.unmuteAll)
            check("mute all: Turn alerts back on → what matches alerts at once", title(rec.level) == "Extremely high glucose" && a.allMutedLine == nil,
                  "\(title(rec.level)) \(a.allMutedLine ?? "nil")")
        }
        do {
            clock = t0
            let (a, rec) = make()
            feed(a, 200)
            a.perform(.muteAll(30))
            walk(a, 200, minutes: 29)
            check("mute all: quiet until its end", rec.level == nil, title(rec.level))
            walk(a, 200, minutes: 1)
            check("mute all: ends at its time → the high at once", title(rec.level) == "High glucose" && a.allMutedLine == nil, title(rec.level))
        }

        // Mirroring the wearer: the same notifications and actions on the wearer's alerts, this Mac only.
        do {
            clock = t0
            let (a, rec) = make(source: "wearer", follow: true)
            var n = 0
            func wearer(_ k: String, _ state: String = "fire", id: String? = nil, sgv: Double) -> String {
                n += 1
                let i = id ?? String(format: "%016x", n)
                a.onWearerAlert(["v": 2, "type": "alert", "id": i, "k": k, "state": state, "at": clock.timeIntervalSince1970,
                                 "t": clock.timeIntervalSince1970, "sgv": sgv, "r": 0, "th": 70, "n": n], via: "test")
                return i
            }
            feed(a, 60)
            _ = wearer("low", sgv: 60)
            check("mirror: the wearer's low posts here with Mute… + Relax", title(rec.level) == "Low glucose" && cat(rec.level) == "alert-r",
                  "\(title(rec.level)) \(cat(rec.level))")
            a.respond("mute", .low, typed: "45")
            check("mirror: Mute… → \"Low muted until \(at(45))\"", title(rec.level) == "Low muted until \(at(45))" && cat(rec.level) == "muted",
                  title(rec.level))
            clock += 60; feed(a, 50)
            _ = wearer("very_low", sgv: 50)
            check("mirror: the wearer's very low stays quiet while the low side is muted here", cat(rec.level) == "muted", title(rec.level))
            a.respond("unmute", .low, typed: nil)
            check("mirror: Unmute → the wearer's newest alert at once", title(rec.level) == "Very low glucose", title(rec.level))
            a.perform(.muteAll(0))
            check("mirror: Mute all removes it", rec.level == nil, title(rec.level))
            clock += 60
            _ = wearer("extremely_high", sgv: 320)
            check("mirror: Mute all drops the wearer's extremely high too", rec.level == nil, title(rec.level))
            a.perform(.unmuteAll)
            check("mirror: Turn alerts back on → the wearer's newest alert at once", title(rec.level) == "Extremely high glucose", title(rec.level))
            a.respond("relax", .extremelyHigh, typed: nil)
            check("mirror: Relax → Stop relaxing", cat(rec.level) == "alert-u", cat(rec.level))
            a.respond("mute", .extremelyHigh, typed: "2 h")
            walk(a, 160, minutes: 11)
            check("mirror: the margin on this Mac's readings ends the wearer's mute here (high − 10 for 10 min)",
                  rec.level == nil && a.paused.isEmpty && a.active.isEmpty, "\(title(rec.level)) \(a.paused) \(a.active)")
        }
    }
}
