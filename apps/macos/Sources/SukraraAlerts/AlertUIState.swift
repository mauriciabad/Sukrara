// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later

/// What the UI shows about alerts. Placeholder: chunk 05 (alerts core) defines the engine output this is built
/// from (active alert, snooze / mute with their exits).
public struct AlertUIState: Sendable, Equatable {
    /// An alert is ringing or waiting for acknowledgement.
    public var isAlerting: Bool

    public init(isAlerting: Bool = false) {
        self.isAlerting = isAlerting
    }

    public static let idle = AlertUIState()
}
