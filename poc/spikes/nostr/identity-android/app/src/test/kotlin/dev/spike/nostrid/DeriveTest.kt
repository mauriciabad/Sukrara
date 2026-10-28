// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.nostrid

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kotlin ↔ Python parity: identity/vectors.json is written by spikes/nostr/identity/test_identity.py. */
class DeriveTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("vectors.json")!!.readText())

    @Test fun rfc5869() {
        val okm = Derive.hkdf(Derive.unhex("0b".repeat(22)), Derive.unhex("000102030405060708090a0b0c"),
            Derive.unhex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Derive.hex(okm))
    }

    @Test fun derivedAccountsMatchPython() {
        val main = Derive.unhex(v.getString("main_sec"))
        val arr = v.getJSONArray("derived")
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val root = Derive.derivedAccount(main, Derive.APP_ID, d.getInt("index"))
            assertEquals(d.getString("root_sec"), Derive.hex(root))
            assertEquals(d.getString("profile_sec"), Derive.hex(Derive.subkey(root, "profile")))
        }
    }

    @Test fun shortLinkMatchesPython() {
        val inv = v.getJSONObject("invite")
        val secret = Derive.unhex(inv.getString("secret"))
        assertEquals(inv.getString("short_link"), Derive.shortLink(inv.getString("i_pub"), secret))
        val p = Derive.parse(inv.getString("short_link"))!!
        assertTrue(inv.getString("i_pub").startsWith(p.fp))
        assertTrue(p.secret.contentEquals(secret))
    }

    @Test fun onlyTheCurrentFormatParses() {
        val good = v.getJSONObject("invite").getString("short_link")
        val q = "i=npub1sx93muthatzjzm9mc9t9cq7fsczq2n76hjwk43lk6x6ec7f7xzyshsma7v&t=pEzb2FHGbVDJllnfcXW-fg&x=1792238661&r=wss%3A%2F%2Fnos.lol"
        for (bad in listOf("https://sukrara.mauri.app/join#$q", "cgmshare:join?$q", good.replace("sukrara.mauri.app", "evil.example"),
                good.dropLast(1), good.substringAfter('#'))) {
            assertNull(bad, Derive.parse(bad))
        }
    }
}
