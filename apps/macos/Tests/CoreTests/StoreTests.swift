// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraTesting
import Testing

struct StoreTests {
    let directory = FileManager.default.temporaryDirectory.appending(path: "sukrara-stores-\(UUID().uuidString)")
    let clock = FakeClock(Date(timeIntervalSince1970: 1_800_000_000))

    func reading(_ minutesAgo: Int, _ mgdl: Int, rate: Double? = nil) -> Reading {
        Reading(t: clock.now.addingTimeInterval(Double(-minutesAgo * 60)), mgdl: mgdl, rate: rate)
    }

    var csv: URL { directory.appending(path: ReadingsFileStore.fileName) }

    @Test func readingsRoundTripAppendAndFirstPerTimeKept() async throws {
        let store = ReadingsFileStore(directory: directory, clock: clock)
        try await store.merge([reading(2, 120, rate: 0.5), reading(1, 125)])
        try await store.merge([reading(0, 130)])  // append path
        try await store.merge([reading(1, 999)])  // same t: the first stays
        let reopened = ReadingsFileStore(directory: directory, clock: clock)
        let all = try await reopened.load(since: .distantPast)
        #expect(all.map(\.mgdl) == [120, 125, 130])
        #expect(all.first?.rate == 0.5 && all.last?.rate == nil)
        let text = try String(contentsOf: csv, encoding: .utf8)
        #expect(text.hasPrefix("t_ms,mgdl,rate\n") && text.hasSuffix("\n"))
        #expect(await reopened.issue == nil)
    }

    @Test func olderReadingsAreInsertedInOrder() async throws {
        let store = ReadingsFileStore(directory: directory, clock: clock)
        try await store.merge([reading(0, 130)])
        try await store.merge([reading(10, 110)])  // backfill: rewrite path
        #expect(
            try await ReadingsFileStore(directory: directory, clock: clock).load(since: .distantPast).map(\.mgdl)
                == [110, 130])
    }

    @Test func retentionDropsReadingsOlderThan31Days() async throws {
        let store = ReadingsFileStore(directory: directory, clock: clock)
        try await store.merge([reading(60 * 24 * 30, 100), reading(0, 130)])
        clock.advance(by: 2 * 86_400)
        let later = ReadingsFileStore(directory: directory, clock: clock)
        #expect(try await later.load(since: .distantPast).map(\.mgdl) == [130])
        try await later.merge([reading(60 * 24 * 40, 90)])  // too old to keep
        #expect(try await later.load(since: .distantPast).count == 1)
    }

    @Test func truncatedLastLineIsDroppedQuietly() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try "t_ms,mgdl,rate\n1800000000000,120,\n18000000600".write(to: csv, atomically: true, encoding: .utf8)
        let store = ReadingsFileStore(directory: directory, clock: clock)
        #expect(try await store.load(since: .distantPast).map(\.mgdl) == [120])
        #expect(await store.issue == nil)
    }

    @Test func corruptLinesQuarantineTheFileAndKeepTheGoodOnes() async throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try "t_ms,mgdl,rate\n1799999940000,118,\ngarbage\n1800000000000,120,0.1\n".write(
            to: csv, atomically: true, encoding: .utf8)
        let store = ReadingsFileStore(directory: directory, clock: clock)
        #expect(try await store.load(since: .distantPast).map(\.mgdl) == [118, 120])
        let issue = try #require(await store.issue)
        #expect(issue.file == "readings.csv")
        #expect(FileManager.default.fileExists(atPath: try #require(issue.quarantined).path))
        #expect(!(try String(contentsOf: csv, encoding: .utf8)).contains("garbage"))
    }

    @Test func removeAllForgetsEverything() async throws {
        let store = ReadingsFileStore(directory: directory, clock: clock)
        try await store.merge([reading(0, 130)])
        try await store.removeAll()
        #expect(try await ReadingsFileStore(directory: directory, clock: clock).load(since: .distantPast).isEmpty)
    }

    @Test func treatmentsRoundTripAndCorruption() async throws {
        let store = TreatmentFileStore(directory: directory, clock: clock)
        let entry = TreatmentLog.Entry(
            treatment: Treatment(
                id: "a", kind: "rapid", amount: 2, t: 1_800_000_000, note: "", u: 1, deleted: false, origin: "follower"),
            creator: "f", delivery: .sending, sentEventId: "e")
        try await store.save(TreatmentLog(entries: ["a": entry]))
        let reopened = TreatmentFileStore(directory: directory, clock: clock)
        #expect(await reopened.load().entries["a"] == entry)
        #expect(await reopened.load().unsentCount == 1)

        try "{not json".write(to: await store.url, atomically: true, encoding: .utf8)
        let broken = TreatmentFileStore(directory: directory, clock: clock)
        #expect(await broken.load().entries.isEmpty)
        #expect(await broken.issue?.quarantined != nil)
    }

    @Test func jsonFileStoreRoundTripAndAbsent() async throws {
        let store = SensorStateStore(url: directory.appending(path: "sensor.json"), clock: clock)
        #expect(await store.load() == nil)
        let sensor = WearerSensor(start: 1, end: 2, warmupEnd: 3)
        try await store.save(sensor)
        #expect(await SensorStateStore(url: directory.appending(path: "sensor.json")).load() == sensor)
        try await store.save(nil)
        #expect(await store.load() == nil)
    }
}
