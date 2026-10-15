# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""CGM-over-Nostr protocol helpers (see PROTOCOL.md). Shared by publisher.py, follower.py and keys.py."""
from __future__ import annotations

import asyncio, hashlib, json, secrets, sys, time
from dataclasses import dataclass, field
from urllib.parse import parse_qs, quote, urlparse

import nostr_sdk as n
import websockets

LIVE_KIND, CHUNK_KIND = 21078, 30078
LIVE_TTL = 3600
CHUNK_TTL = 7 * 24 * 3600
DEFAULT_RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://relay.damus.io",
                  "wss://offchain.pub", "wss://nostr.mom", "wss://relay.nostr.net"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


@dataclass
class Reading:
    t: int                 # unix seconds
    sgv: int               # mg/dL
    rate: float | None     # mg/dL/min
    dev: str = ""


# ---------- keys & link ----------

@dataclass
class Stream:
    """What a follower needs: source pubkey + read key + relays. The uploader also has `source` (secret)."""
    source_pub: n.PublicKey
    read: n.Keys
    relays: list[str] = field(default_factory=lambda: list(DEFAULT_RELAYS))
    source: n.Keys | None = None

    def link(self) -> str:
        return (f"cgmnostr:1?s={self.source_pub.to_bech32()}&k={self.read.secret_key().to_bech32()}"
                f"&r={quote(','.join(self.relays), safe='')}")

    @staticmethod
    def from_link(link: str) -> "Stream":
        q = parse_qs(urlparse(link.replace("cgmnostr:1?", "cgmnostr://x/?")).query)
        return Stream(n.PublicKey.parse(q["s"][0]), n.Keys.parse(q["k"][0]), q["r"][0].split(","))

    def chunk_id(self, hour_start: int) -> str:
        return hashlib.sha256(f"cgm1:{self.read.public_key().to_hex()}:{hour_start}".encode()).hexdigest()[:32]

    # ----- encryption -----
    def encrypt(self, obj) -> str:
        assert self.source, "publishing needs the source secret key"
        return n.nip44_encrypt(self.source.secret_key(), self.read.public_key(),
                               json.dumps(obj, separators=(",", ":")), n.Nip44Version.V2)

    def decrypt(self, ct: str):
        return json.loads(n.nip44_decrypt(self.read.secret_key(), self.source_pub, ct))

    # ----- events -----
    def _event(self, kind, content, tags) -> dict:
        return json.loads(n.EventBuilder(n.Kind(kind), content).tags(tags).finalize(self.source).as_json())

    def live_event(self, r: Reading) -> dict:
        body = {"v": 1, "t": r.t, "sgv": r.sgv, "r": r.rate, "dev": r.dev}
        return self._event(LIVE_KIND, self.encrypt(body), [n.Tag.expiration(n.Timestamp.from_secs(int(time.time()) + LIVE_TTL))])

    def chunk_event(self, hour_start: int, readings: list[Reading]) -> dict:
        rows = [[r.t - hour_start, r.sgv, None if r.rate is None else round(r.rate * 100)]
                for r in sorted(readings, key=lambda r: r.t) if hour_start <= r.t < hour_start + 3600]
        body = {"v": 1, "h": hour_start, "dev": readings[-1].dev if readings else "", "r": rows}
        tags = [n.Tag.identifier(self.chunk_id(hour_start)),
                n.Tag.expiration(n.Timestamp.from_secs(hour_start + CHUNK_TTL))]
        return self._event(CHUNK_KIND, self.encrypt(body), tags)

    def parse(self, ev: dict) -> list[Reading]:
        """Verify + decrypt an event from this stream into readings ([] if not ours / invalid)."""
        if ev.get("pubkey") != self.source_pub.to_hex():
            return []
        try:
            if not n.Event.from_json(json.dumps(ev)).verify():
                return []
            body = self.decrypt(ev["content"])
        except Exception:
            return []
        if ev["kind"] == LIVE_KIND:
            return [Reading(body["t"], body["sgv"], body.get("r"), body.get("dev", ""))]
        if ev["kind"] == CHUNK_KIND:
            h = body["h"]
            return [Reading(h + dt, sgv, None if r100 is None else r100 / 100, body.get("dev", ""))
                    for dt, sgv, r100 in body["r"]]
        return []


def new_stream(relays=None) -> Stream:
    s, g = n.Keys.generate(), n.Keys.generate()
    return Stream(s.public_key(), g, list(relays or DEFAULT_RELAYS), s)


def hour_of(t: int) -> int:
    return t - t % 3600


# ---------- Nightscout direction ----------

