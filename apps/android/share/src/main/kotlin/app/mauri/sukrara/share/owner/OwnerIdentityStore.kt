// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import kotlinx.serialization.json.Json

/**
 * The wearer's sharing state, encrypted by [SecureStore] under the name [NAME] (Keystore alias
 * `sukrara.identity.owner`). A state that exists but can't be read is [Load.Unreadable] — never "no identity": starting
 * fresh would silently cut every follower off. Only an explicit restore or create replaces it ([save] with
 * `replaceUnreadable`); the secure store keeps a copy of a damaged file aside.
 */
class OwnerIdentityStore(private val secure: SecureStore) {
    sealed interface Load {
        data object Missing : Load

        data class Loaded(val state: OwnerState) : Load

        /** [transient]: the Keystore was busy, retrying may work; false: the file is damaged. */
        data class Unreadable(val transient: Boolean) : Load
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Load {
        val bytes = try {
            secure.read(NAME)
        } catch (e: SecretUnreadableException) {
            return Load.Unreadable(e.transient)
        } ?: return Load.Missing
        return runCatching { json.decodeFromString(OwnerState.serializer(), bytes.decodeToString()) }
            .fold({ Load.Loaded(it) }, { Load.Unreadable(transient = false) })
    }

    /** Refuses (returns false) to write over a state that exists but can't be read, unless [replaceUnreadable]. */
    suspend fun save(state: OwnerState, replaceUnreadable: Boolean = false): Boolean {
        if (!replaceUnreadable && load() is Load.Unreadable) return false
        secure.write(NAME, json.encodeToString(OwnerState.serializer(), state).encodeToByteArray())
        return true
    }

    companion object {
        const val NAME = "identity.owner"
    }
}
