// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.update

/**
 * There is no API to rank a Live Update below another one (the newest wins, and with ≥2 chips all become
 * icon-only anyway). So: watch other apps' promoted notifications and stop asking for promotion while any exist.
 * Listeners see FLAG_PROMOTED_ONGOING on other apps' notifications (it's not redacted).
 */
class ChipYieldListener : NotificationListenerService() {
    override fun onListenerConnected() = refresh()
    override fun onNotificationPosted(sbn: StatusBarNotification) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = refresh()

    private fun refresh() {
        val others = runCatching { activeNotifications }.getOrNull().orEmpty()
            .filter { it.packageName != packageName && it.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0 }
            .map { it.packageName }
            .distinct()
        if (others != Hub.state.value.otherLiveUpdates) {
            Hub.state.update { it.copy(otherLiveUpdates = others) }
            if (others.isNotEmpty()) Hub.note("Live Update from ${others.joinToString()} → yielding the chip")
        }
    }
}
