// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.data

import app.mauri.sukrara.core.data.secure.KeystoreSecureStore
import app.mauri.sukrara.core.data.secure.SecretName
import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SecureStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val box = SoftwareSecretBox()
    private val log = CorruptionLog()
    private val dir by lazy { tmp.newFolder("secure") }
    private val store by lazy { KeystoreSecureStore(dir, box, log, FakeClock(), Dispatchers.IO) }
    private val secret = "nsec-like secret bytes".encodeToByteArray()

    @Test
    fun `missing secret reads as null`() =
        runTest {
            assertNull(store.read(SecretName.LIBREVIEW))
        }

    @Test
    fun `round trip, stored encrypted with a format byte`() =
        runTest {
            store.write(SecretName.IDENTITY_OWNER, secret)
            assertArrayEquals(secret, store.read(SecretName.IDENTITY_OWNER))
            val raw = File(dir, "identity.owner.bin").readBytes()
            assertEquals(1.toByte(), raw[0])
            assertFalse(String(raw).contains("nsec-like"))
        }

    @Test
    fun `a damaged secret throws on every read, stays in place and is reported once`() =
        runTest {
            store.write(SecretName.IDENTITY_OWNER, secret)
            val file = File(dir, "identity.owner.bin")
            file.writeBytes(file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })

            repeat(2) { assertUnreadable(transient = false) }

            assertTrue("never turned into 'no identity'", file.exists())
            assertEquals(1, log.files.value.size)
            assertEquals(
                "secret:identity.owner",
                log.files.value
                    .single()
                    .store,
            )
            assertTrue(
                log.files.value
                    .single()
                    .quarantinedTo!!
                    .exists(),
            )
        }

    @Test
    fun `a file copied under another name doesn't open`() =
        runTest {
            store.write(SecretName.IDENTITY_OWNER, secret)
            store.write(SecretName.IDENTITY_FOLLOWER, "other".encodeToByteArray())
            File(dir, "identity.owner.bin").copyTo(File(dir, "identity.follower.bin"), overwrite = true)
            try {
                store.read(SecretName.IDENTITY_FOLLOWER)
                fail("swapped file opened")
            } catch (e: SecretUnreadableException) {
                assertFalse(e.transient)
            }
        }

    @Test
    fun `a transient keystore error throws without a report and the secret reads later`() =
        runTest {
            store.write(SecretName.NIGHTSCOUT, secret)
            box.busy = true
            assertUnreadable(name = SecretName.NIGHTSCOUT, transient = true)
            assertTrue(log.files.value.isEmpty())
            box.busy = false
            assertArrayEquals(secret, store.read(SecretName.NIGHTSCOUT))
        }

    @Test
    fun `an unknown format byte is damage`() =
        runTest {
            dir.mkdirs()
            File(dir, "sensor.bin").writeBytes(byteArrayOf(9, 1, 2, 3))
            assertUnreadable(name = SecretName.SENSOR, transient = false)
        }

    @Test
    fun `delete removes the secret, a restore write replaces a damaged one`() =
        runTest {
            store.write(SecretName.TIDEPOOL, secret)
            store.delete(SecretName.TIDEPOOL)
            assertNull(store.read(SecretName.TIDEPOOL))
            File(dir, "tidepool.bin").writeBytes(byteArrayOf(1, 0))
            assertUnreadable(name = SecretName.TIDEPOOL, transient = false)
            store.write(SecretName.TIDEPOOL, secret)
            assertArrayEquals(secret, store.read(SecretName.TIDEPOOL))
        }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown names are refused`() =
        runTest {
            store.write("password", secret)
        }

    private suspend fun assertUnreadable(
        name: String = SecretName.IDENTITY_OWNER,
        transient: Boolean,
    ) {
        try {
            store.read(name)
            fail("read returned instead of throwing")
        } catch (e: SecretUnreadableException) {
            assertEquals(transient, e.transient)
        }
    }
}
