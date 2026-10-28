// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.pen.opennov.NotAPenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/*
 * Insulin pen NFC entry points, declared disabled in this module's manifest; both are enabled at runtime only while
 * pens can be read (the wearer's phone with NFC, Settings › Insulin pens not Off): the tap filter also matches bank
 * cards, so it must not be live when nobody reads pens.
 */

/**
 * The pen tap's entry point (manifest: TECH_DISCOVERED, IsoDep + NfcA, protected by DISPATCH_NFC_MESSAGE so only the
 * NFC service can start it). No UI: it hands the tag to [PenReadActivity] (not exported) and finishes.
 */
class PenTapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)?.let { tag ->
            startActivity(
                Intent(this, PenReadActivity::class.java).putExtra(NfcAdapter.EXTRA_TAG, tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }
}

/**
 * The pen tap from any app: a small translucent window at the bottom (no scrim, the app behind stays usable) reads the
 * pen on a background thread, vibrates, shows the result card and closes by itself after the user's accessibility
 * timeout (never while TalkBack is inside it). A tap outside, Back or *Close* closes it at once. A tag that never
 * answers the pen's selection (a bank card) shows nothing at all.
 */
class PenReadActivity : ComponentActivity() {
    private val module get() = (application as PensHost).pens

    /** This window shows the card only once its own tag answered as a pen (a bank card leaves the last card alone). */
    private val showing = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        // a debug preview (a made-up read the debug receiver applied) shows the card it left; a recreated window
        // (rotation, dark mode) shows the card it had and never reads the tag again
        if (intent.getBooleanExtra(PREVIEW, false) || savedInstanceState != null) {
            showing.value = true
        } else {
            start(intent)
        }
        setContent {
            SukraraTheme {
                val st by module.pens.read.collectAsState()
                val shown by showing
                var touched by remember { mutableIntStateOf(0) }
                val s = st
                if (shown && s == null) LaunchedEffect(Unit) { finish() }
                if (shown && s != null) {
                    val card = remember(s) { PenCardText(this).card(s) }
                    PenReadCard(
                        card = card,
                        reading = s.stage == PenReadState.Stage.Reading,
                        canUndo = module.pens.canUndo(s.undo),
                        onAction = ::act,
                        onClose = ::finish,
                        onTouch = { touched++ },
                        text = PenCardText(this),
                    )
                    // a card that asks for a decision waits 60 s; a plain result at least 6 s, longer with the
                    // accessibility timeout; any touch on the card restarts the time
                    LaunchedEffect(s, touched) {
                        if (s.stage == PenReadState.Stage.Reading) return@LaunchedEffect
                        val asks = card.actions.any { it !is PenAction.Undo && it !is PenAction.AddTreatment } || s.report?.first == true
                        val am = getSystemService(AccessibilityManager::class.java)
                        val ms =
                            maxOf(
                                6000,
                                am.getRecommendedTimeoutMillis(
                                    if (asks) 60_000 else 6000,
                                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
                                ),
                            )
                        // never closes under TalkBack: with touch exploration on it stays until *Close*, Back or a tap outside
                        if (am.isTouchExplorationEnabled) return@LaunchedEffect
                        delay(ms.toLong())
                        finish()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        start(intent)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * Reads the tag in the module's scope, not this window's: closing the card (a tap outside, the screen locking)
     * never cancels a read or a write half way. The window only shows [Pens.read].
     */
    private fun start(i: Intent) {
        val tag = i.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        if (tag == null || !PenTag.isPen(tag)) {
            finish()
            return
        }
        val view = window.decorView
        val module = module
        module.scope.launch {
            module.ready()
            if (!module.availableNow()) {
                view.post { finish() }
                return@launch
            }
            var selected = false
            var lastProgress = 0f
            val res =
                withContext(Dispatchers.IO) {
                    runCatching {
                        PenTag.read(
                            tag,
                            onSelected = {
                                selected = true
                                module.pens.startedReading()
                                view.post {
                                    showing.value = true
                                    PenHaptics.play(view, PenHaptics.Moment.Tap)
                                }
                            },
                            progress = { p ->
                                if (p > lastProgress) view.post { PenHaptics.play(view, PenHaptics.Moment.Segment) }
                                lastProgress = p
                            },
                            stopBefore = { s -> runBlocking { module.pens.stopBefore(s) } },
                        )
                    }
                }
            val read = res.getOrNull()
            when (PenTag.outcome(selected, res.exceptionOrNull() ?: if (read == null) NotAPenException() else null)) {
                // not a pen: no card, no vibration, no notification, nothing logged
                PenTag.Outcome.NotPen -> {
                    view.post { if (!showing.value) finish() }
                }

                PenTag.Outcome.Failed -> {
                    module.pens.failed()
                    view.post { PenHaptics.play(view, PenHaptics.Moment.Failure) }
                }

                PenTag.Outcome.Pen -> {
                    val st =
                        runCatching { module.pens.onRead(read!!) }
                            .onFailure { Log.e(TAG, "pen read not applied", it) }
                            .getOrElse {
                                module.pens.failed()
                                view.post { PenHaptics.play(view, PenHaptics.Moment.Failure) }
                                return@launch
                            }
                    if (st == null) {
                        view.post { finish() }
                        return@launch
                    }
                    val r = st.report
                    val nothing =
                        r != null && !r.first && r.created.isEmpty() && r.merged.isEmpty() && r.updated.isEmpty() && r.asked.isEmpty()
                    view.post { PenHaptics.play(view, if (nothing) PenHaptics.Moment.Nothing else PenHaptics.Moment.Success) }
                    runCatching { module.notices.read(st, module.notifyAfterRead(), module.pens.canUndo(st.undo)) }
                }
            }
        }
    }

    companion object {
        /** Debug builds only: show the card of a read the debug receiver applied (no tag). */
        const val PREVIEW = "app.mauri.sukrara.pen.extra.PREVIEW"
        private const val TAG = "SukraraPen"
    }

    /** The card's buttons, in the module's scope (closing the card never cancels an answer half way). */
    private fun act(a: PenAction) {
        val view = window.decorView
        when (a) {
            PenAction.AddTreatment -> {
                startActivity(PenNotices.addTreatment(this))
                finish()
                return
            }

            PenAction.No -> {
                finish()
            }

            else -> {
                Unit
            }
        }
        val module = module
        module.scope.launch {
            val done =
                runCatching {
                    when (a) {
                        PenAction.Undo -> module.pens.undoLast().also { if (it) module.notices.cancelRead() }
                        PenAction.TurnOn -> module.pens.turnOn().let { true }
                        PenAction.No -> module.pens.decline().let { true }
                        is PenAction.Suggest -> module.pens.acceptSuggestion(a.question, a.keys)
                        is PenAction.AllPriming -> module.pens.allPriming(a.question, a.keys)
                        is PenAction.Same -> module.pens.answerMatch(a.question, a.entry, a.keys)
                        PenAction.AddTreatment -> true
                    }
                }.onFailure { Log.e(TAG, "pen action failed", it) }
                    .getOrDefault(false)
            // a refused button (too late, the question changed) is felt, never silent
            view.post { PenHaptics.play(view, if (done) PenHaptics.Moment.Undo else PenHaptics.Moment.Failure) }
            runCatching { module.syncNotices() }
        }
    }
}
