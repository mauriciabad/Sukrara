// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.sensor.SensorProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What [PairingStore.load] found. */
sealed interface PairingLoad {
    /** No sensor set up. */
    data object None : PairingLoad

    data class Ok(val pairing: Pairing) : PairingLoad

    /**
     * Something is stored but can't be used (unreadable, Keystore key lost after a restore, wrong sizes). Never used
     * partially: the person is asked to scan the sensor again (a takeover gives a fresh pairing).
     */
    data class Corrupt(val reason: String) : PairingLoad
}

/**
 * The saved sensor. The WHOLE record (address, start, PIN, authorization) is one SecureStore entry [NAME] (AES-GCM with
 * the Keystore key `sukrara.sensor`): one atomic write, so a crash can never leave a PIN or authorization that belongs
 * to another sensor. V1's own format (no PoC compatibility, D1).
 */
class PairingStore(private val secure: SecureStore) {
    companion object {
        const val NAME = "sensor"
        private const val PIN_BYTES = 4
        private const val KAUTH_BYTES = 149
        private val json = Json { ignoreUnknownKeys = false }
    }

    @Serializable
    private data class Record(
        val serial: String,
        val address: String,
        val startEpoch: Long,
        val securityVersion: Int,
        val wearMin: Int,
        val warmupMin: Int,
        val profile: SensorProfile,
        val pinHex: String,
        val kAuthHex: String? = null,
        val ended: Boolean = false,
    )

    suspend fun load(): PairingLoad {
        val bytes = try {
            secure.read(NAME) ?: return PairingLoad.None
        } catch (e: Exception) {
            return PairingLoad.Corrupt("secure storage unreadable (${e.javaClass.simpleName})")
        }
        return try {
            val r = json.decodeFromString(Record.serializer(), bytes.decodeToString())
            val pin = r.pinHex.hexToByteArray()
            val kAuth = r.kAuthHex?.hexToByteArray()
            when {
                pin.size != PIN_BYTES -> PairingLoad.Corrupt("PIN has ${pin.size} bytes")
                kAuth != null && kAuth.size != KAUTH_BYTES -> PairingLoad.Corrupt("authorization has ${kAuth.size} bytes")
                r.serial.isEmpty() || r.address.isEmpty() -> PairingLoad.Corrupt("serial or address missing")
                else -> PairingLoad.Ok(
                    Pairing(r.serial, r.address, r.startEpoch, r.securityVersion, r.wearMin, r.warmupMin, r.profile, pin, kAuth, r.ended),
                )
            }
        } catch (e: IllegalArgumentException) { // SerializationException and bad hex are IllegalArgumentExceptions
            PairingLoad.Corrupt("record unreadable (${e.javaClass.simpleName})")
        }
    }

    /** Replaces the saved sensor (an NFC tap: always a new PIN, so the caller passes kAuth = null). */
    suspend fun save(p: Pairing) {
        val r = Record(
            p.serial, p.address.uppercase(), p.startEpoch, p.securityVersion, p.wearMin, p.warmupMin, p.profile,
            p.pin.toHexString(), p.kAuth?.toHexString(), p.ended,
        )
        secure.write(NAME, json.encodeToString(Record.serializer(), r).encodeToByteArray())
    }

    /** The first Bluetooth pairing after a scan finished: keep its authorization if [serial] is still the saved sensor. */
    suspend fun setAuthorization(serial: String, kAuth: ByteArray) = updateIf(serial) { it.copy(kAuth = kAuth) }

    /** The sensor has ended for good: don't connect to it again until a new scan. */
    suspend fun markEnded(serial: String) = updateIf(serial) { it.copy(ended = true) }

    suspend fun forget() = secure.delete(NAME)

    private suspend fun updateIf(serial: String, change: (Pairing) -> Pairing) {
        val current = (load() as? PairingLoad.Ok)?.pairing ?: return
        if (current.serial == serial) save(change(current))
    }
}
