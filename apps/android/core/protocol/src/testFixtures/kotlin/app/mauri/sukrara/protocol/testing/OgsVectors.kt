// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.testing

import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** JSON files under the repo's `contracts/` ([Contracts]); a missing file fails the test. */
object ContractFiles {
    fun json(path: String): JsonObject = Json.parseToJsonElement(Contracts.file(path).readText()).jsonObject
}

/** contracts/protocol/ogs/ogs-draft1.json: throwaway keys, real signed + encrypted events from the Python reference. */
class OgsVectors(val root: JsonObject = ContractFiles.json("protocol/ogs/ogs-draft1.json")) {
    val now: Long = root.getValue("now").jsonPrimitive.content.toLong()
    val sid: String = root.getValue("sid").jsonPrimitive.content
    val keys: Map<String, KeyPair> = root.getValue("keys").jsonObject.mapValues { (_, v) -> pair(v.jsonObject) }
    val events: Map<String, SignedEvent> = root.getValue("events").jsonObject.mapValues { (name, v) ->
        SignedEvent.parse(v.toString()) ?: error("event $name doesn't parse")
    }
    val plaintexts: JsonObject = root.getValue("plaintexts").jsonObject
    val derivations: JsonObject = root.getValue("derivations").jsonObject

    /** Every secret the vectors know, by public key (the named keys and the invite key). */
    val secretsByPub: Map<String, KeyPair> = buildMap {
        this@OgsVectors.keys.values.forEach { put(it.public.hex, it) }
        val inv = derivations.getValue("invite").jsonObject
        put(inv.str("invite_key_pub"), KeyPair(SecretKey.ofHex(inv.str("invite_key_sec"))!!, PublicKeyHex(inv.str("invite_key_pub"))))
    }

    fun key(name: String): KeyPair = keys.getValue(name)

    companion object {
        fun pair(o: JsonObject) = KeyPair(SecretKey.ofHex(o.str("sec"))!!, PublicKeyHex(o.str("pub")))

        fun JsonObject.str(k: String): String = getValue(k).jsonPrimitive.content

        fun JsonElement.str(): String = jsonPrimitive.content
    }
}
