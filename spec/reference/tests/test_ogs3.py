# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline tests for the OGS draft 3 reference (spec/draft3)."""
import json
import random
import unittest
import uuid
from pathlib import Path

from ogs import crypto as c
from ogs.nostr import Keys
from ogs.d3 import control as ctl
from ogs.d3 import data as D
from ogs.d3 import vectors as V
from ogs.d3.ble import WINDOW, adv_uuid, auth, gatt_uuids, info
from ogs.d3.core import (DEPTH, SPAN, DayKeys, Epoch, EpochSecret, Node, cover, day_of, descend, object_name, pad,
                         seal, sign_record)
from ogs.d3.member import Member
from ogs.d3.owner import Owner

VECTORS = Path(__file__).resolve().parents[2] / "draft3" / "test-vectors" / "ogs-draft3.json"
ROOT = bytes(range(32))
NOW = 1792087200


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
        s = bytes(range(12))
        for text in (ctl.invite_text(i, s), ctl.invite_text(i, s, ["wss://nos.lol", "wss://relay.example.org:8080"]),
                     ctl.invite_text(i, s, [], "https://any.example/x/join")):
            inv = ctl.parse_invite(text)
            self.assertEqual((inv.fp, inv.secret), ("ab" * 12, s))
        self.assertEqual(len(ctl.invite_payload(i, s)), 34)
        self.assertEqual(ctl.parse_invite(ctl.invite_text(i, s, ["wss://nos.lol"])).hints, ("wss://nos.lol",))
        for bad in ("ogs:" + "A" * 33, "https://sukrara.mauri.app/join#" + "A" * 32, "ogs:" + "B" * 34,
                    "ogs:" + ctl.invite_payload(i, s) + "@UPPER.case"):
            with self.assertRaises(ValueError):
                ctl.parse_invite(bad)

    def test_match_code_is_six_digits(self):
        self.assertRegex(ctl.match_code("cd" * 32, "x" * 16), r"^\d{6}$")

    def test_automatic_invites_are_read_only(self):
        o = Owner(Keys(bytes(31) + b"\x01"), [Keys(bytes(31) + b"\x02")], relays=[], profile=None, now=NOW)
        for scopes in (["ack"], ["write"], ["manage"]):
            with self.assertRaises(ValueError):
                o.invite(NOW, offer={"scopes": scopes, "back": 1, "days": 1, "auto": True, "multi": True})
        o.invite(NOW, offer={"scopes": [], "back": 1, "days": 1, "auto": True, "multi": True})


def _join(o: Owner, mk: Keys, ik: Keys, offer: dict, now: int) -> Member:
    text, w = o.invite(now, offer=offer)
    m = Member(mk, ik)
    m.open_invite(ctl.parse_invite(text), w, now)
    o.on_wrap(m.join(None, now), now)
    for g in o.approve(mk.pub, now):
        m.on_event(g, now)
    return m


