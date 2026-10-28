// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraAlerts
import SukraraCore
import Testing

@testable import SukraraApp

@Suite struct MirroringExitTests {
    func model() -> SettingsModel {
        let w = WearerSettings(
            json: #"{"alerts":{"alerts":{"low":{"repeatMin":30}},"muteAfterInsulin":{"on":false,"min":90}}}"#,
            wearer: "Ana")
        return SettingsModel(
            store: scratchStore(), follow: FollowModel(status: .notFollowing, actions: nil, macName: "Mac"), wearer: w,
            version: "0")
    }

    @Test func useOwnSwitchesTheAlertSourceToOwnAndBack() {
        let m = model()
        #expect(m.mirrors)
        m.useOwn()
        #expect(!m.mirrors && m.alerts.settings.source == .own)
        m.settingsFrom = .wearer
        #expect(m.alerts.settings.source == .wearer)
    }

    @Test func defaultsKeepsTheOwnSource() {
        let m = model()
        m.useDefaults()
        #expect(m.settingsFrom == .default && m.alerts.settings.source == .own)
    }

    @Test func mirroredAlertRowsReadTheWearersValues() {
        let m = model()
        #expect(m.wearerNumber("alerts.kind.repeat", kind: .low) == 30)
        #expect(m.wearerBool("alerts.muteAfterInsulin.on") == false)
        #expect(m.wearerNumber("alerts.muteAfterInsulin.min") == 90)
        m.useOwn()
        #expect(m.wearerNumber("alerts.kind.repeat", kind: .low) == nil)
    }
}

@Suite struct FailedDeleteTests {
    @Test func aTombstoneThatIsntSentKeepsTheEntry() async {
        let sender = RecordingSender()
        sender.accept = false
        let t = Treatment(id: "a", k: .rapid, a: 4, t: 1000, u: 1, o: .mac)
        let m = TreatmentsModel(rows: [.init(treatment: t, delivery: .accepted)], canWrite: true, actions: sender)
        await m.delete(m.rows[0])
        #expect(m.rows.map(\.id) == ["a"] && !m.undoAvailable)
    }
}
