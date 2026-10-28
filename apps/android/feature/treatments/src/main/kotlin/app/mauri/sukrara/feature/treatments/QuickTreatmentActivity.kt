// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.core.model.PenNearEntry
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.designsystem.theme.SukraraTheme
import kotlinx.coroutines.launch

/**
 * The quick Add treatment dialog over whatever is on screen: a tap on the glucose notification, a home-screen widget
 * or the Quick Settings tile opens this instead of the app. Translucent, no task of its own in Recents; when this
 * phone can't add entries (no role yet, a follower without "Can add entries") it opens the app instead.
 *
 * Over the lock screen an entry needs no unlock, but the dialog closes by itself, unsaved, after *Time to save*
 * ([LockCountdown]) so a stray touch in a pocket can't log anything; the screen turning off closes it at once.
 * *Unlock* pauses the countdown for the unlock prompt (success: an ordinary dialog, every input kept).
 */
class QuickTreatmentActivity : ComponentActivity() {
    private val module by lazy { treatmentsModule }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private var countdown by mutableStateOf<LockCountdown?>(null)
    private var now by mutableLongStateOf(0L)
    private var warnings by mutableIntStateOf(0)
    private var unlocking = false
    private var form = TreatmentFormState()

    /** The countdown left before a configuration change: a recreated activity never gets more time. */
    private var carriedMs: Long? = null
    private var saved = false

    private val screen =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    // put away: discard, no toast (a late broadcast after the screen came back on is ignored)
                    Intent.ACTION_SCREEN_OFF -> if (countdown != null && !interactive()) finish()

