// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing

import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.share.owner.OwnerEngine
import app.mauri.sukrara.share.owner.OwnerShareState
import app.mauri.sukrara.share.owner.usecase.CreateInvite
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** A created invite: the link (also the QR) and when it stops working (epoch s). */
data class Invite(val link: String, val label: String, val expiresAt: Long)

/**
 * What the sharing screens do with the wearer's sharing (the [OwnerEngine]); a port so screens and view models
 * are tested with a fake engine and never touch relays.
 */
interface OwnerSharing {
    val state: StateFlow<OwnerShareState>

    /** A new identity ("Start fresh", confirmed first) with how the wearer appears. False when one exists or can't be read. */
    suspend fun startFresh(profile: Profile): Boolean

    /** "How you appear" (name + picture) for followers. False without an identity or while another app shares as it. */
    suspend fun setProfile(profile: Profile): Boolean

    /** "Can add entries" for an active follower; false when refused (not a follower any more, another app shares as you). */
    suspend fun setWriter(follower: String, allowed: Boolean): Boolean

    /** A recovery code (the identity encrypted with [password]); null without an identity. Slow (scrypt): off the main thread. */
    suspend fun exportRecovery(password: String): String?

    /** The wearer kept the recovery code: no more "Not backed up". */
    suspend fun recoverySaved()

    /**
     * Hands the identity to the phone's password manager: [save] gets the entry's name and the secret and says whether
     * the password manager took it. The secret never leaves this call.
     */
    suspend fun saveToPasswordManager(save: suspend (id: String, secret: String) -> Boolean): Boolean

    /** Restore from a recovery code; the UI calls it only when the [app.mauri.sukrara.feature.sharing.setup.RestoreGate] is open. */
    suspend fun restore(
        code: String,
        password: String?,
    ): RestoreIdentity.Result

    suspend fun createInvite(label: String): Invite?

    /**
     * Allow; false when it didn't work (expired or used invite, no longer waiting, another app shares as you, or a leaked
     * link without the right [typedCode] from the follower's phone).
     */
    suspend fun approve(follower: String, typedCode: String? = null): Boolean

    suspend fun deny(follower: String)

    suspend fun remove(follower: String)

    /** "I turned it off — retry" on the identity conflict screen. */
    suspend fun retryConflict()
}

/** [OwnerSharing] on the engine; [scope] = the app's process scope (the engine's relay work outlives screens). */
class EngineOwnerSharing(
    private val engine: OwnerEngine,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) : OwnerSharing {
    /** The app starts the engine for a wearer (`followRole`); setup only reads its state. */
    override val state: StateFlow<OwnerShareState> get() = engine.state

    override suspend fun startFresh(profile: Profile): Boolean = engine.createIdentity(scope, profile)

    override suspend fun setProfile(profile: Profile): Boolean = engine.setProfile(profile)

    override suspend fun setWriter(follower: String, allowed: Boolean): Boolean =
        engine.setWriter(follower, allowed)?.let { s -> (follower in s.writers) == allowed } ?: false

    override suspend fun exportRecovery(password: String): String? = engine.exportRecovery(password)

    override suspend fun recoverySaved() {
        engine.markRecoverySaved()
    }

    override suspend fun saveToPasswordManager(save: suspend (id: String, secret: String) -> Boolean): Boolean =
        engine.saveToPasswordManager(save)

    override suspend fun restore(
        code: String,
        password: String?,
    ): RestoreIdentity.Result = engine.restore(scope, code, password)

    override suspend fun createInvite(label: String): Invite? {
        val at = now()
        return engine.createInvite(label)?.let { Invite(it, label.trim(), at + CreateInvite.TTL) }
    }

    override suspend fun approve(follower: String, typedCode: String?): Boolean = engine.approve(follower, typedCode)

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
