// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.R
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.treatments.LockCountdown
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import app.mauri.sukrara.App
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.NewEntry
import app.mauri.sukrara.treatments.Treat

/**
 * Where entries from the [TreatmentDialog] go, for whichever role this phone has: the wearer's own log (followers,
 * Macs and LibreView pick it up from there), or — for a follower the wearer made a writer — sent to the wearer.
 */
object QuickEntry {
    fun canAdd(ctx: Context): Boolean = when (App.role(ctx)) {
        "owner" -> App.owner(ctx).hasIdentity()
        "follower" -> App.follower(ctx).canWrite()
        else -> false
    }

    fun save(ctx: Context, entries: List<NewEntry>) {
        when (App.role(ctx)) {
            "owner" -> {
                Treat.init(ctx)
                entries.forEach { Treat.store.add(it.k, it.a, it.at, it.note) }
                Treat.uploader.kick()
            }
            "follower" -> App.follower(ctx).let { f -> entries.forEach { f.addTreatment(it.k, it.a, it.note, it.at) } }
        }
    }

    fun saved(ctx: Context, n: Int, role: String?): String =
        ctx.resources.getQuantityString(if (role == "follower") R.plurals.quick_entries_sent else R.plurals.quick_entries_saved, n, n)
}

/**
 * The quick treatment dialog over whatever is on screen: a tap on a home-screen widget or on the glucose notification
 * opens this instead of the app (widgets: "generative functions — let the user create new content",
 * developer.android.com/develop/ui/views/appwidgets/overview). Translucent, no task of its own in Recents; when this
 * phone can't add entries (no identity yet, or a follower without "Can add entries") it opens the app instead.
 *
 * Shown over the lock screen (investigation 17): from the lock screen widget, the glucose notification, the Quick
 * Settings tile or a pen notification an entry can be added without unlocking; only "open the app" asks to unlock first.
 *
 * Lock mode (round 52): while the dialog is over the keyguard it closes by itself after *Time to save*
 * ([LockCountdown], the ring on *Cancel*), so a stray touch in a pocket can't log anything. Touches never add time;
 * *Unlock* pauses it for the unlock prompt (cancelled / failed: resumes with at least 5 s; success: the same dialog,
 * every input kept, no countdown). The screen turning off closes it at once. Checked on every resume, so a dialog
 * opened unlocked that ends up over the keyguard gets a fresh countdown and keeps its input.
 */
