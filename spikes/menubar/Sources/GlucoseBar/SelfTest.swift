// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// CLI checks, no network and no Bluetooth:
///   Sukrara -lvfixture ../libreview/fixture.json   payload bytes + record numbers vs lv.py's golden output
///   Sukrara -treatmentsselftest                      merge rule, legacy-link refusals
enum SelfTest {
    private enum RN: Decodable {
        case s(String), n(Int64)
        init(from d: Decoder) throws {
            let c = try d.singleValueContainer()
            if let n = try? c.decode(Int64.self) { self = .n(n) } else { self = .s(try c.decode(String.self)) }
        }
    }

    private struct Fixture: Decodable {
        var zone: String, now: Int64, deviceId: String, token: String
        var treatments: [Treatment]
        var recordNumbers: [[RN]]       // [id, version, recordNumber]
        var expectedPayload: String
    }

    static func fixture(_ path: String) -> Bool {
        guard let data = FileManager.default.contents(atPath: path),
              let f = try? JSONDecoder().decode(Fixture.self, from: data),
              let tz = TimeZone(identifier: f.zone) else { print("cannot read fixture \(path)"); return false }
        var ok = true
        for row in f.recordNumbers {
            guard case .s(let id) = row[0], case .n(let v) = row[1], case .n(let want) = row[2],
                  let t = f.treatments.first(where: { $0.id == id }) else { continue }
            let got = LVPayload.recordNumber(t, version: v)
            if got != want { print("recordNumber \(id)@\(v): got \(got), want \(want)"); ok = false }
        }
        let got = Array(LVPayload.build(f.treatments, deviceId: f.deviceId, token: f.token, now: f.now, tz: tz).utf8)
        let want = Array(f.expectedPayload.utf8)
        if got != want {
            let i = (0..<min(got.count, want.count)).first { got[$0] != want[$0] } ?? min(got.count, want.count)
            func ctx(_ b: [UInt8]) -> String { String(decoding: b[max(0, i - 60)..<min(b.count, i + 60)], as: UTF8.self) }
            print("payload differs at byte \(i) (got \(got.count) B, want \(want.count) B)\n got:  …\(ctx(got))…\n want: …\(ctx(want))…")
            ok = false
        }
        print(ok ? "fixture OK (\(want.count) B payload, \(f.treatments.count) record numbers)" : "fixture FAILED")
        return ok
    }

    static func merge() -> Bool {
        var failures = 0
        func check(_ name: String, _ cond: Bool) { if !cond { print("FAIL \(name)"); failures += 1 } }
        let base = Treatment(id: "a", k: "rapid", a: 2, t: 100, u: 1000, o: "phone", lv: 0)
        var newer = base; newer.a = 3; newer.u = 2000; newer.o = "mac"
        var older = base; older.a = 9; older.u = 500; older.lv = 500
        var tie = base; tie.a = 4; tie.o = "mac"                     // same u: "phone" > "mac", so base wins
        var uploaded = base; uploaded.lv = 1000

        check("insert unknown", Treatment.merge(nil, base) == base)
        check("newer u wins", Treatment.merge(base, newer).a == 3)
        check("older u loses", Treatment.merge(base, older).a == 2)
        check("lv is max even when older wins", Treatment.merge(base, older).lv == 500)
        check("tie broken by o", Treatment.merge(base, tie).a == 2 && Treatment.merge(tie, base).a == 2)
        check("lv-only update", Treatment.merge(base, uploaded) == uploaded && !Treatment.merge(base, uploaded).needsUpload)
        check("lv not lowered", Treatment.merge(uploaded, base).lv == 1000)
        check("same row unchanged", Treatment.merge(base, base) == base)
        check("note truncated to 100 B", Treatment(k: "note", a: 0, t: 0, n: String(repeating: "é", count: 80)).n.utf8.count == 100)
        // The phone refuses entries over the legacy link (SPEC §2 `w`): refused rows stop being pushed, and come back
        // when the phone allows entries again; v2-routed entries are never pushed over the legacy link.
        MainActor.assumeIsolated {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-selftest-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: dir) }
            let st = TreatmentStore(directory: dir)
            let viaLegacy = Treatment(k: "rapid", a: 1, t: 100), viaV2 = Treatment(k: "carbs", a: 10, t: 100)
            st.upsertLocal(viaLegacy, route: "legacy")
            st.upsertLocal(viaV2, legacy: false, route: "v2")
            check("v2 entry not pushed over legacy", st.unacked.map(\.id) == [viaLegacy.id])
            st.setLegacyEntries(false)
            st.legacyRefused(viaLegacy)
            check("refused: not pushed again", st.unacked.isEmpty && st.isLegacyRefused(viaLegacy.id))
            check("refused shown", TreatmentPaths.status(of: viaLegacy, store: st, legacyOn: true, lvOn: false) == "BT ✕ refused")
            st.setLegacyEntries(true)
            check("allowed again: pushed again", st.unacked.map(\.id) == [viaLegacy.id] && !st.isLegacyRefused(viaLegacy.id))
            check("v2 delete stays off legacy", st.delete(viaV2.id, legacy: false) != nil && st.unacked.count == 1)
        }
        print(failures == 0 ? "treatments selftest OK" : "treatments selftest: \(failures) failure(s)")
        return failures == 0
    }
}
