// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import SukraraCore

/// What the menu, panel, widget feed and follow window see of the follower (one value, replaced on every change).
public struct FollowerSnapshot: Sendable, Equatable {
    public var link: LinkState
    /// The last 24 hours of readings, oldest first.
    public var readings: [Reading]
    public var sensor: WearerSensor?
    /// Visible treatments, newest first.
    public var treatments: [Treatment]
    /// Treatments this Mac wrote that no relay confirmed yet (warn before "Stop following").
    public var unsentTreatments: Int
    public var wearerSettings: MirroredSettings?
    /// Files that were unreadable and moved aside.
    public var storeIssues: [StoreIssue]
    /// nil when the Bluetooth path isn't running.
    public var nearby: NearbyState?
    /// A phone nearby offering to pair (while not following).
    public var nearbyOffer: NearbyOffer?

    public static let empty = FollowerSnapshot(
        link: .notFollowing, readings: [], sensor: nil, treatments: [], unsentTreatments: 0, wearerSettings: nil,
        storeIssues: [], nearby: nil, nearbyOffer: nil)
}

/// "Pair nearby" open on a wearer's phone in Bluetooth range.
public struct NearbyOffer: Sendable, Equatable {
    public let invite: Invite
    public var wearerName: String? { invite.profile?.name }
}

public enum FollowerJoinError: Error, Equatable, Sendable {
    /// The text isn't a Sukrara invite link.
    case invalidLink
    /// No invite for this link reached the relays in time (the phone may be offline, or the link was replaced).
    case notFound
    case expired
    /// The invite was sealed by someone else than the link names.
    case fingerprintMismatch
    case alreadyFollowing
    case cancelled
    /// The follower key couldn't be stored in the Keychain.
    case keychain
    /// This Mac's follower key couldn't be made.
    case keyFailed
}

public enum TreatmentSendResult: Equatable, Sendable {
    /// Signed and on its way (delivery shown per entry: sending → sent → accepted).
    case sending
    /// The wearer hasn't allowed this Mac to add treatments (or this Mac isn't following).
    case notAllowed
}

/// The follower runtime as the app sees it (`FollowerRuntime`; 21's AppModel and 23's follow window code against
/// this). Every call is safe in every state.
public protocol FollowerService: Sendable {
    /// Each call is one subscriber: the current snapshot first, then every change (only the newest is buffered).
    func snapshots() async -> AsyncStream<FollowerSnapshot>
    /// Alerts the wearer's phone mirrors (PROTOCOL-v2 "Alerts"), deduped across restarts. Not buffered for absent
    /// subscribers: subscribe before `start()`.
    func alerts() async -> AsyncStream<AlertPayload>
    func start() async
    /// Terminal: closes every connection and saves the state.
    func stop() async
    /// The Mac woke or the network came back: reconnect now instead of waiting for the watchdog.
    func reconnect() async
    /// Follows the wearer behind an invite link (web link, `sukrara:` link or a QR code's text).
    func join(link: String) async throws(FollowerJoinError)
    func cancelJoin() async
    /// Follows the wearer whose phone offers nearby pairing.
    func joinNearby(_ offer: NearbyOffer) async throws(FollowerJoinError)
    /// Looks for phones offering nearby pairing while not following (the follow window is open).
    func searchNearby(_ on: Bool) async
    /// Stops following: wipes the wearer's readings, treatments, sensor and settings, then the follower key.
    func unfollow() async
    func send(_ treatment: Treatment) async -> TreatmentSendResult
}

/// A relay connection the runtime can start and stop (`RelayPool`; tests use `FakeRelay`).
public protocol RelayLink: RelayTransport {
    func start() async
    func stop() async
}

extension RelayPool: RelayLink {}
