// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraShare
import SukraraTesting
import Testing

/// Juggluco on the home network, without a network: the answers are Juggluco's documented formats.
@Suite struct JugglucoApiTests {
    let now = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func entriesTakeTheRateFromDeltaAndDropWhatIsNoMeasurement() throws {
        let ms = 1_800_000_000_000.0
        let json = """
            [{"date": \(ms - 60_000), "sgv": 120, "delta": 10, "direction": "FortyFiveUp"},
             {"date": \(ms - 120_000), "sgv": 118, "delta": 0, "direction": ""},
             {"date": \(ms - 180_000), "sgv": 0, "delta": 0, "direction": "Flat"},
             {"date": \(ms - 240_000), "sgv": 450, "delta": 5, "direction": "Flat"},
             {"date": \(ms - 300_000), "sgv": 35, "direction": "Flat"},
             {"date": \(ms + 3_600_000), "sgv": 100, "direction": "Flat"}]
            """
        let r = try JugglucoApi.entries(Data(json.utf8), now: now)
        // oldest first, 0 and the future dropped
        #expect(r.map(\.mgdl) == [GlucoseRange.lo, GlucoseRange.hi, 118, 120])
        #expect(r.last?.rate == 2)  // delta is the rate × 5
        #expect(r[2].rate == nil)  // direction "" = no rate
        #expect(r[1].rate == nil)  // HI has no rate
    }

    @Test func entriesWrittenBySukraraAreRefused() {
        let json = #"[{"date": 1800000000000, "sgv": 120, "device": "Sukrara"}]"#
        #expect(throws: JugglucoError.notJuggluco) { try JugglucoApi.entries(Data(json.utf8), now: now) }
        #expect(throws: JugglucoError.badAnswer) { try JugglucoApi.entries(Data("{}".utf8), now: now) }
    }

    @Test func liveEventsAreReadOnlyFromGlucoseEvents() {
        var parser = SseParser()
        let lines = [
            "event: ping", "data: {\"timestamp\": 1799999990, \"glucoseMgDl\": 99}", "",
            "event: glucose", "data: {\"timestamp\": 1799999990, \"glucoseMgDl\": 101, \"rateMgDlPerMinute\": -1.5}",
            "data: {\"timestamp\": 1799999999, \"glucoseMgDl\": 77}",
        ]
        let data = lines.compactMap { parser.feed($0) }
        #expect(data.count == 1)
        let r = JugglucoApi.live(data[0], now: now)
        #expect(r == Reading(t: Date(timeIntervalSince1970: 1_799_999_990), mgdl: 101, rate: -1.5))
    }

    @Test func secretTravelsHashed() {
        #expect(JugglucoApi.headers(secret: "") == [:])
        #expect(JugglucoApi.headers(secret: "abc") == ["api-secret": "a9993e364706816aba3e25717850c26c9cd0d89d"])
    }

    @Test func onlyHomeNetworkAddressesAreContacted() {
        #expect(LanAddress("192.168.1.23")?.isPrivate == true)
        #expect(LanAddress("10.0.0.2")?.isPrivate == true)
        #expect(LanAddress("172.20.1.1")?.isPrivate == true)
        #expect(LanAddress("172.32.1.1")?.isPrivate == false)
        #expect(LanAddress("8.8.8.8")?.isPrivate == false)
        #expect(LanAddress("169.254.1.1")?.isPrivate == false)
        #expect(LanAddress("phone.local") == nil)
        #expect(LanAddress("192.168.1.256") == nil)
        #expect(JugglucoApi.base(LanAddress("8.8.8.8")!) == nil)
        #expect(JugglucoApi.base(LanAddress("192.168.1.23")!)?.absoluteString == "http://192.168.1.23:17580")
    }

