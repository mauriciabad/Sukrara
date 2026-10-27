// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Validation 21, item 11: no exception class names or socket addresses in the UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UserErrorsTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun jugglucoUnreachableIsASentence() {
        val m = UserErrors.source(ctx, "Juggluco unreachable", "ConnectException: Failed to connect to /127.0.0.1:17580")!!
        assertEquals("Can't reach Juggluco. Open it and check that its web server is on.", m)
        assertEquals(null, UserErrors.source(ctx, "live (Juggluco SSE)", null))
        assertFalse(UserErrors.source(ctx, "direct: failed", "UnsatisfiedLinkError: x (native core missing?)")!!.contains("Error"))
    }

    @Test fun exceptionsBecomeSentences() {
        assertEquals("Can't connect to the server. Check the internet connection.",
            UserErrors.of(ctx, java.net.ConnectException("Failed to connect to /127.0.0.1:17580")))
        assertEquals("Wrong email or password", UserErrors.of(ctx, IllegalStateException("Wrong email or password")))
        assertEquals("Something went wrong. Try again.", UserErrors.of(ctx, IllegalStateException("NullPointerException at x")))
        assertEquals("Something went wrong. Try again.", UserErrors.of(ctx, RuntimeException()))
    }

    @Test fun readable() {
        assertTrue(UserErrors.readable("Invite expired"))
        assertFalse(UserErrors.readable("Failed to connect to /127.0.0.1:17580"))
        assertFalse(UserErrors.readable("java.lang.IllegalStateException: boom"))
        assertFalse(UserErrors.readable("HTTP 500 from host:8443"))
    }
}
