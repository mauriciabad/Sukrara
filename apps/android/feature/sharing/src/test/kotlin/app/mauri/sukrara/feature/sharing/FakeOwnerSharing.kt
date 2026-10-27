// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import kotlinx.coroutines.flow.MutableStateFlow

/** A scripted owner engine: records every call, never touches relays. */
class FakeOwnerSharing(
    initial: OwnerShareState = OwnerShareState.NoIdentity,
    var restoreResult: RestoreIdentity.Result = RestoreIdentity.Result.WrongPassword,
    var invite: Invite? = Invite("https://sukrara.mauri.app/join#x", "Anna", 100),
) : OwnerSharing {
    override val state = MutableStateFlow(initial)
    val calls = mutableListOf<String>()

    override suspend fun startFresh(): Boolean {
        calls += "startFresh"
        return true
    }

    override suspend fun restore(
        code: String,
        password: String?,
    ): RestoreIdentity.Result {
        calls += "restore"
        return restoreResult
    }

    override suspend fun createInvite(label: String): Invite? {
        calls += "invite:$label"
        return invite
    }

    override suspend fun approve(follower: String) {
        calls += "approve:$follower"
    }

    override suspend fun deny(follower: String) {
        calls += "deny:$follower"
    }

    override suspend fun remove(follower: String) {
        calls += "remove:$follower"
    }

    override suspend fun retryConflict() {
        calls += "retry"
    }
}
