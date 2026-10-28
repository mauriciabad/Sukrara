// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanInviteTest {
    private val link = "https://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_"

    @Test fun theFirstInviteAmongTheCodesReadWins() {
        // a menu QR next to the share screen, an unreadable one, then the invite (with stray whitespace)
        assertEquals(
            link,
            firstInvite(listOf("https://example.com/menu", null, " $link\n", "sukrara:join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_")),
        )
    }

    @Test fun codesThatAreNotInvitesAreIgnored() {
        assertNull(firstInvite(listOf("https://sukrara.mauri.app/join#short", "hello", null)))
        assertNull(firstInvite(emptyList()))
    }
}
