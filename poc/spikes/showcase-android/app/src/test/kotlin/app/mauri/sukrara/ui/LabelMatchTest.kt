// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Allow dialog's "✓ Matches" / "Different name" hint: the wearer's invite label vs the follower's chosen name. */
class LabelMatchTest {
    @Test fun matches() {
        assertEquals(true, labelMatches("Mom", "Mom"))
        assertEquals(true, labelMatches(" mom ", "MOM"))
        assertEquals(true, labelMatches("Mom's phone", "Mom"))
        assertEquals(true, labelMatches("Mom phone", "Mom"))
        assertEquals(true, labelMatches("Marta", "Marta Abad"))
        assertEquals(true, labelMatches("Àvia", "àvia"))
    }

    @Test fun differs() {
        assertEquals(false, labelMatches("Mom", "Intruder"))
        assertEquals(false, labelMatches("Mom", ""))
        assertEquals(false, labelMatches("Ma", "Marta"))           // whole words only
    }

    @Test fun noLabel() {
        assertEquals(null, labelMatches("", "Mom"))
        assertEquals(null, labelMatches("   ", "Mom"))
    }
}
