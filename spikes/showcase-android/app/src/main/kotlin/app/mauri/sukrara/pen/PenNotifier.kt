// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.R
import app.mauri.sukrara.ui.MainActivity

/**
 * The pen's notifications (PRODUCT §4.1): *Pen reads* (low importance: no sound, no heads-up; the card and the haptic
 * already told the user) carries each read's result, replaced by the next read, gone after 2 h; *Pen doses to check*
 * (default importance, silent) carries only what waits for the user (a question) and stays until it's answered.
 */
object PenNotifier {
    const val READS = "pen_reads"
    const val CHECK = "pen_check"
    private const val ID_READ = 40
    private const val ID_CHECK = 41

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(READS, ctx.getString(R.string.channel_pen_reads), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHECK, ctx.getString(R.string.channel_pen_check), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)   // the read already vibrated
        })
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    /** Sukrara opened at [what] (`penEntry` / `penQuestion` / `penHistory` with its id). */
    fun open(ctx: Context, what: String, id: String, code: Int): PendingIntent = PendingIntent.getActivity(ctx, code,
        Intent(ctx, MainActivity::class.java).putExtra(what, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(ctx: Context, what: String, extra: String?, label: String, code: Int) = Notification.Action.Builder(null, label,
        PendingIntent.getBroadcast(ctx, code, Intent(ctx, PenActionReceiver::class.java).setAction(what).putExtra("id", extra),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    /** One read's result: the card's title and line, *Add treatment* and *Undo*; tap = the entry's sheet. */
    fun read(ctx: Context, title: String, line: String, entry: app.mauri.sukrara.treatments.Treatment?, canUndo: Boolean, setup: String? = null) {
        channels(ctx)
        val b = Notification.Builder(ctx, READS)
            .setSmallIcon(R.drawable.ic_stat_sukrara)
            .setContentTitle(title).setContentText(line)
            .setTimeoutAfter(2 * 3600_000L)
            // no amounts on the lock screen (PRODUCT §1b): the public version only says a pen was read
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(ctx, READS).setSmallIcon(R.drawable.ic_stat_sukrara)
                .setContentTitle(ctx.getString(R.string.pen_notif_public)).build())
            .setOnlyAlertOnce(true).setAutoCancel(true)
            .setContentIntent(if (setup != null) open(ctx, "penSetup", setup, 400) else open(ctx, if (entry != null) "penEntry" else "penHistory", entry?.id ?: "", 400))
        // a new (or untested) pen: *Set up* stays reachable after the card closed
        if (setup != null) b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.pen_set_up), open(ctx, "penSetup", setup, 403)).build())
        // an activity straight from the action (a receiver may not start one: Android 12 notification trampolines)
        if (entry != null) b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.pen_add_treatment),
            PendingIntent.getActivity(ctx, 401, PenWords.addTreatment(ctx, entry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        if (canUndo) b.addAction(action(ctx, PenActionReceiver.UNDO, null, ctx.getString(R.string.pen_undo), 402))
        runCatching { nm(ctx).notify(ID_READ, b.build()) }
    }

    fun cancelRead(ctx: Context) = runCatching { nm(ctx).cancel(ID_READ) }

    /** The questions still open: one notification for the oldest (with the suggestion and *All priming*), or a count. */
    fun sync(ctx: Context) {
        val open = Pens.ledger.value.open().sortedBy { it.at }
        if (open.isEmpty()) { runCatching { nm(ctx).cancel(ID_CHECK) }; return }
        channels(ctx)
        val q = open.first()
        val words = PenWords(ctx)
        val b = Notification.Builder(ctx, CHECK).setSmallIcon(R.drawable.ic_stat_sukrara).setOnlyAlertOnce(true)
            .setContentIntent(open(ctx, "penQuestion", q.id, 410))
        if (open.size > 1) {
            b.setContentTitle(ctx.resources.getQuantityString(R.plurals.pen_q_several, open.size, open.size))
        } else if (q.type == PenEngine.DOSES) {
            b.setContentTitle(ctx.getString(R.string.pen_q_doses_title))
                .setContentText(ctx.getString(R.string.pen_q_notif_text, words.doses(q), words.time(q)))
            words.suggestion(q)?.let { b.addAction(action(ctx, PenActionReceiver.SUGGEST, q.id, ctx.getString(R.string.pen_log_amount, it), 411)) }
            b.addAction(action(ctx, PenActionReceiver.PRIMING, q.id, ctx.getString(R.string.pen_all_priming), 412))
        } else {
            b.setContentTitle(ctx.getString(R.string.pen_q_same_title)).setContentText(words.matchLine(q))
        }
        runCatching { nm(ctx).notify(ID_CHECK, b.build()) }
    }
}

/** The notifications' actions: *Undo* the last read, *Log 1.5u* (the suggestion), *All priming*. */
class PenActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Pens.init(ctx)
        val id = i.getStringExtra("id")
        when (i.action) {
            UNDO -> Pens.readState.value?.undo?.let { Pens.undoRead(ctx, it) }.also { PenNotifier.cancelRead(ctx) }
            SUGGEST -> id?.let { Pens.acceptSuggestion(ctx, it) }
            PRIMING -> id?.let { Pens.allPriming(ctx, it) }
        }
    }

    companion object {
        const val UNDO = "app.mauri.sukrara.pen.UNDO"
        const val SUGGEST = "app.mauri.sukrara.pen.SUGGEST"
        const val PRIMING = "app.mauri.sukrara.pen.PRIMING"
    }
}
