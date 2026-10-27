// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner.requests

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mauri.sukrara.share.ShareOwnerModule
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The `Application` gives the receiver the wearer's sharing graph (A4: AppGraph composes it). */
interface ShareOwnerHost {
    val shareOwner: ShareOwnerModule
}

/**
 * Allow / Deny from the access-request notification. Not exported: only the notification's explicit PendingIntents
 * reach it; Allow arrives only from an unlocked phone (the action requires authentication). Starts the engine if the
 * process was asleep (idempotent), answers within the broadcast's time budget; the key delivery is saved before it
 * is published, so a process killed right after still sends it on the next start.
 */
class RequestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val follower = intent.getStringExtra(EXTRA_FOLLOWER) ?: return
        val allow = when (intent.action) {
            ACTION_ALLOW -> true
            ACTION_DENY -> false
            else -> return
        }
        val module = (context.applicationContext as? ShareOwnerHost)?.shareOwner ?: return
        val result = goAsync()
        module.scope.launch {
            try {
                withTimeoutOrNull(ANSWER_BUDGET_MS) { module.answer(follower, allow) }
            } finally {
                module.requestNotifications.cancel()
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_ALLOW = "app.mauri.sukrara.share.ALLOW_REQUEST"
        const val ACTION_DENY = "app.mauri.sukrara.share.DENY_REQUEST"
        const val EXTRA_FOLLOWER = "follower"

        /** goAsync allows ~10 s before an ANR; the answer is saved locally well within it. */
        private const val ANSWER_BUDGET_MS = 8_000L
    }
}
