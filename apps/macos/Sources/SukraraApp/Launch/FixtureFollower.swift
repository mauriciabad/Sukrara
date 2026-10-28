// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
#if DEBUG
    import Foundation
    import SukraraCore
    import SukraraGraph
    import SukraraShare

    /// The follower of a `.fixture` launch (debug builds only): a made-up wearer "Fixture" with a seeded synthetic day,
    /// a sensor mid-life and two treatments. No socket, no Keychain, no file: the snapshot is built in memory, so the
    /// bundle smoke test can check with `lsof` that nothing opens a connection.
    actor FixtureFollower: FollowerService {
        private let snapshot: FollowerSnapshot

        init(now: Date = Date()) {
            let t = now.timeIntervalSince1970
            let readings = RealisticSynthetic.readings(until: t - 60, days: 1).map {
                Reading(t: Date(timeIntervalSince1970: $0.t), mgdl: Int($0.v.rounded()), rate: $0.rate)
            }
            let start = Int(t) - 6 * 86400
            var snapshot = FollowerSnapshot.empty
            snapshot.link = .following(wearer: "Fixture", canWrite: true)
            snapshot.readings = readings
            snapshot.sensor = WearerSensor(start: start, end: start + 14 * 86400, warmupEnd: start + 3600)
            snapshot.treatments = [
                Treatment(id: "fixture-1", k: .rapid, a: 4.5, t: Int64(t) - 1500, u: 1, o: .phone),
                Treatment(id: "fixture-2", k: .carbs, a: 45, t: Int64(t) - 1320, u: 1, o: .mac),
            ]
            snapshot.deliveries = ["fixture-2": .accepted]
            self.snapshot = snapshot
        }

        func snapshots() async -> AsyncStream<FollowerSnapshot> {
            let (stream, continuation) = AsyncStream.makeStream(of: FollowerSnapshot.self)
            continuation.yield(snapshot)
            return stream
        }

        func alerts() async -> AsyncStream<AlertPayload> { AsyncStream { _ in } }
        func start() async {}
        func stop() async {}
        func reconnect() async {}
        func join(link: String) async throws(FollowerJoinError) { throw .alreadyFollowing }
        func cancelJoin() async {}
        func joinNearby(_ offer: NearbyOffer) async throws(FollowerJoinError) { throw .alreadyFollowing }
        func searchNearby(_ on: Bool) async {}
        func unfollow() async {}
        func send(_ treatment: Treatment) async -> TreatmentSendResult { .notAllowed }
    }
#endif
