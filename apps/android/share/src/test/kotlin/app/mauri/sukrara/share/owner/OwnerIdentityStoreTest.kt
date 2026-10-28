// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.owner.usecase.CreateIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerIdentityStoreTest {
    private val state = CreateIdentity(OwnerFixtures.context())(null, OwnerFixtures.NOW).state

    @Test fun roundTripAndMissing() = runTest {
        val store = OwnerIdentityStore(InMemorySecureStore())
        assertEquals(OwnerIdentityStore.Load.Missing, store.load())
        assertTrue(store.save(state))
        assertEquals(OwnerIdentityStore.Load.Loaded(state), store.load())
    }

    @Test fun corruptStateIsUnreadableAndNeverReplacedSilently() = runTest {
        val secure = InMemorySecureStore()
        secure.write(OwnerIdentityStore.NAME, "not json".encodeToByteArray())
        val store = OwnerIdentityStore(secure)
        assertEquals(OwnerIdentityStore.Load.Unreadable(transient = false), store.load())
        assertFalse(store.save(state))
        assertTrue(store.save(state, replaceUnreadable = true))
        assertEquals(OwnerIdentityStore.Load.Loaded(state), store.load())
    }

    @Test fun keystoreBusyIsTransient() = runTest {
        val busy = object : SecureStore {
            override suspend fun read(name: String): ByteArray? = throw SecretUnreadableException(name, transient = true, cause = null)

            override suspend fun write(name: String, bytes: ByteArray) = error("must not write")

            override suspend fun delete(name: String) = error("must not delete")
        }
        val store = OwnerIdentityStore(busy)
        assertEquals(OwnerIdentityStore.Load.Unreadable(transient = true), store.load())
        assertFalse(store.save(state))
    }

    @Test fun toStringHoldsNoSecrets() {
        assertFalse(state.toString().contains(state.identityHex))
        assertFalse(state.epochs.toString().contains(state.current.secretHex))
    }
}
