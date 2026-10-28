// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.event.Avatar
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.RumorCodec
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

/** contracts/protocol/identity/{profile-v1,avatar-v1}.json: name edits, avatar normalisation, wire, update body. */
class ProfileFixtureTest {
    private val fx = ContractFiles.json("protocol/identity/profile-v1.json")

    @Test fun names() = fx.getValue("names").jsonArray.forEach {
        val o = it.jsonObject
        assertEquals(o.str("name"), Profile.editedName(o.str("typed"), o.str("previous")))
    }

    @Test fun v1Normalisation() = fx.getValue("v1").jsonArray.forEach {
        val o = it.jsonObject
        val a = Avatar.v1(o.getValue("c").jsonPrimitive.long.toInt(), o.str("s"), o.getValue("a").jsonPrimitive.long.toInt(), o.getValue("n").jsonPrimitive.long)
        assertEquals(o.getValue("avatar"), a.json)
    }

    @Test fun editorOps() = fx.getValue("ops").jsonArray.forEach {
        val o = it.jsonObject
        val from = Avatar.of(o.getValue("from"))!!
        val out = when (o.str("op")) {
            "turn" -> from.turned()
            "shape" -> from.withShape(o.str("value"))
            "colors" -> from.withColors(o.str("value").toInt())
            else -> error("unknown op")
        }
        assertEquals(o.getValue("avatar"), out.json)
    }

    @Test fun wire() = fx.getValue("wire").jsonArray.forEach {
        val o = it.jsonObject
        val expected = o.getValue("avatar").takeUnless { e -> e is JsonNull }
        assertEquals(expected, Avatar.of(o.getValue("in"))?.json)
    }

    @Test fun updateBody() = fx.getValue("update").jsonArray.forEach {
        val o = it.jsonObject
        val profile = Profile(o.str("name"), Avatar.of(o.getValue("avatar")))
        assertEquals(o.getValue("body"), Json.parseToJsonElement(RumorCodec.encode(Rumor.ProfileUpdate(profile))))
    }

    @Test fun dots() = ContractFiles.json("protocol/identity/avatar-v1.json").getValue("dots").jsonArray.forEach {
        val o = it.jsonObject
        val dots = Avatar.v1Dots(o.getValue("seed").jsonPrimitive.long)
        o.getValue("circles").jsonArray.forEachIndexed { i, c ->
            val (x, y, r, alpha) = c.jsonArray.map { n -> n.jsonPrimitive.content.toDouble() }
            assertEquals(x, dots[i].x, 1e-5)
            assertEquals(y, dots[i].y, 1e-5)
            assertEquals(r, dots[i].r, 1e-5)
            assertEquals(alpha.toInt(), dots[i].alpha)
        }
    }
}
