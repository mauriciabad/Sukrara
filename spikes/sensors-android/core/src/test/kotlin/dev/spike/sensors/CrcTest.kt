// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors

import dev.spike.sensors.harness.Trace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each checksum is pinned by real frames, so a wrong variant cannot slip past (see traces/README.md). */
class CrcTest {
    @Test fun `dexcom frames carry a CRC-16 XMODEM trailer`() {
        val trace = Trace.resource("g6-session")
        for (entry in trace.entries) {
            val crc = Crc.xmodem(entry.bytes, 0, entry.bytes.size - 2)
            assertEquals(entry.label, entry.bytes.u16le(entry.bytes.size - 2), crc)
        }
    }

    @Test fun `CGM Service frames carry a CRC-16 MCRF4XX E2E trailer`() {
        val trace = Trace.resource("smartguide")
        for (label in listOf("error-1", "error-2", "low-1", "low-2", "status")) {
            assertTrue(label, Crc.mcrf4xx(trace[label], 0, trace[label].size - 2) == trace[label].u16le(trace[label].size - 2))
        }
        // Juggluco writes 02 95 2c to the SmartGuide's control point: opcode 0x02 plus this CRC.
        assertEquals("02952c", (byteArrayOf(0x02) + Crc.le(Crc.mcrf4xx(byteArrayOf(0x02)))).hex())
    }

    @Test fun `aidex checksums match its own test vectors`() {
        // Juggluco aidexx/crc.cpp TEST: the last byte is the CRC-8/MAXIM of the 16 before it.
        val data = "dd3fa65fed530f30733d2c04597b027864".unhex()
        assertEquals(data.u8(16), Crc.crc8Maxim(data, 0, 16))
        // crc.cpp TEST2: 23 01 00 is signed with 3b 20 (CRC-16/CCITT-FALSE, little-endian).
        assertEquals("3b20", Crc.le(Crc.ccittFalse("230100".unhex())).hex())
    }
}
