// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A NIP-01 event as it travels over relays and Bluetooth. Engines and transports pass this, never library types. Its
 * signature is only trusted after [NostrCrypto.verify].
 */
@Serializable
data class SignedEvent(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    /** The first value of the first tag called [name]. */
    fun tag(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    /** NIP-40 expiration (unix s), or null. */
    val expiration: Long? get() = tag("expiration")?.toLongOrNull()

    fun isExpired(now: Long): Boolean = expiration?.let { it <= now } == true

    fun toJson(): String = WireJson.encodeToString(serializer(), this)

    companion object {
        /** null for anything that isn't an event object. */
        fun parse(json: String): SignedEvent? = runCatching { WireJson.decodeFromString(serializer(), json) }.getOrNull()
    }
}

/** An event before signing: what [NostrCrypto.sign] turns into a [SignedEvent]. */
data class UnsignedEvent(val kind: Int, val content: String, val tags: List<List<String>>, val createdAt: Long)

/** The JSON settings every codec uses: compact output, unknown keys ignored on input. */
internal val WireJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
