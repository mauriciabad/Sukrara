// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.requests

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.share.R
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.PendingRequest

/** Shows / removes the access-request notification. */
interface RequestNotifications {
    fun show(request: PendingRequest)

    fun cancel()
}

/**
 * Keeps the access-request notification true to the engine: shown for a new request, removed as soon as that request
 * is no longer pending — answered from the notification, in the app, or the sharing paused by a conflict.
 */
class RequestNotifier(private val notifications: RequestNotifications) {
    private var shown: String? = null

    fun onRequest(request: PendingRequest) {
        notifications.show(request)
        shown = request.follower
    }

    fun onState(state: OwnerShareState) {
        val follower = shown ?: return
        if (state is OwnerShareState.Loading) return
        if (state is OwnerShareState.Sharing && state.pending.any { it.follower == follower }) return
        notifications.cancel()
        shown = null
    }
}

/**
 * The notification on channel [channel] in the app's info group [group] (posted / cancelled by the app's notification
 * table, which owns ids and grouping): "Ana wants to see your glucose · Asked with your invite for Ana · 14:02", Deny
 * and Allow. Allow requires an unlocked phone ([Notification.Action.Builder.setAuthenticationRequired]). When another
 * phone tried the same invite (a leaked link) there are no actions: only the app shows who is who.
 */
class AndroidRequestNotifications(
    private val context: Context,
    private val channel: String,
    private val group: String,
    /** The app's clock format ("14:02" / "2:02 PM", the phone's 24-hour setting) for an epoch second. */
    private val time: (Long) -> String,
    private val post: (Notification) -> Unit,
    private val remove: () -> Unit,
) : RequestNotifications {
    override fun show(request: PendingRequest) {
        val time = time(request.at)
        val text = when {
            request.others > 0 -> context.getString(R.string.share_request_others)
            request.label.isNotBlank() -> context.getString(R.string.share_request_for, request.label, time)
            else -> context.getString(R.string.share_request_from, time)
        }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(context.getString(R.string.share_request_title, request.name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setGroup(group)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        if (request.others == 0) {
            builder.addAction(action(RequestActionReceiver.ACTION_DENY, R.string.share_request_deny, request.follower, auth = false))
            builder.addAction(action(RequestActionReceiver.ACTION_ALLOW, R.string.share_request_allow, request.follower, auth = true))
        }
        post(builder.build())
    }

    override fun cancel() = remove()

    private fun action(what: String, label: Int, follower: String, auth: Boolean): Notification.Action {
        val intent = Intent(context, RequestActionReceiver::class.java).setAction(what).putExtra(RequestActionReceiver.EXTRA_FOLLOWER, follower)
        val pending = PendingIntent.getBroadcast(context, what.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, context.getString(label), pending).setAuthenticationRequired(auth).build()
    }
}
