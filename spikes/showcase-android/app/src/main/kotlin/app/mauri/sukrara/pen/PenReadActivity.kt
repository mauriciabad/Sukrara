// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.ui.MainActivity
import app.mauri.sukrara.ui.PenReadCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pen tap from any app (PRODUCT §4.1, rule 17 "nothing blocks a read"): a small translucent window at the bottom
 * (no scrim, the app behind stays usable: FLAG_NOT_TOUCH_MODAL) reads the pen on a background thread, vibrates
 * (CONFIRM / REJECT, the sensor scan's pair), shows the result card and closes by itself after the user's accessibility
 * timeout (never while TalkBack is inside it). A tap outside, back or a swipe closes it at once. Each read also posts the
 * quiet *Pen reads* notification, so the result outlives the card.
 *
 * Also opened by Sukrara's own reader mode (the New sensor screen) with the tag in [NfcAdapter.EXTRA_TAG], and by the
 * debug command `penRead` (made-up reads, debug builds only).
 */
class PenReadActivity : ComponentActivity() {
    private var a11yInside = false
    private var hold = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pens.init(this)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        // a debug preview (DebugFakes penRead, emulator screenshots) stays until closed
        hold = intent.getBooleanExtra(PREVIEW, false)
        if (!hold) start(intent)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                val st by Pens.readState.collectAsState()
                var touched by remember { mutableStateOf(0) }
                st?.let { s ->
                    PenReadCard(s, onClose = ::finish, onOpen = ::openInApp, onInteract = { touched++ }, onA11y = { a11yInside = it })
                    // closes by itself (PRODUCT §4.1): the accessibility timeout, 3 s for "Nothing new", never while reading
                    LaunchedEffect(s, touched) {
                        if (s.stage == "reading" || hold) return@LaunchedEffect
                        val nothing = PenWords(ctx).card(s).title == ctx.getString(app.mauri.sukrara.R.string.pen_read_nothing)
                        val base = if (nothing) 3000 else 6000
                        val ms = ctx.getSystemService(AccessibilityManager::class.java).getRecommendedTimeoutMillis(base,
                            AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS)
                        delay(ms.toLong())
                        while (a11yInside) delay(1000)
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
        if (event.action == MotionEvent.ACTION_OUTSIDE) { finish(); return true }
        return super.onTouchEvent(event)
    }

    private fun start(i: Intent) {
        val tag = i.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        if (!Pens.enabled(this)) { finish(); return }
        if (!PenTag.isPen(tag)) { finish(); return }   // a sticker or another phone: not a pen, not a word
        Pens.reading()
        val view = window.decorView
        PenHaptics.play(view, PenHaptics.Moment.TAP)
        lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) {
                runCatching { PenTag.read(tag, progress = { p ->
                    // a tick per segment that arrived (PenHaptics keeps them ≥ 80 ms apart)
                    if (p > (Pens.readProgress.value ?: 0f)) view.post { PenHaptics.play(view, PenHaptics.Moment.SEGMENT) }
                    Pens.readProgress.value = p
                }) { Pens.stopBefore(it) }
                    ?.let { Pens.onRead(this@PenReadActivity, it) } }.getOrNull()
            }
            if (st == null) { Pens.failed(null); haptic(false) }
            else {
                val nothing = st.report?.let { it.created.isEmpty() && it.merged.isEmpty() && it.updated.isEmpty() && it.asked.isEmpty() } == true && !st.report.first
                PenHaptics.play(view, if (nothing) PenHaptics.Moment.NOTHING else PenHaptics.Moment.SUCCESS)
                notify(this@PenReadActivity, st)
            }
        }
    }

    private fun haptic(ok: Boolean) = PenHaptics.read(window.decorView, ok)

    /** Sukrara at [what] (`penEntry` / `penHistory` / `penSetup` / `penQuestion`) for [id]. */
    private fun openInApp(what: String, id: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(what, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        const val PREVIEW = "preview"

        /** The quiet *Pen reads* notification for a finished read (title and line of its card). */
        fun notify(ctx: Context, st: Pens.ReadState) {
            if (st.stage != "done" && st.stage != "check") return
            val card = PenWords(ctx).card(st)
            val r = st.report
            val entry = r?.let { (it.created + it.merged + it.updated).firstNotNullOfOrNull { id -> Treat.store.entry(id)?.second } }
            PenNotifier.read(ctx, card.title, card.lines.joinToString(" · "), entry, canUndo = entry != null)
        }

        /** Sukrara's own reader mode got a pen: the same reader and card. */
        fun fromReaderMode(ctx: Context, tag: Tag) {
            if (!Pens.enabled(ctx)) return
            ctx.startActivity(Intent(ctx, PenReadActivity::class.java).putExtra(NfcAdapter.EXTRA_TAG, tag).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
