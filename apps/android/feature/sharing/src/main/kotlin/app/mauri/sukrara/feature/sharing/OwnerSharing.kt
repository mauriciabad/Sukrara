// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.share.owner.OwnerEngine
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** A created invite: the link (also the QR) and when it stops working (epoch s). */
data class Invite(val link: String, val label: String, val expiresAt: Long)

/**
 * What the sharing screens do with the wearer's sharing (chunk 13's [OwnerEngine]); a port so screens and view models
 * are tested with a fake engine and never touch relays.
 */
interface OwnerSharing {
    val state: StateFlow<OwnerShareState>

    /** A new identity ("Start fresh", confirmed first). False when one exists or can't be read. */
    suspend fun startFresh(): Boolean

    /** Restore from a recovery code; the UI calls it only when the [app.mauri.sukrara.feature.sharing.setup.RestoreGate] is open. */
    suspend fun restore(
        code: String,
        password: String?,
    ): RestoreIdentity.Result

    suspend fun createInvite(label: String): Invite?

    suspend fun approve(follower: String)

    suspend fun deny(follower: String)

    suspend fun remove(follower: String)

    /** "I turned it off — retry" on the identity conflict screen. */
    suspend fun retryConflict()
}

/** [OwnerSharing] on chunk 13's engine; [scope] = the app's process scope (the engine's relay work outlives screens). */
class EngineOwnerSharing(
    private val engine: OwnerEngine,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) : OwnerSharing {
    /** The app starts the engine for a wearer (`followRole`); setup only reads its state. */
    override val state: StateFlow<OwnerShareState> get() = engine.state

    override suspend fun startFresh(): Boolean = engine.createIdentity(scope, profile = null)

    override suspend fun restore(
        code: String,
        password: String?,
    ): RestoreIdentity.Result = engine.restore(scope, code, password)

    override suspend fun createInvite(label: String): Invite? {
        val at = now()
        return engine.createInvite(label)?.let { Invite(it, label.trim(), at + CreateInvite.TTL) }
    }

    override suspend fun approve(follower: String) {
        engine.approve(follower)
    }

    override suspend fun deny(follower: String) {
        engine.deny(follower)
    }

    override suspend fun remove(follower: String) {
        engine.remove(follower)
    }

    override suspend fun retryConflict() {
        engine.retryConflict()
    }
}
