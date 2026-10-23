// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.novopen.opennov

import app.mauri.novopen.opennov.ll.PHDll
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A NovoPen on the other side of IsoDep: an NFC Forum Type 4 tag (NDEF application, capability container, NDEF file)
 * whose NDEF file carries PHD records. Each NDEF message the phone writes (other than the empty-record ack) gets the
 * next scripted 20601 APDU as the pen's answer; a release request from the phone always gets a release response.
 * The APDUs are xDrip's recordings ([Transcripts]).
 */
class FakePen(private val answers: List<String>, private val failAfter: Int = Int.MAX_VALUE) : Transceiver {
    val phoneSent = ArrayList<ByteArray>()
    var commands = 0; private set
    private var next = 0
    private var penSeq = 0
    private var selected = ""
    private var ndef = ByteArray(1024)
    // CC: length 15, mapping 2.0, MLe 255, MLc 255, NDEF file control TLV (E104, 1024 bytes, open read/write)
    private val cc = Hex.bytes("000F 20 00FF 00FF 04 06 E104 0400 00 00")

    init { load(Transcripts.A_REQUEST) }

    private fun load(innerHex: String) {
        val rec = PHDll(seq = penSeq, inner = Hex.bytes(innerHex)).encode()
        penSeq = (penSeq + 2) and 0x0F
        ndef = ByteArray(1024)
        ByteBuffer.wrap(ndef).putShort(rec.size.toShort()).put(rec)
    }

    override fun transceive(cmd: ByteArray): ByteArray {
        if (++commands > failAfter) throw IOException("Tag was lost.")
        val b = ByteBuffer.wrap(cmd)
        b.get() // CLA
        val ins = b.get().toInt() and 0xff
        val off = b.short.toInt() and 0xffff
        val ok = byteArrayOf(0x90.toByte(), 0)
        return when (ins) {
            0xA4 -> {
                val data = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }
                selected = Hex.of(data)
                ok
            }
            0xB0 -> {
                val len = b.get().toInt() and 0xff
                val file = if (selected == "E103") cc else ndef
                file.copyOfRange(off, off + len) + ok
            }
            0xD6 -> {
                val data = ByteArray(b.get().toInt() and 0xff).also { b.get(it) }
                data.copyInto(ndef, off)
                val nlen = ByteBuffer.wrap(ndef).short.toInt() and 0xffff
                if (off == 0 && nlen > 0) received(ndef.copyOfRange(2, 2 + nlen))
                ok
            }
            else -> byteArrayOf(0x6D, 0)
        }
    }

    private fun received(msg: ByteArray) {
        if (msg.contentEquals(Hex.bytes("D0 00 00"))) return // the phone's ack of our last record
        val inner = PHDll.parse(msg)!!.inner!!
        phoneSent.add(inner)
        when {
            inner.size >= 2 && inner[0] == 0xE4.toByte() -> load(Transcripts.RLRE)
            next < answers.size -> load(answers[next++])
            else -> load("")
        }
    }
}

class OpenNovReaderTest {
    private val now = 1_800_000_000L

    private val oneChunk = listOf(
        Transcripts.C_REPORT, "", Transcripts.D_INFO, Transcripts.S_INFO, Transcripts.T_XFER_OK, Transcripts.SEG_DATA_3,
        Transcripts.RLRQ,
    )

    /** The 18-dose chunk then the 3-record chunk as one 21-record segment on one pen clock. */
    private val twoChunks: List<String> by lazy {
        val sInfo = Hex.of(Hex.bytes(Transcripts.S_INFO)).replace("097B000400000003", "097B000400000015")
        val chunk2 = Hex.of(Hex.bytes(Transcripts.SEG_DATA_3))
            .replace("01000001C6130D21", "0100000904520D21")
            .replace("003200100000000000000003", "003200100000001200000003")
        listOf(Transcripts.C_REPORT, "", Transcripts.D_INFO, sInfo, Transcripts.T_XFER_OK, Transcripts.SEG_DATA_18, chunk2, Transcripts.RLRQ)
    }

    @Test fun readsOneChunk() {
        val pen = FakePen(oneChunk)
        val r = OpenNovReader.read(pen, now)
        assertEquals("EGDO2U", r.serial)
        assertEquals("Novo Nordisk A/S NovoPen", r.model)
        assertEquals(now, r.scanTime)
        assertEquals(116243L, r.penRelNow)
        assertEquals(3, r.stored)
        assertTrue(r.complete)
        assertEquals(
            listOf(PenDose(331, now - (116243 - 331), 2.0, 0x08000000), PenDose(116177, now - 66, 3.0, 0x08000000)),
            r.doses,
        )
        // what the phone said, in order: associate, accept config, get attributes, segment list, transfer, ack data, release
        assertArrayEquals(Hex.bytes(Transcripts.A_RESPONSE), pen.phoneSent[0])
        assertArrayEquals(Hex.bytes(Transcripts.C_ACCEPT), pen.phoneSent[1])
        assertArrayEquals(Hex.bytes(Transcripts.A_INFO), pen.phoneSent[2])
        assertEquals(0, pen.phoneSent[5].size)
        assertEquals(0xE4.toByte(), pen.phoneSent.last()[0])
    }

    @Test fun readsTwoChunksOldestFirst() {
        val r = OpenNovReader.read(FakePen(twoChunks), now)
        assertEquals(21, r.stored)
        assertEquals("20 doses: the rel-0 record isn't one", 20, r.doses.size)
        assertEquals(PenDose(331, now - (0x90452 - 331), 2.0, 0x08000000), r.doses.first())
        assertEquals(0x888F2L, r.doses.last().relTime)
        assertEquals(15, r.doses.count { it.units == 1.0 })
        assertEquals(4, r.doses.count { it.units == 3.0 })
        assertEquals(r.doses.sortedBy { it.time }, r.doses)
        assertTrue(r.complete)
    }

    @Test fun stopsAtKnownHistory() {
        val pen = FakePen(twoChunks)
        val r = OpenNovReader.read(pen, now, stopBefore = now - 30_000)
        assertFalse(r.complete)
        assertEquals(18, r.doses.size)
        assertEquals(0xE4.toByte(), pen.phoneSent.last()[0])
    }

    @Test fun notAPen() {
        val card = Transceiver { byteArrayOf(0x6A, 0x82.toByte()) } // file not found: a bank card has no NDEF app
        try { OpenNovReader.read(card, now); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("selection")) }
    }

    @Test fun tagLostMidTransferFails() {
        val pen = FakePen(twoChunks)
        OpenNovReader.read(pen, now)
        val lostAt = pen.commands - 8 // somewhere in the second chunk
        try { OpenNovReader.read(FakePen(twoChunks, failAfter = lostAt), now); fail("partial read must throw") } catch (_: IOException) {}
    }

    @Test fun logsThroughTheLambda() {
        val lines = ArrayList<String>()
        OpenNovReader.read(FakePen(oneChunk), now, log = { lines.add(it) })
        assertTrue(lines.any { it.startsWith("not a dose: rel 0") })
        assertTrue(lines.any { it.contains("complete") })
    }
}
