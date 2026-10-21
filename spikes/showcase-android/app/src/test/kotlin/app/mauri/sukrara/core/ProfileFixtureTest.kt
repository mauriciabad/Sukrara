// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Profile editing against spikes/nostr/identity/profile-v1.json, the cases the Mac's `-identityselftest` runs too:
 * name rules, v1 normalisation, the editor's buttons, which wire avatars are kept, and the profile update body.
 */
class ProfileFixtureTest {
    private val f = JSONObject(File("../../nostr/identity/profile-v1.json").readText())
    /** Key order doesn't matter: compare a sorted-keys rendering. */
    private fun canon(o: Any?): String = when (o) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> o.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canon(o.get(it)) }
        is String -> JSONObject.quote(o)
        else -> o.toString()
    }
    private fun same(want: Any?, got: Any?, what: String) =
        assertEquals(what, canon(want?.let { JSONObject(it.toString()) }), canon(got?.let { JSONObject(it.toString()) }))

    @Test fun names() {
        val a = f.getJSONArray("names")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            assertEquals(c.toString(), c.getString("name"), Profile.editedName(c.getString("typed"), c.getString("previous")))
        }
    }

    @Test fun v1Normalised() {
        val a = f.getJSONArray("v1")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            same(c.getJSONObject("avatar"), Avatar.v1(c.getInt("c"), c.getString("s"), c.getInt("a"), c.getLong("n")).json, c.toString())
        }
    }

    @Test fun editorButtons() {
        val a = f.getJSONArray("ops")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val from = Avatar.of(c.getJSONObject("from"))!!
            val got = when (c.getString("op")) {
                "turn" -> from.with(angle = from.angle + 45, seed = from.seed + 1)   // ProfileEditor "Turn the colours"
                "shape" -> from.with(shape = c.getString("value"))
                else -> from.with(colors = c.getInt("value"))
            }
            same(c.getJSONObject("avatar"), got.json, c.toString())
        }
    }

    @Test fun wireAvatars() {
        val a = f.getJSONArray("wire")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val got = Avatar.of(c.getJSONObject("in"))
            if (c.isNull("avatar")) assertNull(c.toString(), got) else same(c.getJSONObject("avatar"), got?.json, c.toString())
        }
    }

    @Test fun updateBody() {
        val a = f.getJSONArray("update")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val p = Profile(c.getString("name"), if (c.isNull("avatar")) null else Avatar.of(c.getJSONObject("avatar")))
            same(c.getJSONObject("body"), Proto.profileUpdate(p), c.toString())
        }
    }
}
