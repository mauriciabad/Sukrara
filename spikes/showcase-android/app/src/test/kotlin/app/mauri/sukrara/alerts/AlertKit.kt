// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import org.json.JSONObject
import org.robolectric.Shadows

/**
 * The phone's alerts as the user meets them, for the Robolectric tests of every state and exit (round f30, SPEC §2
 * "States and exits"): a clock the test moves ([advance]), readings, and the posted notifications read back from the
 * NotificationManager (ShadowNotificationManager), whose actions are sent through their own PendingIntents (what a
 * tap or a reply in the shade sends).
 */
class AlertKit(val ctx: Application) {
    val nm: NotificationManager = ctx.getSystemService(NotificationManager::class.java)
    var now = System.currentTimeMillis() / 1000
        private set
    val sent = mutableListOf<JSONObject>()

    /** A fresh install on [role] with [settings], the clock at the real now. */
    fun start(role: String = "wearer", settings: AlertSettings = AlertSettings(), doses: () -> AlertEngine.Doses = { AlertEngine.Doses() }) {
        Proto.clock = { now }
        nm.cancelAll()
        Alerts.resetForTests()
        Alerts.init(ctx)
        Alerts.update(ctx) { settings }
        Alerts.attach(ctx, role, doses = doses, publish = if (role == "wearer") ({ sent += it }) else null)
        Alerts.history.value = emptyList()
    }

    fun stop() {
        Alerts.detach()
        Alerts.resetForTests()
        nm.cancelAll()
        Proto.clock = { System.currentTimeMillis() / 1000 }
    }

    fun advance(s: Long) { now += s }

    fun reading(sgv: Int, rate: Double = 0.0) = Alerts.onReading(ctx, Reading(now, sgv, rate, "test"))

    fun notification(kind: String): Notification? = nm.activeNotifications.singleOrNull { it.id == AlertNotifier.notificationId(kind) }?.notification
    fun title(kind: String) = notification(kind)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    fun sub(kind: String) = notification(kind)?.extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
    fun buttons(kind: String) = notification(kind)?.actions?.map { it.title.toString() } ?: emptyList()
    fun input(kind: String, button: String): RemoteInput = notification(kind)!!.actions.single { it.title.toString() == button }.remoteInputs.single()
    fun choices(kind: String, button: String) = input(kind, button).choices.map { it.toString() }
    fun silent(kind: String) = notification(kind)?.group == AlertNotifier.SILENT_GROUP

    /** Taps [button] on [kind]'s notification (its own PendingIntent), or replies [typed] to it. */
    fun tap(kind: String, button: String, typed: String? = null) {
        val a = notification(kind)!!.actions.single { it.title.toString() == button }
        val i = Intent(Shadows.shadowOf(a.actionIntent).savedIntent)
        if (typed != null) RemoteInput.addResultsToIntent(a.remoteInputs, i, Bundle().apply { putCharSequence(AlertActionReceiver.TYPED, typed) })
        AlertActionReceiver().onReceive(ctx, i)
    }

    /** The history, newest first, without the time: "Low muted for 1 h (notification)". */
    fun history() = Alerts.history.value.map { it.english().substringAfter("  ") }

    /** The badge's status for [r] (what every surface draws). */
    fun status(sgv: Int) = AlertStatus.of(Reading(now, sgv, 0.0), Alerts.settings.value, emptyList(), now, Alerts.paused.value, Alerts.allMuted.value)

    /** A follower's copy of the wearer's `alert` event (SPEC §4). */
    fun remote(kind: String, id: String, state: String, sgv: Int, n: Int = 1) = Alerts.onRemote(ctx, JSONObject()
        .put("v", 2).put("type", "alert").put("id", id).put("k", kind).put("state", state).put("at", now)
        .put("t", now).put("sgv", sgv).put("r", 0.0).put("th", 70.0).put("n", n).put("tr", 0))
}
