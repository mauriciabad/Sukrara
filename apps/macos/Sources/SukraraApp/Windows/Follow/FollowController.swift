// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import SukraraShare

/// Why following didn't start (`FollowerJoinError`, mapped by the runtime adapter).
public enum FollowFailure: Error, Equatable, Sendable {
    case invalidLink, notFound, expired, used, offline, fingerprintMismatch, alreadyFollowing, keychain, noQRCode

    /// The text the follow window shows; every one says what to do next.
    public var message: String {
        switch self {
        case .invalidLink: L10n.t("That isn't a Sukrara invite link. Copy the whole link from the phone and try again.")
        case .notFound: L10n.t("This invite wasn't found. Ask for a new invite on the phone.")
        case .expired: L10n.t("This invite has expired. Ask for a new invite on the phone.")
        case .used: L10n.t("Another device already used this invite. Ask for a new invite on the phone.")
        case .offline: L10n.t("Can't reach the relays. Check the internet connection and try again.")
        case .fingerprintMismatch: L10n.t("This invite doesn't match the link. Ask for a new invite on the phone.")
        case .alreadyFollowing:
            L10n.t("This Mac already follows someone. Stop following in Settings › General first, then open the link again.")
        case .keychain: L10n.t("Sukrara couldn't save the follow key in the Keychain. Try again.")
        case .noQRCode: L10n.t("No Sukrara invite QR code found in that image.")
        }
    }
}

/// The link with the wearer as the windows show it (`LinkState`, mapped by the adapter).
public enum FollowStatus: Equatable, Sendable {
    case notFollowing
    case joining
    case waiting(wearer: String?, code: String?)
    case following(wearer: String?, lastReading: Date?, canWrite: Bool)
    case declined, inviteUsed, revoked
    case broken(String)
}

/// A phone nearby offering pairing (`NearbyOffer`).
public struct NearbyPhone: Equatable, Sendable {
    public var wearer: String
    public var avatarInitial: String { String(wearer.prefix(1)).uppercased() }
    public init(wearer: String) { self.wearer = wearer }
}

/// What the follow UI needs from the follower runtime (`FollowerService`, behind an adapter).
public protocol FollowActions: AnyObject {
    func join(link: String) async throws(FollowFailure)
    func joinNearby(name: String) async throws(FollowFailure)
    func cancelJoin() async
    func unfollow() async
}

/// The follow UI's state: the link, the text typed or pasted, the error shown, and the nearby offer.
@Observable
public final class FollowModel {
    public var status: FollowStatus
    public var linkText = ""
    public private(set) var failure: FollowFailure?
    public var nearby: NearbyPhone?
    /// The name the wearer sees for this Mac (nearby pairing): the computer's name by default.
    public var macName: String
    /// Unfollow asks first ("the wearer has to invite again").
    public var confirmingUnfollow = false
    @ObservationIgnored private weak var actions: (any FollowActions)?
    /// Look for phones offering nearby pairing (on while a follow step is on screen and not following).
    @ObservationIgnored public var searchNearby: (Bool) -> Void = { _ in }

    public init(status: FollowStatus, actions: (any FollowActions)?, macName: String = Host.current().localizedName ?? "Mac") {
        self.status = status
        self.actions = actions
        self.macName = macName
    }

    /// Paste or `sukrara:` link: nothing goes to the relays until the text parses as an invite.
    public func follow() async {
        failure = nil
        let link = linkText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard InviteLink.parse(link) != nil else {
            failure = .invalidLink
            return
        }
        // following or waiting: a new link changes nothing and the status stays what it is
        guard canFollow else {
            failure = .alreadyFollowing
            return
        }
        let before = status
        status = .joining
        do {
            try await actions?.join(link: link)
        } catch {
            failure = error
            status = error == .alreadyFollowing ? before : .notFollowing
        }
    }

    /// The QR image's text (QRImport) goes the same way as a pasted link.
    public func follow(qrText: String?) async {
        guard let qrText else {
            failure = .noQRCode
            return
        }
        linkText = qrText
        await follow()
    }

    public func askNearby() async {
        failure = nil
        guard let phone = nearby else { return }
        status = .waiting(wearer: phone.wearer, code: nil)
        do {
            try await actions?.joinNearby(name: macName)
        } catch {
            failure = error
            status = .notFollowing
        }
    }

    public func cancel() async {
        await actions?.cancelJoin()
        status = .notFollowing
    }

    public func unfollow() async {
        confirmingUnfollow = false
        await actions?.unfollow()
        status = .notFollowing
        linkText = ""
    }

    public func clearFailure() { failure = nil }

    /// Nobody followed and nothing on its way: the states where a nearby phone may be offered.
    public var canFollow: Bool {
        switch status {
        case .notFollowing, .declined, .inviteUsed, .revoked, .broken: true
        case .joining, .waiting, .following: false
        }
    }
}
