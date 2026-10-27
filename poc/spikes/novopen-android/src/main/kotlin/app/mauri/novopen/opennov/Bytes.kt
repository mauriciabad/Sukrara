// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip (insulin/opennov + buffer/MyByteBuffer, GPL-3.0), original by JamOrHam.
package app.mauri.novopen.opennov

import java.nio.ByteBuffer

// Big-endian unsigned accessors (MyByteBuffer / BaseMessage in xDrip).
internal fun ByteBuffer.u8(): Int = get().toInt() and 0xff
internal fun ByteBuffer.u16(): Int = short.toInt() and 0xffff
internal fun ByteBuffer.u32(): Long = int.toLong() and 0xffffffffL
internal fun ByteBuffer.putU8(v: Int): ByteBuffer = put((v and 0xff).toByte())
internal fun ByteBuffer.putU16(v: Int): ByteBuffer = putShort((v and 0xffff).toShort())
internal fun ByteBuffer.putU32(v: Long): ByteBuffer = putInt(v.toInt())

/** A u16 length followed by that many bytes. */
internal fun ByteBuffer.indexedBytes(): ByteArray = ByteArray(u16()).also { get(it) }
internal fun ByteBuffer.putIndexed(b: ByteArray?): ByteBuffer {
    putU16(b?.size ?: 0)
    if (b != null) put(b)
    return this
}
internal fun ByteBuffer.indexedString(): String = String(indexedBytes(), Charsets.ISO_8859_1).replace("\u0000", "")

/** The attribute's value as an integer when it is 2 or 4 bytes long, else -1. */
internal fun ivalue(bytes: ByteArray): Long = when (bytes.size) {
    4 -> ByteBuffer.wrap(bytes).u32()
    2 -> ByteBuffer.wrap(bytes).u16().toLong()
    else -> -1
}

object Hex {
    /** Every hex digit in [s], ignoring spaces and anything else (xDrip's tolerantHexStringToByteArray). */
    fun bytes(s: String): ByteArray {
        val digits = s.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        require(digits.length % 2 == 0) { "odd hex length" }
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun of(b: ByteArray?): String = b?.joinToString("") { "%02X".format(it) } ?: "null"
}

/**
 * The read's log sink, per thread (xDrip's static `d` flag + UserError.Log). Parsers log through [OpenNovLog.d];
 * [OpenNovReader.read] installs the caller's lambda for the duration of the read.
 */
internal object OpenNovLog {
    private val sink = ThreadLocal<((String) -> Unit)?>()

    fun <T> with(log: (String) -> Unit, block: () -> T): T {
        val prev = sink.get()
        sink.set(log)
        try { return block() } finally { sink.set(prev) }
    }

    fun d(msg: () -> String) { sink.get()?.invoke(msg()) }
}
