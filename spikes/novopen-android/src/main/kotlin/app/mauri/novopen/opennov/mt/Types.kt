// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip (insulin/opennov/mt, GPL-3.0), original by JamOrHam.
// IEEE 11073-20601 message types (one file instead of xDrip's one class per type).
package app.mauri.novopen.opennov.mt

import app.mauri.novopen.opennov.OpenNovLog
import app.mauri.novopen.opennov.indexedBytes
import app.mauri.novopen.opennov.indexedString
import app.mauri.novopen.opennov.ivalue
import app.mauri.novopen.opennov.putIndexed
import app.mauri.novopen.opennov.putU16
import app.mauri.novopen.opennov.putU32
import app.mauri.novopen.opennov.putU8
import app.mauri.novopen.opennov.u16
import app.mauri.novopen.opennov.u32
import java.nio.ByteBuffer

class Apdu(val at: Int, val apduType: ApduType?, val choiceLength: Int = 0, private val choicePayload: ByteArray? = null) {
    fun encode(): ByteArray = ByteBuffer.allocate((choicePayload?.size ?: 0) + 4).putU16(at).putIndexed(choicePayload).array()
    val isError get() = apduType == ApduType.AbrtApdu
    val wantsRelease get() = apduType == ApduType.RlrqApdu

    enum class ApduType(val value: Int) {
        AarqApdu(0xE200), AareApdu(0xE300), RlrqApdu(0xE400), RlreApdu(0xE500), AbrtApdu(0xE600), PrstApdu(0xE700);

        companion object { fun findByValue(v: Int) = entries.firstOrNull { it.value == v } }
    }

    companion object {
        fun build(type: ApduType, payload: ByteArray?) = Apdu(type.value, type, choicePayload = payload).encode()

        fun parse(b: ByteBuffer): Apdu? {
            val at = b.u16()
            val type = ApduType.findByValue(at) ?: run { OpenNovLog.d { "Cannot parse apdu type: $at" }; return null }
            return Apdu(at, type, b.u16())
        }
    }
}

data class ApoepElement(
    var version: Long = -1, var encoding: Int = -1, var nomenclature: Long = -1, var functional: Long = -1,
    var systemType: Long = -1, var systemId: ByteArray = ByteArray(0), var configId: Int = -1, var recMode: Long = -1,
    var olistCount: Int = -1, var olistLen: Int = -1,
) {
    fun encode(): ByteArray = ByteBuffer.allocate(30 + systemId.size).putU32(version).putU16(encoding).putU32(nomenclature)
        .putU32(functional).putU32(systemType).putIndexed(systemId).putU16(configId).putU32(recMode).putU16(olistCount)
        .putU16(olistLen).array()

    companion object {
        const val APOEP = 20601
        const val SYS_TYPE_MANAGER = 0x80000000L
        const val SYS_TYPE_AGENT = 0x00800000L

        fun parse(b: ByteBuffer) = ApoepElement(b.u32(), b.u16(), b.u32(), b.u32(), b.u32(), b.indexedBytes(), b.u16(), b.u32(), b.u16(), b.u16())
        fun parse(bytes: ByteArray) = parse(ByteBuffer.wrap(bytes))
    }
}

class ARequest {
    var protocol = 0; private set
    var version = 0L; private set
    var elements = 0; private set
    var apoep: ApoepElement? = null; private set

    fun valid() = protocol == ApoepElement.APOEP && apoep?.systemId?.size == 8

    companion object {
        fun parse(b: ByteBuffer) = ARequest().apply {
            version = b.u32()
            elements = b.u16()
            b.u16() // len
            repeat(elements) {
                protocol = b.u16()
                val bytes = b.indexedBytes()
                if (protocol == ApoepElement.APOEP) apoep = ApoepElement.parse(bytes)
            }
        }
    }
}

class AResponse(var result: Int = 0, var protocol: Int = 0, var apoep: ApoepElement? = null) {
    /** The manager's answer to [request]: accepted (3 = "accepted-unknown-config"), the agent's APOEP element echoed. */
    constructor(request: ARequest) : this(3, ApoepElement.APOEP, request.apoep!!.copy())

    fun encode(): ByteArray {
        val e = apoep!!.apply { recMode = 0; configId = 0; systemType = ApoepElement.SYS_TYPE_MANAGER; olistCount = 0; olistLen = 0 }.encode()
        return ByteBuffer.allocate(6 + e.size).putU16(result).putU16(protocol).putIndexed(e).array()
    }

    companion object {
        fun parse(b: ByteBuffer) = AResponse(b.u16(), b.u16()).apply { if (protocol == ApoepElement.APOEP) apoep = ApoepElement.parse(b.indexedBytes()) }
    }
}

object ArgumentsSimple {
    fun encode(handle: Int): ByteArray = ByteBuffer.allocate(6).putU16(handle).putU16(0).putU16(0).array()
}

