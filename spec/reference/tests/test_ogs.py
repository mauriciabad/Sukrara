# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline tests. Crypto is checked against excerpts of the official vectors: NIP-44 (paulmillr/nip44,
nip44.vectors.json, sha256 269ed0f6…, all 101 valid + 20 invalid cases pass when run against the full file) and
BIP-340 (bitcoin/bips bip-0340/test-vectors.csv, all 19 rows pass)."""
import json
import struct
import unittest
from pathlib import Path

from ogs import ble, cgms, crypto as c, model as m, vectors
from ogs.nostr import Keys

NIP44_ENCRYPT = [
    ("0000000000000000000000000000000000000000000000000000000000000001",
     "0000000000000000000000000000000000000000000000000000000000000002",
     "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
     "0000000000000000000000000000000000000000000000000000000000000001", "a",
     "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuT"
     "MxWfp1RTN9Xga8no+kF5Vsb"),
    ("0000000000000000000000000000000000000000000000000000000000000002",
     "0000000000000000000000000000000000000000000000000000000000000001",
     "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d",
     "f00000000000000000000000000000f00000000000000000000000000000000f", "\U0001F355\U0001FAC3",
     "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8"
     "nDITO+QfaQ61+KbWQIOO2Yj"),
]
NIP44_CK = [("315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268",
             "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133",
             "3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1")]
NIP44_INVALID = [
    ("ca2527a037347b91bea0c8a30fc8d9600ffd81ec00038671e3a0f0cb0fc9f642",
     "#Atqupco0WyaOW2IGDKcshwxI9xO8HgD/P8Ddt46CbxDbrhdG8VmJdU0MIDf06CUvEvdnr1cp1fiMtlM/GrE92xAc1K5odTpCzUB+mjXgbaqt"
     "ntBUbTToSUoT0ovrlPwzGjyp"),
    ("36f04e558af246352dcf73b692fbd3646a2207bd8abd4b1cd26b234db84d9481",
     "AK1AjUvoYW3IS7C/BGRUoqEC7ayTfDUgnEPNeWTF/reBZFaha6EAIRueE9D1B1RuoiuFScC0Q94yjIuxZD3JStQtE8JMNacWFs9rlYP+Zyd"
     "tHhRucp+lxfdvFlaGV/sQlqZz"),
]
BIP340 = [  # secret, public, aux, message, signature, valid
    ("0000000000000000000000000000000000000000000000000000000000000003",
     "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9", "00" * 32, "00" * 32,
     "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F"
     "477DF4900D310536C0", True),
    ("B7E151628AED2A6ABF7158809CF4F3C762E7160F38B4DA56A784D9045190CFEF",
     "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659", "00" * 31 + "01",
     "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
     "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE33418906D11AC976ABCCB20B091292BFF4EA897EFCB639EA87"
     "1CFA95F6DE339E4B0A", True),
    ("", "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659", "",
     "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
     "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A14602975563CC27944640AC607CD107AE10923D9EF7A73C643E166BE"
     "5EBEAFA34B1AC553E2", False),
]
VECTORS = Path(__file__).resolve().parents[2] / "test-vectors" / "ogs-draft1.json"


class Crypto(unittest.TestCase):
    def test_nip44(self):
        for s1, s2, ck, nonce, pt, payload in NIP44_ENCRYPT:
            key = c.nip44_conversation_key(bytes.fromhex(s1), c.xonly(bytes.fromhex(s2)))
            self.assertEqual(key.hex(), ck)
            self.assertEqual(c.nip44_encrypt(pt, key, bytes.fromhex(nonce)), payload)
            self.assertEqual(c.nip44_decrypt(payload, key), pt)
        for s1, p2, ck in NIP44_CK:
            self.assertEqual(c.nip44_conversation_key(bytes.fromhex(s1), bytes.fromhex(p2)).hex(), ck)
        for ck, payload in NIP44_INVALID:
            with self.assertRaises(ValueError):
                c.nip44_decrypt(payload, bytes.fromhex(ck))

    def test_bip340(self):
        for sec, pub, aux, msg, sig, ok in BIP340:
            if sec:
                self.assertEqual(c.schnorr_sign(bytes.fromhex(msg), bytes.fromhex(sec), bytes.fromhex(aux)).hex(),
                                 sig.lower())
                self.assertEqual(c.xonly(bytes.fromhex(sec)).hex(), pub.lower())
            self.assertEqual(c.schnorr_verify(bytes.fromhex(msg), bytes.fromhex(pub), bytes.fromhex(sig)), ok)

    def test_hkdf_rfc5869_a1(self):
        okm = c.hkdf(bytes.fromhex("0b" * 22), bytes.fromhex("000102030405060708090a0b0c"),
                     bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42)
        self.assertEqual(okm.hex(), "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

    def test_nsec_roundtrip(self):
        k = Keys(bytes(31) + b"\x01")
        self.assertEqual(c.nsec_decode(k.nsec()), k.sec)


class Model(unittest.TestCase):
    def test_invite_rejects_other_forms(self):
        for bad in ("https://sukrara.mauri.app/join#short", "cgmshare:join?i=x&t=y", "https://evil.example/join#"
                    + "A" * 32):
            with self.assertRaises(ValueError):
                m.parse_invite(bad)
        self.assertEqual(len(m.parse_invite("https://other.example/join#" + "A" * 32, any_host=True).token), 22)

    def test_payload_validation(self):
        with self.assertRaises(m.Invalid):
            m.parse_live({"v": 3, "t": 1, "sgv": 100})
        with self.assertRaises(m.Invalid):
            m.parse_live({"v": 2, "t": 1, "sgv": 2000})
        with self.assertRaises(m.Invalid):
            m.parse_treatment({"v": 1, "id": "x", "k": "rapid", "t": 1, "u": 1})
        r, s, has = m.parse_live({"v": 2, "t": 1, "sgv": 100.0, "r": None, "extra": "ignored"})
        self.assertEqual((r.sgv, r.rate, s, has), (100, None, None, False))

    def test_app_identity_vectors(self):
        """spikes/nostr/identity/vectors.json is what Sukrara's own tests pin (Android core/InviteTest.kt, Mac
        `-qrselftest`), so matching it ties this independent code to the apps' Kotlin and Swift invite code."""
        v = json.loads((Path(__file__).resolve().parents[3] / "spikes/nostr/identity/vectors.json").read_text())
        inv = v["invite"]
        secret = bytes.fromhex(inv["secret"])
        self.assertEqual(m.invite_key(secret).pub, inv["invite_key_pub"])
        self.assertEqual("https://sukrara.mauri.app/join#" + m.invite_payload(inv["i_pub"], secret), inv["short_link"])
        p = m.parse_invite(inv["short_link"])
        self.assertTrue(inv["i_pub"].startswith(p.fp))
        self.assertEqual(p.secret, secret)
        for d in v["derived"]:        # N§12.1 one-way derivation of I from a main key
            root = m.derive_scalar(bytes.fromhex(v["main_sec"]), b"nostr/derived-account/v1",
                                   f"app.mauri.sukrara#{d['index']}".encode())
            self.assertEqual(root.hex(), d["root_sec"])

    def test_direction(self):
        self.assertEqual([m.direction(x) for x in (3, 1, 0.5, -1, -2.5, -3, None)],
                         ["DoubleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "DoubleDown", "NONE"])


class Ble(unittest.TestCase):
    def test_roundtrip_and_limits(self):
        msg = ["EVENT", {"x": "é" * 3000}]
        for mtu in (23, 100, 517, 1000):
            frames = ble.split(65535, ble.encode(msg), mtu)
            self.assertTrue(all(len(f) <= 512 for f in frames))
            r = ble.Reassembler()
            out = [r.push(f) for f in frames]
            self.assertEqual(out[-1], msg)
        self.assertEqual(ble.payload_size(23), 14)
        self.assertEqual(ble.payload_size(517), 506)

    def test_gap_and_timeout(self):
        frames = ble.split(1, ble.encode(["EVENT", {"x": "y" * 100}]), 23)
        r = ble.Reassembler()
        r.push(frames[0])
        self.assertIsNone(r.push(frames[2]))           # gap → message dropped
        t = [0.0]
        r = ble.Reassembler(clock=lambda: t[0])
        r.push(frames[0])
        t[0] = 31.0
        r.push(struct.pack(">HHH", 2, 0, 2) + b"[")    # any frame triggers expiry of idle buffers
        self.assertNotIn(1, r.buf)


class Cgms(unittest.TestCase):
    def test_sfloat(self):
        self.assertEqual(cgms.sfloat(120), 0x0078)
        self.assertEqual(cgms.sfloat(-1.25), 0xEF83)
        self.assertEqual(cgms.sfloat_value(0xF019), 2.5)
        self.assertEqual(cgms.sfloat(None), 0x07FF)

    def test_measurement_and_crc(self):
        p = cgms.measurement(123, 4320, 0.5, crc=True)
        self.assertEqual(cgms.parse_measurement(p), {"sgv": 123, "offset": 4320, "rate": 0.5, "crc_ok": True})
        self.assertEqual(cgms.crc16_mcrf4xx(b"123456789"), 0x6F91)   # CRC-16/MCRF4XX check value


class Vectors(unittest.TestCase):
    def test_committed_vectors_verify(self):
        self.assertEqual(vectors.verify_file(json.loads(VECTORS.read_text())), [])

    def test_generation_is_deterministic(self):
        self.assertEqual(vectors.dumps(vectors.generate()), VECTORS.read_text())


if __name__ == "__main__":
    unittest.main()
