# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Owner and follower nodes for protocol v2 (reference implementation, used by owner.py/follower.py/scenarios.py)."""
from __future__ import annotations

import asyncio, json, math, secrets, sqlite3, time
from pathlib import Path

import websockets

from cgm2 import (CHUNK, LIVE, RUMOR_TO_FOLLOWER, RUMOR_TO_OWNER, WRAP, Epoch, FollowerState, OwnerState, Pool, Reading,
                  backup_d, backup_event, chunk_event, chunk_tags, dec, gift, gk, hour_of, invite_link, keys, live_event,
                  log, match_code, new_token, parse_invite, pub, roster_d, roster_event, tag, unwrap, verify)


async def fetch(relays, flt, timeout=8) -> list[dict]:
    """One-shot REQ on every relay until EOSE; union of events."""
    async def one(url):
        out = []
        try:
            async with websockets.connect(url, open_timeout=8, max_size=2**22) as ws:
                sid = secrets.token_hex(4)
                await ws.send(json.dumps(["REQ", sid, flt]))
                end = time.time() + timeout
                while time.time() < end:
                    m = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
                    if m[0] == "EVENT" and m[1] == sid:
                        out.append(m[2])
                    elif m[0] in ("EOSE", "CLOSED") and m[1] == sid:
                        break
        except Exception:
            pass
        return out
    res = await asyncio.gather(*(one(u) for u in relays))
    seen, out = set(), []
    for ev in (e for r in res for e in r):
        if ev["id"] not in seen:
            seen.add(ev["id"])
            out.append(ev)
    return out


def synthetic(t: int) -> Reading:
    return Reading(t, int(130 + 60 * math.sin(t / 5400)), round(60 * math.cos(t / 5400) / 90, 2), "synthetic")


# ======================================================================== owner

