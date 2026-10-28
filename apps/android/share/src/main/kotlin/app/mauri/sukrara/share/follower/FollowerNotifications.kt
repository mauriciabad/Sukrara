// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.follower

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.R

/**
 * What the wearer told this follower, as a notification on channel [channel] in the app's info group [group] (posted
 * by the app's notification table, which owns the id): the invite was used by another phone, the request was declined,
 * access was removed. Tapping opens the app, where Settings › Sharing shows the same state and its way out.
 */
class FollowerNotifications(
    private val context: Context,
    private val channel: String,
    private val group: String,
    private val post: (Notification) -> Unit,
) {
    fun show(notice: FollowerNotice, wearer: Profile?) {
        val name = wearer?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.share_follow_someone)
        val (title, text) =
            when (notice) {
                FollowerNotice.InviteUsed -> R.string.share_follow_used_title to R.string.share_follow_used_text
                FollowerNotice.Declined -> R.string.share_follow_declined_title to R.string.share_follow_declined_text
                FollowerNotice.Revoked -> R.string.share_follow_revoked_title to R.string.share_follow_revoked_text
            }
        val body = context.getString(text, name)
        val builder =
            Notification
                .Builder(context, channel)
                .setSmallIcon(context.applicationInfo.icon)
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setGroup(group)
                .setContentTitle(context.getString(title, name))
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE))
        }
        post(builder.build())
    }
}
