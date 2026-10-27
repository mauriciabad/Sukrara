// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import app.mauri.sukrara.pen.opennov.NotAPenException
import app.mauri.sukrara.pen.opennov.OpenNovReader
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * 0.11.46: the background filter is IsoDep + NfcA (the pen's NDEF check times out on Android), so bank / transit cards
 * and passports reach the pen reader. A tag that never answers the pen's selection is NOT_PEN (said nothing about);
 * only a pen that answered and then broke off is FAILED (the failure card).
 */
class PenTagTest {
    @Test fun classification() {
        assertEquals(PenTag.Outcome.PEN, PenTag.outcome(selected = true, error = null))
        assertEquals(PenTag.Outcome.NOT_PEN, PenTag.outcome(selected = false, error = NotAPenException()))
        assertEquals(PenTag.Outcome.NOT_PEN, PenTag.outcome(selected = false, error = IOException("Tag was lost")))
        assertEquals(PenTag.Outcome.FAILED, PenTag.outcome(selected = true, error = IOException("transfer incomplete")))
    }

    /** A bank card answers 6A82 (file not found) to the pen's NDEF application select: NotAPenException, onSelected never runs. */
    @Test fun bankCardIsNotAPen() {
        var selected = false
        try {
            OpenNovReader.read({ byteArrayOf(0x6A, 0x82.toByte()) }, onSelected = { selected = true })
            fail("a bank card read as a pen")
        } catch (e: NotAPenException) {
            assertEquals(PenTag.Outcome.NOT_PEN, PenTag.outcome(selected, e))
        }
        assertEquals(false, selected)
    }
}
