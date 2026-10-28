// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported to Kotlin from xDrip (insulin/opennov/Machine, GPL-3.0), original by JamOrHam; the empty-report and
// stop-early changes follow Juggluco's copy (Jaap Korthals Altes).
package app.mauri.novopen.opennov

/**
 * The manager side of the 20601 session: associate, accept the configuration, ask the pen's attributes, list the
 * PM-store segments, transfer each one, release.
 *
 * Differences from xDrip: no "last read success" store (xDrip skips the transfer when nothing new arrived and the
 * last read finished; we always read, or stop when [stopAfter] says the history is already known), and [complete]
 * tells a finished transfer apart from a session that just ended (xDrip reports both as success).
 */
class Machine(private val nowS: () -> Long, private val stopAfter: ((OpContext) -> Boolean)? = null) {
    enum class State {
        AWAIT_ASSOCIATION_REQ, AWAIT_CONFIGURATION, ASK_INFORMATION, AWAIT_INFORMATION, AWAIT_STORAGE_INFO,
        AWAIT_XFER_CONFIRM, AWAIT_LOG_DATA, AWAIT_CLOSE_DOWN;

        fun next() = entries[(ordinal + 1) % entries.size]
    }

    val context = OpContext(nowS)
    var state = State.AWAIT_ASSOCIATION_REQ; private set

    /** Every segment was transferred (or the pen holds none, or [stopAfter] ended it on purpose). */
    var complete = false; private set
    /** [stopAfter] ended the transfer before the whole store was sent. */
    var stoppedEarly = false; private set
    /** Doses the pen says its store holds (segment usage count), -1 until known. */
    var stored = -1L; private set
    private var requestCounter = 0
    private var currentSegmentCount = -1L

    fun processPayload(payload: ByteArray?): FSA {
        OpenNovLog.d { "state: $state payload: ${Hex.of(payload)}" }
        val msg = Message.parse(context, payload) ?: return FSA.empty() // xDrip: NPE on an unparsable APDU
        if (context.isError()) { OpenNovLog.d { "Got error response parsing message" }; return FSA.empty() }
        return processState(msg)
    }

    private fun processState(msg: Message): FSA {
        if (context.wantsRelease()) {
            OpenNovLog.d { "Remote end requests release" }
            return closeDown(msg)
        }
        when (state) {
            State.AWAIT_ASSOCIATION_REQ -> if (context.aRequest?.valid() == true) {
                state = state.next()
                return FSA.writeRead(msg.getAResponse())
            }
            State.AWAIT_CONFIGURATION -> {
                val c = context.configuration
                if (c != null && c.isAsExpected()) {
                    if (c.numberOfSegments > 1) OpenNovLog.d { "Multiple segments @ ${c.numberOfSegments}" }
                    state = state.next()
                    return FSA.writeRead(msg.getAcceptConfig())
                }
                OpenNovLog.d { "Configuration missing or not as expected" }
            }
            State.ASK_INFORMATION -> {
                state = state.next()
                return FSA.writeRead(msg.getAskInformation())
            }
            State.AWAIT_INFORMATION -> {
                if (context.specification == null) return FSA.writeRead(msg.getAskInformation())
                state = state.next()
                return FSA.writeRead(msg.getConfirmedAction())
            }
            State.AWAIT_STORAGE_INFO -> return nextSegment(msg)
            State.AWAIT_XFER_CONFIRM -> {
                if (msg.length != 0) {
                    if (context.trigSegmDataXfer?.isOkay() == true) {
                        context.trigSegmDataXfer = null
                        state = state.next()
                    } else {
                        OpenNovLog.d { "Transfer information not right - trying again" }
                        return nextSegment(msg)
                    }
                }
                return FSA.writeNull()
            }
            State.AWAIT_LOG_DATA -> {
                if (msg.length == 0) return FSA.writeNull()
                val er = context.eventReport ?: return FSA.empty()
                // Juggluco: a report with no records ends the transfer (xDrip would keep confirming it).
                if (er.count == 0L) { complete = true; return closeDown(msg) }
                val sil = context.segmentInfoList ?: return FSA.empty()
                if (currentSegmentCount == er.index + er.count) {
                    OpenNovLog.d { "Segment ${er.instance} complete @ $currentSegmentCount" }
                    sil.markProcessed(er.instance)
                    if (sil.hasUnprocessed()) return nextSegment(msg)
                    complete = true
                } else if (stopAfter?.invoke(context) == true) {
                    // Juggluco's early stop: the pen sends newest first, so once a chunk reaches doses we already
                    // know, the rest of the history is known too.
                    OpenNovLog.d { "Known history reached, closing" }
                    complete = true
                    stoppedEarly = true
                    return closeDown(msg)
                }
                return FSA.writeRead(msg.getConfirmedXfer(er.instance, er.index.toInt(), er.count.toInt(), false, false))
            }
            State.AWAIT_CLOSE_DOWN -> {
                OpenNovLog.d { if (msg.closed) "Closed down successfully" else "Didn't close down this time" }
                return FSA.empty()
            }
        }
        return FSA.empty()
    }

    private fun closeDown(msg: Message): FSA {
        state = State.AWAIT_CLOSE_DOWN
        return FSA.writeRead(msg.getCloseDown())
    }

    private fun nextSegment(msg: Message): FSA {
        if (++requestCounter > MAX_REQUESTS) { OpenNovLog.d { "Exceeded max requests" }; return FSA.empty() }
        val sil = context.segmentInfoList
        if (sil == null || !sil.isTypical()) { OpenNovLog.d { "Non typical segments" }; return FSA.empty() }
        if (stored < 0) stored = sil.items.sumOf { maxOf(0L, it.usage) }
        val segment = sil.nextUnprocessedId()
        state = State.AWAIT_XFER_CONFIRM
        if (segment < 0) {
            complete = true
            return closeDown(msg)
        }
        currentSegmentCount = sil.nextUnprocessedCount()
        if (currentSegmentCount == 0L) { // an empty store: nothing to transfer
            complete = true
            return closeDown(msg)
        }
        return FSA.writeRead(msg.getXferAction(segment))
    }

    companion object {
        private const val MAX_REQUESTS = 100
    }
}
