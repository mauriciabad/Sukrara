// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore
import SukraraShare

/// The windows' follow and treatment actions over the follower runtime (`FollowerService`), and the runtime's
/// snapshot in the windows' words. The only place the window layer meets SukraraShare's types.
final class FollowerAdapter: FollowActions, TreatmentActions {
    private let follower: any FollowerService
    /// The phone offering nearby pairing in the newest snapshot ("Ask to follow" joins this one).
    private var offer: NearbyOffer?

    init(follower: any FollowerService) { self.follower = follower }

    func join(link: String) async throws(FollowFailure) {
        do {
            try await follower.join(link: link)
        } catch {
            if let failure = Self.failure(error) { throw failure }
        }
    }

    /// The runtime names this Mac with the profile it was made with; the edited name isn't sent (V1).
    func joinNearby(name: String) async throws(FollowFailure) {
        guard let offer else { throw .notFound }
        do {
            try await follower.joinNearby(offer)
        } catch {
            if let failure = Self.failure(error) { throw failure }
        }
    }

    func cancelJoin() async { await follower.cancelJoin() }
    func unfollow() async { await follower.unfollow() }

    func send(_ treatment: Treatment) async -> Bool { await follower.send(treatment) == .sending }

    /// Looks for phones offering nearby pairing while the follow step is on screen.
    func searchNearby(_ on: Bool) { Task { await follower.searchNearby(on) } }

    /// Keeps the newest nearby offer (the follow model shows it; joining uses it).
    func update(_ snapshot: FollowerSnapshot) { offer = snapshot.nearbyOffer }

    // MARK: mapping

    /// nil = nothing to show (the user cancelled).
    static func failure(_ error: FollowerJoinError) -> FollowFailure? {
        switch error {
        case .invalidLink: .invalidLink
        case .notFound: .notFound
        case .expired: .expired
        case .fingerprintMismatch: .fingerprintMismatch
        case .alreadyFollowing: .alreadyFollowing
        case .keychain, .keyFailed: .keychain
        case .cancelled: nil
        }
    }

    static func status(_ snapshot: FollowerSnapshot) -> FollowStatus {
        switch snapshot.link {
        case .notFollowing: .notFollowing
        case .joining: .joining
        case .requested(let code, let wearer, let nearby): .waiting(wearer: wearer, code: nearby ? code : nil)
        case .following(let wearer, let canWrite):
            .following(wearer: wearer, lastReading: snapshot.readings.last?.t, canWrite: canWrite)
        case .declined: .declined
        case .inviteUsed: .inviteUsed
        case .revoked: .revoked
        case .broken(let why): .broken(why)
        }
    }

    static func wearerName(_ link: LinkState) -> String? {
        switch link {
        case .requested(_, let wearer, _), .following(let wearer, _): wearer
        default: nil
        }
    }

    static func delivery(_ d: TreatmentDelivery?) -> TreatmentDeliveryState? {
        switch d {
        case .sending: .sending
        case .sent: .sent
        case .accepted: .accepted
        case nil: nil
        }
    }
}
