// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.testing.InMemorySecureStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStoreTest {
    private val pin = byteArrayOf(0x9A.toByte(), 0x94.toByte(), 0x8F.toByte(), 0x6E)
    private val pairing = Pairing("3MH0001", "18:32:19:b7:1d:11", 1_700_000_000L, 0, 21_600, 60, pin = pin, kAuth = null)

    @Test
    fun roundTripWithEverythingInTheSecureStore() = runTest {
        val secure = InMemorySecureStore()
        val store = PairingStore(secure)
        assertEquals(PairingLoad.None, store.load())
        store.save(pairing)
        assertEquals(PairingLoad.Ok(pairing.copy(address = "18:32:19:B7:1D:11")), store.load())
        assertTrue(secure.read(PairingStore.NAME) != null) // the whole record, encrypted by the SecureStore
    }

    @Test
    fun authorizationAndEndedOnlyForTheSavedSerial() = runTest {
        val store = PairingStore(InMemorySecureStore())
        store.save(pairing)
        store.setAuthorization("OTHER", ByteArray(149) { 1 })
        assertEquals(null, (store.load() as PairingLoad.Ok).pairing.kAuth)
        store.setAuthorization("3MH0001", ByteArray(149) { 1 })
        store.markEnded("3MH0001")
        val p = (store.load() as PairingLoad.Ok).pairing
        assertEquals(149, p.kAuth?.size)
        assertTrue(p.ended)
        store.forget()
        assertEquals(PairingLoad.None, store.load())
    }

    @Test
    fun unreadableRecordsAreCorruptNeverPartial() = runTest {
        val secure = InMemorySecureStore()
        val store = PairingStore(secure)
        secure.write(PairingStore.NAME, "not json".encodeToByteArray())
        assertTrue(store.load() is PairingLoad.Corrupt)
        store.save(pairing.copy(pin = byteArrayOf(1, 2)))
        assertEquals(PairingLoad.Corrupt("PIN has 2 bytes"), store.load())
        store.save(pairing.copy(kAuth = ByteArray(10)))
        assertEquals(PairingLoad.Corrupt("authorization has 10 bytes"), store.load())
    }

    /** The Keystore key is gone (restore to a new phone, key invalidated): a visible state, not a crash. */
    @Test
    fun aFailingSecureStoreIsCorrupt() = runTest {
        val broken = object : SecureStore {
            override suspend fun read(name: String): ByteArray = throw java.security.GeneralSecurityException("key invalidated")

            override suspend fun write(name: String, bytes: ByteArray) = Unit

            override suspend fun delete(name: String) = Unit
        }
        assertEquals(PairingLoad.Corrupt("secure storage unreadable (GeneralSecurityException)"), PairingStore(broken).load())
    }

    @Test
    fun noSecretsInToString() {
        val text = pairing.copy(kAuth = ByteArray(149) { 0x44 }).toString()
        assertFalse(text.contains("9a948f6e", ignoreCase = true))
        assertFalse(text.contains("[-102"))
        assertFalse(text.contains("4444"))
        assertTrue(text.contains("paired=true"))
    }
}
