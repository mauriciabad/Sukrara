// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// CLI checks, no network and no Bluetooth:
///   Sukrara -treatmentsselftest   merge rule, the local store
enum SelfTest {
    static func merge() -> Bool {
        var failures = 0
        func check(_ name: String, _ cond: Bool) { if !cond { print("FAIL \(name)"); failures += 1 } }
        let base = Treatment(id: "a", k: "rapid", a: 2, t: 100, u: 1000, o: "phone")
        var newer = base; newer.a = 3; newer.u = 2000; newer.o = "mac"
        var older = base; older.a = 9; older.u = 500
        var tie = base; tie.a = 4; tie.o = "mac"                     // same u: "phone" > "mac", so base wins

        check("insert unknown", Treatment.merge(nil, base) == base)
        check("newer u wins", Treatment.merge(base, newer).a == 3)
        check("older u loses", Treatment.merge(base, older).a == 2)
        check("tie broken by o", Treatment.merge(base, tie).a == 2 && Treatment.merge(tie, base).a == 2)
        check("same row unchanged", Treatment.merge(base, base) == base)
        check("note truncated to 100 B", Treatment(k: "note", a: 0, t: 0, n: String(repeating: "é", count: 80)).n.utf8.count == 100)
        MainActor.assumeIsolated {
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sukrara-selftest-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: dir) }
            let st = TreatmentStore(directory: dir)
            let mine = Treatment(k: "carbs", a: 10, t: 100)
            st.upsertLocal(mine)
            check("delete leaves a newer tombstone", st.delete(mine.id).map { $0.del && $0.u > mine.u } ?? false)
            check("older copy from v2 doesn't undo the delete", !st.receive(mine) && st.row(mine.id)?.del == true)
            check("persisted", TreatmentStore(directory: dir).row(mine.id)?.del == true)
        }
        print(failures == 0 ? "treatments selftest OK" : "treatments selftest: \(failures) failure(s)")
        return failures == 0
    }
}
