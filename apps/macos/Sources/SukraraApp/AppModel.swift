// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Observation
import SukraraAlerts
import SukraraCore
import SukraraGraph
import SukraraShare

/// The state every surface of the menu bar app renders. One instance, owned by `AppDelegate`; services write it,
/// views read it.
@Observable
public final class AppModel {
    public let plan: LaunchPlan
    /// The current card data, nil until the first reading.
    public var glucose: GlucoseSnapshot?
    public var alerts: AlertUIState = .idle
    public var link: LinkState = .notFollowing

    public init(plan: LaunchPlan) {
        self.plan = plan
    }

    /// The menu bar title, from the one card renderer.
    public var statusTitle: String { GlucoseCard.face(glucose, surface: .menubarTitle) }

    /// Why there is no value, in words (the menu's first line and the status item's VoiceOver label), or nil when
    /// a value is shown.
    public var notMonitoringReason: String? {
        if plan.isOffline { return "Not monitoring: offline dev build (no sensor, relays or alerts)" }
        guard glucose?.latest == nil else { return nil }
        switch link {
        case .notFollowing, .joining: return "Not monitoring: not following anyone yet"
        case .requested: return "Not monitoring: waiting for the wearer to allow this Mac"
        case .following: return "Not monitoring: no reading from the wearer yet"
        case .declined: return "Not monitoring: the wearer declined"
        case .inviteUsed: return "Not monitoring: the invite was used by another device"
        case .revoked: return "Not monitoring: the wearer stopped sharing"
        case .broken(let why): return "Not monitoring: \(why)"
        }
    }
}
