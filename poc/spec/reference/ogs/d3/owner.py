# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""The administrator and publisher side of OGS draft 3 (ogs3-core.md C§8, C§9, C§11): epochs, membership,
invites, grants, rotation, and publishing records. Used for the test vectors and the live end-to-end run; a real
owner app also needs persistence and a human in the loop (approve/decline are explicit calls here)."""
from __future__ import annotations

import os
import time

from .. import crypto as c
from ..nostr import Keys
from . import control as ctl
from .core import DayKeys, EpochSecret, day_of, lane_of, object_name, record_day, seal, sign_record
from .member import CAPS, Member, View, expiry

LOOKBACK = 30       # C§4.1: a new epoch starts 30 days back, so recent history can be (re)published under it
HISTORY_MAX = 31    # C§9.4: days per history request


class Owner:
    def __init__(self, I: Keys, devices: list[Keys], *, relays: list[str], profile: dict | None, now: int,
                 rng=os.urandom, epoch: EpochSecret | None = None, inbox: Keys | None = None):
        self.I, self.devices, self.relays, self.profile, self.rng = I, devices, relays, profile, rng
        self.ttl: int | None = None              # test runs: expire everything this many seconds after publishing
        self.members: dict[str, dict] = {}       # M → {inbox, scopes, from, to, profile, status}
        self.invites: dict[str, dict] = {}       # token → {secret, x, inbox, offer, claimed}
        self.pending: dict[str, dict] = {}       # M → join awaiting the wearer's decision
        self.epochs: dict[str, EpochSecret] = {}
        self.inboxes: dict[str, Keys] = {}       # epoch id → the administrator's inbox key for that epoch
        self.seq: dict[str, int] = {}
        self.ends: dict[str, int] = {}           # ended epoch → its last day (C§11.2)
        self.final: dict[str, dict] = {}         # ended epoch → its last membership body
        self.current = ""
        self._seen: set[str] = set()             # wrap ids already handled (C§9.4: deduplicate by id)
        self._last: dict[tuple, int] = {}        # (epoch, author, name) → last created_at (C§7.1)
        self._start_epoch(epoch or EpochSecret.generate(day_of(now) - LOOKBACK, rng), inbox or self._key())
        # the owner checks writers' records with the same rules as any member, holding every day of every epoch
        self.reader = Member(I, I, I.pub, back=2)
        self.sync_reader()

    def _key(self) -> Keys:
        while True:
            s = self.rng(32)
            if c.seckey_ok(s):
                return Keys(s)

    def _start_epoch(self, ep: EpochSecret, inbox: Keys):
        self.epochs[ep.id], self.inboxes[ep.id], self.seq[ep.id] = ep, inbox, 1
        self.current = ep.id

    # ---- membership (C§9.3) ---------------------------------------------------------------------------------------

    def membership(self, eid: str | None = None) -> dict:
        eid = eid or self.current
        if eid in self.final:
            return self.final[eid]
        members = [{"k": k, "scopes": m["scopes"], "from": m["from"], "to": m["to"]}
                   for k, m in sorted(self.members.items()) if m["status"] == "active" and m["scopes"]]
        return {"type": "membership", "epoch": eid, "seq": self.seq[eid],
                "devices": [{"k": d.pub} for d in self.devices], "members": members,
                "inbox": self.inboxes[eid].pub, "relays": self.relays, "profile": self.profile,
                "caps": CAPS}

    def sync_reader(self):
        views = {}
        for e, s in self.epochs.items():
            v = View(s.full(), self.membership(e))
            if e in self.ends:
                v.set_end(self.ends[e])
            views[e] = v
        self.reader.views = views
        self.reader.current = self.current
        self.reader.status, self.reader._indexed = "active", None

    # ---- publishing (C§7) -----------------------------------------------------------------------------------------

    def envelope(self, author: Keys, body: dict, *, at: int, epoch: str | None = None, nonce=None, aux=None,
                 exp: int | None = None) -> dict:
        eid = epoch or self.current
        obj = (eid, author.pub, object_name(body, author.pub))
        at = max(at, self._last.get(obj, -1) + 1)       # C§7.1: strictly increasing per object, or relays refuse
        self._last[obj] = at
        day = record_day(body, at)
        rec = sign_record(author, body, at, aux)
        dk = DayKeys(eid, day, self.epochs[eid].full().leaf(day))
        return seal(dk.author_keys(author, lane_of(body["type"])), dk, rec, name=object_name(body, author.pub),
                    expiration=exp or (at + self.ttl if self.ttl else expiry(body, at)), created_at=at, nonce=nonce, aux=aux)

    def publish_membership(self, at: int, **pins) -> dict:
        return self.envelope(self.I, self.membership(), at=at, **pins)

    # ---- invites and joins (C§8) ----------------------------------------------------------------------------------

    def invite(self, now: int, *, offer: dict, hours: int = 24, secret: bytes | None = None,
               inbox: Keys | None = None, web: str | None = None, hints: list[str] = (), **pins) -> tuple[str, dict]:
        if offer.get("auto") and offer.get("scopes"):
            raise ValueError("C§8.7: an automatically approved invite is read-only")
        secret = secret or self.rng(ctl.SECRET_BYTES)
        inbox = inbox or self._key()
        x = now + hours * 3600
        token = ctl.b64u(secret)
        self.invites[token] = {"secret": secret, "x": x, "inbox": inbox, "offer": offer, "claimed": None}
        body = {"type": "invite", "x": x, "inbox": inbox.pub, "relays": self.relays, "profile": self.profile,
                "offer": offer}
        wrap = ctl.wrap(self.I, ctl.invite_key(secret).pub, body, now=now, expiration=max(now + 60, x), **pins)
        return ctl.invite_text(self.I.pub, secret, list(hints), web), wrap

    def inbox_keys(self) -> list[Keys]:
        return list(self.inboxes.values()) + [i["inbox"] for i in self.invites.values()]

    def on_wrap(self, ev: dict, now: int) -> tuple[str, list[dict]]:
        """C§9.4. Returns (verdict, events to send). A valid join becomes `pending`: call approve() or decline()
        after the wearer has typed the match code (or policy `auto` approves it, C§8.6)."""
        if ev.get("id") in self._seen:
            return "ignored:duplicate", []
        self._seen.add(ev.get("id"))
        p = next((t[1] for t in ev.get("tags", []) if t[0] == "p"), None)
        key = next((k for k in self.inbox_keys() if k.pub == p), None)
        if key is None:
            return "ignored:not-for-us", []
        try:
            body, sender, at = ctl.open_wrap(key, ev)
        except ValueError as e:
            return f"rejected:unwrap ({e})", []
        t = body["type"]
        if t == "join":
            inv = self.invites.get(body.get("token"))
            if inv is None or inv["inbox"].pub != key.pub:
                return "rejected:unknown-token", []
            if inv["x"] <= now:
                return "rejected:invite-expired", []
            if not isinstance(body.get("inbox"), str) or len(body["inbox"]) != 64:
                return "rejected:bad-join", []
            known = self.members.get(sender)
            if known is not None:               # a join replayed after the decision never re-admits anyone
                return ("ignored:already-member", []) if known["status"] == "active" else \
                    ("rejected:removed", [ctl.wrap(self.I, known["inbox"], {"type": "ended", "reason": "removed"},
                                                   now=now)])
            if inv["claimed"] not in (None, sender) and not inv["offer"].get("multi"):
                return "rejected:claimed", [ctl.wrap(self.I, body["inbox"], {"type": "ended", "reason": "claimed"},
                                                     now=now)]
            inv["claimed"] = sender
            self.pending[sender] = {"inbox": body["inbox"], "profile": body.get("profile"), "token": body["token"],
                                    "offer": inv["offer"], "code": ctl.match_code(sender, body["token"])}
            if inv["offer"].get("auto"):
                return "accepted:join-auto", self.approve(sender, now)
            return "accepted:join-pending", []
        m = self.members.get(sender)
        if m is None:
            return "rejected:not-a-member", []
        if t == "rekey":
            if m["status"] != "active":
                return "accepted:rekey-ended", [ctl.wrap(self.I, m["inbox"], {"type": "ended", "reason": "removed"},
                                                         now=now)]
            return "accepted:rekey", [self.grant(sender, now)]
        if m["status"] != "active":
            return "rejected:not-active", []
        if t == "history":
            lo, hi = body.get("from"), body.get("to")
            if not (isinstance(lo, int) and isinstance(hi, int) and m["from"] <= lo <= hi <= day_of(now)
                    and (m["to"] is None or hi <= m["to"])):
                return "rejected:outside-grant", []
            if hi - lo + 1 > HISTORY_MAX:
                return "rejected:too-many-days", []
            return f"accepted:history {lo}..{hi}", []
        if t == "leave":
            return "accepted:leave", self.remove(sender, now, notify=False)
        if t == "profile":
            m["profile"] = body.get("profile")
            return "accepted:profile", []
        if t == "inbox" and isinstance(body.get("inbox"), str) and len(body["inbox"]) == 64:
            m["inbox"] = body["inbox"]
            return "accepted:inbox", []
        return f"ignored:{t}", []

    def approve(self, member: str, now: int, **pins) -> list[dict]:
        j = self.pending.pop(member)
        off = j["offer"]
        today = day_of(now)
        until = off.get("days")
        self.members[member] = {"inbox": j["inbox"], "scopes": list(off.get("scopes", [])),
                                "from": today - int(off.get("back", 1)),
                                "to": None if until is None else today + int(until),   # ≥ days × 24 h
                                "profile": j["profile"], "status": "active"}
        if self.members[member]["scopes"]:
            self.seq[self.current] += 1
        self.sync_reader()
        return [self.grant(member, now, **pins)]

    def decline(self, member: str, now: int) -> list[dict]:
        j = self.pending.pop(member)
        return [ctl.wrap(self.I, j["inbox"], {"type": "ended", "reason": "declined"}, now=now)]

    def grant(self, member: str, now: int, **pins) -> dict:
        """C§9.2: for every epoch whose days overlap the grant window, its cover and its newest membership record;
        an older epoch also its last day, and its cover never reaches beyond it."""
        m = self.members[member]
        epochs = []
        aux = pins.get("aux")
        for eid, ep in self.epochs.items():
            lo = max(m["from"], ep.start)
            hi = ep.start + 1023 if m["to"] is None else m["to"]
            end = self.ends.get(eid)
            if end is not None:
                hi = min(hi, end)
            if lo <= hi:
                o = ep.grant(lo, hi).to_json(end)
                o["membership"] = sign_record(self.I, self.membership(eid), now, aux)
                epochs.append(o)
        body = {"type": "grant", "current": self.current, "epochs": epochs,
                "grant": {"scopes": m["scopes"], "from": m["from"], "to": m["to"]}}
        return ctl.wrap(self.I, m["inbox"], body, now=now, **pins)

    # ---- removal and rotation (C§11) ------------------------------------------------------------------------------

    def end_epoch(self, now: int, *, revoke: bool, at: int | None = None, **pins) -> list[dict]:
        """C§11.2 steps 2–3 in the old epoch: a last membership without the removed keys (when one was listed), then
        `epoch_end`. The epoch's last day is day(now); its last membership is frozen for later grants."""
        old, out = self.current, []
        if revoke:
            self.seq[old] += 1
            out.append(self.envelope(self.I, self.membership(old), at=now, epoch=old, **pins.get("membership", {})))
        out.append(self.envelope(self.I, {"type": "epoch_end", "at": now}, at=now, epoch=old, **pins.get("end", {})))
        self.final[old], self.ends[old] = self.membership(old), day_of(now)
        return out

    def rotate(self, now: int, epoch: EpochSecret | None = None, inbox: Keys | None = None, *,
               revoke: bool = False) -> list[dict]:
        """New epoch: the old one ends (C§11.2), new membership, a grant to every active member."""
        out = self.end_epoch(now, revoke=revoke)
        self._start_epoch(epoch or EpochSecret.generate(day_of(now) - LOOKBACK, self.rng), inbox or self._key())
        self.sync_reader()
        out.append(self.publish_membership(now))
        out += [self.grant(k, now) for k, m in sorted(self.members.items()) if m["status"] == "active"]
        return out

    def remove(self, member: str, now: int, *, notify: bool = True, **kw) -> list[dict]:
        m = self.members[member]
        m["status"] = "removed"
        out = [ctl.wrap(self.I, m["inbox"], {"type": "ended", "reason": "removed"}, now=now)] if notify else []
        return out + self.rotate(now, revoke=bool(m["scopes"]), **kw)

    # ---- receiving members' records (C§10.4) ----------------------------------------------------------------------

    def accept(self, ev: dict, now: int):
        """Writers' entries and acks, checked with the member rules; the owner's devices MAY republish them."""
        return self.reader.on_event(ev, now)
