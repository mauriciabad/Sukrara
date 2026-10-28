// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.owner.IdentityCheck
import app.mauri.sukrara.share.owner.requests.RequestNotifications
import app.mauri.sukrara.share.owner.requests.RequestNotifier
import org.junit.Assert.assertEquals
import org.junit.Test

/** The access-request notification stays only while its request is pending, however it was answered. */
class RequestNotifierTest {
    private val shown = mutableListOf<String>()
    private var cancels = 0
    private val notifier = RequestNotifier(
        object : RequestNotifications {
            override fun show(request: PendingRequest) {
                shown += request.follower
            }

            override fun showNotAllowed() = Unit

            override fun cancel() {
                cancels++
            }
        },
    )
    private val ana = PendingRequest("ana", "Ana", "tok", "12", 100)

    private fun sharing(vararg pending: PendingRequest) = OwnerShareState.Sharing(1, emptyList(), pending.toList(), emptyMap(), 0, 0)

    @Test fun answeredInTheAppRemovesTheNotification() {
        notifier.onRequest(ana)
        notifier.onState(OwnerShareState.Loading)
        notifier.onState(sharing(ana))
        assertEquals(0, cancels)
        notifier.onState(sharing())
        notifier.onState(sharing())
        assertEquals(listOf("ana"), shown)
        assertEquals(1, cancels)
    }

    @Test fun aConflictPausesSharingAndRemovesIt() {
        notifier.onRequest(ana)
        notifier.onState(OwnerShareState.AnotherAppSharingAsYou(IdentityCheck.Reason.RosterFromOtherDevice, true, emptyMap()))
        assertEquals(1, cancels)
    }

    @Test fun nothingShownNothingCancelled() {
        notifier.onState(sharing())
        assertEquals(0, cancels)
    }
}