class Attribute(val type: Int, val bytes: ByteArray) {
    val atype: Atype? = Atype.findByValue(type)
    val ivalue: Long = ivalue(bytes)

    enum class Atype(val value: Int) {
        MDC_ATTR_SYS_ID(2436), MDC_ATTR_ID_INSTNO(2338), MDC_ATTR_ID_MODEL(2344), MDC_ATTR_ID_PROD_SPECN(2349),
        MDC_ATTR_ID_TYPE(2351), MDC_ATTR_METRIC_STORE_CAPAC_CNT(2369), MDC_ATTR_METRIC_STORE_SAMPLE_ALG(2371),
        MDC_ATTR_METRIC_STORE_USAGE_CNT(2372), MDC_ATTR_NUM_SEG(2385), MDC_ATTR_OP_STAT(2387), MDC_ATTR_SEG_USAGE_CNT(2427),
        MDC_ATTR_TIME_REL(2447), MDC_ATTR_UNIT_CODE(2454), MDC_ATTR_DEV_CONFIG_ID(2628), MDC_ATTR_MDS_TIME_INFO(2629),
        MDC_ATTR_METRIC_SPEC_SMALL(2630), MDC_ATTR_REG_CERT_DATA_LIST(2635), MDC_ATTR_PM_STORE_CAPAB(2637),
        MDC_ATTR_PM_SEG_MAP(2638), MDC_ATTR_ATTRIBUTE_VAL_MAP(2645), MDC_ATTR_NU_VAL_OBS_SIMP(2646),
        MDC_ATTR_PM_STORE_LABEL_STRING(2647), MDC_ATTR_PM_SEG_LABEL_STRING(2648), MDC_ATTR_SYS_TYPE_SPEC_LIST(2650),
        MDC_ATTR_CLEAR_TIMEOUT(2659), MDC_ATTR_TRANSFER_TIMEOUT(2660), MDC_ATTR_ENUM_OBS_VAL_BASIC_BIT_STR(2662);

        companion object { fun findByValue(v: Int) = entries.firstOrNull { it.value == v } }
    }

    companion object {
        fun parse(b: ByteBuffer): Attribute {
            val type = b.u16()
            val bytes = ByteArray(b.u16()).also { b.get(it) }
            return Attribute(type, bytes).also { OpenNovLog.d { "Attribute: ${it.atype} ($type) ${bytes.size}" } }
        }
    }
}

class Configuration {
    var id = -1; private set
    var handle = -1; private set
    var numberOfSegments = -1L; private set
    var totalStoredEntries = -1L; private set
    var totalStorageCapacity = -1L; private set
    var unitCode = -1L; private set

    /** Insulin units, a segment count and a usage count: the NovoPen shape xDrip knows. */
    fun isAsExpected() = unitCode == MDC_DIM_INTL_UNIT && numberOfSegments >= 0 && totalStoredEntries >= 0

    companion object {
        private const val MDC_DIM_INTL_UNIT = 5472L

        fun parse(b: ByteBuffer): Configuration? {
            val id = b.u16()
            val count = b.u16()
            b.u16() // len
            var c: Configuration? = null
            repeat(count) {
                b.u16() // class
                val handle = b.u16()
                val acount = b.u16()
                b.u16() // alen
                repeat(acount) {
                    val conf = c ?: Configuration().also { n -> n.id = id; n.handle = handle; c = n }
                    val a = Attribute.parse(b)
                    when (a.atype) {
                        Attribute.Atype.MDC_ATTR_NUM_SEG -> conf.numberOfSegments = a.ivalue
                        Attribute.Atype.MDC_ATTR_METRIC_STORE_USAGE_CNT -> conf.totalStoredEntries = a.ivalue
                        Attribute.Atype.MDC_ATTR_UNIT_CODE -> conf.unitCode = a.ivalue
                        Attribute.Atype.MDC_ATTR_METRIC_STORE_CAPAC_CNT -> conf.totalStorageCapacity = a.ivalue
                        else -> {} // value maps (MDC_ATTR_ATTRIBUTE_VAL_MAP) aren't needed to read doses
                    }
                }
            }
            return c
        }
    }
}

class ConfirmedAction(val handle: Int, val type: Int, var bytes: ByteArray? = null) {
    fun allSegments() = apply { bytes = ByteBuffer.allocate(6).putU16(ALL_SEGMENTS).putU16(2).putU16(0).array() }
    fun segment(segment: Int) = apply { bytes = ByteBuffer.allocate(2).putU16(segment).array() }
    fun encode(): ByteArray = ByteBuffer.allocate(6 + (bytes?.size ?: 0)).putU16(handle).putU16(type).putIndexed(bytes ?: ByteArray(0)).array()

