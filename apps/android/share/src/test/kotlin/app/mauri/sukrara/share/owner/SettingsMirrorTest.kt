// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.owner

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.FollowerModel
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.PublishSettingsMirror
import app.mauri.sukrara.share.owner.usecase.SettingsMirror
import app.mauri.sukrara.share.owner.usecase.WearerSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROTOCOL-v2 §Settings, owner side: only the schema's `"mirror": true` rows go out, `u` moves only on a real change,
 * and the follower accepts what the wearer publishes (keys and roster from contracts/protocol/sync/settings-mirror.json).
 */
class SettingsMirrorTest {
    private val fx = ContractFiles.json("protocol/sync/settings-mirror.json")
    private val keys = fx.getValue("keys").jsonObject
    private val now =
        fx
            .getValue("now")
            .jsonPrimitive.content
            .toLong()

    private fun key(name: String): KeyPair =
        crypto.keyPair(
            SecretKey.ofHex(
                keys
                    .getValue(name)
                    .jsonObject
                    .getValue("sec")
                    .jsonPrimitive.content,
            )!!,
        )!!

    private fun event(name: String) =
        SignedEvent.parse(
            fx
                .getValue("events")
                .jsonObject
                .getValue(name)
                .toString(),
        )!!

    private val owner =
        OwnerState(
            identityHex = key("I").secret.revealHex(),
            deviceHex = key("D").secret.revealHex(),
            sid = fx.getValue("sid").jsonPrimitive.content,
            epochs = listOf(EpochKey(1, key("G1").secret.revealHex())),
        )

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    /** The settings layer's full values: graph keys incl. a per-device one, the whole SPEC §3 alert settings. */
    private fun wearer(low: Int = 70) =
        WearerSettings(
            graph = obj("""{"low":$low,"high":180,"defaultHours":6,"placeHours":3,"slots":"TIR:86400:1,TIR:604800:1,GMI:1209600:1"}"""),
            alerts =
                obj(
                    """{"v":1,"on":true,"alerts":{"low":{"on":true,"mgdl":$low,"repeatMin":15,"alarm":true},
               "no_data":{"on":true,"min":20,"repeatMin":30,"alarm":false},
               "falling_fast":{"on":false,"rate":2.0,"repeatMin":0,"alarm":false}},
               "muteAfterInsulin":{"on":true,"min":60},"sounds":{"pack":"arcade"},
               "notificationActions":{"muteMin":30},"source":"own"}""",
                ),
        )

    @Test fun onlyTheSchemasMirroredRowsGoOut() {
        val out = SettingsMirror.select(wearer())
        assertEquals(obj("""{"low":70,"high":180,"defaultHours":6,"slots":"TIR:86400:1,TIR:604800:1,GMI:1209600:1"}"""), out.graph)
        assertEquals(
            obj(
                """{"alerts":{"low":{"on":true,"mgdl":70,"repeatMin":15},"falling_fast":{"on":false,"rate":2.0,"repeatMin":0}},
                   "muteAfterInsulin":{"on":true,"min":60}}""",
            ),
            out.alerts,
        )
    }

    @Test fun uMovesOnlyOnAChangeAndTheFollowerTakesTheNewest() {
        val mirror = PublishSettingsMirror(OwnerFixtures.context())
        val first = mirror.onChange(owner, wearer(), now - 30)
        assertEquals(now - 30, first.state.settingsU)
        // the same mirrored content (a per-device setting changed): nothing to send
        val perDevice = wearer().copy(graph = JsonObject(wearer().graph + ("placeHours" to Json.parseToJsonElement("12"))))
        assertTrue(mirror.onChange(first.state, perDevice, now - 20).publish.isEmpty())
        // start / new epoch / weekly: same u, newer created_at
        val again = mirror.republish(first.state, now - 30)
        assertEquals(first.state.settingsU, again.state.settingsU)
        assertTrue(again.publish.single().createdAt > first.publish.single().createdAt)
        val changed = mirror.onChange(again.state, wearer(low = 75), now - 10)
        assertTrue(changed.state.settingsU > first.state.settingsU)

        val follower = FollowerModel(crypto, FollowerState(follower = key("F"), identity = key("I").public))
        follower.feed(event("key-1"), now)
        follower.feed(event("roster-1"), now)
        assertTrue(follower.feed(first.publish.single(), now) is FollowResult.Accepted)
        assertEquals(FollowResult.Ignored("settings-older"), follower.feed(again.publish.single(), now))
        assertTrue(follower.feed(changed.publish.single(), now) is FollowResult.Accepted)
        assertEquals(
            obj("""{"low":75,"high":180,"defaultHours":6,"slots":"TIR:86400:1,TIR:604800:1,GMI:1209600:1"}"""),
            follower.settings!!.graph,
        )
    }

    @Test fun nothingIsPublishedBeforeTheSettingsLayerGaveAnything() {
        assertTrue(PublishSettingsMirror(OwnerFixtures.context()).republish(owner, now).publish.isEmpty())
    }
}
