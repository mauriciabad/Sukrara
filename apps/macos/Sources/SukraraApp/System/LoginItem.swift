// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import ServiceManagement

/// "Open at login" (`SMAppService.mainApp`). Every state is shown with its way out (Settings › General): on → off;
/// needs approval → Open Login Items…; failed → the reason, try again or Open Login Items…; off / not found → on.
/// Only a launch whose plan allows `loginItem` touches it (a dev build never registers itself).
@Observable
final class LoginItem {
    enum Status: Equatable {
        case on, off
        /// Registered, but the user has to allow it in System Settings › General › Login Items.
        case needsApproval
        /// macOS has no login item for this app (never registered, or this copy isn't the installed app).
        case notFound
        /// The last register / unregister was refused, with the reason in words.
        case failed(String)

        /// The switch: on while registered (also while waiting for approval, so it can be turned off again).
        var isOn: Bool { self == .on || self == .needsApproval }
        /// Whether the row offers Open Login Items… (where the user allows it, or adds it by hand).
        var offersLoginItems: Bool {
            switch self {
            case .needsApproval, .failed: true
            default: false
            }
        }
    }

    private(set) var status: Status = .off
    @ObservationIgnored private let service: any LoginService

    init(service: any LoginService) {
        self.service = service
        refresh()
    }

    /// Re-reads the service (at launch and whenever Sukrara becomes active: the user may have changed it in System
    /// Settings). A failure stays on screen until the service says something definite (on / needs approval).
    func refresh() {
        switch service.status {
        case .enabled: status = .on
        case .requiresApproval: status = .needsApproval
        case .notFound: if case .failed = status {} else { status = .notFound }
        default: if case .failed = status {} else { status = .off }
        }
    }

    func set(_ on: Bool) {
        do {
            if on { try service.register() } else { try service.unregister() }
            status = .off  // this attempt decides: drop an earlier failure
            refresh()
            if on, !status.isOn {
                status = .failed(
                    L10n.t(
                        "macOS didn't add Sukrara to the login items. Move Sukrara to the Applications folder and try again."
                    ))
            }
        } catch {
            status = .failed(error.localizedDescription)
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