class OwnerNode:
    """The phone that owns the sensor: publishes data, answers access requests, revokes, restores."""

    def __init__(self, st: OwnerState, path: Path, policy=lambda name, code: True, name="owner"):
        self.st, self.path, self.policy, self.name = st, path, policy, name
        self.pool = Pool(st.relays, auth_keys=st.I)          # NIP-42 as I, so AUTH-only relays serve our gift wraps
        self.readings: dict[int, Reading] = {}
        self.seen_wraps: set[str] = set()
        self.events: list[dict] = []          # for scenario assertions

    def emit(self, **kw):
        kw["node"] = self.name
        self.events.append(kw)
        log(json.dumps(kw))

    # ----- lifecycle
    def start(self):
        self.pool.start()
        self.pool.subscribe({"kinds": [WRAP], "#p": [pub(self.st.I)], "since": int(time.time()) - 3 * 86400}, self.on_wrap)
        self.publish_roster()
        self.publish_backup()

    def save(self):
        self.st.save(self.path)

    def publish_roster(self):
        e = self.st.current
        self.pool.publish(roster_event(self.st.I, e.pub, self.st.sid, e.n, [pub(self.st.D)], [], self.st.relays))

    def publish_backup(self):
        self.pool.publish(backup_event(self.st.I, self.st.backup_body()))
        self.save()

    # ----- data
    def add(self, r: Reading, live=True):
        new = r.t not in self.readings
        self.readings[r.t] = r
        if live and new:
            self.pool.publish(live_event(self.st.D, self.st.current.pub, r))

    def publish_chunks(self, hours=None):
        e = self.st.current
        for h in sorted(hours if hours is not None else {hour_of(int(time.time()))}):
            rs = [r for r in self.readings.values() if hour_of(r.t) == h]
            if rs:
                self.pool.publish(chunk_event(self.st.D, e.pub, self.st.sid, h, rs))

    def recent_hours(self, hours):
        cut = time.time() - hours * 3600
        return {hour_of(t) for t in self.readings if t >= cut}

    # ----- sharing
    def invite(self, ttl=86400) -> str:
        tok, x = new_token(), int(time.time()) + ttl
        self.st.invites.append({"t": tok, "x": x})
        self.publish_backup()
        return invite_link(pub(self.st.I), tok, x, self.st.relays)

    def deliver_key(self, f_hex):
        e = self.st.current
        body = {"v": 2, "type": "key", "sid": self.st.sid, "epoch": e.n, "g": e.g.secret_key().to_bech32(),
                "devices": [pub(self.st.D)], "relays": self.st.relays}
        self.pool.publish(gift(self.st.I, f_hex, RUMOR_TO_FOLLOWER, body))

    def on_wrap(self, ev, relay):
        if ev["id"] in self.seen_wraps:
            return
        self.seen_wraps.add(ev["id"])
        got = unwrap(self.st.I, ev)
        if not got:
            return
        sender, kind, body = got
        if kind != RUMOR_TO_OWNER or body.get("v") != 2:
            return
        follower = next((f for f in self.st.followers if f["f"] == sender), None)
        if body.get("type") == "request":
            inv = next((i for i in self.st.invites if i["t"] == body.get("token")), None)
            if follower and not follower.get("revoked"):
                return self.deliver_key(sender)                      # duplicate request from an approved follower
            if not inv or inv["x"] < time.time():
                return self.emit(evt="request_rejected", why="unknown or expired invite", name=body.get("name"))
            code = match_code(sender, inv["t"])
            if not self.policy(body.get("name", "?"), code):
                self.st.invites.remove(inv)
                self.publish_backup()
                return self.emit(evt="request_denied", name=body.get("name"), code=code)
            self.st.invites.remove(inv)                              # single-use
            self.st.followers.append({"f": sender, "name": body.get("name", "?"), "since": int(time.time())})
            self.deliver_key(sender)
            self.publish_backup()
            self.emit(evt="approved", name=body.get("name"), code=code, epoch=self.st.current.n)
        elif body.get("type") == "rekey":
            if follower and not follower.get("revoked"):
                self.deliver_key(sender)
                self.emit(evt="rekeyed", name=follower["name"], epoch=self.st.current.n)
            else:
                self.emit(evt="rekey_refused", f=sender[:12])

    # ----- rotation
    def rotate(self, why: str):
        self.st.epochs.append(Epoch(self.st.current.n + 1, keys()))
        self.publish_roster()
        for f in self.st.active_followers():
            self.deliver_key(f["f"])
        self.publish_backup()
        self.publish_chunks(self.recent_hours(24))                   # recent history readable with the new key
        self.emit(evt="rotated", why=why, epoch=self.st.current.n, followers=len(self.st.active_followers()))

    def revoke(self, name: str):
        f = next(f for f in self.st.followers if f["name"] == name and not f.get("revoked"))
        f["revoked"] = int(time.time())
        self.pool.publish(gift(self.st.I, f["f"], RUMOR_TO_FOLLOWER, {"v": 2, "type": "revoked"}))
        self.rotate(f"revoked {name}")

    @staticmethod
    async def restore(identity_nsec: str, path: Path, relays, **kw) -> "OwnerNode":
        """Phone change: identity from the password manager → owner backup from relays → new device key + epoch."""
        I = keys(identity_nsec)
        evs = [e for e in await fetch(relays, {"authors": [pub(I)], "kinds": [CHUNK], "#d": [backup_d(pub(I))]})
               if verify(e)]
        if not evs:
            raise RuntimeError("no owner backup found on relays")
        newest = max(evs, key=lambda e: e["created_at"])
        st = OwnerState.from_backup(I, dec(I, pub(I), newest["content"]))
        node = OwnerNode(st, path, **kw)
        node.start()
        node.rotate("restored on a new device")
        return node


# ===================================================================== follower