class QuickTreatmentActivity : ComponentActivity() {
    private val lock = LockSession()
    private var warned = false
    private var unlocking = false
    private val km by lazy { getSystemService(KeyguardManager::class.java) }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                // the person put the phone away: discard, no toast
                // (a late broadcast from an earlier screen-off, delivered after the screen came back on, is ignored)
                Intent.ACTION_SCREEN_OFF -> if (lock.countdown != null && !getSystemService(android.os.PowerManager::class.java).isInteractive) finish()
                // unlocked on the lock screen itself (fingerprint) while the dialog was up
                Intent.ACTION_USER_PRESENT -> endLock()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Features.init(this)
        if (!QuickEntry.canAdd(this)) { openApp(); return }
        val role = App.role(this)
        lock.onUnlock = ::unlock
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) },
            RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val cd = lock.countdown
            // the countdown's clock: every frame while in lock mode (the ring empties smoothly)
            LaunchedEffect(cd) {
                if (cd == null) return@LaunchedEffect
                withFrameMillis { }
                cd.firstFrame(SystemClock.elapsedRealtime())
                while (true) {
                    withFrameMillis { }
                    if (tick(cd)) break
                }
            }
            val reduced = remember { app.mauri.sukrara.pen.PenHaptics.reducedMotion(ctx) }
            // timed out: the dialog's exit (fade + slight scale, 150 ms), then the activity goes
            val shown by animateFloatAsState(if (lock.closing) 0f else 1f, tween(if (reduced) 0 else 150), label = "exit") { if (lock.closing) finish() }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                // M3 dialog scrim (scrim colour at 32 %); a tap on it dismisses, like outside a dialog
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f * shown))
                    .clickable(interactionSource = null, indication = null, onClick = ::finish)
                    .safeDrawingPadding(), contentAlignment = Alignment.Center) {
                    // TalkBack's one *Closes in 10 seconds* (a polite live region, only with touch exploration)
                    val soon = if (lock.announce) pluralStringResource(R.plurals.lock_closes_in, 10, 10) else ""
                    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = soon })
                    // the card itself swallows taps so they don't reach the scrim
                    Box(Modifier.graphicsLayer { alpha = shown; scaleX = 0.9f + 0.1f * shown; scaleY = 0.9f + 0.1f * shown }
                        .clickable(interactionSource = null, indication = null) {}) {
                      CompositionLocalProvider(LocalLockSession provides lock) {
                        TreatmentDialog(
                            onDismiss = ::finish,
                            onSave = { list ->
                                QuickEntry.save(this@QuickTreatmentActivity, list)
                                Toast.makeText(this@QuickTreatmentActivity, QuickEntry.saved(this@QuickTreatmentActivity, list.size, role), Toast.LENGTH_SHORT).show()
                            },
                            onOpenApp = ::openApp,
                            inWindow = true,
                            // *Add treatment* from a pen read (PRODUCT §4.1): the injection's time, the other insulin focused
                            focus = intent.getStringExtra("focus"),
                            initialAt = intent.getLongExtra("at", 0L).takeIf { it > 0 },
                        )
                      }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!QuickEntry.canAdd(this) || unlocking) return
        val locked = km.isKeyguardLocked
        if (locked && lock.countdown == null) startLock()
        else if (!locked) endLock()
    }

    override fun onStop() {
        super.onStop()
        // the screen went off in lock mode (stopped with the display; doesn't wait for the broadcast)
        if (lock.countdown != null && !getSystemService(android.os.PowerManager::class.java).isInteractive) finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    /** Lock mode starts (or starts again after the screen came back on over the keyguard): a fresh countdown. */
    private fun startLock() {
        val ms = Features.value.lockTreatmentS * 1000
        val am = getSystemService(AccessibilityManager::class.java)
        val recommended = am.getRecommendedTimeoutMillis(ms, AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT).toLong()
        val now = SystemClock.elapsedRealtime()
        warned = false; lock.announce = false
        lock.now = now
        lock.countdown = LockCountdown(LockCountdown.duration(Features.value.lockTreatmentS, recommended, am.isTouchExplorationEnabled), now)
    }

    /** Unlocked: an ordinary dialog again, every input kept; the focused field gets its keyboard back. */
    private fun endLock() {
        val cd = lock.countdown ?: return
        cd.stop()
        lock.countdown = null
        lock.announce = false
        lock.unlocks++
    }

    /** One frame of the countdown; true = this countdown is over (stopped or timed out). */
    private fun tick(cd: LockCountdown): Boolean {
        val now = SystemClock.elapsedRealtime()
        lock.now = now
        if (cd.stopped || lock.countdown !== cd) return true
        if (cd.expired(now)) { timeout(); return true }
        if (!warned && cd.warning(now)) {
            warned = true
            lock.warnings++
            // only typed input is worth a tick: an empty dialog closes silently
            if (lock.hasInput) window.decorView.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        }
        if (!lock.announce && cd.remaining(now) <= LockCountdown.ANNOUNCE_MS && !cd.paused &&
            getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled) lock.announce = true
        return false
    }

    /** Time's up: nothing is saved; a toast says so only if something had been typed. No haptic (from a pocket it means nothing). */
    private fun timeout() {
        if (lock.hasInput) Toast.makeText(this, R.string.lock_not_saved, Toast.LENGTH_SHORT).show()
        lock.closing = true
    }

    /** *Unlock*: the countdown pauses while the unlock prompt is up. */
    private fun unlock() {
        val cd = lock.countdown ?: return
        cd.pause(SystemClock.elapsedRealtime())
        unlocking = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { unlocking = false; endLock() }
            override fun onDismissCancelled() = resumeAfterPrompt()
            override fun onDismissError() = resumeAfterPrompt()
        })
    }

    private fun resumeAfterPrompt() {
        unlocking = false
        val cd = lock.countdown ?: return
        cd.resume(SystemClock.elapsedRealtime())
        if (cd.remaining(SystemClock.elapsedRealtime()) > LockCountdown.WARN_MS) warned = false
        if (!km.isKeyguardLocked) endLock()
    }

    private fun openApp() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchApp()
            })
            return
        }
        launchApp()
    }

    private fun launchApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
