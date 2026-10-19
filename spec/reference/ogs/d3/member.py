# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""A transport-independent OGS draft 3 member (reader, and contributor when its grant has scopes): the acceptance
rules of ogs3-core.md C§10 and nothing else. Feed it events from relays or BLE with `on_event`."""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from ..nostr import Keys, expired, tag, verify
from . import control as ctl
from . import data as D
from .core import (DEVICE_TYPES, ENTRY_TYPES, KIND_ENVELOPE, KIND_WRAP, DayKeys, Epoch, Node, check_record,
                   day_of, lane_of, object_name, open_envelope, record_day, seal, sign_record)

CAPS = ["cgm", "sensor", "device", "alert", "ack", "settings", "bg", "insulin", "carbs", "exercise", "note",
        "history", "ble"]
ALERT_NOTIFY_AGE = 600      # D§8.2: an alert whose `last` is older than this is shown, never notified
REKEY_EVERY = 300          # C§10.2: at most one rekey request per 5 minutes
REKEY_GRACE = 120          # C§10.2: after an epoch_end, wait this long for the new grant before asking


@dataclass
class Result:
    verdict: str            # accepted | rejected | ignored
    what: str
    detail: str = ""
    out: list = field(default_factory=list)

    def __str__(self):
        return f"{self.verdict}:{self.what}" + (f" ({self.detail})" if self.detail else "")


@dataclass
class View:
    """One epoch as this member holds it."""
    epoch: Epoch
    membership: dict | None = None
    ended: bool = False


def expiry(body: dict, created_at: int) -> int:
    """C§7.3 RECOMMENDED retention (absolute NIP-40 expiration)."""
    t = body["type"]
    end_of_day = (record_day(body, created_at) + 1) * 86400
    if t == "cgm":
        return end_of_day + (30 if body["len"] == 86400 else 2) * 86400
    if t in ENTRY_TYPES:
        return end_of_day + 30 * 86400
    if t in ("alert", "ack"):
        return created_at + 2 * 86400
    return created_at + 30 * 86400


class Member:
    def __init__(self, M: Keys, inbox: Keys, i_pub: str | None = None, *, back: int = 1):
        self.M, self.inbox, self.i_pub = M, inbox, i_pub
        self.back = back                    # how many days before today to index (live view)
        self.ttl: int | None = None
        self.status = "new"                 # new → joined → active → ended
        self.reason: str | None = None
        self.ended_at = -1                  # rumor time of the last `ended`; older grants are ignored (C§10.2)
        self.views: dict[str, View] = {}
        self.current: str | None = None
        self.grant: dict = {}
        self.owner_inbox: str | None = None
        self.invite_inbox: str | None = None
        self.token: str | None = None
        self.relays: list[str] = []
        self.profile: dict | None = None
        self.readings: dict[tuple[str, int], tuple[int, int, float | None, int]] = {}
        self.objects: dict[tuple[str, str], int] = {}       # (author, name) → newest created_at (cgm)
        self.entries: dict[tuple[str, str], tuple[dict, str]] = {}
        self.sensors: dict[str, dict] = {}
        self.device_status: dict[str, dict] = {}
        self.alerts: dict[str, dict] = {}
        self.notified: set[tuple[str, int]] = set()
        self.acks: dict[tuple[str, str], tuple[dict, int]] = {}
        self.settings: dict | None = None
        self.unknown: dict[tuple[str, str], dict] = {}
        self._seen: set[str] = set()
        self._index: dict[str, tuple[str, int, str, str]] = {}
        self._indexed: tuple | None = None
        self._rekey_at = -10 ** 9
        self._end_seen: int | None = None

    # ---- joining (C§8) --------------------------------------------------------------------------------------------

    def open_invite(self, inv: ctl.Invite, ev: dict, now: int | None = None) -> dict:
        now = int(time.time()) if now is None else now
        body, sender, _ = ctl.open_wrap(inv.key, ev)
        if body.get("type") != "invite":
            raise ValueError("not an invite")
        if not sender.startswith(inv.fp):
            raise ValueError("fingerprint mismatch")
        if not isinstance(body.get("x"), int) or body["x"] <= now:
            raise ValueError("invite expired")
        self.i_pub, self.invite_inbox, self.token = sender, body["inbox"], inv.token
        self.relays = [u for u in body.get("relays", []) if isinstance(u, str) and u.startswith("wss://")]
        return {"i": sender, "inbox": body["inbox"], "relays": self.relays, "profile": body.get("profile"),
                "offer": body.get("offer", {}), "x": body["x"]}

    def join(self, profile: dict | None, now: int | None = None, **pins) -> dict:
        self.status = "joined"
        return ctl.wrap(self.M, self.invite_inbox, {"type": "join", "token": self.token, "inbox": self.inbox.pub,
                                                    "profile": profile, "caps": CAPS}, now=now, **pins)

    @property
    def match_code(self) -> str:
        return ctl.match_code(self.M.pub, self.token)

    def request(self, body: dict, now: int | None = None, **pins) -> dict:
        """rekey / history / profile / inbox / leave / link / manage, to the current epoch's administrator inbox."""
        return ctl.wrap(self.M, self.owner_inbox, body, now=now, **pins)

    # ---- authority (C§10.3) ---------------------------------------------------------------------------------------

    def role(self, view: View, pub: str, day: int) -> tuple[str, list[str]] | None:
        if pub == self.i_pub:
            return "identity", []
        ms = view.membership or {}
        if any(d["k"] == pub for d in ms.get("devices", [])):
            return "device", []
        for m in ms.get("members", []):
            if m["k"] == pub and m["from"] <= day <= (m.get("to") if m.get("to") is not None else 10 ** 9):
                return "member", m["scopes"]
        return None

    def publishers(self, view: View) -> list[tuple[str, str]]:
        """(publisher key, lane) pairs this member expects in an epoch (C§5.3)."""
        out = [(self.i_pub, "m")] if self.i_pub else []
        ms = view.membership or {}
        for d in ms.get("devices", []):
            out += [(d["k"], "g"), (d["k"], "r")]
        out += [(m["k"], "r") for m in ms.get("members", []) if m["scopes"]]
        return out

    def days(self, now: int) -> list[int]:
        today = day_of(now)
        lo = today - self.back
        if self.grant.get("from") is not None:
            lo = max(lo, self.grant["from"])
        return list(range(lo, today + 2))

    def reindex(self, now: int, days: list[int] | None = None):
        days = days or self.days(now)
        key = (tuple(days), tuple((e, v.membership and v.membership["seq"]) for e, v in self.views.items()))
        if key == self._indexed:
            return
        idx = {}
        for eid, v in self.views.items():
            pubs = self.publishers(v)
            for d in days:
                leaf = v.epoch.leaf(d)
                if leaf is None:
                    continue
                dk = DayKeys(eid, d, leaf)
                for pub, lane in pubs:
                    idx[dk.author(pub, lane)] = (eid, d, pub, lane)
        self._index, self._indexed = idx, key

    def filters(self, now: int | None = None) -> dict[str, dict]:
        now = int(time.time()) if now is None else now
        self.reindex(now)
        f = {"inbox": {"kinds": [KIND_WRAP], "#p": [self.inbox.pub], "since": now - 3 * 86400}}
        if self._index:
            f["stream"] = {"kinds": [KIND_ENVELOPE], "authors": sorted(self._index)}
        return f

    # ---- events (C§10) --------------------------------------------------------------------------------------------

    def on_event(self, ev: dict, now: int | None = None) -> Result:
        now = int(time.time()) if now is None else now
        if ev.get("kind") == KIND_WRAP:
            return self._on_wrap(ev, now)
        if ev.get("kind") != KIND_ENVELOPE:
            return Result("ignored", "kind")
        if not verify(ev):
            return Result("rejected", "bad-signature")
        if ev["id"] in self._seen:
            return Result("ignored", "duplicate")
        if expired(ev, now):
            return Result("ignored", "expired")
        if self.status == "ended":
            return Result("ignored", "ended")
        self.reindex(now)
        loc = self._index.get(ev["pubkey"])
        if loc is None:                     # not remembered: a later grant or membership may make it readable
            return Result("ignored", "unknown-author")
        self._seen.add(ev["id"])
        eid, day, publisher, lane = loc
        view = self.views[eid]
        dk = DayKeys(eid, day, view.epoch.leaf(day))
        try:
            rec = open_envelope(ev, dk)
        except (ValueError, json.JSONDecodeError) as e:
            return Result("rejected", "decrypt", str(e))
        try:
            body = check_record(rec)
            D.validate(body)
        except (ValueError, KeyError, json.JSONDecodeError) as e:
            return Result("rejected", "bad-record", str(e))
        author = rec["pubkey"]
        prole = self.role(view, publisher, day)
        if author != publisher and (prole is None or prole[0] != "device"):
            return Result("rejected", "rewrapped")
        if lane_of(body["type"]) != lane:
            return Result("rejected", "wrong-lane")
        if record_day(body, rec["created_at"]) != day:
            return Result("rejected", "wrong-day")
        if tag(ev, "d") != dk.dtag(object_name(body, author)):
            return Result("rejected", "d-mismatch")
        role = self.role(view, author, day)
        why = self._authorize(body, role)
        if why:
            return Result("rejected", why)
        return self._apply(eid, view, body, rec, author, role, now)

    @staticmethod
    def _authorize(body: dict, role) -> str | None:
        t = body["type"]
        kind, scopes = role or (None, [])
        if t in ("membership", "epoch_end"):
            return None if kind == "identity" else "not-identity"
        if t in DEVICE_TYPES:
            return None if kind == "device" else "not-device"
        if t in ENTRY_TYPES:
            return None if kind == "device" or (kind == "member" and "write" in scopes) else "scope"
        if t == "ack":
            return None if kind == "device" or (kind == "member" and "ack" in scopes) else "scope"
        return None if kind == "device" or (kind == "member" and "write" in scopes) else "scope"

    def _apply(self, eid, view, body, rec, author, role, now) -> Result:
        t, at = body["type"], rec["created_at"]
        if t == "membership":
            if body["epoch"] != eid:
                return Result("rejected", "epoch-mismatch")
            if view.membership and body["seq"] <= view.membership["seq"]:
                return Result("ignored", "older")
            view.membership = body
            if eid == self.current:
                self.owner_inbox, self.profile = body["inbox"], body.get("profile")
                self.relays = body.get("relays") or self.relays
            self._indexed = None
            return Result("accepted", "membership", f"seq {body['seq']}")
        if t == "epoch_end":
            view.ended = True
            if eid == self.current and self._end_seen is None:
                self._end_seen = now
            return Result("accepted", "epoch-end")
        if t == "cgm":
            name = object_name(body, author)
            if self.objects.get((author, name), -1) >= at:
                return Result("ignored", "older")
            self.objects[(author, name)] = at
            n = 0
            for ts, v, rate, fl in D.rows_of(body):
                cur = self.readings.get((body["sess"], ts))
                if cur is None or cur[0] <= at:
                    self.readings[(body["sess"], ts)] = (at, v, rate, fl)
                    n += 1
            return Result("accepted", "cgm", f"{n} rows")
        if t in ("sensor", "settings", "alert", "device"):
            store, key = {"sensor": (self.sensors, body.get("sess")), "alert": (self.alerts, body.get("id")),
                          "device": (self.device_status, author), "settings": (None, None)}[t]
            cur = self.settings if t == "settings" else store.get(key)
            if cur is not None and cur["rev"] >= body["rev"]:
                return Result("ignored", "older")
            if t == "settings":
                self.settings = body
            else:
                store[key] = body
            if t == "alert":
                if body["state"] == "active" and now - body["last"] <= ALERT_NOTIFY_AGE and \
                        (body["id"], body["n"]) not in self.notified:
                    self.notified.add((body["id"], body["n"]))
                    return Result("accepted", "alert", f"{body['kind']} #{body['n']}")
                return Result("accepted", "alert-silent")
            return Result("accepted", t)
        if t == "ack":
            cur = self.acks.get((body["alert"], author))
            if cur is not None and cur[1] >= at:
                return Result("ignored", "older")
            self.acks[(body["alert"], author)] = (body, at)
            return Result("accepted", "ack")
        if t in ENTRY_TYPES:
            by = body.get("by") or author
            if by != author and role[0] != "device":
                return Result("rejected", "not-creator")
            key = (by, body["id"])
            cur = self.entries.get(key)
            if cur is not None:
                if cur[0]["type"] != t:
                    return Result("rejected", "type-changed")
                if (body["rev"], author) <= (cur[0]["rev"], cur[1]):
                    return Result("ignored", "older")
            self.entries[key] = (body, author)
            return Result("accepted", t, "deleted" if body.get("del") else "")
        self.unknown[(author, object_name(body, author))] = body
        return Result("accepted", "unknown-type", t)

    def _on_wrap(self, ev: dict, now: int) -> Result:
        if not verify(ev):
            return Result("rejected", "bad-signature")
        if ev["id"] in self._seen:
            return Result("ignored", "duplicate")
        self._seen.add(ev["id"])
        if expired(ev, now):
            return Result("ignored", "expired")
        if tag(ev, "p") != self.inbox.pub:
            return Result("ignored", "not-for-us")
        try:
            body, sender, at = ctl.open_wrap(self.inbox, ev)
        except (ValueError, json.JSONDecodeError) as e:
            return Result("rejected", "unwrap", str(e))
        if sender != self.i_pub:
            return Result("rejected", "not-from-identity")
        if body["type"] == "grant":
            if at <= self.ended_at:
                return Result("ignored", "grant-before-ended")
            return self._on_grant(body, now)
        if body["type"] == "ended":
            self.status, self.reason, self.ended_at = "ended", body.get("reason"), max(self.ended_at, at)
            return Result("accepted", "ended", self.reason or "")
        if body["type"] == "invite-link":
            return Result("accepted", "invite-link")
        return Result("ignored", "unknown-type", body["type"])

    def _on_grant(self, body: dict, now: int) -> Result:
        try:
            epochs = [Epoch.from_json(e) for e in body["epochs"]]
            terms = body["grant"]
            if not isinstance(terms.get("scopes"), list) or not isinstance(terms.get("from"), int):
                raise ValueError("terms")
            if body["current"] not in [e.id for e in epochs]:
                raise ValueError("current epoch missing")
            mrec = body["membership"]
            mbody = check_record(mrec)
            D.validate(mbody)
            if mrec["pubkey"] != self.i_pub or mbody["type"] != "membership" or mbody["epoch"] != body["current"]:
                raise ValueError("membership")
        except (KeyError, TypeError, ValueError) as e:
            return Result("rejected", "bad-grant", str(e))
        for e in epochs:
            v = self.views.get(e.id)
            if v is None:
                self.views[e.id] = View(e)
            else:
                have = {(n.level, n.index) for n in v.epoch.nodes}
                v.epoch.nodes += [n for n in e.nodes if (n.level, n.index) not in have]
        cur = self.views[body["current"]]
        if cur.membership is None or mbody["seq"] > cur.membership["seq"]:
            cur.membership = mbody
        self.current, self.grant = body["current"], terms
        self.owner_inbox = mbody["inbox"]
        self.relays = mbody.get("relays") or self.relays
        self.profile = mbody.get("profile")
        self.status, self._indexed, self._end_seen = "active", None, None
        return Result("accepted", "grant", f"epoch {body['current']}")

    def tick(self, now: int | None = None) -> list[dict]:
        """C§10.2: the current epoch ended and no grant for a newer one came within REKEY_GRACE → ask once."""
        now = int(time.time()) if now is None else now
        cur = self.views.get(self.current or "")
        if self.status != "active" or cur is None or not cur.ended or self._end_seen is None:
            return []
        if now - self._end_seen < REKEY_GRACE or now - self._rekey_at < REKEY_EVERY or not self.owner_inbox:
            return []
        self._rekey_at = now
        return [self.request({"type": "rekey", "have": sorted(self.views)}, now)]

    # ---- contributing (C§10.4) ------------------------------------------------------------------------------------

    def write(self, body: dict, now: int | None = None, *, nonce=None, aux=None, exp: int | None = None) -> dict:
        """Publish an entry or ack as this member: a record signed by M in an envelope of M's blinded r-lane key."""
        now = int(time.time()) if now is None else now
        rec = sign_record(self.M, body, now, aux)
        view = self.views[self.current]
        day = record_day(body, now)
        leaf = view.epoch.leaf(day)
        if leaf is None:
            raise ValueError("no key for that day")
        dk = DayKeys(self.current, day, leaf)
        return seal(dk.author_keys(self.M, "r"), dk, rec, name=object_name(body, self.M.pub),
                    expiration=exp or (now + self.ttl if self.ttl else expiry(body, now)), created_at=now, nonce=nonce, aux=aux)

    # ---- maintenance (C§11.4) -------------------------------------------------------------------------------------

    def puncture(self, keep_from_day: int):
        """Forward secrecy: replace every cover node by the sub-nodes that cover only days ≥ keep_from_day, so the
        seeds for older days are gone from this device."""
        from .core import child
        for v in self.views.values():
            rel = keep_from_day - v.epoch.start
            out: list[Node] = []

            def rec(n: Node):
                if n.last < rel:
                    return
                if n.first >= rel:
                    out.append(n)
                    return
                for bit in (0, 1):
                    rec(Node(n.level + 1, 2 * n.index + bit, child(n.seed, bit)))

            for n in v.epoch.nodes:
                rec(n)
            v.epoch.nodes = out
        self._indexed = None
