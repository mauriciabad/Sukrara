// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.core.testing.InMemorySecureStore
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.FailReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingStoreTest {
    private val pin = byteArrayOf(0x9A.toByte(), 0x94.toByte(), 0x8F.toByte(), 0x6E)
    private val pairing = Pairing("3MH0001", "18:32:19:b7:1d:11", 1_700_000_000L, 0, 21_600, 60, pin = pin, kAuth = null)

    @Test
    fun roundTripWithEverythingInTheSecureStore() =
        runTest {
            val secure = InMemorySecureStore()
            val store = PairingStore(secure)
            assertEquals(PairingLoad.None, store.load())
            store.save(pairing)
            assertEquals(PairingLoad.Ok(pairing.copy(address = "18:32:19:B7:1D:11")), store.load())
            assertTrue(secure.read(PairingStore.NAME) != null) // the whole record, encrypted by the SecureStore
        }

    /**
     * A scanned family's failure and skipped devices are kept; a late call from a replaced pairing changes nothing; a
     * new pairing of the same family clears the failure and keeps skipping the old, ended sensor.
     */
    @Test
    fun failureAndSkippedDevicesPersistAndANewPairingCarriesTheEndedSensor() =
        runTest {
            val store = PairingStore(InMemorySecureStore())
            val first = Pairing.scanned(SensorProfile.DexcomG7, "", pairedAtMs = 1L)
            store.saveScanned(first)
            store.skipDevice(first, "aa:00:00:00:00:01")
            store.setFound(first, "aa:00:00:00:00:02", "DXCM02")
            store.markEnded(first)
            store.markFailed(first.copy(pairedAt = 99L), FailReason.Refused) // a replaced driver: ignored
            var p = (store.load() as PairingLoad.Ok).pairing
            assertEquals(null, p.failed)
            assertEquals(listOf("AA:00:00:00:00:01"), p.skipAddresses)

            val second = Pairing.scanned(SensorProfile.DexcomG7, "", pairedAtMs = 2L)
            store.saveScanned(second)
            p = (store.load() as PairingLoad.Ok).pairing
            assertEquals(listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"), p.skipAddresses)
            assertFalse(p.ended)
            store.markFailed(second, FailReason.WrongId)
            p = (store.load() as PairingLoad.Ok).pairing
            assertEquals(FailReason.WrongId, p.failed)
            assertEquals("", p.address)

            store.saveScanned(Pairing.scanned(SensorProfile.DexcomG7, "", pairedAtMs = 3L))
            assertEquals(null, (store.load() as PairingLoad.Ok).pairing.failed)
            // another family starts clean
            store.saveScanned(Pairing.scanned(SensorProfile.SmartGuide, "", pairedAtMs = 4L))
            assertEquals(emptyList<String>(), (store.load() as PairingLoad.Ok).pairing.skipAddresses)
            // at most MAX_SKIPPED, newest kept
            val many =
                (1..12).fold(
                    Pairing.scanned(SensorProfile.DexcomG7, "", 5L),
                ) { acc, i -> acc.copy(skipAddresses = acc.skipping("aa:00:00:00:00:%02d".format(i))) }
            assertEquals(Pairing.MAX_SKIPPED, many.skipAddresses.size)
            assertEquals("AA:00:00:00:00:12", many.skipAddresses.last())
        }

    @Test
    fun authorizationAndEndedOnlyForTheSavedSerial() =
        runTest {
            val store = PairingStore(InMemorySecureStore())
            store.save(pairing)
            store.setAuthorization(pairing.copy(serial = "OTHER"), ByteArray(149) { 1 })
            // a late authorization from a driver that ran with the sensor's previous PIN (re-scanned meanwhile)
            store.setAuthorization(pairing.copy(pin = byteArrayOf(1, 2, 3, 4)), ByteArray(149) { 1 })
            assertEquals(null, (store.load() as PairingLoad.Ok).pairing.kAuth)
            store.setAuthorization(pairing, ByteArray(149) { 1 })
            store.markEnded(pairing)
            val p = (store.load() as PairingLoad.Ok).pairing
            assertEquals(149, p.kAuth?.size)
            assertTrue(p.ended)
            store.forget()
            assertEquals(PairingLoad.None, store.load())
        }

    @Test
    fun unreadableRecordsAreCorruptNeverPartial() =
        runTest {
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
    fun aFailingSecureStoreIsCorrupt() =
        runTest {
            val broken =
                object : SecureStore {
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
