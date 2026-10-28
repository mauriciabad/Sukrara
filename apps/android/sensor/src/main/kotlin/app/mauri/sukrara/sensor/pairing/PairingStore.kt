// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.pairing

import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.SensorSetup
import app.mauri.sukrara.sensor.ble.FailReason
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    data class Corrupt(val reason: String, val transient: Boolean = false) : PairingLoad
}

/**
 * The saved sensor. The WHOLE record (address, start, PIN, authorization) is one SecureStore entry [NAME] (AES-GCM with
 * the Keystore key `sukrara.sensor`): one atomic write, so a crash can never leave a PIN or authorization that belongs
 * to another sensor. Sukrara's own format.
 */
class PairingStore(private val secure: SecureStore) {
    private val writes = Mutex()

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
        val deviceName: String = "",
        val pairedAt: Long = 0,
        /** [FailReason] name, or null. */
        val failed: String? = null,
        val skipAddresses: List<String> = emptyList(),
    )

    suspend fun load(): PairingLoad {
        val bytes =
            try {
                secure.read(NAME) ?: return PairingLoad.None
            } catch (e: SecretUnreadableException) {
                return PairingLoad.Corrupt("secure storage unreadable", transient = e.transient)
            } catch (e: Exception) {
                return PairingLoad.Corrupt("secure storage unreadable (${e.javaClass.simpleName})")
            }
        return try {
            val r = json.decodeFromString(Record.serializer(), bytes.decodeToString())
            val pin = r.pinHex.hexToByteArray()
            val kAuth = r.kAuthHex?.hexToByteArray()
            val problem =
                if (r.profile == SensorProfile.Libre3) {
                    when {
                        pin.size != PIN_BYTES -> "PIN has ${pin.size} bytes"
                        kAuth != null && kAuth.size != KAUTH_BYTES -> "authorization has ${kAuth.size} bytes"
                        r.serial.isEmpty() || r.address.isEmpty() -> "serial or address missing"
                        else -> null
                    }
                } else {
                    // a scanned family: no PIN; the id, if the family has one, is what every key comes from
                    val setup = r.profile.setup
                    when {
                        !r.profile.needsScan -> "${r.profile.id} can't be paired"
                        pin.isNotEmpty() || kAuth != null -> "secrets on a ${r.profile.id} record"
                        setup is SensorSetup.TypedId && !setup.valid(r.serial) -> "${r.profile.id} id has the wrong form"
                        r.failed != null && FailReason.entries.none { it.name == r.failed } -> "unknown failure ${r.failed}"
                        else -> null
                    }
                }
            when {
                problem != null -> {
                    PairingLoad.Corrupt(problem)
                }

                else -> {
                    PairingLoad.Ok(
                        Pairing(
                            r.serial,
                            r.address,
                            r.startEpoch,
                            r.securityVersion,
                            r.wearMin,
                            r.warmupMin,
                            r.profile,
                            pin,
                            kAuth,
                            r.ended,
                            r.deviceName,
                            r.pairedAt,
                            r.failed?.let { FailReason.valueOf(it) },
                            r.skipAddresses,
                        ),
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            // SerializationException and bad hex are IllegalArgumentExceptions
            PairingLoad.Corrupt("record unreadable (${e.javaClass.simpleName})")
        }
    }

    /** Replaces the saved sensor (an NFC tap: always a new PIN, so the caller passes kAuth = null). */
    suspend fun save(p: Pairing) = writes.withLock { write(p) }

    /**
     * A new pairing of a scanned family replaces the saved one. The devices the old one skipped, and the old sensor
     * itself once it has ended, stay skipped when it is the same family: a G7 still bonded and in its grace period is
     * never picked for the new sensor.
     */
    suspend fun saveScanned(p: Pairing) =
        writes.withLock {
            val old = (load() as? PairingLoad.Ok)?.pairing?.takeIf { it.profile.scanGroup() == p.profile.scanGroup() }
            val skip =
                when {
                    old == null -> p.skipAddresses
                    old.ended && old.address.isNotEmpty() -> old.skipping(old.address)
                    else -> old.skipAddresses
                }
            write(p.copy(skipAddresses = skip, failed = null))
        }

    /** The pairing failed in a way only the person can fix: kept (no retry after a restart) until a new pairing. */
    suspend fun markFailed(ranWith: Pairing, reason: FailReason) =
        updateIf({ it.sameScanned(ranWith) }) { it.copy(failed = reason, address = "", deviceName = "") }

    /** The scan picked a device that isn't this pairing's sensor (an old one): never pick it again, scan again. */
    suspend fun skipDevice(ranWith: Pairing, address: String) =
        updateIf(
            { it.sameScanned(ranWith) },
        ) { it.copy(skipAddresses = it.skipping(address), address = "", deviceName = "", startEpoch = 0) }

    private suspend fun write(p: Pairing) {
        val r =
            Record(
                p.serial,
                p.address.uppercase(),
                p.startEpoch,
                p.securityVersion,
                p.wearMin,
                p.warmupMin,
                p.profile,
                p.pin.toHexString(),
                p.kAuth?.toHexString(),
                p.ended,
                p.deviceName,
                p.pairedAt,
                p.failed?.name,
                p.skipAddresses,
            )
        secure.write(NAME, json.encodeToString(Record.serializer(), r).encodeToByteArray())
    }

    /**
     * The first Bluetooth pairing after a scan finished: keep its authorization only if [ranWith] (the pairing the
     * driver used) is still the saved one — same serial, start and PIN. A late call from a driver whose sensor was
     * re-scanned meanwhile (new PIN) is ignored, so an old authorization never lands on a new PIN.
     */
    suspend fun setAuthorization(ranWith: Pairing, kAuth: ByteArray) = updateIf({ it.sameSession(ranWith) }) { it.copy(kAuth = kAuth) }

    /**
     * A scan found the sensor [ranWith] describes: keep its address (later connections go straight to it, no more
     * scanning) and advertised name. Ignored when another sensor was paired meanwhile.
     */
    suspend fun setFound(ranWith: Pairing, address: String, name: String) =
        updateIf({ it.sameScanned(ranWith) }) { it.copy(address = address.uppercase(), deviceName = name) }

    /** The sensor said when its session started (a scanned family): the warm-up and the end count from it. */
    suspend fun setStart(ranWith: Pairing, startEpoch: Long) = updateIf({ it.sameScanned(ranWith) }) { it.copy(startEpoch = startEpoch) }

    /** Families a scan finds the same way (G7 10 d and 15 d / Stelo are one radio family). */
    private fun SensorProfile.scanGroup(): SensorProfile = if (this == SensorProfile.DexcomG7Long) SensorProfile.DexcomG7 else this

    /** The sensor has ended for good: don't connect to it again until a new scan. */
    suspend fun markEnded(ranWith: Pairing) =
        updateIf({ if (it.profile.needsScan) it.sameScanned(ranWith) else it.serial == ranWith.serial }) { it.copy(ended = true) }

    suspend fun forget() = writes.withLock { secure.delete(NAME) }

    private suspend fun updateIf(matches: (Pairing) -> Boolean, change: (Pairing) -> Pairing) =
        writes.withLock {
            val current = (load() as? PairingLoad.Ok)?.pairing ?: return@withLock
            if (matches(current)) write(change(current))
        }

    /** The same pairing (family, id and when it was made): a late call from a replaced driver changes nothing. */
    private fun Pairing.sameScanned(o: Pairing) = profile == o.profile && serial == o.serial && pairedAt == o.pairedAt

    private fun Pairing.sameSession(o: Pairing) = serial == o.serial && startEpoch == o.startEpoch && pin.contentEquals(o.pin)
}
