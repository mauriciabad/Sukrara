# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Investigation 07 — Mac-side BLE follower test client for CGM Share (real radio: phone ↔ Mac).

Speaks the GATT framing of docs/07-bluetooth-followers.md and verifies/decrypts every event with the v2 reference
(../v2/cgm2.py), applying the follower acceptance rules (seal signer = I, roster devices, gk → G_n, roster by I).

  uv run python ble/ble_follower.py --pair --name "Mac BLE"      # nearby pairing: request over BLE, wait for key
  uv run python ble/ble_follower.py --seconds 300                 # follow over BLE only
  uv run python ble/ble_follower.py --treat rapid:1.5:"test"      # send a treatment (signed by F) over BLE
  uv run python ble/ble_follower.py --treat carbs:20:x --forge     # same, signed by a random NON-writer key

State (contains the follower secret, never printed): ../secrets/ble-follower.json
"""
from __future__ import annotations

import argparse, asyncio, json, struct, sys, time, uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "v2"))
import cgm2  # noqa: E402
from bleak import BleakClient, BleakScanner  # noqa: E402

SERVICE = "7c6a0001-5c3e-4f0a-9a57-c95e0b1e0007"
RX = "7c6a0002-5c3e-4f0a-9a57-c95e0b1e0007"
TX = "7c6a0003-5c3e-4f0a-9a57-c95e0b1e0007"
STATE = Path(__file__).resolve().parent.parent / "secrets" / "ble-follower.json"


def ts() -> str:
    return time.strftime("%H:%M:%S") + f".{int(time.time() * 1000) % 1000:03d}"


def log(*a):
    print(ts(), *a, flush=True)


def frames(msg_id: int, payload: bytes, per: int):
    count = max(1, -(-len(payload) // per))
    for i in range(count):
        yield struct.pack(">HHH", msg_id, i, count) + payload[i * per:(i + 1) * per]


class Follower:
    def __init__(self, args):
        self.args = args
        self.st = json.loads(STATE.read_text()) if STATE.exists() else {}
        self.F = cgm2.keys(self.st["F"]) if "F" in self.st else None
        self.parts: dict[int, list] = {}
        self.msg_id = 0
        self.info: asyncio.Future | None = None
        self.stats = {"bytes": 0, "events": 0, "accepted": 0, "rejected": 0, "first": None, "last": None}
        self.client: BleakClient | None = None

    def save(self):
        STATE.parent.mkdir(exist_ok=True)
        STATE.write_text(json.dumps(self.st))

    # ---------------------------------------------------------------- receive
    def on_notify(self, _c, data: bytearray):
        now = time.time()
        self.stats["bytes"] += len(data)
        self.stats["first"] = self.stats["first"] or now
        self.stats["last"] = now
        mid, idx, count = struct.unpack(">HHH", data[:6])
        if idx == 0 or mid not in self.parts or len(self.parts[mid]) != count:
            self.parts[mid] = [None] * count
        self.parts[mid][idx] = bytes(data[6:])
        if any(p is None for p in self.parts[mid]):
            return
        msg = json.loads(b"".join(self.parts.pop(mid)).decode())
        if msg[0] == "INFO":
            if self.info and not self.info.done():
                self.info.set_result(msg[1])
        elif msg[0] == "EVENT":
            self.stats["events"] += 1
            try:
                self.on_event(msg[1])
            except Exception as e:  # noqa: BLE001
                log("event error", e)

    def reject(self, why, ev):
        self.stats["rejected"] += 1
        log(f"REJECT kind {ev.get('kind')} from {ev.get('pubkey', '')[:12]}: {why}")

    def on_event(self, ev: dict):
        st, kind = self.st, ev.get("kind")
        if kind == cgm2.WRAP:
            if not self.F:
                return
            u = cgm2.unwrap(self.F, ev)
            if not u:
                return  # not for us (e.g. another follower's delivery)
            sender, rkind, body = u
            if rkind != cgm2.RUMOR_TO_FOLLOWER or sender != st.get("i"):
                return self.reject("delivery not sealed by I", ev)
            if body.get("type") == "key":
                g = cgm2.keys(body["g"])
                if cgm2.gk(cgm2.pub(g)) in st.get("epochs", {}):
                    return  # already known (wraps are re-sent in every backlog)
                st.setdefault("epochs", {})[cgm2.gk(cgm2.pub(g))] = {"n": body["epoch"], "g": g.secret_key().to_hex()}
                st["sid"], st["devices"] = body["sid"], body["devices"]
                self.save()
                self.stats["accepted"] += 1
                log(f"KEY accepted over BLE: epoch {body['epoch']} gk {cgm2.gk(cgm2.pub(g))} devices {[d[:12] for d in body['devices']]}")
                if self.client:  # new key: ask for the backlog again, now readable
                    asyncio.ensure_future(self.send(["HELLO", {"v": 2}]))
            elif body.get("type") == "revoked":
                log("REVOKED notice over BLE")
            return
        if kind not in (cgm2.LIVE, cgm2.CHUNK) or "sid" not in st:
            return
        sid = st["sid"]
        if cgm2.tag(ev, "c") == cgm2.H(f"cgm2:treatments:{sid}"):
            log(f"treatment event forwarded by owner (author {ev['pubkey'][:12]})")
            return
        ep = st.get("epochs", {}).get(cgm2.tag(ev, "gk"))
        if not ep:
            return self.reject("unknown gk", ev)
        if not cgm2.verify(ev):
            return self.reject("bad signature", ev)
        G = cgm2.keys(ep["g"])
        if kind == cgm2.CHUNK and cgm2.tag(ev, "d") == cgm2.roster_d(sid):
            if ev["pubkey"] != st["i"]:
                return self.reject("roster not by I", ev)
            body = cgm2.dec(G, st["i"], ev["content"])
            st["devices"], st["writers"] = body["devices"], body.get("writers", [])
            self.save()
            self.stats["accepted"] += 1
            me = cgm2.pub(self.F) if self.F else ""
            log(f"roster accepted over BLE: epoch {body['epoch']} devices {[d[:12] for d in body['devices']]} "
                f"writers {[w[:12] for w in st['writers']]} → this Mac {'MAY' if me in st['writers'] else 'may not'} add entries")
            return
        if ev["pubkey"] not in st.get("devices", []):
            return self.reject("author not in roster devices", ev)
        body = cgm2.dec(G, ev["pubkey"], ev["content"])
        self.stats["accepted"] += 1
        now = time.time()
        if kind == cgm2.LIVE:
            log(f"LIVE {body['sgv']} mg/dL t={body['t']} epoch {ep['n']} — reading age {now - body['t']:.0f}s, "
                f"publish→BLE receipt {now - ev['created_at']:.1f}s")
        else:
            rows = body["r"]
            log(f"chunk h={body['h']} {len(rows)} readings (last {rows[-1][1] if rows else '-'} mg/dL) epoch {ep['n']} "
                f"{len(json.dumps(ev))} B")

    # ---------------------------------------------------------------- send
    async def send(self, msg):
        payload = json.dumps(msg, separators=(",", ":")).encode()
        per = min(self.client.mtu_size - 3, 512) - 6
        self.msg_id = (self.msg_id + 1) & 0xFFFF
        for f in frames(self.msg_id, payload, per):
            await self.client.write_gatt_char(RX, f, response=True)

    def treatment(self, spec: str, forge: bool) -> dict:
        k, a, note = (spec.split(":", 2) + ["", ""])[:3]
        st = self.st
        ep = max(st["epochs"].values(), key=lambda e: e["n"])
        G = cgm2.keys(ep["g"])
        W = cgm2.keys() if forge else self.F
        tr = {"v": 1, "id": str(uuid.uuid4()), "k": k, "a": float(a or 0), "t": int(time.time()), "n": note,
              "u": int(time.time() * 1000), "del": False, "o": "mac"}
        sid = st["sid"]
        tags = [cgm2.n.Tag.identifier(cgm2.H(f"cgm2:tr:{sid}:{tr['id']}")), cgm2.n.Tag.parse(["c", cgm2.H(f"cgm2:treatments:{sid}")]),
                cgm2.n.Tag.parse(["gk", cgm2.gk(cgm2.pub(G))]), cgm2.exp(time.time() + 30 * cgm2.DAY)]
        log(f"treatment {k} {a} '{note}' signed by {'RANDOM non-writer key' if forge else 'F'} {cgm2.pub(W)[:12]}")
        return cgm2.signed(W, cgm2.CHUNK, cgm2.enc(W, cgm2.pub(G), tr), tags)

    # ---------------------------------------------------------------- main
    async def run(self):
        a = self.args
        self.info = asyncio.get_running_loop().create_future()
        log("scanning for", SERVICE)
        t0 = time.time()
        dev = await BleakScanner.find_device_by_filter(
            lambda d, ad: SERVICE.lower() in [s.lower() for s in ad.service_uuids], timeout=20)
        if not dev:
            return log("no CGM Share owner found")
        log(f"found {dev.name} ({dev.address}) in {time.time() - t0:.1f}s")
        t1 = time.time()
        async with BleakClient(dev) as c:
            self.client = c
            log(f"connected in {time.time() - t1:.1f}s, MTU {c.mtu_size}")
            await c.start_notify(TX, self.on_notify)
            t_hello = time.time()
            await self.send(["HELLO", {"v": 2}])
            info = await asyncio.wait_for(self.info, 15)
            log(f"INFO: owner {info['i'][:12]}… pairing {'OPEN' if info.get('pair') else 'closed'} "
                f"({(time.time() - t_hello) * 1000:.0f} ms after HELLO)")
            if a.pair:
                if not info.get("pair"):
                    return log("owner has not opened 'Pair nearby'")
                self.F = cgm2.keys()
                tok = info["pair"]["t"]
                self.st = {"F": self.F.secret_key().to_hex(), "i": info["i"], "token": tok, "name": a.name}
                self.save()
                code = cgm2.match_code(cgm2.pub(self.F), tok)
                await self.send(["EVENT", cgm2.gift(self.F, info["i"], cgm2.RUMOR_TO_OWNER,
                                                   {"v": 2, "type": "request", "token": tok, "name": a.name})])
                log(f"access request sent over BLE as '{a.name}' F={cgm2.pub(self.F)[:12]}… MATCH CODE {code}")
            # backlog throughput: wait until the stream goes quiet for 3 s
            await asyncio.sleep(1)
            while time.time() - (self.stats["last"] or time.time()) < 3:
                await asyncio.sleep(0.5)
            s = self.stats
            if s["first"]:
                dt = s["last"] - t_hello
                log(f"backlog: {s['events']} events, {s['bytes']} B in {dt:.2f}s → {s['bytes'] / max(dt, 0.001) / 1024:.1f} KiB/s "
                    f"({s['accepted']} accepted, {s['rejected']} rejected)")
            if a.treat:
                await self.send(["EVENT", self.treatment(a.treat, a.forge)])
                log("treatment sent over BLE")
            end = time.time() + a.seconds
            while time.time() < end and c.is_connected:
                await asyncio.sleep(1)
            log(f"done: {s['events']} events, {s['bytes']} B, {s['accepted']} accepted, {s['rejected']} rejected, "
                f"connected={c.is_connected}")


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--pair", action="store_true")
    p.add_argument("--name", default="Mac BLE")
    p.add_argument("--seconds", type=int, default=20)
    p.add_argument("--treat")
    p.add_argument("--forge", action="store_true")
    args = p.parse_args()
    for attempt in range(3):  # CoreBluetooth sometimes hands out a stale identifier (rotating private address)
        try:
            asyncio.run(Follower(args).run())
            break
        except TimeoutError:
            log(f"connect timed out (attempt {attempt + 1}), rescanning")