class FollowerNode:
    """A follower device: joins via invite, keeps its own SQLite, accepts only roster-listed devices."""

    def __init__(self, st: FollowerState, path: Path, db: Path, name="follower"):
        self.st, self.path, self.name = st, path, name
        self.db = sqlite3.connect(db)
        self.db.execute("create table if not exists readings(t integer primary key, sgv integer, rate real,"
                        " epoch integer, via text, received integer)")
        self.pool = Pool(st.relays, auth_keys=st.F)
        self.seen: set[str] = set()
        self.subscribed_devices: tuple = ()
        self.rekey_asked: dict[str, float] = {}
        self.events: list[dict] = []

    def emit(self, **kw):
        kw["node"] = self.name
        self.events.append(kw)
        log(json.dumps(kw))

    def count(self, since=0):
        return self.db.execute("select count(*) from readings where received >= ?", (since,)).fetchone()[0]

    def start(self):
        if not self.st.relays:                  # a fresh follower learns its relays from the invite link
            return
        self.pool = Pool(self.st.relays, auth_keys=self.st.F)
        self.pool.start()
        self.pool.subscribe({"kinds": [WRAP], "#p": [pub(self.st.F)], "since": int(time.time()) - 3 * 86400}, self.on_wrap)
        self.started = True
        if self.st.sid:
            self.subscribe_data()

    async def request_access(self, link: str, display_name: str):
        inv = parse_invite(link)
        self.st.i_pub, self.st.relays = inv["i"], inv["r"]
        if not getattr(self, "started", False):
            self.start()
        self.pool.publish(gift(self.st.F, inv["i"], RUMOR_TO_OWNER,
                               {"v": 2, "type": "request", "token": inv["t"], "name": display_name}))
        self.st.save(self.path)
        self.emit(evt="requested", code=match_code(pub(self.st.F), inv["t"]))

    def subscribe_data(self):
        self.pool.subscribe({"kinds": [CHUNK], "authors": [self.st.i_pub], "#d": [roster_d(self.st.sid)]}, self.on_data)
        if self.st.devices and tuple(self.st.devices) != self.subscribed_devices:
            self.subscribed_devices = tuple(self.st.devices)
            _, c = chunk_tags(self.st.sid, 0)
            now = int(time.time())
            self.pool.subscribe({"kinds": [CHUNK], "authors": self.st.devices, "#c": [c], "since": now - 13 * 3600}, self.on_data)
            self.pool.subscribe({"kinds": [LIVE], "authors": self.st.devices, "since": now - 120}, self.on_data)

    def on_wrap(self, ev, relay):
        if ev["id"] in self.seen:
            return
        self.seen.add(ev["id"])
        got = unwrap(self.st.F, ev)
        if not got:
            return
        sender, kind, body = got
        if kind != RUMOR_TO_FOLLOWER or sender != self.st.i_pub:     # only the stream identity may send keys
            return self.emit(evt="reject", why="key delivery not sealed by the identity key", sender=sender[:12])
        if body.get("type") == "key":
            g = keys(body["g"])
            if gk(pub(g)) not in self.st.epochs:
                self.st.epochs[gk(pub(g))] = (body["epoch"], g)
                self.st.sid, self.st.devices, self.st.relays = body["sid"], body["devices"], body["relays"]
                self.st.status = "active"
                self.st.save(self.path)
                self.emit(evt="key", epoch=body["epoch"])
                self.subscribe_data()
        elif body.get("type") == "revoked":
            self.st.status = "revoked"
            self.st.save(self.path)
            self.emit(evt="revoked")

    def on_data(self, ev, relay):
        if ev["id"] in self.seen:
            return
        self.seen.add(ev["id"])
        g = tag(ev, "gk")
        if g not in self.st.epochs:
            if self.st.status == "active" and time.time() - self.rekey_asked.get(g, 0) > 300:
                self.rekey_asked[g] = time.time()
                self.pool.publish(gift(self.st.F, self.st.i_pub, RUMOR_TO_OWNER, {"v": 2, "type": "rekey", "have": g}))
                self.emit(evt="rekey_requested", gk=g)
            return
        epoch, G = self.st.epochs[g]
        if not verify(ev):
            return self.emit(evt="reject", why="bad signature")
        if ev["kind"] == CHUNK and tag(ev, "d") == roster_d(self.st.sid):
            if ev["pubkey"] != self.st.i_pub:
                return self.emit(evt="reject", why="roster not signed by identity")
            body = dec(G, ev["pubkey"], ev["content"])
            if body["devices"] != self.st.devices:
                self.st.devices = body["devices"]
                self.st.save(self.path)
                self.emit(evt="roster", epoch=body["epoch"], devices=[d[:12] for d in body["devices"]])
                self.subscribe_data()
            return
        if ev["pubkey"] not in self.st.devices:
            return self.emit(evt="reject", why="author not in roster", author=ev["pubkey"][:12])
        try:
            body = dec(G, ev["pubkey"], ev["content"])
        except Exception:
            return self.emit(evt="reject", why="decrypt failed")
        rows = ([(body["t"], body["sgv"], body.get("r"))] if ev["kind"] == LIVE else
                [(body["h"] + dt, sgv, None if r100 is None else r100 / 100) for dt, sgv, r100 in body["r"]])
        host = relay.split("//")[1]
        new = 0
        for t, sgv, rate in rows:
            cur = self.db.execute("insert or ignore into readings values(?,?,?,?,?,?)",
                                  (t, sgv, rate, epoch, f"relay:{host}", int(time.time())))
            new += cur.rowcount
        self.db.commit()
        if ev["kind"] == LIVE and new:
            self.emit(evt="reading", sgv=rows[0][1], t=rows[0][0], epoch=epoch, via=host,
                      latency=round(time.time() - rows[0][0], 1))
        elif new:
            self.emit(evt="chunk", rows=new, epoch=epoch, via=host)
