# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""A transport-independent OGS follower: the acceptance rules of spec/ogs-nostr.md §9, nothing else.

Feed it events from relays or from the BLE link (`on_event`), read the results from its state. It never trusts a
transport: every event is checked the same way whichever path it came by.
"""
from __future__ import annotations

import json
import time
from dataclasses import dataclass, field

from . import crypto as c
from . import events as E
from . import model as m
from .nostr import Keys, UnwrapError, decrypt, expired, tag, unwrap, verify


@dataclass
class Result:
    verdict: str          # accepted | rejected | ignored
    what: str             # roster | chunk | live | sensor | alert | treatment | key | revoked | ... | reason
    detail: str = ""
    out: list = field(default_factory=list)   # events the follower wants to send (rekey requests)

    def __str__(self):
        return f"{self.verdict}:{self.what}" + (f" ({self.detail})" if self.detail else "")


class Follower:
    def __init__(self, F: Keys, i_pub: str | None = None, token: str | None = None):
        self.F = F
        self.i_pub = i_pub
        self.token = token
        self.status = "new"                  # new → requested → active | declined | used ; active → revoked
        self.sid: str | None = None
        self.epochs: dict[str, tuple[int, Keys]] = {}     # gk → (n, G)
        self.devices: list[str] = []
        self.writers: list[str] = []
        self.relays: list[str] = []
        self.profile: dict | None = None
        self.roster_at = (-1, -1)            # (epoch, created_at) of the roster in use
        self.readings: dict[int, m.Reading] = {}
        self.sensor: m.Sensor | None = None
        self.sensor_at = -1
        self.treatments: dict[str, m.Treatment] = {}
        self.alerts: list[m.Alert] = []
        self._alert_seen: set[tuple] = set()
        self._seen: set[str] = set()
        self._rekey_at: dict[str, int] = {}
        self.log: list[str] = []

    # ---- joining (N§7) ----------------------------------------------------------------------------------------------

    def open_invite(self, inv: m.Invite, wrap: dict, now: int | None = None) -> dict:
        """N§7.3: the invite wrap must open with invite_key(secret), carry a 30502 rumor sealed by a key whose
        first 8 bytes are the link's fingerprint, and not be expired."""
        now = int(time.time()) if now is None else now
        rum, sender = unwrap(inv.key, wrap)
        if rum.get("kind") != m.KIND_INVITE:
            raise m.Invalid("not an invite rumor")
        if not sender.startswith(inv.fp):
            raise m.Invalid("sealed by a key that does not match the link's fingerprint")
        body = m.parse_rumor(json.loads(rum["content"]), m.KIND_INVITE)
        if body.get("type") != "invite" or m._int(body, "x") <= now:
            raise m.Invalid("expired or not an invite")
        self.i_pub, self.token = sender, inv.token
        self.relays = [r for r in body.get("r") or [] if isinstance(r, str) and r.startswith("wss://")]
        return {"i": sender, "x": body["x"], "relays": self.relays, "profile": body.get("profile")}

    def request(self, profile: dict | None, **pins) -> dict:
        self.status = "requested"
        return E.access_request(self.F, self.i_pub, self.token, profile, **pins)

    @property
    def match_code(self) -> str:
        return m.match_code(self.F.pub, self.token)

    # ---- every incoming event (N§9) ----------------------------------------------------------------------------------

    def on_event(self, ev: dict, now: int | None = None) -> Result:
        now = int(time.time()) if now is None else now
        r = self._on_event(ev, now)
        self.log.append(f"{ev.get('kind')} {ev.get('id', '')[:8]} {r}")
        return r

    def _on_event(self, ev: dict, now: int) -> Result:
        eid = ev.get("id")
        if eid in self._seen:
            return Result("ignored", "duplicate")
        if not verify(ev):
            return Result("rejected", "bad-signature")
        self._seen.add(eid)
        if expired(ev, now):
            return Result("ignored", "expired")
        if ev["kind"] == m.KIND_WRAP:
            return self._on_wrap(ev, now)
        if self.status == "revoked":
            return Result("ignored", "revoked")
        g = tag(ev, "gk")
        if g is None:
            return Result("ignored", "no-gk")
        author = ev["pubkey"]
        if g not in self.epochs:
            self._seen.discard(eid)          # readable later, once the key arrives
            if self.status == "active" and (author == self.i_pub or author in self.devices):
                if now - self._rekey_at.get(g, -10 ** 9) >= 300:
                    self._rekey_at[g] = now
                    return Result("ignored", "unknown-epoch", g, [E.rekey_request(self.F, self.i_pub, g)])
            return Result("ignored", "unknown-epoch", g)
        n, G = self.epochs[g]
        if self.sid is None:
            return Result("ignored", "no-sid")
        try:
            if ev["kind"] == m.KIND_DATA:
                return self._on_data(ev, n, G, author, now)
            if ev["kind"] in (m.KIND_LIVE, m.KIND_ALERT):
                if author not in self.devices:
                    return Result("rejected", "author-not-device")
                body = json.loads(decrypt(G, author, ev["content"]))
                return self._on_live(ev, body, now) if ev["kind"] == m.KIND_LIVE else self._on_alert(body, now)
        except (ValueError, KeyError, TypeError) as e:     # decrypt / JSON / m.Invalid
            return Result("rejected", "invalid", str(e))
        return Result("ignored", "unknown-kind")

    def _on_data(self, ev, n, G, author, now) -> Result:
        d, cc = tag(ev, "d"), tag(ev, "c")
        if d == m.d_roster(self.sid):
            if author != self.i_pub:
                return Result("rejected", "roster-not-by-identity")
            ros = m.parse_roster(json.loads(decrypt(G, author, ev["content"])))
            if ros.epoch != n:
                return Result("rejected", "roster-epoch-mismatch", f"{ros.epoch} under gk of epoch {n}")
            if (ros.epoch, ev["created_at"]) <= self.roster_at:
                return Result("ignored", "roster-older")
            self.roster_at = (ros.epoch, ev["created_at"])
            self.devices, self.writers, self.profile = ros.devices, ros.writers, ros.profile
            if ros.relays:
                self.relays = ros.relays
            return Result("accepted", "roster", f"epoch {ros.epoch}, {len(ros.devices)} device(s), "
                                                f"{len(ros.writers)} writer(s)")
        if cc == m.c_treatments(self.sid):
            if author not in self.devices and author not in self.writers:
                return Result("rejected", "author-not-in-roster")
            tr = m.parse_treatment(json.loads(decrypt(G, author, ev["content"])))
            if d != m.d_treatment(self.sid, tr.id):
                return Result("rejected", "d-mismatch")
            cur = self.treatments.get(tr.id)
            if cur is None or m.treatment_newer(tr, cur):
                self.treatments[tr.id] = tr
                return Result("accepted", "treatment", f"{tr.k} {tr.a:g}{' deleted' if tr.deleted else ''}")
            return Result("ignored", "treatment-older")
        if cc == m.c_glucose(self.sid):
            if author not in self.devices:
                return Result("rejected", "author-not-device")
            h, rows = m.parse_chunk(json.loads(decrypt(G, author, ev["content"])))
            if d != m.d_chunk(self.sid, h):
                return Result("rejected", "d-mismatch")
            new = sum(1 for r in rows if self.readings.setdefault(r.t, r) is r)
            return Result("accepted", "chunk", f"hour {h}: {len(rows)} rows, {new} new")
        return Result("ignored", "unknown-30078")

    def _on_live(self, ev, body, now) -> Result:
        reading, sensor, has = m.parse_live(body)
        what = []
        if ev["created_at"] >= self.sensor_at:
            self.sensor_at = ev["created_at"]
            self.sensor = sensor if has else None
            what.append("sensor" if has else "sensor-cleared")
        if reading:
            if reading.t > now + 300:
                return Result("rejected", "reading-in-future")
            self.readings.setdefault(reading.t, reading)
            return Result("accepted", "live", f"{reading.sgv} mg/dL {m.direction(reading.rate)}")
        return Result("accepted", "sensor", ",".join(what))

    def _on_alert(self, body, now) -> Result:
        a = m.parse_alert(body)
        if now - a.at > m.ALERT_MAX_AGE:
            return Result("ignored", "alert-stale")
        key = (a.id, a.state, a.n)
        if key in self._alert_seen:
            return Result("ignored", "alert-duplicate")
        self._alert_seen.add(key)
        self.alerts.append(a)
        return Result("accepted", "alert", f"{a.k} {a.state}")

    def _on_wrap(self, ev, now) -> Result:
        if tag(ev, "p") != self.F.pub:
            return Result("ignored", "wrap-not-for-us")
        try:
            rum, sender = unwrap(self.F, ev)
        except (UnwrapError, ValueError) as e:
            return Result("rejected", "unwrap", str(e))
        if rum.get("kind") != m.KIND_TO_FOLLOWER or sender != self.i_pub:
            return Result("rejected", "wrap-not-from-identity")
        try:
            body = m.parse_rumor(json.loads(rum["content"]), m.KIND_TO_FOLLOWER)
            typ = body["type"]
            if typ == "key":
                return self._on_key(body)
        except (ValueError, KeyError, TypeError) as e:
            return Result("rejected", "invalid", str(e))
        if typ == "revoked":
            self.status = "revoked"
            return Result("accepted", "revoked")
        if typ in ("invite-used", "declined"):
            if self.status != "requested":
                return Result("ignored", typ)
            self.status = "used" if typ == "invite-used" else "declined"
            return Result("accepted", typ)
        return Result("ignored", "unknown-type", typ)

    def _on_key(self, body) -> Result:
        sid, n = body.get("sid"), m._int(body, "epoch")
        if not isinstance(sid, str) or len(sid) != 64:
            raise m.Invalid("bad sid")
        if self.sid is not None and sid != self.sid:
            return Result("rejected", "sid-changed")
        G = Keys(c.nsec_decode(body["g"]))
        g = m.gk(G.pub)
        if g in self.epochs:
            return Result("ignored", "key-known")
        newest = max((e[0] for e in self.epochs.values()), default=0)
        self.sid = sid
        self.epochs[g] = (n, G)
        if n >= newest:
            devs = body.get("devices") or []
            if devs:
                self.devices = devs
            relays = [r for r in body.get("relays") or [] if isinstance(r, str) and r.startswith("wss://")]
            if relays:
                self.relays = relays
        if self.status != "revoked":
            self.status = "active"
        return Result("accepted", "key", f"epoch {n}")

    # ---- what a follower asks relays for (N§8.3) ---------------------------------------------------------------------

    def filters(self, now: int | None = None) -> dict[str, dict]:
        now = int(time.time()) if now is None else now
        f = {"inbox": {"kinds": [m.KIND_WRAP], "#p": [self.F.pub], "since": now - 3 * 86400}}
        if self.sid:
            f["roster"] = {"kinds": [m.KIND_DATA], "authors": [self.i_pub], "#d": [m.d_roster(self.sid)]}
            if self.devices:
                f["chunks"] = {"kinds": [m.KIND_DATA], "authors": self.devices, "#c": [m.c_glucose(self.sid)],
                               "since": now - 13 * 3600}
                f["live"] = {"kinds": [m.KIND_LIVE, m.KIND_ALERT], "authors": self.devices, "since": now - 120}
                f["treatments"] = {"kinds": [m.KIND_DATA], "authors": sorted(set(self.devices + self.writers)),
                                   "#c": [m.c_treatments(self.sid)], "since": now - 31 * 86400}
        return f
