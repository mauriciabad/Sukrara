// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraAlerts
import Testing

@Suite struct AlertUIStateTests {
    @Test func startsIdle() {
        #expect(AlertUIState.idle.isAlerting == false)
    }
}