    @Test func theSweepCoversTheMacsSmallPrivateNetworksOnly() {
        func i(_ a: String, _ m: String, loopback: Bool = false, up: Bool = true) -> LanInterface {
            LanInterface(address: LanAddress(a)!, netmask: LanAddress(m)!, up: up, loopback: loopback)
        }
        let hosts = LanInterface.hosts([
            i("192.168.1.10", "255.255.255.0"),
            i("127.0.0.1", "255.0.0.0", loopback: true),
            i("169.254.3.4", "255.255.0.0"),
            i("81.2.3.4", "255.255.255.0"),
            i("10.1.0.5", "255.255.0.0"),  // a /16: too big to sweep
            i("172.16.0.9", "255.255.255.252", up: false),
            i("10.9.9.9", "255.255.252.0"),  // a /22: 1022 hosts
        ])
        #expect(hosts.count == 253 + 1021)
        #expect(!hosts.contains(LanAddress("192.168.1.10")!))  // never the Mac itself
        #expect(hosts.contains(LanAddress("192.168.1.1")!) && hosts.contains(LanAddress("192.168.1.254")!))
        #expect(!hosts.contains(LanAddress("192.168.1.255")!))
        #expect(hosts.allSatisfy { $0.isPrivate })
    }
}

/// The source's loop over a fake home network.
@Suite struct JugglucoLanSourceTests {
    static let phone = "192.168.1.7"
    static let mac = [LanInterface(address: LanAddress("192.168.1.10")!, netmask: LanAddress("255.255.255.0")!)]
    let clock = FakeClock()

    static func entries(_ now: Date) -> Data {
        let ms = now.timeIntervalSince1970 * 1000
        return Data(
            """
            [{"date": \(ms - 60_000), "sgv": 120, "delta": 5, "direction": "Flat"},
             {"date": \(ms - 120_000), "sgv": 119, "delta": 5, "direction": "Flat"}]
            """.utf8)
    }

