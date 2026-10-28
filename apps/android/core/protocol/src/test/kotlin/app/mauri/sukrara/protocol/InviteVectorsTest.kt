// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.invite.InviteFailure
import app.mauri.sukrara.protocol.invite.InviteLink
import app.mauri.sukrara.protocol.invite.Invites
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** contracts/protocol/identity/vectors.json and the OGS invite vectors: key derivation, short link, match code. */
class InviteVectorsTest {
    private val crypto = SdkTestCrypto()
    private val ogs = OgsVectors()

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val okm = Derive.hkdf(
            Hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")!!,
            Hex.decode("000102030405060708090a0b0c")!!,
            Hex.decode("f0f1f2f3f4f5f6f7f8f9")!!,
            42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", Hex.encode(okm))
    }

    @Test fun identityVectorsInviteKeyAndLink() {
        val inv = ContractFiles.json("protocol/identity/vectors.json").getValue("invite").jsonObject
        val secret = Hex.decode(inv.str("secret"))!!
        assertEquals(inv.str("invite_key_pub"), crypto.keyPair(Derive.inviteKey(secret))!!.public.hex)
        val link = InviteLink.of(PublicKeyHex(inv.str("i_pub")), secret)
        assertEquals(inv.str("short_link"), link.link)
        val parsed = InviteLink.parse(inv.str("short_link"))!!
        assertEquals(inv.str("i_pub").take(16), parsed.fingerprint)
        assertArrayEquals(secret, parsed.secretBytes())
    }

    @Test fun ogsInviteDerivationsAndMatchCode() {
        val inv = ogs.derivations.getValue("invite").jsonObject
        val link = InviteLink.parse(inv.str("link"))!!
        assertEquals(inv.str("fingerprint"), link.fingerprint)
        assertEquals(inv.str("token"), link.token)
        assertEquals(inv.str("invite_key_pub"), Invites.inviteKey(crypto, link).public.hex)
        assertEquals(inv.str("match_code(F)"), Invites.matchCode(ogs.key("F").public, link.token))
        assertEquals(link.link, InviteLink.parse("  " + inv.str("link").replace(InviteLink.INVITE_BASE, InviteLink.APP_SCHEME) + "\n")!!.link)
    }

    @Test fun onlyTheExactFormIsAnInvite() {
        listOf(
            "https://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7",
            "https://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7__",
            "https://sukrara.mauri.app/join#i=abc&t=def",
            "cgmshare:join?i=abc",
            "http://sukrara.mauri.app/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_",
            "https://evil.example/join#yDFq2KB_YY0AESIzRFVmd4iZqrvM3e7_",
        ).forEach { assertNull(it, InviteLink.parse(it)) }
    }

    @Test fun invitesOpenOnlyWithFingerprintAndExpiry() {
        val link = InviteLink.parse(ogs.derivations.getValue("invite").jsonObject.str("link"))!!
        ogs.root.getValue("invite_checks").jsonArray.forEach { c ->
            val o = c.jsonObject
            val now = o["now"]?.jsonPrimitive?.content?.toLong() ?: ogs.now
            val result = Invites.open(crypto, link, listOf(ogs.events.getValue(o.str("wrap"))), now)
            val expected = when (o.str("expect")) {
                "ok" -> null
                "fingerprint-mismatch" -> InviteFailure.FingerprintMismatch
                "expired" -> InviteFailure.Expired
                else -> error("unknown expectation")
            }
            if (expected == null) {
                val opened = (result as Invites.Result.Opened).invite
                assertEquals(ogs.key("I").public, opened.identity)
                assertEquals(link.token, opened.token)
            } else {
                assertEquals(Invites.Result.Failed(expected), result)
            }
        }
        assertEquals(Invites.Result.Failed(InviteFailure.NothingFound), Invites.open(crypto, link, emptyList(), ogs.now))
        assertEquals(Invites.Result.Failed(InviteFailure.DoesNotOpen), Invites.open(crypto, link, listOf(ogs.events.getValue("key-1")), ogs.now))
    }

    @Test fun createdInvitesOpenWithTheirOwnLink() {
        val i = ogs.key("I")
        val secret = ByteArray(16) { it.toByte() }
        val (link, wrap) = Invites.create(crypto, i, secret, ogs.now, ogs.now + 86_400, listOf("wss://nos.lol", "ws://bad"), Profile("Alex", null))
        val opened = Invites.open(crypto, InviteLink.parse(link.link)!!, listOf(wrap), ogs.now) as Invites.Result.Opened
        assertEquals(listOf("wss://nos.lol"), opened.invite.relays)
        assertEquals("Alex", opened.invite.profile?.name)
        assertEquals((ogs.now + 86_400).toString(), wrap.tag("expiration"))
    }
}
