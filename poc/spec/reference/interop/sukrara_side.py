# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""The *Sukrara side* of the interop proof (investigation 24). Runs in the spikes/nostr environment and uses the
repo's own v2 code (spikes/nostr/v2/cgm2.py, spikes/nostr/identity/ident.py) and rust-nostr (nostr-sdk 0.45.1, the
library Sukrara for Android and Sukrara for Mac use for NIP-44 and NIP-59). The OGS reference never imports this.

  uv run --project spikes/nostr python spec/reference/interop/sukrara_side.py fixture OUT.json
  uv run --project spikes/nostr python spec/reference/interop/sukrara_side.py oracle spec/test-vectors/ogs-draft1.json
  uv run --project spikes/nostr python spec/reference/interop/sukrara_side.py serve --minutes 4 --link-file L

Keys are generated fresh (throwaway), glucose is a synthetic curve, every relay event expires (≤ 30 days, live 1 h).
"""
from __future__ import annotations

import argparse
import asyncio
import json
import math
import secrets
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path[:0] = [str(ROOT / "spikes/nostr"), str(ROOT / "spikes/nostr/v2"), str(ROOT / "spikes/nostr/identity")]
import nostr_sdk as n  # noqa: E402
import cgm2  # noqa: E402
import ident  # noqa: E402
from cgmnostr import Pool, Reading  # noqa: E402

PROFILE = {"name": "Interop wearer", "avatar": {"v": 1, "c": 3, "s": "drop", "a": 90, "n": 7}}
RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"]


def synthetic(t: int) -> Reading:
    return Reading(t, int(130 + 60 * math.sin(t / 5400)), round(60 * math.cos(t / 5400) / 90, 2), "synthetic")


def T(*a) -> n.Tag:
    return n.Tag.parse(list(a))


class Owner:
    """A wearer built from the repo's v2 functions, plus the Sukrara 0.11 additions (alerts, treatments, sensor life,
    v3 invites, profiles) written with the same primitives (cgm2.signed / cgm2.enc / nostr-sdk NIP-59)."""

    def __init__(self):
        self.I, self.D, self.G = cgm2.keys(), cgm2.keys(), cgm2.keys()
        self.sid = secrets.token_hex(32)
        self.secret = secrets.token_bytes(16)
        self.link = ident.short_link(cgm2.pub(self.I), self.secret)
        self.token = ident.b64u(self.secret)
        self.expiry = int(time.time()) + 3600
        now = int(time.time())
        self.sensor = {"ss": now - 4 * 86400, "se": now - 4 * 86400 + 15 * 86400, "sw": now - 4 * 86400 + 3600}

    @property
    def g(self) -> str:
        return cgm2.pub(self.G)

    def invite(self) -> dict:
        return ident.invite_wrap(self.I, self.secret, self.expiry, RELAYS, PROFILE)

    def key(self, f_hex: str) -> dict:
        body = {"v": 2, "type": "key", "sid": self.sid, "epoch": 1, "g": self.G.secret_key().to_bech32(),
                "devices": [cgm2.pub(self.D)], "relays": RELAYS}
        return cgm2.gift(self.I, f_hex, cgm2.RUMOR_TO_FOLLOWER, body)

    def roster(self) -> dict:
        body = {"v": 2, "epoch": 1, "devices": [cgm2.pub(self.D)], "writers": [], "relays": RELAYS,
                "profile": PROFILE}
        return cgm2.signed(self.I, cgm2.CHUNK, cgm2.enc(self.I, self.g, body),
                           [n.Tag.identifier(cgm2.roster_d(self.sid)), T("gk", cgm2.gk(self.g)),
                            cgm2.exp(time.time() + 30 * 86400)], "roster" + self.sid)

    def chunks(self, hours: int = 2) -> list[dict]:
        now = int(time.time())
        out = []
        for h in range(cgm2.hour_of(now) - 3600 * (hours - 1), now, 3600):
            rs = [synthetic(t) for t in range(h, min(h + 3600, now), 60)]
            out.append(cgm2.chunk_event(self.D, self.g, self.sid, h, rs))
        return out

    def live(self, t: int) -> dict:
        r = synthetic(t)
        body = {"v": 2, "t": r.t, "sgv": r.sgv, "r": r.rate, "dev": r.dev, **self.sensor}
        return cgm2.signed(self.D, cgm2.LIVE, cgm2.enc(self.D, self.g, body),
                           [T("gk", cgm2.gk(self.g)), cgm2.exp(time.time() + 3600)])

    def plain_live(self, t: int) -> dict:
        return cgm2.live_event(self.D, self.g, synthetic(t))      # the repo's v2 live event, unchanged

    def alert(self, t: int) -> dict:
        r = synthetic(t)
        body = {"v": 2, "type": "alert", "id": secrets.token_hex(8), "k": "high", "state": "fire", "at": t,
                "t": r.t, "sgv": r.sgv, "r": r.rate, "th": 180, "n": 1, "tr": 0}
        return cgm2.signed(self.D, 21079, cgm2.enc(self.D, self.g, body),
                           [T("gk", cgm2.gk(self.g)), cgm2.exp(time.time() + 3600)])

    def treatment(self) -> dict:
        tid = "4d2c4e0a-8f3b-4b8e-9c1d-2a3b4c5d6e7f"
        now = int(time.time())
        body = {"v": 1, "id": tid, "k": "carbs", "a": 25, "t": now - 900, "n": "", "u": now * 1000, "del": False,
                "o": "phone"}
        return cgm2.signed(self.D, cgm2.CHUNK, cgm2.enc(self.D, self.g, body),
                           [n.Tag.identifier(cgm2.H(f"cgm2:tr:{self.sid}:{tid}")),
                            T("c", cgm2.H(f"cgm2:treatments:{self.sid}")), T("gk", cgm2.gk(self.g)),
                            cgm2.exp(time.time() + 30 * 86400)], cgm2.pub(self.D) + tid)


def fixture(out: Path):
    o = Owner()
    F = cgm2.keys()
    now = int(time.time())
    evs = [o.key(cgm2.pub(F)), o.roster(), *o.chunks(), o.plain_live(now - 120), o.live(now - 60), o.alert(now - 30),
           o.treatment()]
    expect = ["accepted:key", "accepted:roster"] + ["accepted:chunk"] * (len(evs) - 6) + \
             ["accepted:live", "accepted:live", "accepted:alert", "accepted:treatment"]
    fx = {"about": "Throwaway fixture produced by spikes/nostr v2 code + nostr-sdk (investigation 24 interop)",
          "follower_sec": F.secret_key().to_hex(), "i": cgm2.pub(o.I), "token": o.token, "now": now,
          "invite_link": o.link, "invite_wrap": o.invite(), "events": evs, "expect": expect}
    out.write_text(json.dumps(fx, indent=1))
    print(f"wrote {out}: {len(evs)} events + a v3 invite (I {cgm2.pub(o.I)[:12]}…, synthetic data)")


def oracle(path: Path) -> int:
    """Check the reference's vectors with nostr-sdk and the repo's v2/identity code."""
    vec = json.loads(path.read_text())
    keys = {v["pub"]: n.Keys.parse(v["sec"]) for v in vec["keys"].values()}
    inv = vec["derivations"]["invite"]
    keys[inv["invite_key_pub"]] = n.Keys.parse(inv["invite_key_sec"])
    fails, checks = [], 0

    def check(name, cond):
        nonlocal checks
        checks += 1
        if not cond:
            fails.append(name)

    for name, e in vec["events"].items():
        check(f"nostr-sdk verify {name}", cgm2.verify(e) == (name != "live-tampered"))
    for name, p in vec["plaintexts"].items():
        e = vec["events"][name]
        if "rumor" in p:
            got = cgm2.unwrap(keys[p["recipient"]], e)          # nostr-sdk UnwrappedGift, as the Mac app does
            check(f"nostr-sdk unwrap {name}", got is not None and got[0] == p["seal_signer"]
                  and got[1] == p["rumor"]["kind"] and got[2] == json.loads(p["rumor"]["content"]))
        else:
            check(f"nostr-sdk nip44 {name}", n.nip44_decrypt(keys[p["read_key"]].secret_key(),
                                                              n.PublicKey.parse(p["author"]), e["content"])
                  == p["content"])
    d = vec["derivations"]
    sid = vec["sid"]
    check("gk", cgm2.gk(vec["keys"]["G1"]["pub"]) == d["gk(G1)"])
    check("chunk d", cgm2.chunk_tags(sid, d["d_chunk(sid, hour)"]["hour"]) ==
          (d["d_chunk(sid, hour)"]["d"], d["H('cgm2:glucose:'+sid)"]))
    check("roster d", cgm2.roster_d(sid) == d["d_roster(sid)"])
    check("backup d", cgm2.backup_d(vec["keys"]["I"]["pub"]) == d["d_backup(I)"])
    pl = ident.parse_link(inv["link"])
    check("invite link", pl["fp"] == inv["fingerprint"] and pl["secret"].hex() == inv["secret"])
    check("invite key", cgm2.pub(ident.invite_key(bytes.fromhex(inv["secret"]))) == inv["invite_key_pub"])
    check("match code", cgm2.match_code(vec["keys"]["F"]["pub"], inv["token"]) == inv["match_code(F)"])
    check("nsec(G1)", n.Keys.parse(d["nsec(G1)"]).secret_key().to_hex() == vec["keys"]["G1"]["sec"])
    for f in fails:
        print("FAIL", f)
    print(f"{'FAIL' if fails else 'PASS'}: {checks - len(fails)}/{checks} checks of the reference vectors against "
          f"nostr-sdk {n.__version__ if hasattr(n, '__version__') else '0.45'} + spikes/nostr v2/identity code")
    return 1 if fails else 0


async def serve(minutes: float, link_file: Path):
    o = Owner()
    pool = Pool(RELAYS + ["wss://relay.snort.social"], auth_keys=o.I)
    pool.start()
    seen: set[str] = set()
    approved: list[str] = []

    def on_wrap(ev, relay):
        if ev["id"] in seen:
            return
        seen.add(ev["id"])
        got = cgm2.unwrap(o.I, ev)
        if not got:
            return
        sender, kind, body = got
        if kind != cgm2.RUMOR_TO_OWNER or body.get("v") != 2:
            return
        print(f"  owner got {body.get('type')} from {sender[:12]}… via {relay}")
        if body.get("type") == "request" and body.get("token") == o.token and time.time() < o.expiry:
            if sender not in approved:
                approved.append(sender)
                print(f"  owner: '{(body.get('profile') or {}).get('name')}' asks, match code "
                      f"{cgm2.match_code(sender, o.token)} → Allow (auto)")
                pool.publish(o.key(sender))
                for ev2 in [o.roster(), *o.chunks(), o.treatment()]:
                    pool.publish(ev2)
        elif body.get("type") == "rekey" and sender in approved:
            pool.publish(o.key(sender))

    pool.subscribe({"kinds": [cgm2.WRAP], "#p": [cgm2.pub(o.I)], "since": int(time.time()) - 3 * 86400}, on_wrap)
    pool.publish(o.invite())
    link_file.write_text(o.link)
    print(f"owner (spikes/nostr v2 code) up: I {cgm2.pub(o.I)[:12]}…, invite written to {link_file}")
    end = time.time() + minutes * 60
    nxt = 0.0
    alerted = False
    while time.time() < end:
        if approved and time.time() >= nxt:
            now = int(time.time())
            pool.publish(o.live(now))
            if not alerted:
                pool.publish(o.alert(now))
                alerted = True
            nxt = time.time() + 30
        await asyncio.sleep(1)
    print(f"owner done: {len(approved)} follower(s) approved; relay stats {json.dumps(pool.stats())[:300]}")


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("fixture").add_argument("out")
    sub.add_parser("oracle").add_argument("vectors")
    s = sub.add_parser("serve")
    s.add_argument("--minutes", type=float, default=4)
    s.add_argument("--link-file", required=True)
    a = p.parse_args()
    if a.cmd == "fixture":
        fixture(Path(a.out))
    elif a.cmd == "oracle":
        sys.exit(oracle(Path(a.vectors)))
    else:
        asyncio.run(serve(a.minutes, Path(a.link_file)))


if __name__ == "__main__":
    main()
