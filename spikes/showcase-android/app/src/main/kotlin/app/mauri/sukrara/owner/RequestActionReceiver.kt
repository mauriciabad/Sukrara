// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.App
import app.mauri.sukrara.core.Notifications

/** Allow / Deny from the access-request notification (Allow only reaches here on an unlocked phone). Not exported. */
class RequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val f = i.getStringExtra("f") ?: return
        val owner = App.owner(c)
        when (i.action) {
            Notifications.ACTION_ALLOW -> owner.approve(f)
            Notifications.ACTION_DENY -> owner.deny(f)
        }
        Notifications.cancel(c, Notifications.ID_REQUEST)
    }
}
