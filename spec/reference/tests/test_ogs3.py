# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline tests for the OGS draft 3 reference (spec/draft3)."""
import json
import random
import unittest
from pathlib import Path

from ogs import crypto as c
from ogs.nostr import Keys
from ogs.d3 import control as ctl
from ogs.d3 import data as D
from ogs.d3 import vectors as V
from ogs.d3.ble import WINDOW, adv_uuid, gatt_uuids
from ogs.d3.core import (DEPTH, SPAN, DayKeys, Epoch, EpochSecret, Node, cover, descend, pad)
from ogs.d3.member import Member

VECTORS = Path(__file__).resolve().parents[2] / "draft3" / "test-vectors" / "ogs-draft3.json"
ROOT = bytes(range(32))


class Tree(unittest.TestCase):
    def test_cover_is_exact_and_minimal(self):
        rnd = random.Random(7)
        full = EpochSecret("00" * 8, 0, ROOT).full()
        for _ in range(60):
            a = rnd.randrange(SPAN)
            b = rnd.randrange(a, SPAN)
            nodes = cover(ROOT, a, b)
            leaves = sorted(x for n in nodes for x in range(n.first, n.last + 1))
            self.assertEqual(leaves, list(range(a, b + 1)))
            self.assertLessEqual(len(nodes), 2 * DEPTH)
            for n in nodes:                       # no two siblings (they would merge into their parent)
                self.assertFalse(any(m.level == n.level and m.index == n.index ^ 1 for m in nodes))
                self.assertEqual(descend(n, n.first), full.leaf(n.first))

    def test_bounded_grant_cannot_reach_outside(self):
        ep = EpochSecret("11" * 8, 20000, ROOT)
        g = ep.grant(20010, 20013)
        self.assertTrue(all(g.has(d) for d in range(20010, 20014)))
        self.assertFalse(g.has(20009) or g.has(20014))
        self.assertIsNone(g.leaf(20014))

    def test_puncture_keeps_only_future(self):
        ep = EpochSecret("22" * 8, 20000, ROOT)
        m = Member(Keys(bytes(31) + b"\x01"), Keys(bytes(31) + b"\x02"))
        from ogs.d3.member import View
        m.views = {ep.id: View(ep.grant(20000, None))}
        m.puncture(20100)
        e = m.views[ep.id].epoch
        self.assertFalse(e.has(20099))
        self.assertEqual(e.leaf(20100), ep.full().leaf(20100))
        self.assertEqual(e.last_day(), 20000 + SPAN - 1)


class Blinding(unittest.TestCase):
    def test_blinded_secret_matches_blinded_public(self):
        dk = DayKeys("33" * 8, 20741, bytes(32))
        for i in range(1, 6):
            k = Keys(c.hkdf(bytes([i]), b"s", b"i"))
            for lane in ("g", "r", "m"):
                bk = dk.author_keys(k, lane)
                self.assertEqual(bk.pub, dk.author(k.pub, lane))
                self.assertNotEqual(bk.pub, k.pub)
        other = DayKeys("33" * 8, 20742, bytes([1]) * 32)
        k = Keys(bytes(31) + b"\x05")
        self.assertNotEqual(dk.author(k.pub, "r"), other.author(k.pub, "r"))   # unlinkable across days


class Invites(unittest.TestCase):
    def test_forms(self):
        i = "ab" * 32
        s = bytes(range(16))
        for text in (ctl.invite_text(i, s), ctl.invite_text(i, s, ["wss://nos.lol", "wss://relay.example.org:8080"]),
                     ctl.invite_text(i, s, [], "https://any.example/x/join")):
            inv = ctl.parse_invite(text)
            self.assertEqual((inv.fp, inv.secret), ("ab" * 8, s))
        self.assertEqual(ctl.parse_invite(ctl.invite_text(i, s, ["wss://nos.lol"])).hints, ("wss://nos.lol",))
        for bad in ("ogs:" + "A" * 33, "https://sukrara.mauri.app/join#" + "A" * 32, "ogs:" + "B" * 34,
                    "ogs:" + ctl.invite_payload(i, s) + "@UPPER.case"):
            with self.assertRaises(ValueError):
                ctl.parse_invite(bad)

    def test_match_code_is_six_digits(self):
        self.assertRegex(ctl.match_code("cd" * 32, "x" * 22), r"^\d{6}$")


class Data(unittest.TestCase):
    def test_validation(self):
        ok = {"type": "cgm", "sess": "abcdefgh", "start": 900, "len": 900, "rows": [[0, 100], [60, 101, -5, 32]]}
        D.validate(ok)
        for bad in ({**ok, "start": 901}, {**ok, "len": 7}, {**ok, "rows": [[60, 100], [0, 99]]},
                    {**ok, "rows": [[0, 1001]]}, {**ok, "tz": 900}, {"type": "carbs", "id": "x", "t": 1, "g": 1,
                                                                     "rev": 1},
                    {"type": "Bad Type"}):
            with self.assertRaises(D.Invalid):
                D.validate(bad)
        self.assertEqual(D.validate({"type": "future_thing", "x": 1})["x"], 1)    # unknown types are kept

    def test_units_and_direction(self):
        self.assertEqual(D.to_mmol(180), 10.0)
        self.assertEqual(D.from_mmol(3.9), 70)
        self.assertEqual([D.direction(x) for x in (3, 1, 0.5, -1, -2.5, None)],
                         ["DoubleUp", "FortyFiveUp", "Flat", "FortyFiveDown", "SingleDown", "NONE"])

    def test_padding(self):
        for n in (1, 255, 256, 257, 1000):
            p = pad("x" * n)
            self.assertEqual(len(p) % 256, 0)
            self.assertEqual(p.rstrip(" "), "x" * n)
        self.assertEqual(json.loads(pad('{"a":1}')), {"a": 1})


class Ble(unittest.TestCase):
    def test_uuids_look_random_and_rotate(self):
        dk = DayKeys("44" * 8, 20741, bytes(32))
        g = gatt_uuids(dk)
        self.assertEqual(len(set(g.values())), 3)
        self.assertTrue(all(u[14] == "4" and u[19] in "89ab" for u in g.values()))
        w = 20741 * 86400 // WINDOW
        self.assertNotEqual(adv_uuid(dk, w), adv_uuid(dk, w + 1))
        with self.assertRaises(ValueError):
            adv_uuid(dk, w - 1)


class Vectors(unittest.TestCase):
    def test_committed_vectors_verify(self):
        self.assertEqual(V.verify_file(json.loads(VECTORS.read_text())), [])

    def test_generation_is_deterministic(self):
        vec = V.generate()
        V.verify_file(vec, fill_final=True)
        self.assertEqual(V.dumps(vec), VECTORS.read_text())


if __name__ == "__main__":
    unittest.main()
