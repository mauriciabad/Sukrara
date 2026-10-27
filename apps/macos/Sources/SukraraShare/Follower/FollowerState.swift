// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation

/// Where this follower stands with the wearer. Every state has an exit: unfollow (discard the state) or join again.
public enum LinkStatus: String, Codable, Sendable {
    /// Invite opened and the follower key made; the access request isn't sent yet.
    case new
    /// Access request sent; waiting for the wearer's Allow / Deny.
    case requested
    /// A key arrived: following.
    case active
    /// The wearer removed this follower.
    case revoked
    /// Another device's request claimed the invite first.
    case used
    /// The wearer denied the request.
    case declined
}

/// Persistent follower state, V1 format (`v` 1). Holds the follower secret and the read keys: stored only in the
/// Keychain (service `app.mauri.sukrara`, account `follower`, chunk 15). Readings and treatments themselves are not
/// here (the session hands them out as deltas); what is here is everything trust decisions depend on.
public struct FollowerState: Codable, Equatable, Sendable {
    public static let formatVersion = 1
    /// Recent alert keys kept for dedupe across restarts (a BLE backlog repeats the last alerts).
    static let alertKeysKept = 64

    public struct Epoch: Codable, Equatable, Sendable {
        public let n: Int
        public let g: SecretKeyHex

        public init(n: Int, g: SecretKeyHex) {
            self.n = n
            self.g = g
        }
    }

    /// Who created a treatment id (the first accepted signer) and which version this follower holds.
    public struct TreatmentRecord: Codable, Equatable, Sendable {
        public var creator: String
        public var u: Int
        public var origin: String
    }

    public var v: Int = Self.formatVersion
    public var follower: SecretKeyHex
    public var identity: String
    public var token: String
    public var code: String
    /// Joined over Bluetooth: the wearer types `code` to allow.
    public var nearby: Bool
    /// How this follower appears to the wearer.
    public var me: Profile
    /// The person followed (invite, then each roster).
    public var wearer: Profile?
    public var relays: [String]
    public var status: LinkStatus = .new
    public var requestedAt: Int?
    public var sid: String?
    public var epochs: [Epoch] = []
    public var devices: [String] = []
    public var writers: [String] = []
    public var rosterEpoch = -1
    public var rosterCreatedAt = -1
    public var sensorCreatedAt = -1
    public var settingsU: Int?
    public var treatments: [String: TreatmentRecord] = [:]
    public var alertKeys: [String] = []

    public init(
        follower: SecretKeyHex, identity: String, token: String, code: String, nearby: Bool, me: Profile,
        wearer: Profile?, relays: [String]
    ) {
        self.follower = follower
        self.identity = identity
        self.token = token
        self.code = code
        self.nearby = nearby
        self.me = me
        self.wearer = wearer
        self.relays = relays
    }

    public static func decode(_ data: Data) throws -> FollowerState {
        let state = try JSONDecoder().decode(FollowerState.self, from: data)
        guard state.v == formatVersion else {
            throw DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: "unsupported follower state v"))
        }
        return state
    }

    public func encoded() throws -> Data { try JSONEncoder().encode(self) }
}
