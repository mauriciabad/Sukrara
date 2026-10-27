// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip (insulin/opennov/ll, GPL-3.0), original by JamOrHam; retry fix from Juggluco's copy.
// NFC Forum Type 4 tag commands (ISO 7816-4 APDUs) and the PHD record inside the NDEF file.
package app.mauri.sukrara.pen.opennov.ll

import app.mauri.sukrara.pen.opennov.Hex
import app.mauri.sukrara.pen.opennov.OpenNovLog
import app.mauri.sukrara.pen.opennov.Transceiver
import app.mauri.sukrara.pen.opennov.putU16
import app.mauri.sukrara.pen.opennov.putU8
import app.mauri.sukrara.pen.opennov.u16
import app.mauri.sukrara.pen.opennov.u8
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** NDEF short record of type "PHD" (NFC Forum Personal Health Device Communication) carrying one 20601 APDU. */
class PHDll(val seq: Int = -1, val inner: ByteArray? = null, private val idHeader: ByteArray? = null, private var chk: Int = 0) {
    var opcode = -1; private set
    var typeLen = -1; private set
    var payloadLen = -1; private set

    fun checkBit(bit: Int) = ((chk shr bit) and 1) != 0
    val chkByte get() = chk

    fun encode(): ByteArray {
        val ilen = inner?.size ?: 0
        val hasId = (idHeader?.size ?: 0) > 0
        val b = ByteBuffer.allocate(ilen + 7)
        b.putU8(MB or ME or SR or (if (hasId) IL else 0) or WELL_KNOWN).putU8(TYPE_ID.size).putU8(ilen + 1)
        if (hasId) b.put(idHeader!!)
        b.put(TYPE_ID).putU8((seq and 0x0F) or 0x80 or chk)
        if (ilen > 0) b.put(inner!!)
        return b.array()
    }

    companion object {
        private const val MB = 1 shl 7
        private const val ME = 1 shl 6
        private const val SR = 1 shl 4
        private const val IL = 1 shl 3
        private const val WELL_KNOWN = 1
        private val TYPE_ID = "PHD".toByteArray()

        fun parse(bytes: ByteArray?): PHDll? {
            if (bytes == null) return null
            return runCatching {
                val b = ByteBuffer.wrap(bytes)
                val opcode = b.u8()
                val hasId = (opcode and IL) != 0
                val typeLen = b.u8()
                val payloadLen = b.u8() - 1
                val idLen = if (hasId) b.u8() else 0
                val proto = ByteArray(3).also { b.get(it) }
                if (!proto.contentEquals(TYPE_ID)) return null
                val id = if (hasId) ByteArray(idLen).also { b.get(it) } else null
                val chk = b.u8()
                val inner = ByteArray(payloadLen).also { b.get(it) }
                PHDll(chk and 0x0F, inner, id, chk).also { it.opcode = opcode; it.typeLen = typeLen; it.payloadLen = payloadLen }
            }.getOrNull()
        }
    }
}

object T4Read {
    fun encode(offset: Int, length: Int): ByteArray =
        ByteBuffer.allocate(5).putU8(0x00).putU8(0xB0).putU16(offset).putU8(length).array()

    /** READ BINARY commands of at most [mtu] bytes each covering [offset, offset + length). */
    fun encodeForMtu(offset: Int, length: Int, mtu: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var off = offset
        var left = length
        while (left > 0) {
            val n = minOf(left, mtu)
            out.add(encode(off, n))
            off += n
            left -= n
        }
        return out
    }
}

/** A response APDU: data + SW1SW2. [parse] with a previous reply appends its data (a read split by the MTU). */
class T4Reply {
    var bytes: ByteArray? = null
    var lastShort = 0; private set

    fun isOkay() = lastShort == COMMAND_COMPLETED

    fun asInteger(): Int {
        val b = bytes ?: return -1
        return when (b.size) {
            2 -> ByteBuffer.wrap(b).u16()
            4 -> ByteBuffer.wrap(b).int
            else -> -1
        }
    }

    companion object {
        private const val COMMAND_COMPLETED = 0x9000

        fun parse(bytes: ByteArray?, prev: T4Reply? = null): T4Reply? {
            if (bytes == null || bytes.size < 2) return null
            val r = prev ?: T4Reply()
            val blen = bytes.size - 2
            if (blen > 0) r.bytes = (r.bytes ?: ByteArray(0)) + bytes.copyOfRange(0, blen)
            r.lastShort = ByteBuffer.wrap(bytes, blen, 2).u16()
            return r
        }
    }
}

object T4Select {
    private val NDEF_TAG_APPLICATION = Hex.bytes("D2760000850101")
    private val CAPABILITY_CONTAINER = Hex.bytes("E103")
    private val NDEF_FILE = Hex.bytes("E104")

    private fun encode(p1: Int, p2: Int, data: ByteArray, le: Int?): ByteArray {
        val b = ByteBuffer.allocate(5 + data.size + (if (le != null) 1 else 0))
        b.putU8(0x00).putU8(0xA4).putU8(p1).putU8(p2).putU8(data.size).put(data)
        if (le != null) b.putU8(le)
        return b.array()
    }

    /** SELECT by name the NDEF Tag Application (version 2). */
    fun aSelect() = encode(0x04, 0x00, NDEF_TAG_APPLICATION, 0)
    fun ccSelect() = encode(0x00, 0x0C, CAPABILITY_CONTAINER, null)
    fun ndefSelect() = encode(0x00, 0x0C, NDEF_FILE, null)
}

