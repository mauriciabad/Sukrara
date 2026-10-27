// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraTesting
import Testing

@Suite struct IdentifierTests {
    @Test func v1Ids() {
        #expect(SukraraIDs.appBundleID == "app.mauri.sukrara")
        #expect(SukraraIDs.widgetBundleID == "app.mauri.sukrara.widget")
        #expect(SukraraIDs.appGroup == "QWUV9H4A3G.app.mauri.sukrara")
        #expect(SukraraIDs.keychainService == "app.mauri.sukrara")
        #expect(SukraraIDs.urlScheme == "sukrara")
        #expect(
            SukraraIDs.WidgetKind.all == [
                "app.mauri.sukrara.widget.value", "app.mauri.sukrara.widget.graph", "app.mauri.sukrara.widget.combined",
            ])
    }
}

@Suite struct PathsTests {
    let paths = SukraraPaths(home: URL(fileURLWithPath: "/Users/u", isDirectory: true))

    @Test func v1Paths() {
        #expect(paths.appSupport.path == "/Users/u/Library/Application Support/app.mauri.sukrara")
        #expect(paths.logs.path == "/Users/u/Library/Logs/app.mauri.sukrara")
        #expect(paths.soundFile(named: "low-1").path == "/Users/u/Library/Sounds/app.mauri.sukrara.low-1.caf")
        #expect(SukraraPaths.appGroupSnapshotName == "glucose.json")
        #expect(SukraraPaths.logLimitBytes == 1_000_000 && SukraraPaths.logFileCount == 2)
    }
}

@Suite struct ReadingCodingTests {
    @Test func encodesEpochMillisecondsWhateverTheEncoderStrategy() throws {
        let reading = Reading(t: Date(timeIntervalSince1970: 1_800_000_000.123), mgdl: 104, rate: -1.5)
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        encoder.dateEncodingStrategy = .iso8601  // must not matter
        let json = String(decoding: try encoder.encode(reading), as: UTF8.self)
        #expect(json == #"{"mgdl":104,"rate":-1.5,"t":1800000000123}"#)
        let back = try JSONDecoder().decode(Reading.self, from: Data(json.utf8))
        #expect(back.mgdl == 104 && back.rate == -1.5)
        #expect(abs(back.t.timeIntervalSince1970 - 1_800_000_000.123) < 0.0005)
    }

    @Test func rateIsOptional() throws {
        let back = try JSONDecoder().decode(Reading.self, from: Data(#"{"t":0,"mgdl":70}"#.utf8))
        #expect(back == Reading(t: Date(timeIntervalSince1970: 0), mgdl: 70))
    }
}

@Suite struct FakeStoreTests {
    @Test func readingsMergeOnePerTimestamp() async throws {
        let store = InMemoryReadingsStore()
        let t0 = Date(timeIntervalSince1970: 1000)
        await store.merge([Reading(t: t0, mgdl: 100), Reading(t: t0.addingTimeInterval(60), mgdl: 110)])
        await store.merge([Reading(t: t0, mgdl: 101)])
        let all = await store.load(since: .distantPast)
        #expect(all.map(\.mgdl) == [101, 110])
        #expect(await store.load(since: t0.addingTimeInterval(1)).map(\.mgdl) == [110])
    }

    @Test func settingsFallBackToTheSpecDefault() {
        let store = InMemorySettingsStore()
        let spec = SettingSpec(key: "graphHours", defaultValue: 12.0)
        #expect(store.value(spec) == 12)
        store.set(6.0, for: spec)
        #expect(store.value(spec) == 6)
    }

    @Test func clockAdvances() {
        let clock = FakeClock(Date(timeIntervalSince1970: 0))
        clock.advance(by: 300)
        #expect(clock.now == Date(timeIntervalSince1970: 300))
    }
}

@Suite struct ContractsSmokeTests {
    @Test func findsTheRepoContractsFolder() {
        #expect(Contracts.exists("README.md"))
        #expect(Contracts.root.lastPathComponent == "contracts")
    }

    @Test func listsAlertScenariosWhenPresent() throws {
        // The scenarios arrive with chunk 05; until then this only checks the lookup.
        guard Contracts.exists("alerts/scenarios") else { return }
        let files = try FileManager.default.contentsOfDirectory(atPath: Contracts.url("alerts/scenarios").path)
        #expect(!files.isEmpty)
    }
}