class Lifecycle(unittest.TestCase):
    """C§11.2: what a removed writer or a replaced device can still sign in the old epoch is not accepted."""

    def setUp(self):
        self.I, self.D = Keys(bytes(31) + b"\x01"), Keys(bytes(31) + b"\x02")
        self.o = Owner(self.I, [self.D], relays=[], profile=None, now=NOW)
        self.reader = _join(self.o, Keys(bytes(31) + b"\x03"), Keys(bytes(31) + b"\x04"), {"scopes": [], "back": 1}, NOW)
        self.writer = _join(self.o, Keys(bytes(31) + b"\x05"), Keys(bytes(31) + b"\x06"),
                            {"scopes": ["write"], "back": 1}, NOW)
        self.reader.on_event(self.o.publish_membership(NOW), NOW)

    def forge(self, m: Member, epoch: str, t: int) -> dict:
        body = {"type": "insulin", "id": str(uuid.UUID(int=t, version=4)), "t": t, "rev": t * 1000, "u": 20,
                "kind": "rapid"}
        dk = DayKeys(epoch, day_of(t), m.views[epoch].epoch.leaf(day_of(t)))
        return seal(dk.author_keys(m.M, "r"), dk, sign_record(m.M, body, t), name=object_name(body, m.M.pub),
                    expiration=t + 86400, created_at=t)

    def test_removed_writer_cannot_inject_into_the_old_epoch(self):
        old = self.o.current
        for e in self.o.remove(self.writer.M.pub, NOW + 10):
            self.reader.on_event(e, NOW + 20)
        self.assertNotEqual(self.reader.current, old)
        for t in (NOW + 30, NOW + 86400 + 30, NOW + 300 * 86400):        # today, tomorrow, months later
            ev = self.forge(self.writer, old, t)
            self.assertEqual(str(self.reader.on_event(ev, t)), "ignored:unknown-author")
            self.assertEqual(str(self.o.accept(ev, t)), "ignored:unknown-author")
        self.assertIsNone(self.reader.views[old].epoch.leaf(day_of(NOW) + 1))   # cut at the epoch's last day

    def test_grant_alone_cuts_the_old_epoch(self):
        """A member that never sees epoch_end or the last membership on relays still gets both in its grant."""
        old = self.o.current
        out = self.o.remove(self.writer.M.pub, NOW + 10)
        for e in out:
            if e["kind"] == 1059:
                self.reader.on_event(e, NOW + 20)
        self.assertEqual(self.reader.views[old].end, day_of(NOW))
        self.assertNotIn(self.writer.M.pub, [m["k"] for m in self.reader.views[old].membership["members"]])
        self.assertEqual(str(self.reader.on_event(self.forge(self.writer, old, NOW + 40), NOW + 40)),
                         "ignored:unknown-author")

    def test_ended_and_grant_are_ordered_by_rumor_time(self):
        m = self.reader
        g = self.o.grant(m.M.pub, NOW + 100)
        stale = ctl.wrap(self.I, m.inbox.pub, {"type": "ended", "reason": "removed"}, now=NOW + 50)
        self.assertEqual(str(m.on_event(g, NOW + 100)), f"accepted:grant (epoch {self.o.current})")
        self.assertEqual(str(m.on_event(stale, NOW + 110)), "ignored:ended-before-grant")
        self.assertEqual(m.status, "active")
        self.assertTrue(str(m.on_event(ctl.wrap(self.I, m.inbox.pub, {"type": "ended", "reason": "removed"},
                                                now=NOW + 120), NOW + 120)).startswith("accepted:ended"))

    def test_history_requests_are_capped(self):
        req = self.writer.request({"type": "history", "from": day_of(NOW) - 1, "to": day_of(NOW)}, NOW)
        self.assertTrue(self.o.on_wrap(req, NOW)[0].startswith("accepted:history"))
        self.writer.grant["from"] = 0
        self.o.members[self.writer.M.pub]["from"] = day_of(NOW) - 100
        req = self.writer.request({"type": "history", "from": day_of(NOW) - 60, "to": day_of(NOW)}, NOW + 1)
        self.assertEqual(self.o.on_wrap(req, NOW + 1)[0], "rejected:too-many-days")


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


class Merge(unittest.TestCase):
    def test_equal_versions_converge_in_any_order(self):
        """C§10.5: a tie (same rev / created_at) is broken by the greater record id, so arrival order never matters."""
        o = Owner(Keys(bytes(31) + b"\x01"), [Keys(bytes(31) + b"\x02"), Keys(bytes(31) + b"\x07")], relays=[],
                  profile=None, now=NOW)
        evs = [o.envelope(d, {"type": "settings", "unit": u, "rev": NOW * 1000}, at=NOW)
               for d, u in zip(o.devices, ("mg/dL", "mmol/L"))]
        evs += [o.envelope(d, {"type": "cgm", "sess": "abcdefgh", "start": NOW - NOW % 900, "len": 900,
                               "rows": [[0, v]]}, at=NOW) for d, v in zip(o.devices, (100, 101))]
        results = []
        for order in (evs, evs[::-1]):
            m = Member(Keys(bytes(31) + b"\x09"), Keys(bytes(31) + b"\x0a"), o.I.pub)
            m.views, m.current, m.status = o.reader.views, o.current, "active"
            for e in order:
                m.on_event(e, NOW)
            results.append((m.settings["unit"], [r[2] for r in m.readings.values()]))
        self.assertEqual(results[0], results[1])


class Ble(unittest.TestCase):
    def test_member_must_prove_the_day_key_too(self):
        dk, other = DayKeys("44" * 8, 20741, bytes(32)), DayKeys("44" * 8, 20741, bytes([9]) * 32)
        self.assertNotEqual(auth(dk, "c" * 32, "n" * 32), auth(other, "c" * 32, "n" * 32))
        self.assertNotEqual(auth(dk, "c" * 32, "n" * 32)[1]["proof"], info(dk, "n" * 32, "c" * 32)[1]["proof"])

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
