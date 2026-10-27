// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// Whether this Mac follows a wearer, for the menu and the follow window. Every state has a visible exit:
/// `notFollowing` → join; `joining` → cancel; `requested` → cancel request (= stop following); `following` → stop
/// following; `declined` / `inviteUsed` / `revoked` / `broken` → stop following, then join again.
public enum LinkState: Sendable, Equatable {
    /// Not following anyone (first run, or after "Stop following").
    case notFollowing
    /// Looking up the invite on the relays.
    case joining
    /// Access asked; the wearer allows it on their phone (nearby: by typing `code`).
    case requested(code: String, wearer: String?, nearby: Bool)
    /// Following. `canWrite`: the wearer allowed this Mac to add treatments.
    case following(wearer: String?, canWrite: Bool)
    /// The wearer said no.
    case declined
    /// Another device used this invite first.
    case inviteUsed
    /// The wearer stopped sharing with this Mac.
    case revoked
    /// Following can't continue: the reason in words (Keychain unreadable, state damaged).
    case broken(String)

    /// Readings may be shown as the wearer's (only while following).
    public var isFollowing: Bool {
        if case .following = self { return true }
        return false
    }
}
