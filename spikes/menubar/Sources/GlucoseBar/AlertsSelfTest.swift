// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// `Sukrara -alerts-selftest`: the AlertEngine over scripted readings with an injected clock (spikes/alerts/SPEC.md
/// §1–§3), plus the settings JSON and sound naming. No notifications, no network.
enum AlertsSelfTest {
    static func run() -> Bool {
        var ok = true
        func check(_ name: String, _ cond: Bool, _ detail: @autoclosure () -> String = "") {
            print("\(cond ? "PASS" : "FAIL")  \(name)\(cond ? "" : "  — " + detail())")
            ok = ok && cond
        }

        // A fresh engine with default settings; `at(min)` moves the clock, `feed` sends a reading stamped now.
        var clock = Date(timeIntervalSince1970: 1_760_650_000)
        var insulin: [Date] = []
        var log: [AlertEvent] = []
        func engine(_ edit: (inout AlertSettings) -> Void = { _ in }) -> AlertEngine {
            var s = AlertSettings()
            edit(&s)
            let e = AlertEngine(settings: s, now: { clock })
            e.rapidInsulin = { insulin }
            e.onFire = { log.append($0) }
            e.onClear = { log.append($0) }
            log = []
            insulin = []
            return e
        }
        func at(_ min: Double) { clock = Date(timeIntervalSince1970: 1_760_650_000 + min * 60) }
        func feed(_ e: AlertEngine, _ sgv: Double, _ rate: Double? = 0) -> [AlertEvent] {
            let before = log.count
            e.reading(Reading(date: clock, mgdl: sgv, rate: rate))
            return Array(log[before...])
        }
        func tick(_ e: AlertEngine) -> [AlertEvent] {
            let before = log.count
            e.tick()
            return Array(log[before...])
        }
        func fires(_ evs: [AlertEvent]) -> [String] { evs.filter { $0.state == "fire" }.map { "\($0.k.rawValue)#\($0.n)" } }
        func clears(_ evs: [AlertEvent]) -> [String] { evs.filter { $0.state == "clear" }.map(\.k.rawValue) }

        // 1. Rising into high, repeat timing, no flapping, escalation, clear.
        var e = engine()
        at(0); check("in range: nothing", feed(e, 170, 1).isEmpty)
        at(5); let h1 = feed(e, 186, 0.5)
        check("rising into high fires high", fires(h1) == ["high#1"], "\(fires(h1))")
        check("event carries th/sgv/r/n", h1.first.map { $0.th == 185 && $0.sgv == 186 && $0.r == 0.5 && $0.n == 1 && $0.id.count == 16 } ?? false)
        at(35); check("still high at +30 min: no repeat (repeatMin 60)", feed(e, 190, 0.1).isEmpty)
        at(65); let h2 = feed(e, 192, 0.1)
        check("still high at +60 min: re-alert n=2, new id", fires(h2) == ["high#2"] && h2.first?.id != h1.first?.id, "\(fires(h2))")
        at(70); let c1 = feed(e, 180, 0)
        check("back under: clear repeats the last firing's id", clears(c1) == ["high"] && c1.first?.id == h2.first?.id)
        at(75); check("back to high within repeatMin of its last firing: no flapping", fires(feed(e, 187, 0.2)).isEmpty)
        check("…state says it's waiting", e.stateLine.contains("not repeated yet"), e.stateLine)
        at(126); check("…fires once repeatMin since the last firing has passed", fires(feed(e, 188, 0.2)) == ["high#1"])
        at(127); let vh = feed(e, 255, 1)
        check("high → very high fires at once (escalation)", fires(vh) == ["very_high#1"] && clears(vh) == ["high"], "\(vh.map { "\($0.k.rawValue) \($0.state)" })")
        at(130); let back = feed(e, 120, -1)
        check("back in range: very high cleared", clears(back) == ["very_high"] && e.active.isEmpty)

        // 2. Stale readings never alert.
        e = engine()
        at(30); e.reading(Reading(date: clock.addingTimeInterval(-16 * 60), mgdl: 300, rate: 0))
        check("reading 16 min old: no alert", log.filter { $0.state == "fire" && $0.k != .noData }.isEmpty)

        // 3. Mute after rapid insulin; it fires when the window ends and it's still high.
        e = engine()
        at(0); insulin = [clock.addingTimeInterval(-5 * 60)]
        check("high 5 min after rapid insulin: muted", fires(feed(e, 200, 0.5)).isEmpty)
        check("…state line says why", e.stateLine == "High · muted: insulin 5 min ago", e.stateLine)
        at(10); check("still muted 15 min after insulin", fires(feed(e, 205, 0.5)).isEmpty)
        at(16); check("window over (21 min) and still high: fires on the timer", fires(tick(e)) == ["high#1"])
        insulin = [clock.addingTimeInterval(-60)]; at(17)
        check("an insulin entry in the future doesn't mute", { insulin = [clock.addingTimeInterval(600)]; return e.muteReason(.high, Reading(date: clock, mgdl: 200, rate: 0)) == nil }())
        e = engine(); at(0); insulin = [clock.addingTimeInterval(-60)]
        check("lows are never muted by insulin", fires(feed(e, 60, -0.5)) == ["low#1"])
        e = engine { $0.muteAfterInsulin.on = false }; at(0); insulin = [clock.addingTimeInterval(-60)]
        check("muteAfterInsulin off: high fires despite insulin", fires(feed(e, 200, 0.5)) == ["high#1"])

        // 4. Falling-high projection.
        e = engine(); at(0)
        check("195 falling 1/min (→175 in 20 min < 185): muted", fires(feed(e, 195, -1)).isEmpty && e.stateLine.contains("muted: falling"), e.stateLine)
        at(1); check("trend flattens (195 − 0.2×20 = 191): un-muted at once", fires(feed(e, 195, -0.2)) == ["high#1"])
        e = engine(); at(0)
        check("very high falling into range: muted too", fires(feed(e, 255, -4)).isEmpty)
        check("…but a rise isn't", fires(feed(e, 255, 0.5)) == ["very_high#1"])

        // 5. Low → urgent low, urgent repeats every 5 min, clear.
        e = engine(); at(0)
        check("64: low", fires(feed(e, 64, -1)) == ["low#1"])
        at(2); let ul = feed(e, 54, -1)
        check("54: urgent low at once (low cleared first)", fires(ul) == ["urgent_low#1"] && clears(ul) == ["low"])
        at(4); check("urgent low +2 min: no repeat", fires(feed(e, 53, 0)).isEmpty)
        at(7); check("urgent low +5 min: re-alert n=2", fires(feed(e, 52, 0)) == ["urgent_low#2"])
        at(12); check("urgent low repeats on the timer with no new reading", fires(tick(e)) == ["urgent_low#3"])
        at(13); let cl = feed(e, 90, 1)
        check("back in range: urgent low cleared with the last firing's id", clears(cl) == ["urgent_low"] && cl.first?.id == log.last(where: { $0.state == "fire" && $0.k == .urgentLow })?.id)
        e = engine { $0[.urgentLow].on = false }; at(0)
        check("urgent low off: 50 is a low", fires(feed(e, 50, 0)) == ["low#1"])
        e = engine { $0[.low].repeatMin = 0 }; at(0); _ = feed(e, 60, 0)
        at(200); check("repeatMin 0: never repeats", fires(feed(e, 60, 0)).isEmpty)

        // 6. Rate alerts (off by default), independent of level; rising fast muted by insulin.
        e = engine(); at(0)
        check("rate alerts off by default", fires(feed(e, 120, -3)).isEmpty)
        e = engine { $0[.fallingFast].on = true; $0[.risingFast].on = true }; at(0)
        check("falling fast at −2.5", fires(feed(e, 120, -2.5)) == ["falling_fast#1"])
        at(1); let rr = feed(e, 120, nil)
        check("no rate → rate alert cleared", clears(rr) == ["falling_fast"])
        at(2); check("high + rising fast: both groups fire", Set(fires(feed(e, 200, 2.5))) == ["high#1", "rising_fast#1"])
        e = engine { $0[.risingFast].on = true }; at(0); insulin = [clock.addingTimeInterval(-120)]
        check("rising fast muted by insulin", fires(feed(e, 150, 3)).isEmpty)

        // 7. No data: timer, cleared by the next reading, repeats.
        e = engine(); at(0); _ = feed(e, 120, 0)
        at(19); check("no reading for 19 min: nothing", tick(e).isEmpty)
        at(20); let nd = tick(e)
        check("20 min: no_data fires (th 20, last reading attached)", fires(nd) == ["no_data#1"] && nd.first?.th == 20 && nd.first?.sgv == 120)
        at(50); check("no_data doesn't repeat before 60 min", tick(e).isEmpty)
        at(80); check("no_data repeats after 60 min", fires(tick(e)) == ["no_data#2"])
        at(81); check("next reading clears no_data", clears(feed(e, 118, 0)) == ["no_data"])
        e = engine(); at(0); e.levelAndRate = false; _ = feed(e, 300, 3)
        check("wearer mode: no level/rate rules here…", log.isEmpty)
        at(25); check("…but no_data still runs locally", fires(tick(e)) == ["no_data#1"])
        e = engine { $0.on = false }; at(0); _ = feed(e, 40, -3); at(60); _ = tick(e)
        check("master switch off: nothing at all", log.isEmpty && e.stateLine == "Alerts are off")

        // 8. Settings JSON (§3): the spec example, partial and junk input keep defaults, same keys out.
        let spec = """
        {"v":1,"on":true,"alerts":{"urgent_low":{"on":true,"mgdl":55,"repeatMin":5},"low":{"on":true,"mgdl":65,"repeatMin":15},
        "high":{"on":true,"mgdl":185,"repeatMin":60},"very_high":{"on":true,"mgdl":250,"repeatMin":30},
        "falling_fast":{"on":false,"rate":2.0,"repeatMin":30},"rising_fast":{"on":false,"rate":2.0,"repeatMin":30},
        "no_data":{"on":true,"min":20,"repeatMin":60}},"muteAfterInsulin":{"on":true,"min":20},
        "muteFallingHigh":{"on":true,"min":20},"sounds":{"pack":"arcade","kinds":{"high":"kart"},"shuffle":true},"source":"wearer"}
        """
        let parsed = try? JSONDecoder().decode(AlertSettings.self, from: Data(spec.utf8))
        var expect = AlertSettings(); expect.sounds.kinds = ["high": "kart"]
        check("SPEC §3 example parses to the defaults (+ kart for high)", parsed == expect)
        let partial = try? JSONDecoder().decode(AlertSettings.self, from: Data(#"{"on":false,"alerts":{"high":{"mgdl":200}},"source":"bogus","sounds":7}"#.utf8))
        check("partial / junk JSON keeps the other defaults", partial.map { !$0.on && $0[.high].threshold == 200 && $0[.high].on && $0[.high].repeatMin == 60
                                                                         && $0.source == "wearer" && $0.sounds.pack == "arcade" && $0[.low] == AlertKind.low.defaultRule } ?? false)
        let out = (try? JSONSerialization.jsonObject(with: Data(AlertSettings().json.utf8))) as? [String: Any] ?? [:]
        let keys = Set(out.keys), ruleKeys = Set(((out["alerts"] as? [String: Any])?["no_data"] as? [String: Any] ?? [:]).keys)
        check("written JSON has the §3 keys", keys == ["v", "on", "alerts", "muteAfterInsulin", "muteFallingHigh", "sounds", "source"]
              && ruleKeys == ["on", "min", "repeatMin"], "\(keys) \(ruleKeys)")
        check("round trip", (try? JSONDecoder().decode(AlertSettings.self, from: Data(expect.json.utf8))) == expect)

        // 9. Sounds: pack, per-kind override, system/none, shuffle off = variant 1, files exist for every combination.
        var s = AlertSettings(); s.sounds = AlertSounds(pack: "quest", kinds: ["high": "kart", "low": "none", "no_data": "system"], shuffle: false)
        check("pack + overrides + no shuffle", Alerts.sound(for: .urgentLow, s).label == "quest_urgent_low_1.caf"
              && Alerts.sound(for: .high, s).label == "kart_high_1.caf" && Alerts.sound(for: .low, s).label == "none"
              && Alerts.sound(for: .noData, s).label == "system")
        s.sounds.shuffle = true
        let variants = Set((0..<60).map { _ in Alerts.sound(for: .veryHigh, s).label })
        check("shuffle picks variants 1–3", variants == ["quest_very_high_1.caf", "quest_very_high_2.caf", "quest_very_high_3.caf"], "\(variants)")
        let missing = AlertSettings.packs.flatMap { p in AlertKind.allCases.flatMap { k in (1...3).map { "\(p)_\(k.rawValue)_\($0).caf" } } }
            .filter { Alerts.soundURL($0) == nil }
        check("all 84 sound files found (bundle or repo)", missing.isEmpty, "missing \(missing.prefix(3))")

        // 10. A wearer's §4 body parses; junk doesn't.
        let body: [String: Any] = ["v": 2, "type": "alert", "id": "0123456789abcdef", "k": "high", "state": "fire", "at": 1760650000,
                                   "t": 1760649990, "sgv": 192, "r": -0.4, "th": 185, "n": 2]
        check("§4 alert body parses", AlertEvent(body: body).map { $0.k == .high && $0.n == 2 && $0.sgv == 192 && $0.th == 185 } ?? false)
        var bad = body; bad["k"] = "meteor"
        check("unknown kind rejected", AlertEvent(body: bad) == nil)

        print(ok ? "alerts self-test OK" : "alerts self-test FAILED")
        return ok
    }
}
