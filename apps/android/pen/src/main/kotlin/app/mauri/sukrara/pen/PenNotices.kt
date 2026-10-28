// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.core.model.NavTargets
import app.mauri.sukrara.pen.engine.Ledger
import kotlinx.coroutines.launch

/** Where the pen notifications go (`AppGraph` wires the `:monitor` channels and Sukrara's info group). */
class PenNotificationPorts(
    val readsChannel: String,
    val checkChannel: String,
    val group: String,
    val post: (Kind, Notification) -> Unit,
    val cancel: (Kind) -> Unit,
) {
    enum class Kind { Read, Check }
}

/**
 * The pen's notifications: *Pen reads* carries a read's result (replaced by the next read, gone after 2 h, private on
 * the lock screen) only while *Notification after each read* is on; *Pen doses to check* carries what waits for the
 * person (a question), is ongoing, and is posted again on every change and app start until it's answered.
 */
class PenNotices(
    private val ctx: Context,
    private val ports: PenNotificationPorts,
) {
    private val text = PenCardText(ctx)

    private fun base(channel: String) =
        Notification
            .Builder(ctx, channel)
            .setSmallIcon(ctx.applicationInfo.icon)
            .setOnlyAlertOnce(true)
            .setGroup(ports.group)
            .setContentIntent(openApp())

    private fun openApp(): PendingIntent? =
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
            PendingIntent.getActivity(ctx, 400, it, PendingIntent.FLAG_IMMUTABLE)
        }

    private fun action(
        a: PenAction,
        label: String,
    ): Notification.Action {
        val intent = PenActionReceiver.intent(ctx, a)
        // one PendingIntent per action and question: a newer question can never retarget an older button
        val pending = PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun label(a: PenAction): String =
        when (a) {
            PenAction.Undo -> ctx.getString(R.string.pen_undo)
            PenAction.AddTreatment -> ctx.getString(R.string.pen_add_treatment)
            PenAction.TurnOn -> ctx.getString(R.string.pen_turn_on)
            PenAction.No -> ctx.getString(R.string.pen_no)
            is PenAction.Suggest -> a.label
            is PenAction.AllPriming -> ctx.getString(R.string.pen_all_priming)
            is PenAction.Same -> ctx.getString(if (a.entry != null) R.string.pen_m_same else R.string.pen_m_different)
        }

    /** One read's result, only when [enabled] (Settings → Insulin pens → *Notification after each read*). */
    fun read(
        st: PenReadState,
        enabled: Boolean,
        canUndo: Boolean,
    ) {
        if (!enabled || st.stage != PenReadState.Stage.Done || st.undone) return
        val card = text.card(st)
        val b =
            base(ports.readsChannel)
                .setContentTitle(card.title)
                .setContentText(card.lines.joinToString(" · "))
                .setTimeoutAfter(2 * 3600_000L)
                .setAutoCancel(true)
                // no amounts on the lock screen: the public version only says a pen was read
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    Notification
                        .Builder(ctx, ports.readsChannel)
                        .setSmallIcon(ctx.applicationInfo.icon)
                        .setContentTitle(ctx.getString(R.string.pen_notif_public))
                        .setGroup(ports.group)
                        .build(),
                )
        if (st.entries.isNotEmpty()) {
            b.addAction(
                Notification.Action
                    .Builder(
                        null,
                        ctx.getString(R.string.pen_add_treatment),
                        PendingIntent.getActivity(ctx, 401, addTreatment(ctx), PendingIntent.FLAG_IMMUTABLE),
                    ).build(),
            )
            if (canUndo) b.addAction(action(PenAction.Undo, label(PenAction.Undo)))
        }
        ports.post(PenNotificationPorts.Kind.Read, b.build())
    }

    fun cancelRead() = ports.cancel(PenNotificationPorts.Kind.Read)

    /** The notification's *Undo* came too late (something changed since, or Sukrara restarted): say so, never silently. */
    fun undoFailed() {
        val b =
            base(ports.readsChannel)
                .setContentTitle(ctx.getString(R.string.pen_undo_failed))
                .setContentText(ctx.getString(R.string.pen_undo_failed_text))
                .setTimeoutAfter(2 * 3600_000L)
                .setAutoCancel(true)
        ports.post(PenNotificationPorts.Kind.Read, b.build())
    }

    /** The questions still open: the oldest with its answers, or a count; cancelled when none is open. */
    fun sync(
        l: Ledger,
        log: List<app.mauri.sukrara.pen.engine.PenEntry>,
    ) {
        val open = l.open().sortedBy { it.at }
        if (open.isEmpty()) {
            ports.cancel(PenNotificationPorts.Kind.Check)
            return
        }
        val q = open.first()
        val b =
            base(ports.checkChannel)
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (open.size > 1) {
            b.setContentTitle(ctx.resources.getQuantityString(R.plurals.pen_q_several, open.size, open.size))
        } else {
            b.setContentTitle(text.questionTitle(q)).setContentText(text.questionLine(q, l, log))
            text.questionActions(q, l, log).forEach { b.addAction(action(it, label(it))) }
        }
        ports.post(PenNotificationPorts.Kind.Check, b.build())
    }

    /** *Pen reads* can't show: the app's notifications or that channel are blocked in Android settings. */
    fun readBlocked(): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return !nm.areNotificationsEnabled() ||
            nm.getNotificationChannel(ports.readsChannel)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    /** Android's settings page of the *Pen reads* channel. */
    fun readChannelSettings(): Intent =
        Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ports.readsChannel)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        /** The quick Add treatment dialog over the current app. */
        fun addTreatment(ctx: Context): Intent =
            Intent(NavTargets.ACTION_QUICK_TREATMENT).setPackage(ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The notifications' buttons (not exported: only Sukrara's own PendingIntents reach it). */
class PenActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        ctx: Context,
        i: Intent,
    ) {
        val module = (ctx.applicationContext as PensHost).pens
        val pending = goAsync()
        val qid = i.getStringExtra(EXTRA_QUESTION)
        val keys = i.getStringArrayExtra(EXTRA_KEYS)?.toList()
        module.scope.launch {
            try {
                module.ready()
                when (i.action) {
                    UNDO -> module.undoLast()
                    SUGGEST -> qid?.let { module.pens.acceptSuggestion(it, keys) }
                    PRIMING -> qid?.let { module.pens.allPriming(it, keys) }
                    SAME -> qid?.let { module.pens.answerMatch(it, i.getStringExtra(EXTRA_ENTRY), keys) }
                }
                // a refused (stale) answer: the notification is posted again with the question as it is now
                module.syncNotices()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val UNDO = "app.mauri.sukrara.pen.UNDO"
        const val SUGGEST = "app.mauri.sukrara.pen.SUGGEST"
        const val PRIMING = "app.mauri.sukrara.pen.PRIMING"
        const val SAME = "app.mauri.sukrara.pen.SAME"
        private const val EXTRA_QUESTION = "question"
        private const val EXTRA_KEYS = "keys"
        private const val EXTRA_ENTRY = "entry"

        fun intent(
            ctx: Context,
            a: PenAction,
        ): Intent {
            val i = Intent(ctx, PenActionReceiver::class.java)
            when (a) {
                PenAction.Undo -> {
                    i.setAction(UNDO)
                }

                is PenAction.Suggest -> {
                    i.setAction(SUGGEST).putExtra(EXTRA_QUESTION, a.question).putExtra(EXTRA_KEYS, a.keys.toTypedArray())
                }

                is PenAction.AllPriming -> {
                    i.setAction(PRIMING).putExtra(EXTRA_QUESTION, a.question).putExtra(EXTRA_KEYS, a.keys.toTypedArray())
                }

                is PenAction.Same -> {
                    i
                        .setAction(SAME)
                        .putExtra(EXTRA_QUESTION, a.question)
                        .putExtra(EXTRA_KEYS, a.keys.toTypedArray())
                        .putExtra(EXTRA_ENTRY, a.entry)
                }

                else -> {
                    Unit
                }
            }
            return i.setIdentifier("${i.action}:${(a as? PenAction.Suggest)?.question ?: ""}:${a.hashCode()}")
        }
    }
}