    companion object {
        private const val ALL_SEGMENTS = 0x0001
        fun parse(b: ByteBuffer) = ConfirmedAction(b.u16(), b.u16(), b.indexedBytes())
    }
}

class DataApdu(val olen: Int = 0, val invokeId: Int, val dchoice: Int, val dlen: Int = 0, private val dataPayload: ByteArray? = null) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate((dataPayload?.size ?: 0) + 8)
        return b.putU16(b.capacity() - 2).putU16(invokeId).putU16(dchoice).putIndexed(dataPayload).array()
    }

    companion object {
        fun parse(b: ByteBuffer) = DataApdu(b.u16(), b.u16(), b.u16(), b.u16())
    }
}

/**
 * One 12-byte dose record: relative time (pen seconds), units (`FF 00` + tenths of a unit), flags.
 * [absoluteTime] = the phone's clock at the report minus how long ago the pen says it was ([reportRelTime] − [relativeTime]).
 */
class InsulinDose(val relativeTime: Long, val absoluteTime: Long, val units: Double, val unitsRaw: Long, val flags: Long) {
    companion object {
        private const val MAX_UNIT_VALUE = 60.0

        fun parse(b: ByteBuffer, reportRelTime: Long, nowS: Long): InsulinDose {
            val rel = b.u32()
            val raw = b.u32()
            var units = if ((raw and 0xFFFF0000L) == 0xFF000000L) (raw and 0xFFFF) / 10.0 else -1.0
            if (units > MAX_UNIT_VALUE) {
                OpenNovLog.d { "Beyond maximum units value, marking invalid: $units" }
                units = -1.0
            }
            return InsulinDose(rel, nowS - (reportRelTime - rel), units, raw, b.u32())
        }
    }
}

class EventReport(val handle: Int, val relativeTime: Long) {
    var instance = -1; private set
    var index = -1L; private set
    var count = -1L; private set
    var status = -1; private set
    val doses = ArrayList<InsulinDose>()
    var configuration: Configuration? = null; private set

    companion object {
        const val MDC_NOTI_CONFIG = 3356
        const val MDC_NOTI_SEGMENT_DATA = 3361

        fun parse(b: ByteBuffer, nowS: Long): EventReport? {
            val er = EventReport(b.u16(), b.u32())
            val eventType = b.u16()
            b.u16() // len
            OpenNovLog.d { "EventReport: handle: ${er.handle} rt: ${er.relativeTime} $eventType" }
            return when (eventType) {
                MDC_NOTI_SEGMENT_DATA -> er.apply {
                    instance = b.u16(); index = b.u32(); count = b.u32(); status = b.u16()
                    b.u16() // bcount
                    for (i in 0 until count) doses.add(InsulinDose.parse(b, relativeTime, nowS))
                }
                MDC_NOTI_CONFIG -> er.apply { configuration = Configuration.parse(b) }
                else -> { OpenNovLog.d { "Unknown event report type: $eventType" }; null }
            }
        }
    }
}

class EventRequest(
    val handle: Int = 0, val currentTime: Long = 0, val type: Int = 0, val replyLen: Int = 0,
    val reportId: Int = -1, val reportResult: Int = -1, val instance: Int = -1, val index: Int = -1, val count: Int = -1,
    var block: Int = -1, val confirmed: Boolean = false,
) {
    fun encode(): ByteArray? {
        val b = ByteBuffer.allocate(10 + replyLen).putU16(handle).putU32(currentTime).putU16(type).putU16(replyLen)
        when (replyLen) {
            4 -> b.putU16(reportId).putU16(reportResult)
            12 -> b.putU16(instance).putU16(0).putU16(index).putU16(0).putU16(count).putU8(block).putU8(if (confirmed) MARK else 0)
            0 -> {}
            else -> { OpenNovLog.d { "Unsupported replyLen: $replyLen" }; return null }
        }
        return b.array()
    }

    fun firstBlock() = apply { block = FIRST }
    fun middleBlock() = apply { block = MIDDLE }
    fun lastBlock() = apply { block = LAST }

    companion object {
        private const val FIRST = 0x80
        private const val MIDDLE = 0x00
        private const val LAST = 0x40
        private const val MARK = 0x80

        fun parse(b: ByteBuffer): EventRequest {
            val handle = b.u16(); val time = b.u32(); val type = b.u16(); val replyLen = b.u16()
            return if (replyLen == 4) EventRequest(handle, time, type, replyLen, b.u16(), b.u16())
            else EventRequest(handle, time, type, replyLen)
        }
    }
}

object IdModel {
    /** The model strings joined by spaces ("Novo Nordisk A/S NovoPen"). */
    fun parse(bytes: ByteArray): String {
        val b = ByteBuffer.wrap(bytes)
        val parts = ArrayList<String>()
        while (b.hasRemaining()) parts.add(b.indexedString())
        return parts.joinToString(" ")
    }
}

