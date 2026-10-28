// Sukrara — https://github.com/mauriciabad/sukrara
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
    /// This Mac's profile in the newest snapshot.
    private var me: Profile?

    init(follower: any FollowerService) { self.follower = follower }

    func join(link: String) async throws(FollowFailure) {
        do {
            try await follower.join(link: link)
        } catch {
            if let failure = Self.failure(error) { throw failure }
        }
    }

    /// The typed name goes with the request (the runtime keeps the picture it has).
    func joinNearby(name: String) async throws(FollowFailure) {
        guard let offer else { throw .notFound }
        await follower.setProfile(name: name, avatar: me?.avatar)
        do {
            try await follower.joinNearby(offer)
        } catch {
            if let failure = Self.failure(error) { throw failure }
        }
    }

    func setProfile(name: String, avatar: Avatar?) async { await follower.setProfile(name: name, avatar: avatar) }

    func cancelJoin() async { await follower.cancelJoin() }
    func unfollow() async { await follower.unfollow() }

    func send(_ treatment: Treatment) async -> Bool { await follower.send(treatment) == .sending }

    /// Looks for phones offering nearby pairing while the follow step is on screen.
    func searchNearby(_ on: Bool) { Task { await follower.searchNearby(on) } }

    /// Keeps the newest nearby offer (the follow model shows it; joining uses it).
    func update(_ snapshot: FollowerSnapshot) {
        offer = snapshot.nearbyOffer
        me = snapshot.me
    }

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

    /// How the readings arrive while following: always over the relays, and the Bluetooth path's state when it runs.
    static func source(_ snapshot: FollowerSnapshot) -> String? {
        guard snapshot.link.isFollowing else { return nil }
        let internet = L10n.t("Over the internet (relays)")
        switch snapshot.nearby {
        case nil: return internet
        case .off: return internet + " · " + L10n.t("Bluetooth off")
        case .unauthorized: return internet + " · " + L10n.t("Bluetooth not allowed")
        case .searching: return internet + " · " + L10n.t("Looking for the phone over Bluetooth")
        case .connected(let phone): return internet + " · " + L10n.t("Bluetooth: connected to \(phone)")
        }
    }

    /// The newest problem worth a line under the source, with what to do (nil = none).
    static func problem(_ snapshot: FollowerSnapshot) -> String? {
        if let issue = snapshot.storeIssues.last {
            return L10n.t("\(issue.file) couldn't be read and was moved aside; Sukrara started it again.")
        }
        if snapshot.link.isFollowing, snapshot.nearby == .unauthorized {
            return L10n.t(
                "Sukrara may not use Bluetooth: allow it in System Settings › Privacy & Security › Bluetooth to get readings from the phone nearby."
            )
        }
        return nil
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
