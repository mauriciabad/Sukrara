// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import Observation
import ServiceManagement

/// "Open at login" (`SMAppService.mainApp`). Every state has a way out: on → off; needs approval → Open Login Items
/// settings; off → on. Only a launch whose plan allows `loginItem` touches it (a dev build never registers itself).
@Observable
final class LoginItem {
    enum Status: Equatable {
        case on, off
        /// Registered, but the user has to allow it in System Settings > General > Login Items.
        case needsApproval
        case failed(String)
    }

    private(set) var status: Status = .off
    @ObservationIgnored private let service: any LoginService

    init(service: any LoginService) {
        self.service = service
        refresh()
    }

    func refresh() {
        switch service.status {
        case .enabled: status = .on
        case .requiresApproval: status = .needsApproval
        default: if case .failed = status {} else { status = .off }
        }
    }

    func set(_ on: Bool) {
        do {
            if on { try service.register() } else { try service.unregister() }
            refresh()
        } catch {
            status = .failed(error.localizedDescription)
        }
    }

    func openSettings() { SMAppService.openSystemSettingsLoginItems() }
}

/// `SMAppService` behind a protocol (tests fake it; the app registers only from a real install).
protocol LoginService {
    var status: SMAppService.Status { get }
    func register() throws
    func unregister() throws
}

extension SMAppService: LoginService {}
