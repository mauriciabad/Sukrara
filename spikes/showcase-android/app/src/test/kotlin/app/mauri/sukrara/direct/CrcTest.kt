// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct

import org.junit.Assert.assertEquals
import org.junit.Test

/** Each checksum the brand drivers use is pinned by real frames, so a wrong variant cannot slip past (investigation 23). */
class CrcTest {
    @Test fun dexcomFramesCarryXmodem() {
        val trace = Trace.resource("g6-session")
        for ((label, b) in trace.entries) assertEquals(label, b.u16le(b.size - 2), Crc.xmodem(b, 0, b.size - 2))
    }

    @Test fun cgmServiceFramesCarryMcrf4xx() {
        val trace = Trace.resource("smartguide")
        for (label in listOf("error-1", "error-2", "low-1", "low-2", "status")) {
            val b = trace[label]
            assertEquals(label, b.u16le(b.size - 2), Crc.mcrf4xx(b, 0, b.size - 2))
        }
        // Juggluco writes 02 95 2c to the SmartGuide's control point: opcode 0x02 plus this CRC.
        assertEquals("02952c", (byteArrayOf(0x02) + Crc.le(Crc.mcrf4xx(byteArrayOf(0x02)))).hex())
    }

    @Test fun aidexVectors() {
        // Juggluco aidexx/crc.cpp TEST: the last byte is the CRC-8/MAXIM of the 16 before it.
        val data = unhex("dd3fa65fed530f30733d2c04597b027864")
        assertEquals(data.u8(16), Crc.crc8Maxim(data, 0, 16))
        // crc.cpp TEST2: 23 01 00 is signed with 3b 20 (CRC-16/CCITT-FALSE, little-endian).
        assertEquals("3b20", Crc.le(Crc.ccittFalse(unhex("230100"))).hex())
    }
}
