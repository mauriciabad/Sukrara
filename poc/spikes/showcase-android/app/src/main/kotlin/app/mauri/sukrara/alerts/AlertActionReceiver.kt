// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The alert notification's two buttons ([AlertNotifier.Actions], round f30): *Mute…* (a reply: a chip like "1 h" or a
 * typed length) and *Remind less* / *Remind as usual*; the muted notification's *Unmute* and *Change time* (a *Mute…* reply). A
 * reply that isn't a length in range changes nothing and posts the notification again (without sound), which also
 * takes the reply field's spinner away ("update the notification … to hide the direct reply UI").
 */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val kind = i.getStringExtra(KIND)?.takeIf { it in Kind.ALL } ?: return
        when (i.action) {
            MUTE -> AlertNotifier.minutes(RemoteInput.getResultsFromIntent(i)?.getCharSequence(TYPED))
                ?.let { Alerts.mute(c, kind, it, notification = true) } ?: Alerts.repost(c, kind)
            RELAX -> Alerts.relax(c, kind)
            UNRELAX -> Alerts.unrelax(c, kind)
            UNMUTE -> Alerts.unmute(c, kind)
        }
    }

    companion object {
        const val MUTE = "app.mauri.sukrara.alert.MUTE"
        const val RELAX = "app.mauri.sukrara.alert.RELAX"
        const val UNRELAX = "app.mauri.sukrara.alert.UNRELAX"
        const val UNMUTE = "app.mauri.sukrara.alert.UNMUTE"
        const val KIND = "kind"
        const val TYPED = "minutes"
    }
}
