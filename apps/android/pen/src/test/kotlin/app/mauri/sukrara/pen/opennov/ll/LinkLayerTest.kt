// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen.opennov.ll

import app.mauri.sukrara.pen.opennov.Hex
import app.mauri.sukrara.pen.opennov.Transcripts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PHDllTest {
    @Test fun testEncode() {
        val x = PHDll(seq = 7, inner = byteArrayOf(14, 15, 16, 17, 18, 19, 20)).encode()
        assertArrayEquals(Hex.bytes("D10308504844870E0F1011121314"), x)
        val y = PHDll.parse(x)!!
        assertTrue("b7 set", y.checkBit(7))
        assertFalse("b6 clr", y.checkBit(6))
        assertFalse("b5 clr", y.checkBit(5))
        assertFalse("b4 clr", y.checkBit(4))
    }

    @Test fun testParse() {
        val x = PHDll.parse(Hex.bytes(Transcripts.SEG_DATA_18_RECORD))
        assertNotNull(x)
        assertArrayEquals("inner", Hex.bytes(Transcripts.SEG_DATA_18), x!!.inner)
        assertEquals(252, x.payloadLen)
        assertEquals(14, x.seq)
        assertEquals(209, x.opcode)
        assertEquals(3, x.typeLen)
        assertEquals(142, x.chkByte)
    }

    @Test fun notPhd() {
        assertNull(PHDll.parse(Hex.bytes("D1 03 01 41 42 43 80")))
        assertNull(PHDll.parse(Hex.bytes("D1")))
    }
}

class T4ReadTest {
    @Test fun encodeForMtuTest() {
        val list = T4Read.encodeForMtu(2, 259, 255)
        assertEquals(2, list.size)
        assertArrayEquals(Hex.bytes("00B00002FF"), list[0])
        assertArrayEquals(Hex.bytes("00B0010104"), list[1])
    }
}

class T4ReplyTest {
    @Test fun parseTest1() {
        assertNull(T4Reply.parse(null))
        val b1 = Hex.bytes("0090")
        assertNotNull(T4Reply.parse(b1))
        assertFalse(T4Reply.parse(b1)!!.isOkay())
        assertEquals(-1, T4Reply.parse(b1)!!.asInteger())
        val b2 = Hex.bytes("12340090")
        assertFalse(T4Reply.parse(b2)!!.isOkay())
        assertEquals(0x1234, T4Reply.parse(b2)!!.asInteger())
        val b3 = Hex.bytes("45679000")
        assertTrue(T4Reply.parse(b3)!!.isOkay())
        assertEquals(0x4567, T4Reply.parse(b3)!!.asInteger())
    }

    @Test fun parseTest2() {
        val r = T4Reply.parse(Hex.bytes("012345679000"))!!
        assertTrue(r.isOkay())
        assertArrayEquals(Hex.bytes("01234567"), r.bytes)
        val r2 = T4Reply.parse(Hex.bytes("890123459000"), r)!!
        assertTrue(r2.isOkay())
        assertArrayEquals(Hex.bytes("0123456789012345"), r2.bytes)
    }
}

class T4SelectTest {
    @Test fun encodeTest() {
        assertArrayEquals(Hex.bytes("00A4040007D276000085010100"), T4Select.aSelect())
        assertArrayEquals(Hex.bytes("00A4000C02E103"), T4Select.ccSelect())
        assertArrayEquals(Hex.bytes("00A4000C02E104"), T4Select.ndefSelect())
    }
}

class T4UpdateTest {
    @Test fun encodeForMtuTest1() {
        val list = T4Update.encodeForMtu(ByteArray(255) { it.toByte() }, 80)
        val p =
            listOf(
                "00D600004B0000000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F2021222324" +
                    "25262728292A2B2C2D2E2F303132333435363738393A3B3C3D3E3F404142434445464748",
                "00D6004B49494A4B4C4D4E4F505152535455565758595A5B5C5D5E5F606162636465666768696A6B" +
                    "6C6D6E6F707172737475767778797A7B7C7D7E7F808182838485868788898A8B8C8D8E8F9091",
                "00D600944992939495969798999A9B9C9D9E9FA0A1A2" +
                    "A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0C1" +
                    "C2C3C4C5C6C7C8C9CACBCCCDCECFD0D1D2D3D4D5D6D7D8D9DA",
                "00D600DD24DBDCDDDEDFE0E1E2E3E4E5E6E7E8E9EA" +
                    "EBECEDEEEFF0F1F2F3F4F5F6F7F8F9FAFBFCFDFE",
                "00D600000200FF",
            )
        assertEquals(5, list.size)
        p.forEachIndexed { i, h -> assertArrayEquals("fragment $i", Hex.bytes(h), list[i]) }
    }

    @Test fun encodeForMtuTest2() {
        val list = T4Update.encodeForMtu(ByteArray(200) { it.toByte() }, 255)
        val p1 =
            "00D60000CA00C8000102030405060708090A0B0C0D0E0F101112131415161718191A1B1" +
                "C1D1E1F202122232425262728292A2B2C2D2E2F303132333435363738393A3B3C3" +
                "D3E3F404142434445464748494A4B4C4D4E4F505152535455565758595A5B5C5D5E" +
                "5F606162636465666768696A6B6C6D6E6F707172737475767778797A7B7C7D7E7F8" +
                "08182838485868788898A8B8C8D8E8F909192939495969798999A9B9C9D9E9FA0A1" +
                "A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0C1C2C" +
                "3C4C5C6C7"
        assertEquals(1, list.size)
        assertArrayEquals(Hex.bytes(p1), list[0])
    }

    @Test fun emptyMessage() {
        assertArrayEquals(Hex.bytes("00D60000020000"), T4Update.encodeForMtu(ByteArray(0), 255).single())
    }
}