    /// Juggluco at `phone`, asking for `secret` (nil = none).
    static func network(_ now: Date, secret: String? = nil) -> FakeLanHTTP {
        FakeLanHTTP(
            answer: { url, headers in
                guard url.host() == phone else { return nil }
                if let secret, headers["api-secret"] != JugglucoApi.sha1hex(secret) { return (401, Data()) }
                if url.path().hasSuffix("status.json") { return (200, Data(#"{"name": "Juggluco"}"#.utf8)) }
                if url.path().hasSuffix("entries.json") { return (200, entries(now)) }
                return (404, Data())
            },
            live: { url in
                guard url.host() == phone else { return nil }
                let t = Int(now.timeIntervalSince1970)
                return (200, ["event: glucose", "data: {\"timestamp\": \(t), \"glucoseMgDl\": 123}"])
            })
    }

    func source(_ http: FakeLanHTTP, store: InMemoryReadingsStore = InMemoryReadingsStore()) -> JugglucoLanSource {
        JugglucoLanSource(
            http: http, store: store, clock: clock, interfaces: { Self.mac },
            sleep: { _ in try await Task.sleep(for: .seconds(3600)) })
    }

    /// The first snapshot that satisfies `ok` (fails after 5 s).
    func wait(_ source: JugglucoLanSource, _ ok: @escaping @Sendable (LanSnapshot) -> Bool) async -> LanSnapshot? {
        await withTaskGroup(of: LanSnapshot?.self) { group in
            group.addTask {
                for await s in await source.snapshots() where ok(s) { return s }
                return nil
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(5))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    @Test func findsJugglucoOnTheNetworkThenReadsHistoryAndLive() async {
        let http = Self.network(clock.now)
        let store = InMemoryReadingsStore()
        let source = source(http, store: store)
        await source.set(LanConfig())
        let s = await wait(source) { $0.readings.count == 3 }
        #expect(s?.state == .connected(Self.phone))
        #expect(s?.monitoring == true)
        #expect(s?.readings.map(\.mgdl) == [119, 120, 123])
        #expect(await store.load(since: .distantPast).count == 3)  // kept in its own store
        // nothing outside the Mac's networks was asked, and no secret was sent while none is set
        #expect(http.requests.allSatisfy { LanAddress($0.url.host() ?? "")?.isPrivate == true })
        #expect(http.requests.allSatisfy { $0.headers.isEmpty })
        await source.set(nil)
        #expect(await wait(source) { $0.state == .off && !$0.monitoring } != nil)
    }

    @Test func theSecretGoesOnlyToTheHostThatAskedForIt() async {
        let http = Self.network(clock.now, secret: "s3cret")
        let source = source(http)
        await source.set(LanConfig(secret: "s3cret"))
        #expect(await wait(source) { $0.state == .connected(Self.phone) } != nil)
        let withSecret = Set(http.requests.filter { !$0.headers.isEmpty }.compactMap { $0.url.host() })
        #expect(withSecret == [Self.phone])
        await source.set(nil)
    }

    @Test func aWrongSecretSaysSo() async {
        let source = source(Self.network(clock.now, secret: "right"))
        await source.set(LanConfig(address: Self.phone, secret: "wrong"))
        let s = await wait(source) { $0.state == .unauthorized(Self.phone) }
        #expect(s?.monitoring == false)
        await source.set(nil)
    }

    @Test func nothingAnsweringIsNotFound() async {
        let http = FakeLanHTTP(answer: { _, _ in nil })
        let source = source(http)
        await source.set(LanConfig())
        #expect(await wait(source) { $0.state == .notFound } != nil)
        #expect(http.requests.count == 253)
        await source.set(nil)
    }

    @Test func aTypedPublicAddressIsNeverContacted() async {
        let http = Self.network(clock.now)
        let source = source(http)
        await source.set(LanConfig(address: "8.8.8.8"))
        #expect(await wait(source) { $0.state == .refused("8.8.8.8") } != nil)
        await source.set(LanConfig(address: "phone.local"))
        #expect(await wait(source) { $0.state == .refused("phone.local") } != nil)
        #expect(http.requests.isEmpty)
    }

    @Test func aSweepThatFindsOnlyALockedJugglucoAsksForTheSecret() async {
        let source = source(Self.network(clock.now, secret: "right"))
        await source.set(LanConfig())
        #expect(await wait(source) { $0.state == .unauthorized(Self.phone) } != nil)
        await source.set(LanConfig(secret: "wrong"))
        #expect(await wait(source) { $0.state == .unauthorized(Self.phone) } != nil)
        await source.set(nil)
    }

    /// The history asked covers the time since the newest stored reading: a whole first day, a short gap after that.
    @Test func historyAskedCoversTheGap() async {
        func count(_ store: InMemoryReadingsStore) async -> String? {
            let http = Self.network(clock.now)
            let source = source(http, store: store)
            await source.set(LanConfig(address: Self.phone))
            _ = await wait(source) { $0.state == .connected(Self.phone) }
            await source.set(nil)
            let url = http.requests.first { $0.url.path().hasSuffix("entries.json") }?.url
            return url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?
                .queryItems?.first { $0.name == "count" }?.value
        }
        #expect(await count(InMemoryReadingsStore()) == "1600")
        let recent = InMemoryReadingsStore([Reading(t: clock.now.addingTimeInterval(-120), mgdl: 100)])
        #expect(await count(recent) == "180")
        let night = InMemoryReadingsStore([Reading(t: clock.now.addingTimeInterval(-8 * 3600), mgdl: 100)])
        #expect(await count(night) == "490")
    }

    @Test func aDroppedStreamIsUnreachableButStillMonitored() async {
        let http = Self.network(clock.now)
        let source = source(http)
        await source.set(LanConfig(address: Self.phone))
        #expect(await wait(source) { $0.readings.count == 3 } != nil)
        http.dropLive()
        let s = await wait(source) { $0.state == .unreachable(Self.phone) }
        #expect(s?.monitoring == true)  // the no-data alert covers the gap
        await source.set(nil)
    }
}
