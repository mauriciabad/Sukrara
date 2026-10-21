// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alert notification's buttons ([AlertNotifier.Actions]): *Mute <n> min*, *Slow to <n> min* and *Mute for…* (the
 * typed minutes). A reply without valid minutes changes nothing and posts the notification again (without sound), which
 * also takes the reply field's spinner away ("update the notification … to hide the direct reply UI").
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val kind = i.getStringExtra(KIND)?.takeIf { it in Kind.ALL } ?: return
        when (i.action) {
            MUTE -> Alerts.action(c, kind, slow = false, min = i.getIntExtra(MIN, 0).takeIf { it > 0 })
            SLOW -> Alerts.action(c, kind, slow = true)
            MUTE_FOR -> AlertNotifier.minutes(RemoteInput.getResultsFromIntent(i)?.getCharSequence(TYPED))
                ?.let { Alerts.action(c, kind, slow = false, min = it) } ?: Alerts.repost(c, kind)
        }
    }

    companion object {
        const val MUTE = "app.mauri.sukrara.alert.MUTE"
        const val SLOW = "app.mauri.sukrara.alert.SLOW"
        const val MUTE_FOR = "app.mauri.sukrara.alert.MUTE_FOR"
        const val KIND = "kind"
        const val MIN = "min"
        const val TYPED = "minutes"
    }
}
