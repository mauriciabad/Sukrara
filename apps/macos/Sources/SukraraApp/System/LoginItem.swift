// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import ServiceManagement

/// "Open at login" (`SMAppService.mainApp`). The switch always shows what macOS says (`status`); a refused change is
/// shown next to it (`lastError`), never instead of it. Ways out (Settings › General): on → off; needs approval →
/// Open Login Items…; an error → try again or Open Login Items…. Only a launch whose plan allows `loginItem` touches
/// it (a dev build never registers itself).
@Observable
final class LoginItem {
    enum Status: Equatable {
        /// Not a login item (`.notRegistered`; for the main app `.notFound` means the same: never registered).
        case off
        case on
        /// Registered, but the user has to allow it in System Settings › General › Login Items.
        case needsApproval

        /// The switch: on while registered (also while waiting for approval, so it can be turned off again).
        var isOn: Bool { self != .off }
    }

    private(set) var status: Status = .off
    /// The last change macOS refused or didn't take, in words; cleared by the next change that works.
    private(set) var lastError: String?
    @ObservationIgnored private let service: any LoginService

    init(service: any LoginService) {
        self.service = service
        refresh()
    }

    /// Whether the row offers Open Login Items… (where the user allows it, or adds it by hand).
    var offersLoginItems: Bool { status == .needsApproval || lastError != nil }

    /// Re-reads macOS (at launch and whenever Sukrara becomes active: the user may have changed it in System
    /// Settings). An error stays until the state macOS reports is the one that was asked for.
    func refresh() {
        status =
            switch service.status {
            case .enabled: .on
            case .requiresApproval: .needsApproval
            default: .off
            }
    }

    func set(_ on: Bool) {
        var failure: String?
        do {
            if on { try service.register() } else { try service.unregister() }
        } catch {
            failure = error.localizedDescription
        }
        // register() can throw while still creating the item awaiting approval: macOS's state decides, not the throw
        refresh()
        if status.isOn == on {
            lastError = nil
        } else {
            lastError =
                failure
                ?? L10n.t(
                    "macOS didn't add Sukrara to the login items. Move Sukrara to the Applications folder and try again."
                )
        }
    }

    func openSettings() { SMAppService.openSystemSettingsLoginItems() }
}

/// `SMAppService` behind a protocol (tests and snapshots fake it; the app registers only from a real install).
protocol LoginService {
    var status: SMAppService.Status { get }
    func register() throws
    func unregister() throws
}

extension SMAppService: LoginService {}