object RelativeTime {
    fun parse(bytes: ByteArray): Long = ByteBuffer.wrap(bytes).u32()
}

class SegmentEntry(val classId: Int, val metricType: Int, val otype: Int, val handle: Int, val amcount: Int, val amlen: Int) {
    var val1 = 0; private set
    var val2 = 0; private set

    companion object {
        fun parse(b: ByteBuffer): SegmentEntry {
            val se = SegmentEntry(b.u16(), b.u16(), b.u16(), b.u16(), b.u16(), b.u16())
            if (se.amlen == 4) {
                se.val1 = b.u16(); se.val2 = b.u16()
            } else {
                OpenNovLog.d { "unhandled entry length of ${se.amlen}" }
                b.position(b.position() + se.amlen)
            }
            return se
        }
    }
}

class SegmentInfoMap(val items: List<SegmentEntry>) {
    /** Dose amount, dose flags and a vendor field, the three entries a NovoPen 6 / Echo Plus segment has. */
    fun isTypical() = items.size == 3 &&
        items[0].otype == 13313 && items[0].metricType == 130 && items[0].val1 == MDC_ATTR_NU_VAL_OBS_SIMP &&
        items[1].otype == 13314 && items[1].metricType == 130 && items[1].val1 == MDC_ATTR_ENUM_OBS_VAL_BASIC_BIT_STR &&
        items[2].otype == 61440 && items[2].metricType == 130 && items[2].val1 == MDC_ATTR_ENUM_OBS_VAL_BASIC_BIT_STR

    companion object {
        private const val MDC_ATTR_NU_VAL_OBS_SIMP = 2646
        private const val MDC_ATTR_ENUM_OBS_VAL_BASIC_BIT_STR = 2662

        fun parse(bytes: ByteArray): SegmentInfoMap {
            val b = ByteBuffer.wrap(bytes)
            b.u16() // bits
            val count = b.u16()
            b.u16() // len
            return SegmentInfoMap(List(count) { SegmentEntry.parse(b) })
        }
    }
}

class SegmentInfo(val instnum: Int) {
    var usage = -1L; private set
    var map: SegmentInfoMap? = null; private set
    var processed = false

    fun isTypical() = usage >= 0 && map?.isTypical() == true

    companion object {
        fun parse(b: ByteBuffer): SegmentInfo {
            val r = SegmentInfo(b.u16())
            val acount = b.u16()
            b.u16() // length
            repeat(acount) {
                val a = Attribute.parse(b)
                when (a.atype) {
                    Attribute.Atype.MDC_ATTR_PM_SEG_MAP -> r.map = SegmentInfoMap.parse(a.bytes)
                    Attribute.Atype.MDC_ATTR_SEG_USAGE_CNT -> r.usage = a.ivalue
                    else -> {}
                }
            }
            return r
        }
    }
}

class SegmentInfoList(val scount: Int, val items: List<SegmentInfo>) {
    /** Exactly one segment of the known shape (xDrip refuses anything else). */
    fun isTypical() = items.size == 1 && items.all { it.isTypical() }
    fun nextUnprocessedId() = items.firstOrNull { !it.processed }?.instnum ?: -1
    fun nextUnprocessedCount() = items.firstOrNull { !it.processed }?.usage ?: -1
    fun hasUnprocessed() = nextUnprocessedId() >= 0
    fun markProcessed(which: Int) = items.filter { it.instnum == which }.forEach { it.processed = true }

    companion object {
        fun parse(b: ByteBuffer): SegmentInfoList {
            val scount = b.u16()
            b.u16() // length
            return SegmentInfoList(scount, List(scount) { SegmentInfo.parse(b) })
        }
    }
}

class Specification {
    var serial: String? = null; private set
    var partNumber: String? = null; private set
    var hardwareRevision: String? = null; private set
    var softwareRevision: String? = null; private set

    companion object {
        fun parse(bytes: ByteArray): Specification {
            val b = ByteBuffer.wrap(bytes)
            val r = Specification()
            val count = b.u16()
            b.u16() // size
            repeat(count) {
                val specType = b.u16()
                b.u16() // component id
                val s = b.indexedString()
                when (specType) {
                    1 -> r.serial = s
                    2 -> r.partNumber = s
                    3 -> r.hardwareRevision = s
                    4 -> r.softwareRevision = s
                    else -> OpenNovLog.d { "Unknown specification type: $specType" }
                }
            }
            return r
        }
    }
}

class TrigSegmDataXfer(val segmentId: Int, val responseCode: Int) {
    fun isOkay() = segmentId != 0 && responseCode == 0

    companion object {
        fun parse(b: ByteBuffer) = TrigSegmDataXfer(b.u16(), b.u16())
    }
}