def direction(rate: float | None) -> str:
    if rate is None:
        return "NONE"
    for limit, name in ((3, "DoubleUp"), (2, "SingleUp"), (1, "FortyFiveUp")):
        if rate >= limit:
            return name
    for limit, name in ((-1, "Flat"), (-2, "FortyFiveDown"), (-3, "SingleDown")):
        if rate > limit:
            return name
    return "DoubleDown"


# ---------- minimal relay client ----------

class Relay:
    """One relay connection with auto-reconnect, paced publishing and subscriptions."""

    def __init__(self, url: str, min_gap: float = 2.0):
        self.url, self.min_gap = url, min_gap
        self.ws = None
        self.queue: asyncio.PriorityQueue = asyncio.PriorityQueue()
        self._seq = 0
        self.subs: dict[str, tuple[dict, callable]] = {}
        self.ok = self.fail = self.dropped = 0
        self.cooldown_until = 0.0
        self.last_error = ""
        self.connected = asyncio.Event()

    def publish(self, ev: dict):
        # live readings jump the queue; only history chunks wait behind the pacing
        self._seq += 1
        self.queue.put_nowait((0 if ev["kind"] == LIVE_KIND else 1, self._seq, ev))

    def subscribe(self, filters: dict, on_event, sub_id=None) -> str:
        sub_id = sub_id or secrets.token_hex(4)
        self.subs[sub_id] = (filters, on_event)
        if self.ws:
            asyncio.ensure_future(self._send(["REQ", sub_id, filters]))
        return sub_id

    async def _send(self, msg):
        try:
            await self.ws.send(json.dumps(msg))
        except Exception:
            pass

    async def run(self):
        backoff = 2
        while True:
            try:
                async with websockets.connect(self.url, open_timeout=10, max_size=2**22, ping_interval=30) as ws:
                    self.ws, backoff = ws, 2
                    self.connected.set()
                    for sid, (flt, _) in self.subs.items():
                        await ws.send(json.dumps(["REQ", sid, flt]))
                    await asyncio.gather(self._reader(ws), self._writer(ws))
            except Exception as e:
                self.last_error = f"{type(e).__name__}: {str(e)[:80]}"
            self.ws = None
            self.connected.clear()
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 60)

    async def _writer(self, ws):
        while True:
            prio, _, ev = await self.queue.get()
            if time.time() < self.cooldown_until:      # rate-limited/banned: drop instead of making it worse
                self.dropped += 1
                continue
            await ws.send(json.dumps(["EVENT", ev]))
            if prio:                                    # pace chunks; a live event never waits behind one
                await asyncio.sleep(self.min_gap)

    async def _reader(self, ws):
        async for raw in ws:
            msg = json.loads(raw)
            if msg[0] == "EVENT" and msg[1] in self.subs:
                self.subs[msg[1]][1](msg[2], self.url)
            elif msg[0] == "OK":
                if msg[2]:
                    self.ok += 1
                else:
                    self.fail += 1
                    self.last_error = msg[3][:80]
                    reason = msg[3].lower()
                    if any(w in reason for w in ("web of trust", "policy", "restricted", "not allowed", "whitelist")):
                        self.cooldown_until = time.time() + 6 * 3600     # relay doesn't want this key at all
                        log(f"{self.url} rejected: {msg[3][:80]} → policy, pausing 6 h")
                    elif any(w in reason for w in ("rate-limit", "rate limit", "banned", "blocked", "slow down")):
                        self.cooldown_until = time.time() + 600
                        log(f"{self.url} rejected: {msg[3][:80]} → pausing 10 min")
                    else:
                        log(f"{self.url} rejected: {msg[3][:80]}")
            elif msg[0] == "EOSE" and msg[1] in self.subs:
                cb = self.subs[msg[1]][1]
                if hasattr(cb, "eose"):
                    cb.eose(self.url)
            elif msg[0] == "CLOSED":
                log(f"{self.url} closed sub: {msg[2][:80] if len(msg) > 2 else ''}")


class Pool:
    def __init__(self, urls):
        self.relays = [Relay(u) for u in urls]

    def start(self):
        return [asyncio.ensure_future(r.run()) for r in self.relays]

    def publish(self, ev):
        for r in self.relays:
            r.publish(ev)

    def subscribe(self, filters, on_event):
        sid = secrets.token_hex(4)
        for r in self.relays:
            r.subscribe(filters, on_event, sid)
        return sid

    def stats(self):
        return {r.url: {"ok": r.ok, "fail": r.fail, "dropped": r.dropped, "up": r.ws is not None,
                        "paused": r.cooldown_until > time.time(), "err": r.last_error} for r in self.relays}