/** UPDATE BINARY of the NDEF file; a message longer than the MTU goes NLEN=0, the chunks, then the real NLEN. */
object T4Update {
    fun encode(offset: Int, bytes: ByteArray?, firstFragment: Boolean = false, lastFragment: Boolean = false): ByteArray {
        val data = bytes ?: ByteArray(0)
        val isFragment = offset > 0
        val hasDlen = offset == 0 || firstFragment
        val b = ByteBuffer.allocate(if (lastFragment) 7 else data.size + (if (hasDlen) 7 else 5))
        b.putU8(0x00).putU8(0xD6).putU16(if (isFragment) offset + 2 else 0)
            .putU8(if (lastFragment) 2 else data.size + (if (hasDlen) 2 else 0))
        if (hasDlen) b.putU16(if (firstFragment) 0 else data.size)
        if (!lastFragment) b.put(data)
        return b.array()
    }

    fun encodeForMtu(bytes: ByteArray, mtu: Int): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var offset = 0
        val b = ByteBuffer.wrap(bytes)
        while (b.remaining() > 0) {
            val chunk = ByteArray(minOf(b.remaining(), mtu - 7)).also { b.get(it) }
            out.add(encode(offset, chunk, firstFragment = offset == 0 && b.remaining() > 0))
            offset += chunk.size
        }
        if (out.size > 1) out.add(encode(0, bytes, lastFragment = true))
        else if (out.isEmpty()) out.add(encode(0, null))
        return out
    }
}

/**
 * The Type 4 tag side: select the NDEF application and file, read its capability container (max read / write sizes),
 * then exchange NDEF messages by reading and updating that file.
 */
class T4Transceiver(private val tag: Transceiver) {
    private var mlcMax = -1
    private var mleMax = -1

    fun t4Transceive(cmd: ByteArray?, prev: T4Reply? = null): T4Reply? {
        if (cmd == null) return null
        return try {
            T4Reply.parse(tag.transceive(cmd), prev)
        } catch (e: Exception) {
            OpenNovLog.d { "Exception during transceive: $e" }
            null
        }
    }

    private fun transceiveOkay(cmd: ByteArray, failure: String): Boolean =
        (t4Transceive(cmd)?.isOkay() == true).also { if (!it) OpenNovLog.d { failure } }

    fun writeToLinkLayer(bytes: ByteArray): Boolean {
        for (p in T4Update.encodeForMtu(bytes, mlcMax)) if (!transceiveOkay(p, "Error during packet write")) return false
        return true
    }

    /** One NDEF message from the pen: NLEN, then the data in MTU-sized reads. Null after 3 failed attempts. */
    fun readFromLinkLayer(): ByteArray? {
        repeat(MAX_RETRY) {
            val len = t4Transceive(T4Read.encode(0, 2)) ?: return null
            if (!len.isOkay()) return@repeat
            val readLen = len.asInteger()
            OpenNovLog.d { "Reading data of length: $readLen" }
            val out = ByteArrayOutputStream()
            var ok = true
            for (cmd in T4Read.encodeForMtu(2, readLen, minOf(MAX_READ_SIZE, mleMax))) {
                // Juggluco's fix: a failed transceive is retried instead of ending the read; unlike xDrip, a failed
                // attempt's bytes are never kept (each attempt parses into a fresh reply).
                var got: ByteArray? = null
                for (retry in 0 until MAX_RETRY) {
                    val r = t4Transceive(cmd)
                    if (r != null && r.isOkay()) { got = r.bytes ?: ByteArray(0); break }
                    Thread.sleep(RETRY_PAUSE_MS)
                }
                if (got == null) { ok = false; break }
                out.write(got)
            }
            if (ok) {
                val data = out.toByteArray()
                if (data.size == readLen) return data
                OpenNovLog.d { "Read length mismatch ${data.size} vs $readLen" }
            }
        }
        return null
    }

    fun readContainerData(): Boolean {
        val r = t4Transceive(T4Read.encode(0, CC_SIZE))
        val data = r?.bytes
        if (r == null || !r.isOkay() || data == null || data.size != CC_SIZE) {
            OpenNovLog.d { "Failed to read container data: ${Hex.of(data)}" }
            return false
        }
        val b = ByteBuffer.wrap(data)
        b.u16() // cc length
        b.u8() // mapping version
        mleMax = b.u16()
        mlcMax = b.u16()
        OpenNovLog.d { "mleMax: $mleMax mlcMax: $mlcMax" }
        return mleMax > 0 && mlcMax > 7
    }

    fun doNeededSelection() = transceiveOkay(T4Select.aSelect(), "Failed to select application") &&
        transceiveOkay(T4Select.ccSelect(), "Failed to select container") &&
        readContainerData() &&
        transceiveOkay(T4Select.ndefSelect(), "Failed to select ndef")

    companion object {
        private const val MAX_READ_SIZE = 255
        private const val RETRY_PAUSE_MS = 50L
        private const val MAX_RETRY = 3
        private const val CC_SIZE = 15
    }
}

/** PHD record sequence numbers: the pen's messages carry the even ones, ours the odd ones (mod 16). */
class PHDllHelper(private val ts: T4Transceiver) {
    private var sequence = 0

    fun extractInnerPacket(res: ByteArray?, sendAck: Boolean): ByteArray? {
        val p = PHDll.parse(res) ?: return null
        if (p.seq != sequence) {
            OpenNovLog.d { "Unexpected sequence ${p.seq} vs $sequence" }
            return null
        }
        if (sendAck) ts.writeToLinkLayer(EMPTY_NDEF)
        return p.inner
    }

    fun writeInnerPacket(inner: ByteArray): Boolean {
        val outer = PHDll(seq = ++sequence, inner = inner).encode()
        sequence = (sequence + 1) and 0x0F
        return ts.writeToLinkLayer(outer)
    }

    companion object {
        private val EMPTY_NDEF = Hex.bytes("D0 00 00")
    }
}
