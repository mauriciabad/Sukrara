// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen.opennov

import app.mauri.sukrara.pen.opennov.mt.ARequest
import app.mauri.sukrara.pen.opennov.mt.AResponse
import app.mauri.sukrara.pen.opennov.mt.Apdu
import app.mauri.sukrara.pen.opennov.mt.Apdu.ApduType
import app.mauri.sukrara.pen.opennov.mt.ArgumentsSimple
import app.mauri.sukrara.pen.opennov.mt.Attribute
import app.mauri.sukrara.pen.opennov.mt.Configuration
import app.mauri.sukrara.pen.opennov.mt.ConfirmedAction
import app.mauri.sukrara.pen.opennov.mt.DataApdu
import app.mauri.sukrara.pen.opennov.mt.EventReport
import app.mauri.sukrara.pen.opennov.mt.EventRequest
import app.mauri.sukrara.pen.opennov.mt.IdModel
import app.mauri.sukrara.pen.opennov.mt.InsulinDose
import app.mauri.sukrara.pen.opennov.mt.RelativeTime
import app.mauri.sukrara.pen.opennov.mt.SegmentInfoList
import app.mauri.sukrara.pen.opennov.mt.Specification
import app.mauri.sukrara.pen.opennov.mt.TrigSegmDataXfer
import java.nio.ByteBuffer

/** What the session has learnt so far. [nowS] is the phone's clock (epoch s) used to place each dose report. */
class OpContext(val nowS: () -> Long = { System.currentTimeMillis() / 1000 }) {
    var specification: Specification? = null
    var relativeTime: Long = -1
    var eventRequest: EventRequest? = null
    var eventReport: EventReport? = null
    var configuration: Configuration? = null
        get() = field ?: eventReport?.configuration?.also { field = it }
    var trigSegmDataXfer: TrigSegmDataXfer? = null
    var segmentInfoList: SegmentInfoList? = null
    var model: String = ""
    var aRequest: ARequest? = null
    var apdu: Apdu? = null
    var invokeId = -1

    /** Every dose record received in this session, in the order the pen sent them (newest first). */
    val doses = ArrayList<InsulinDose>()

    fun hasConfiguration() = configuration != null

    fun isError() = apdu?.isError != false

    fun wantsRelease() = apdu?.wantsRelease == true
}

class FSA(val action: Action, val payload: ByteArray?) {
    enum class Action { WRITE, WRITE_READ, READ, LOOP, DONE }

    fun doRead() = action == Action.READ || action == Action.WRITE_READ

    companion object {
        fun empty() = FSA(Action.DONE, null)

        fun read() = FSA(Action.READ, null)

        fun writeRead(payload: ByteArray?) = FSA(Action.WRITE_READ, payload)

        fun writeNull() = writeRead(ByteArray(0))
    }
}

/** One 20601 APDU from the pen, parsed into [context]; and the manager's (our) replies built from that context. */
class Message private constructor(val context: OpContext, val length: Int) {
    var invokeId = -1
        private set
    var closed = false
        private set

    fun getAResponse(): ByteArray = Apdu.build(ApduType.AareApdu, AResponse(context.aRequest!!).encode())

    private fun prst(dchoice: Int, payload: ByteArray?, invoke: Int = context.invokeId): ByteArray? =
        if (!context.hasConfiguration()) {
            null
        } else {
            Apdu.build(ApduType.PrstApdu, DataApdu(invokeId = invoke, dchoice = dchoice, dataPayload = payload).encode())
        }

    fun getAcceptConfig() =
        context.configuration?.let { c ->
            prst(
                SCONFIRMED_EVENT_REPORT_CHOSEN,
                EventRequest(
                    currentTime = 0,
                    type = EventReport.MDC_NOTI_CONFIG,
                    replyLen = 4,
                    reportId = c.id,
                    reportResult = 0,
                ).encode(),
            )
        }

    fun getAskInformation() = prst(SGET_CHOSEN, ArgumentsSimple.encode(context.eventReport?.handle ?: 0))

    fun getConfirmedAction() = prst(CONFIRMED_ACTION, ConfirmedAction(STORE_HANDLE, MDC_ACT_SEG_GET_INFO).allSegments().encode())

    fun getXferAction(segment: Int) = prst(CONFIRMED_ACTION, ConfirmedAction(STORE_HANDLE, MDC_ACT_SEG_TRIG_XFER).segment(segment).encode())

