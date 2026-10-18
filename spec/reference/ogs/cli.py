# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""ogs — OGS reference tool.

  ogs vectors generate [--out FILE]     write the draft 1 test vectors (deterministic)
  ogs vectors verify [FILE]             check a vectors file: signatures, NIP-44, derivations, BLE, the scenario
  ogs follow LINK [--minutes N]         join a stream as a third-party follower over public relays (throwaway key)
  ogs decode FIXTURE                    run a fixture file (events + the follower's keys) through the follower
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

from . import model as m
from . import vectors
from .follower import Follower
from .nostr import Keys

DEFAULT_VECTORS = Path(__file__).resolve().parents[2] / "test-vectors" / "ogs-draft1.json"
LOOKUP_RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.snort.social"]


def cmd_vectors(a) -> int:
    if a.action == "generate":
        out = Path(a.file or DEFAULT_VECTORS)
        out.write_text(vectors.dumps(vectors.generate()))
        print(f"wrote {out}")
        a.file = str(out)
    vec = json.loads(Path(a.file or DEFAULT_VECTORS).read_text())
    fails = vectors.verify_file(vec)
    for f in fails:
        print("FAIL", f)
    n = len(vec["scenario"]["steps"])
    print(f"{'FAIL' if fails else 'PASS'}: {len(vec['events'])} events, {len(vec['plaintexts'])} plaintexts, "
          f"{len(vec['invite_checks'])} invite checks, BLE at {len(vec['ble']['frames_by_att_mtu'])} MTUs, "
          f"scenario {n} steps")
    return 1 if fails else 0


def cmd_decode(a) -> int:
    """Fixture = {"follower_sec", "i", "token", "now"?, "events": [...], "expect"?: [...]} (interop/)."""
    fx = json.loads(Path(a.file).read_text())
    f = Follower(Keys.from_hex(fx["follower_sec"]), fx["i"], fx.get("token"))
    f.status = "requested"
    now = fx.get("now")
    fails = 0
    if fx.get("invite_link"):
        try:
            got = Follower(f.F).open_invite(m.parse_invite(fx["invite_link"]), fx["invite_wrap"], now)
            print(f"     invite opened: identity {got['i'][:12]}… profile {got['profile']} relays {got['relays']}")
            if got["i"] != fx["i"]:
                fails += 1
                print("FAIL invite identity differs")
        except (m.Invalid, ValueError) as e:
            fails += 1
            print(f"FAIL invite: {e}")
    for i, ev in enumerate(fx["events"]):
        r = f.on_event(ev, now)
        want = (fx.get("expect") or [None] * len(fx["events"]))[i]
        bad = want is not None and f"{r.verdict}:{r.what}" != want
        fails += bad
        print(f"{'FAIL' if bad else '    '} kind {ev['kind']:>5} by {ev['pubkey'][:8]}  {r}"
              + (f"   (expected {want})" if bad else ""))
    print(f"state: {f.status}, epochs {sorted(n for n, _ in f.epochs.values())}, {len(f.readings)} readings, "
          f"{len(f.treatments)} treatments, {len(f.alerts)} alerts, sensor {f.sensor}, writers {len(f.writers)}")
    if f.readings:
        last = f.readings[max(f.readings)]
        print(f"newest reading: t={last.t} sgv={last.sgv} r={last.rate} → {m.direction(last.rate)}")
    return 1 if fails else 0


async def follow(link: str, minutes: float, state_path: Path) -> int:
    from .relay import Pool
    inv = m.parse_invite(link, any_host=True)
    st = json.loads(state_path.read_text()) if state_path.exists() else {}
    F = Keys.from_hex(st["f"]) if st.get("f") else Keys.generate()
    state_path.parent.mkdir(parents=True, exist_ok=True)
    state_path.write_text(json.dumps({"f": F.sec.hex(), "link": link}))
    state_path.chmod(0o600)
    fol = Follower(F)
    print(f"follower key {F.pub[:16]}… (throwaway, in {state_path})")

    found: list[dict] = []
    look = Pool(LOOKUP_RELAYS, lambda ev, url: found.append(ev), auth_key=inv.key)
    await look.start()
    await look.req("inv", {"kinds": [m.KIND_WRAP], "#p": [inv.key.pub]})
    info = None
    for _ in range(300):
        while found and info is None:
            try:
                info = fol.open_invite(inv, found.pop())
            except (m.Invalid, ValueError) as e:
                print(f"  invite wrap rejected: {e}")
        if info:
            break
        await asyncio.sleep(0.1)
    await look.close()
    if not info:
        print("no valid invite found on the lookup relays")
        return 2
    name = (info.get("profile") or {}).get("name") or "(no name)"
    print(f"invite from {name}, identity {info['i'][:16]}…, relays {info['relays']}, expires "
          f"{time.strftime('%H:%M', time.localtime(info['x']))}")

    seen_out: list = []

    def on_event(ev, url):
        r = fol.on_event(ev)
        if r.verdict != "ignored" or r.what not in ("duplicate",):
            print(f"  {time.strftime('%H:%M:%S')} {url.split('//')[1][:18]:<18} kind {ev['kind']:>5}  {r}")
        seen_out.extend(r.out)

    pool = Pool(fol.relays or LOOKUP_RELAYS, on_event, auth_key=F)
    await pool.start()
    await pool.req("inbox", fol.filters()["inbox"])
    req = fol.request({"name": "OGS reference", "avatar": None})
    oks = await pool.publish(req)
    print(f"access request sent ({sum(ok for _, ok, _ in oks)}/{len(oks)} relays OK). "
          f"Match code {fol.match_code} — the owner allows it.")
    subscribed: dict = {}
    end = time.time() + minutes * 60
    while time.time() < end and fol.status not in ("revoked", "declined", "used"):
        for name_, flt in fol.filters().items():
            key = json.dumps({k: v for k, v in flt.items() if k != "since"}, sort_keys=True)
            if subscribed.get(name_) != key:
                subscribed[name_] = key
                await pool.req(name_, flt)
        while seen_out:
            await pool.publish(seen_out.pop())
            print("  rekey request sent")
        await asyncio.sleep(1)
    await pool.close()
    print(f"done: status {fol.status}, epochs {sorted(n for n, _ in fol.epochs.values())}, "
          f"{len(fol.readings)} readings, {len(fol.treatments)} treatments, {len(fol.alerts)} alerts, "
          f"sensor {fol.sensor}")
    for t in sorted(fol.readings)[-5:]:
        r = fol.readings[t]
        print(f"  {time.strftime('%H:%M', time.localtime(t))} {r.sgv:>3} mg/dL {m.direction(r.rate)}")
    return 0 if fol.status == "active" and fol.readings else 1


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="ogs", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("vectors")
    v.add_argument("action", choices=["generate", "verify"])
    v.add_argument("file", nargs="?")
    v.add_argument("--out", dest="file")
    d = sub.add_parser("decode")
    d.add_argument("file")
    f = sub.add_parser("follow")
    f.add_argument("link")
    f.add_argument("--minutes", type=float, default=5)
    f.add_argument("--state", default=str(Path.home() / ".cache" / "ogs-reference" / "follower.json"))
    a = p.parse_args(argv)
    if a.cmd == "vectors":
        return cmd_vectors(a)
    if a.cmd == "decode":
        return cmd_decode(a)
    return asyncio.run(follow(a.link, a.minutes, Path(a.state)))


if __name__ == "__main__":
    sys.exit(main())