                    // unlocked on the lock screen itself (fingerprint) while the dialog was up
                    Intent.ACTION_USER_PRESENT -> endLock()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!module.canAdd()) {
            openApp()
            return
        }
        registerReceiver(
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            RECEIVER_NOT_EXPORTED,
        )
        enableEdgeToEdge()
        val focusCarbs = QuickTreatmentIntents.focusCarbs(intent) || module.lowNow()
        carriedMs = savedInstanceState?.getLong(KEY_LEFT, -1L)?.takeIf { it >= 0 }
        setContent {
            form = rememberTreatmentFormState()
            SukraraTheme {
                val cd = countdown
                val quiet by module.hints.quiet.collectAsState(QuietRules.NONE)
                LaunchedEffect(cd) { if (cd != null) runCountdown(cd) }
                // M3 dialog scrim; a tap on it dismisses, like outside a dialog
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        // a tap outside dismisses (TalkBack users have Cancel and back; no unlabeled node)
                        .pointerInput(Unit) { detectTapGestures { finish() } }
                        .safeDrawingPadding()
                        .imePadding()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    TreatmentDialogContent(
                        form = form,
                        focusCarbs = focusCarbs,
                        zone = module.clock.zone,
                        now = module.clock::now,
                        onSave = ::save,
                        onCancel = ::finish,
                        // over the lock screen Unlock takes Open Sukrara's place
                        onOpenApp = if (cd == null) ::openApp else null,
                        lock = cd?.let { lockInfo(it) },
                        quiet = quiet,
                        // the card swallows taps so they don't reach the scrim
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
                    )
                    duplicate?.let { d ->
                        PenDuplicateDialog(d.second, onCancel = { cancelDuplicate() }, onLog = { store(d.first, d.third) })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (unlocking || isFinishing) return
        if (keyguard.isKeyguardLocked && countdown == null) {
            startLock()
        } else if (!keyguard.isKeyguardLocked) {
            endLock()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        countdown?.let { outState.putLong(KEY_LEFT, it.remaining(SystemClock.elapsedRealtime())) }
    }

    override fun onStop() {
        super.onStop()
        // the screen went off in lock mode (doesn't wait for the broadcast)
        if (countdown != null && !interactive()) finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        super.onDestroy()
    }

    /** A typed insulin dose a pen already logged: the entries and time waiting for *Log anyway*. */
    private var duplicate by mutableStateOf<Triple<List<NewEntry>, PenNearEntry, java.time.Instant?>?>(null)

    private fun save(
        entries: List<NewEntry>,
        at: java.time.Instant?,
    ) {
        if (saved) return
        saved = true
        lifecycleScope.launch {
            val d = module.penDuplicate(entries, at)
            if (d != null) {
                duplicate = Triple(entries, d.second, at)
            } else {
                store(entries, at)
            }
        }
    }

    /** *Cancel* on *Already logged by your pen?*: back to the form, nothing saved. */
    private fun cancelDuplicate() {
        duplicate = null
        saved = false
    }

    private fun store(
        entries: List<NewEntry>,
        at: java.time.Instant?,
    ) {
        duplicate = null
        if (module.save(entries, at)) {
            val pending =
                module.sync.writer.value
                    ?.throughOutbox == true
            val text = if (pending) R.plurals.treatment_sent else R.plurals.treatment_saved
            Toast.makeText(this, resources.getQuantityString(text, entries.size, entries.size), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /**
     * The countdown as the dialog shows it: the digits and TalkBack's 5 s steps recompose only when they change; the
     * ring reads the time in its draw (every frame, stepped once a second with reduced motion).
     */
    @Composable
    private fun lockInfo(cd: LockCountdown): LockInfo {
        val seconds by remember(cd) { derivedStateOf { cd.wholeSeconds(now) } }
        val spoken by remember(cd) { derivedStateOf { cd.spokenSeconds(now) } }
        val reduced = LocalReducedMotion.current
        return LockInfo(seconds, ::unlock, spoken, { cd.fraction(now, stepped = reduced) }, warnings)
    }

    private suspend fun runCountdown(cd: LockCountdown) {
        cd.firstFrame(SystemClock.elapsedRealtime())
        var warned = cd.warning(SystemClock.elapsedRealtime())
        while (countdown === cd && !cd.stopped) {
            now = SystemClock.elapsedRealtime()
            if (!warned && cd.warning(now)) {
                warned = true
                // the last 5 s: the ring pulses once; one haptic tick only when something was typed (in a pocket, none)
                warnings++
                if (form.hasInput) window.decorView.performHapticFeedback(HapticFeedbackConstants.SEGMENT_TICK)
            }
            if (cd.expired(now)) {
                // nothing is saved; a toast only if something had been typed (no haptic: from a pocket it means nothing)
                if (form.hasInput) Toast.makeText(this, R.string.treatment_lock_not_saved, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            // every frame: the ring empties smoothly (its draw reads [now]; the dialog recomposes only for the digits)
            withFrameMillis { }
        }
    }

    private fun startLock() {
        val seconds = module.lockSeconds.value.toInt()
        val a11y = getSystemService(AccessibilityManager::class.java)
        val recommended =
            a11y.getRecommendedTimeoutMillis(
                seconds * 1000,
                AccessibilityManager.FLAG_CONTENT_CONTROLS or AccessibilityManager.FLAG_CONTENT_TEXT,
            )
        val start = SystemClock.elapsedRealtime()
        now = start
        val full = LockCountdown.duration(seconds, recommended.toLong(), a11y.isTouchExplorationEnabled)
        val left = carriedMs?.coerceAtMost(full) ?: full
        carriedMs = null
        countdown = LockCountdown(full, start - (full - left)).also { if (left < full) it.firstFrame(start - (full - left)) }
    }

    private fun endLock() {
        countdown?.stop()
        countdown = null
    }

    private fun unlock() {
        val cd = countdown ?: return
        cd.pause(SystemClock.elapsedRealtime())
        unlocking = true
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    unlocking = false
                    endLock()
                }

                override fun onDismissCancelled() = resumeAfterPrompt()

                override fun onDismissError() = resumeAfterPrompt()
            },
        )
    }

    private fun resumeAfterPrompt() {
        unlocking = false
        countdown?.resume(SystemClock.elapsedRealtime())
        if (!keyguard.isKeyguardLocked) endLock()
    }

    private fun interactive() = getSystemService(PowerManager::class.java).isInteractive

    /** Opens the app (after the unlock prompt when locked) and closes the dialog. */
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            finish()
            return
        }
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        startActivity(launch)
                        finish()
                    }
                },
            )
        } else {
            startActivity(launch)
            finish()
        }
    }

    private companion object {
        const val KEY_LEFT = "lock_left_ms"
    }
}

/**
 * *Already logged by your pen?*: a pen entry of insulin is this close to the typed dose. *Cancel* goes back to the form;
 * *Log anyway* saves what was typed (a second injection is the person's call).
 */
@Composable
private fun PenDuplicateDialog(
    pen: PenNearEntry,
    onCancel: () -> Unit,
    onLog: () -> Unit,
) {
    val amount = (if (pen.units % 1.0 == 0.0) "${pen.units.toLong()}" else String.format(java.util.Locale.ROOT, "%.1f", pen.units)) + "u"
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.treatment_pen_duplicate_title)) },
        text = {
            Text(
                stringResource(
                    R.string.treatment_pen_duplicate_text,
                    amount,
                    app.mauri.sukrara.graph.Clock
                        .time(pen.t),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onLog) { Text(stringResource(R.string.treatment_pen_duplicate_log)) } },
        dismissButton = { Button(onClick = onCancel) { Text(stringResource(R.string.treatment_cancel)) } },
    )
}
