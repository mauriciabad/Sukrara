// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import app.mauri.sukrara.pen.opennov.NotAPenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        // a debug preview (a made-up read already applied by the debug receiver) shows the card it left
        if (!intent.getBooleanExtra(PREVIEW, false)) {
            module.pens.close()
            start(intent)
        }
        setContent {
            SukraraTheme {
                val st by module.pens.read.collectAsState()
                var touched by remember { mutableIntStateOf(0) }
                st?.let { s ->
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
                        val base =
                            if (card.actions.any { it !is PenAction.Undo && it !is PenAction.AddTreatment } ||
                                s.report?.first == true
                            ) {
                                60_000
                            } else {
                                6000
                            }
                        val ms =
                            maxOf(
                                6000,
                                getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(
                                    base,
                                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
                                ),
                            )
                        // never closes under TalkBack: with touch exploration on it stays until *Close*, Back or a tap outside
                        if (getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) return@LaunchedEffect
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

    private fun start(i: Intent) {
        val tag = i.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        if (tag == null || !module.available() || !PenTag.isPen(tag)) {
            finish()
            return
        }
        val view = window.decorView
        lifecycleScope.launch {
            module.ready()
            var selected = false
            var lastProgress = 0f
            val res =
                withContext(Dispatchers.IO) {
                    runCatching {
                        PenTag.read(
                            tag,
                            onSelected = {
                                selected = true
                                view.post {
                                    module.pens.startedReading()
                                    PenHaptics.play(view, PenHaptics.Moment.Tap)
                                }
                            },
                            progress = { p ->
                                if (p > lastProgress) view.post { PenHaptics.play(view, PenHaptics.Moment.Segment) }
                                lastProgress = p
                            },
                            stopBefore = { s -> kotlinx.coroutines.runBlocking { module.pens.stopBefore(s) } },
                        )
                    }
                }
            val read = res.getOrNull()
            when (PenTag.outcome(selected, res.exceptionOrNull() ?: if (read == null) NotAPenException() else null)) {
                // not a pen: no card, no vibration, no notification, nothing logged
                PenTag.Outcome.NotPen -> {
                    finish()
                }

                PenTag.Outcome.Failed -> {
                    module.pens.failed()
                    PenHaptics.play(view, PenHaptics.Moment.Failure)
                }

                PenTag.Outcome.Pen -> {
                    val st = module.pens.onRead(read!!)
                    if (st == null) {
                        finish()
                        return@launch
                    }
                    val r = st.report
                    val nothing =
                        r != null && !r.first && r.created.isEmpty() && r.merged.isEmpty() && r.updated.isEmpty() && r.asked.isEmpty()
                    PenHaptics.play(view, if (nothing) PenHaptics.Moment.Nothing else PenHaptics.Moment.Success)
                    module.notices.read(st, module.notifyAfterRead(), module.pens.canUndo(st.undo))
                }
            }
        }
    }

    companion object {
        /** Debug builds only: show the card of a read the debug receiver applied (no tag). */
        const val PREVIEW = "app.mauri.sukrara.pen.extra.PREVIEW"
    }

    private fun act(a: PenAction) {
        val view = window.decorView
        lifecycleScope.launch {
            when (a) {
                PenAction.Undo -> {
                    module.pens.read.value?.undo?.let {
                        if (module.pens.undo(it)) {
                            PenHaptics.play(view, PenHaptics.Moment.Undo)
                            module.notices.cancelRead()
                        }
                    }
                }

                PenAction.TurnOn -> {
                    module.pens.turnOn()
                }

                PenAction.No -> {
                    module.pens.decline()
                    finish()
                }

                PenAction.AddTreatment -> {
                    startActivity(PenNotices.addTreatment(this@PenReadActivity))
                    finish()
                }

                is PenAction.Suggest -> {
                    module.pens.acceptSuggestion(a.question, a.keys)
                }

                is PenAction.AllPriming -> {
                    module.pens.allPriming(a.question, a.keys)
                }

                is PenAction.Same -> {
                    module.pens.answerMatch(a.question, a.entry, a.keys)
                }
            }
            module.syncNotices()
        }
    }
}
