// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip's insulin/opennov/MessageTest (GPL-3.0), original by JamOrHam.
package app.mauri.novopen.opennov

import app.mauri.novopen.opennov.Transcripts.A_INFO
import app.mauri.novopen.opennov.Transcripts.A_REQUEST
import app.mauri.novopen.opennov.Transcripts.A_RESPONSE
import app.mauri.novopen.opennov.Transcripts.CA_SEG
import app.mauri.novopen.opennov.Transcripts.C_ACCEPT
import app.mauri.novopen.opennov.Transcripts.C_REPORT
import app.mauri.novopen.opennov.Transcripts.D_INFO
import app.mauri.novopen.opennov.Transcripts.SEG_DATA_3
import app.mauri.novopen.opennov.Transcripts.S_INFO
import app.mauri.novopen.opennov.Transcripts.T_XFER
import app.mauri.novopen.opennov.Transcripts.T_XFER_OK
import app.mauri.novopen.opennov.mt.ApoepElement.Companion.SYS_TYPE_AGENT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTest {
    private fun parse(hex: String) = Message.parse(Hex.bytes(hex))!!
    private fun parse(prev: Message, hex: String) = Message.parse(prev.context, Hex.bytes(hex))!!

    @Test fun parseTest1() {
        assertNull("null should be null", Message.parse(null))
        assertNull("invalid apdu type should be null", Message.parse(ByteArray(4)))
    }

    @Test fun testSegmentInfo() {
        val sil = parse(S_INFO).context.segmentInfoList!!
        assertNotNull(sil.items[0])
        assertEquals(1, sil.scount)
        assertEquals(1, sil.items.size)
        assertEquals(16, sil.items[0].instnum)
        assertEquals(3L, sil.items[0].usage)
        assertTrue("typical", sil.isTypical())
    }

    @Test fun trigSegTest() {
        val x = parse(T_XFER_OK).context.trigSegmDataXfer!!
        assertEquals(16, x.segmentId)
        assertTrue(x.isOkay())
    }

    @Test fun segmentXferTest() {
        val now = 1_800_000_000L
        val er = Message.parse(OpContext { now }, Hex.bytes(SEG_DATA_3))!!.context.eventReport!!
        assertEquals("unit check", 3.0, er.doses[0].units, 0.0)
        assertEquals(2.0, er.doses[1].units, 0.0)
        assertEquals("not a dose", -1.0, er.doses[2].units, 0.0)
        assertEquals(116243L, er.relativeTime)
        assertEquals(16, er.instance)
        assertEquals(3L, er.count)
        assertEquals(116177L, er.doses[0].relativeTime)
        assertEquals("placed 66 s before the read", now - 66, er.doses[0].absoluteTime)
        assertEquals(0x08000000L, er.doses[0].flags)
        assertEquals(0x04000008L, er.doses[2].flags)
    }

    @Test fun ackTest1() {
        val m = parse("e7 00 00 1e 00 1c 80 01 02 01 00 16 01 00 ff ff ff ff 0d 21 00 0c 00 10 00 00 00 00 00 00 00 02 00 80 ")
        assertEquals(0x8001, m.invokeId)
        assertEquals(12, m.context.eventRequest!!.replyLen)
    }

    @Test fun ackTest2() {
        val m = parse("e7 00 00 1e 00 1c 80 01 02 01 00 16 01 00 ff ff ff ff 0d 21 00 0c 00 10 00 00 00 00 00 00 00 12 00 80 ")
        assertEquals(3361, m.context.eventRequest!!.type)
    }

    @Test fun deviceParseTest() {
        val r = parse(D_INFO)
        assertEquals("invoke id", 0, r.invokeId)
        assertEquals("serial", "EGDO2U", r.context.specification!!.serial)
        assertEquals("software", "01.08.00", r.context.specification!!.softwareRevision)
        assertEquals("part", "D20130263200000 D20130263200000 ", r.context.specification!!.partNumber)
        assertEquals("clock", 116243L, r.context.relativeTime)
        assertEquals("model", "Novo Nordisk A/S NovoPen", r.context.model)
    }

    @Test fun aRequestTest() {
        val a = parse(A_REQUEST).context.aRequest!!.apoep!!
        assertEquals(SYS_TYPE_AGENT, a.systemType)
        assertEquals(2147483648L, a.version)
        assertEquals(2147483648L, a.nomenclature)
        assertEquals(32768, a.encoding)
        assertEquals(0, a.olistCount)
        assertEquals(0, a.olistLen)
        assertTrue(parse(A_REQUEST).context.aRequest!!.valid())
    }

    @Test fun aResponseTest() {
        assertArrayEquals("A response vs specimen", Hex.bytes(A_RESPONSE), parse(A_REQUEST).getAResponse())
    }

    @Test fun testConfigReport() {
        val c = parse(C_REPORT).context.configuration
        assertNotNull("configuration available", c)
        assertTrue("as expected", c!!.isAsExpected())
        assertEquals("total capacity", 800L, c.totalStorageCapacity)
        assertEquals(1L, c.numberOfSegments)
    }

    @Test fun testAcceptConfigEncoding() {
        assertArrayEquals("C confirm vs specimen", Hex.bytes(C_ACCEPT), parse(C_REPORT).getAcceptConfig())
    }

    @Test fun testAcceptConfigDecoding() {
        val er = parse(C_ACCEPT).context.eventRequest!!
        assertEquals(0L, er.currentTime)
        assertEquals(0, er.handle)
        assertEquals(3356, er.type)
        assertEquals(4, er.replyLen)
        assertEquals(16394, er.reportId)
        assertEquals(0, er.reportResult)
    }

    @Test fun testAskInformation() {
        assertArrayEquals(Hex.bytes(A_INFO), parse(C_REPORT).getAskInformation())
    }

    @Test fun parseSegmentInfo() {
        val msg = parse(parse(C_REPORT), CA_SEG)
        assertArrayEquals(Hex.bytes(CA_SEG), msg.getConfirmedAction())
    }

    @Test fun segTest() {
        val r = parse(C_REPORT)
        r.context.invokeId = 2
        assertArrayEquals(Hex.bytes(T_XFER), r.getXferAction(16))
    }

    @Test fun parseTest11() { assertNotNull(parse(T_XFER)) }

    @Test fun parseTest14() {
        val r = parse(A_RESPONSE)
        assertFalse(r.context.isError())
    }

    @Test fun noConfigurationNoReply() {
        assertNull(parse(D_INFO).getAcceptConfig())
    }
}
