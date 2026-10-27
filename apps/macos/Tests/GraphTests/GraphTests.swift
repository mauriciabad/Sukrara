// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
import SukraraGraph
import Testing

@Suite struct GlucoseCardTests {
    @Test func noValueIsADashNotANumber() {
        #expect(GlucoseCard.noValueText == "—")
        #expect(Int(GlucoseCard.noValueText) == nil)
    }
}
