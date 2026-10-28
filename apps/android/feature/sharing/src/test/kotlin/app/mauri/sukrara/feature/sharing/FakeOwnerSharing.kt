// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.protocol.event.Profile
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

    override suspend fun startFresh(profile: Profile): Boolean {
        calls += "startFresh:${profile.name}"
        return true
    }

    var profileAccepted = true

    override suspend fun setProfile(profile: Profile): Boolean {
        calls += "profile:${profile.name}"
        return profileAccepted
    }

    var writerAccepted = true

    override suspend fun setWriter(follower: String, allowed: Boolean): Boolean {
        calls += "writer:$follower:$allowed"
        return writerAccepted
    }

    override suspend fun exportRecovery(password: String): String? {
        calls += "recovery"
        return "ncryptsec1test"
    }

    override suspend fun recoverySaved() {
        calls += "recoverySaved"
    }

    override suspend fun saveToPasswordManager(save: suspend (id: String, secret: String) -> Boolean): Boolean {
        calls += "passwordManager"
        return save("Sukrara · npub1test", "nsec1test")
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

    var approveAccepted = true

    override suspend fun approve(follower: String, typedCode: String?): Boolean {
        calls += "approve:$follower" + (typedCode?.let { ":$it" } ?: "")
        return approveAccepted
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