    /** Confirms a segment-data report (as xDrip: always a "middle" block, this message's invoke id). */
    fun getConfirmedXfer(instance: Int, index: Int, count: Int, lastBlock: Boolean, specifyBlock: Boolean): ByteArray? {
        val er =
            EventRequest(
                handle = STORE_HANDLE,
                currentTime = 0xFFFFFFFFL,
                replyLen = 12,
                type = EventReport.MDC_NOTI_SEGMENT_DATA,
                instance = instance,
                index = index,
                count = count,
                confirmed = true,
            )
        if (!specifyBlock) {
            er.middleBlock()
        } else if (lastBlock) {
            er.lastBlock()
        } else if (index == 0) {
            er.firstBlock()
        } else {
            er.middleBlock()
        }
        return prst(SCONFIRMED_EVENT_REPORT_CHOSEN, er.encode(), invoke = invokeId)
    }

    fun getCloseDown(): ByteArray = Apdu.build(ApduType.RlrqApdu, ByteArray(2))

    companion object {
        private const val CONFIRMED_EVENT_REPORT_CHOSEN = 0x0101
        private const val SCONFIRMED_EVENT_REPORT_CHOSEN = 0x0201
        private const val GET_CHOSEN = 0x0203
        private const val SGET_CHOSEN = 0x0103
        private const val CONFIRMED_ACTION = 0x0107
        private const val CONFIRMED_ACTION_CHOSEN = 0x0207
        private const val MDC_ACT_SEG_GET_INFO = 0x0C0D
        private const val MDC_ACT_SEG_TRIG_XFER = 0x0C1C
        private const val STORE_HANDLE = 0x100

        fun parse(payload: ByteArray?): Message? = parse(OpContext(), payload)

        /**
         * Null for a null payload or an unknown APDU type; a message shorter than 4 bytes (an empty PHD record)
         * comes back with only its [length] set.
         */
        fun parse(context: OpContext, payload: ByteArray?): Message? {
            if (payload == null) return null
            val m = Message(context, payload.size)
            if (payload.size < 4) return m
            val b = ByteBuffer.wrap(payload)
            val apdu = Apdu.parse(b) ?: return null
            context.apdu = apdu
            when (apdu.apduType) {
                ApduType.AarqApdu -> {
                    context.aRequest = ARequest.parse(b)
                }

                ApduType.AareApdu -> {
                    AResponse.parse(b)
                }

                ApduType.RlrqApdu -> {
                    OpenNovLog.d { "Release request" }
                }

                ApduType.RlreApdu -> {
                    m.closed = true
                }

                ApduType.AbrtApdu -> {
                    OpenNovLog.d { "Received abort" }
                }

                ApduType.PrstApdu -> {
                    parsePrst(context, m, b)
                }

                null -> {}
            }
            return m
        }

        private fun parsePrst(context: OpContext, m: Message, b: ByteBuffer) {
            val d = DataApdu.parse(b)
            m.invokeId = d.invokeId
            context.invokeId = d.invokeId
            when (d.dchoice) {
                CONFIRMED_ACTION_CHOSEN -> {
                    b.u16() // handle
                    val actionType = b.u16()
                    b.u16() // len
                    when (actionType) {
                        MDC_ACT_SEG_GET_INFO -> context.segmentInfoList = SegmentInfoList.parse(b)
                        MDC_ACT_SEG_TRIG_XFER -> context.trigSegmDataXfer = TrigSegmDataXfer.parse(b)
                        else -> OpenNovLog.d { "Unknown action type: $actionType" }
                    }
                }

                CONFIRMED_EVENT_REPORT_CHOSEN -> {
                    EventReport.parse(b, context.nowS())?.let { er ->
                        context.eventReport = er
                        context.doses.addAll(er.doses)
                    }
                }

                SCONFIRMED_EVENT_REPORT_CHOSEN -> {
                    context.eventRequest = EventRequest.parse(b)
                }

                GET_CHOSEN, SGET_CHOSEN -> {
                    b.u16() // handle
                    val count = b.u16()
                    b.u16() // len
                    repeat(count) {
                        val a = Attribute.parse(b)
                        when (a.atype) { // xDrip switches on a possibly-null enum here (NPE on an unknown attribute)
                            Attribute.Atype.MDC_ATTR_ID_PROD_SPECN -> {
                                context.specification = Specification.parse(a.bytes)
                            }

                            Attribute.Atype.MDC_ATTR_TIME_REL -> {
                                context.relativeTime = RelativeTime.parse(a.bytes)
                            }

                            Attribute.Atype.MDC_ATTR_ID_MODEL -> {
                                context.model = IdModel.parse(a.bytes)
                            }

                            else -> {}
                        }
                    }
                }

                CONFIRMED_ACTION -> {
                    ConfirmedAction.parse(b)
                }

                else -> {
                    OpenNovLog.d { "Unknown dchoice: ${Integer.toHexString(d.dchoice)}" }
                }
            }
        }
    }
}
