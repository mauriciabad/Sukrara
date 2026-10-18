// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import app.mauri.sukrara.treatments.WriterScope.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4 (spec/ogs-nostr.md N§9.1.8, N§9.2): writers change only the entries they created. Same cases as Mac -v2selftest. */
class WriterScopeTest {
    private val d = "d-device"; private val w = "w-writer"; private val w2 = "w2-other-writer"
    private val devices = listOf(d)

    @Test fun ownerAcceptsNewEntriesAndTheWritersOwn() {
        assertTrue("new id", WriterScope.ownerAllows(w, creator = null, inOwnLog = false))
        assertTrue("its own entry", WriterScope.ownerAllows(w, creator = w, inOwnLog = true))
    }

    @Test fun ownerRefusesTheWearersAndOtherWritersEntries() {
        assertFalse("the wearer's entry", WriterScope.ownerAllows(w, creator = null, inOwnLog = true))
        assertFalse("another writer's entry", WriterScope.ownerAllows(w2, creator = w, inOwnLog = true))
    }

    @Test fun followerWriterRules() {
        assertEquals(Verdict.ACCEPT, WriterScope.follower(w, false, "follower", null, null, devices))
        assertEquals(Verdict.ACCEPT, WriterScope.follower(w, false, "follower", w, null, devices))
        assertEquals(Verdict.REJECT, WriterScope.follower(w, false, "follower", d, null, devices))   // the wearer's
        assertEquals(Verdict.REJECT, WriterScope.follower(w2, false, "follower", w, null, devices))  // another writer's
        // the wearer's copy of a writer entry came first: the writer's own copy is redundant, not a rejection
        assertEquals(Verdict.IGNORE, WriterScope.follower(w, false, "follower", d, "follower", devices))
        assertEquals(Verdict.REJECT, WriterScope.follower(w, false, "follower", d, "phone", devices))
    }

    @Test fun followerWearerAlwaysMergesAndBeatsAForgedCopy() {
        assertEquals(Verdict.ACCEPT, WriterScope.follower(d, true, "phone", null, null, devices))
        assertEquals(Verdict.ACCEPT, WriterScope.follower(d, true, "follower", w, null, devices))     // echo of a writer entry
        assertEquals(Verdict.ACCEPT, WriterScope.follower(d, true, "phone", d, null, devices))
        // a writer forged the wearer's id before the real entry arrived: the wearer's own entry replaces it
        assertEquals(Verdict.OVERRIDE, WriterScope.follower(d, true, "phone", w, null, devices))
        assertEquals(d, WriterScope.creatorAfter(Verdict.OVERRIDE, d, w))
        assertEquals(w, WriterScope.creatorAfter(Verdict.ACCEPT, d, w))
        assertEquals(w, WriterScope.creatorAfter(Verdict.ACCEPT, w, null))
    }
}
