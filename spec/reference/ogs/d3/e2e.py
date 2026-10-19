# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Draft 3 end to end on public relays, in one process: an owner (synthetic glucose), a reader F1 and a writer F2
join with invite links, backfill, follow live, F2 adds carbs, the owner removes F1 (rotation) and keeps streaming
to F2. Throwaway keys, synthetic data, everything expires within the hour."""
from __future__ import annotations

import asyncio
import json
import math
import time
import uuid

from ..nostr import Keys
from ..relay import Pool
from . import control as ctl
from .core import day_of
from .member import Member
from .owner import Owner

LOOKUP = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom"]
TTL = 3600
SESS = "e2e-" + uuid.uuid4().hex[:12]


def synthetic(t: int) -> tuple[int, int]:
    return round(120 + 40 * math.sin(t / 1800)), round(40 * math.cos(t / 1800) / 30 * 100)


def block(start: int, until: int) -> dict:
    rows = [[t - start, *synthetic(t)] for t in range(start, min(until + 1, start + 900), 60)]
    return {"type": "cgm", "sess": SESS, "start": start, "len": 900, "tz": 0, "rows": rows}


def grown(start: int, until: int, v: int) -> tuple[dict, int]:
    """The current block plus one new reading half a minute after its last row (a live update)."""
    b = block(start, until)
    t_new = start + b["rows"][-1][0] + 30
    b["rows"].append([t_new - start, v, 50])
    return b, t_new


class Actor:
    def __init__(self, name: str, log):
        self.name, self.log, self.pool, self.subs = name, log, None, {}

    async def sync(self, filters: dict):
        for k, f in filters.items():
            key = json.dumps({x: y for x, y in f.items() if x != "since"}, sort_keys=True)
            if self.subs.get(k) != key:
                self.subs[k] = key
                await self.pool.req(f"{self.name}-{k}", f)


async def run(relays: list[str]) -> int:
    t0 = time.time()
    lines: list[str] = []

    def log(s: str):
        line = f"{time.time() - t0:6.1f}s  {s}"
        lines.append(line)
        print(line, flush=True)

    now = int(time.time())
    I, D = Keys.generate(), Keys.generate()
    owner = Owner(I, [D], relays=relays, profile={"name": "OGS3 e2e (synthetic)"}, now=now)
    owner.ttl = TTL
    log(f"owner: identity {I.pub[:12]}… device {D.pub[:12]}… epoch {owner.current} — relays {relays}")
    oa = Actor("owner", log)
    o_in: list[dict] = []
    oa.pool = Pool(relays, lambda ev, url: o_in.append(ev), log=log)
    await oa.pool.start()
    await oa.pool.ready()

    async def publish(pool, ev, what):
        oks = await pool.publish(ev, wait=6)
        ok = sum(1 for _, good, _ in oks if good)
        log(f"  publish {what:<28} kind {ev['kind']:>5} by {ev['pubkey'][:10]}…  {ok}/{len(oks)} relays OK"
            + ("".join(f"  [{u.split('//')[1][:16]}: {m[:60]}]" for u, good, m in oks if not good)))
        await asyncio.sleep(0.6)
        return ok

    async def owner_subscribe():
        await oa.sync({"inbox": {"kinds": [1059], "#p": [k.pub for k in owner.inbox_keys()],
                                 "since": int(time.time()) - 3 * 86400},
                       **{k: v for k, v in owner.reader.filters().items() if k == "stream"}})

    # backfill: the last hour of synthetic readings, the sensor, the device status
    start = now - now % 900
    published: set[int] = set()
    await publish(oa.pool, owner.publish_membership(now), "membership")
    for b in range(start - 2700, start + 1, 900):
        blk = block(b, now)
        published |= {b + r[0] for r in blk["rows"]}
        await publish(oa.pool, owner.envelope(D, blk, at=now), f"cgm {time.strftime('%H:%M', time.gmtime(b))}")
    today0 = day_of(now) * 86400
    want = {"F1": {t for t in published if t >= today0 - 86400}, "F2": {t for t in published if t >= today0}}
    log(f"published {len(published)} readings; F1 may read {len(want['F1'])} (from yesterday), "
        f"F2 {len(want['F2'])} (from today: the offer says back 0)")
    await publish(oa.pool, owner.envelope(D, {"type": "sensor", "sess": SESS, "state": "active",
                                              "start": now - 86400 * 2, "end": now + 86400 * 12, "model": "synthetic",
                                              "rev": now * 1000}, at=now), "sensor")

    members: dict[str, tuple[Member, Actor, list]] = {}

    async def join(name: str, offer: dict, profile: dict):
        text, wrap = owner.invite(int(time.time()), offer=offer, hints=relays[:2], hours=1)
        log(f"{name}: invite link {text}")
        await publish(oa.pool, wrap, f"invite for {name}")
        await owner_subscribe()
        m = Member(Keys.generate(), Keys.generate())
        m.ttl = TTL
        inv = ctl.parse_invite(text)
        found: list[dict] = []
        look = Pool(list(inv.hints) or LOOKUP, lambda ev, url: found.append(ev), auth_key=inv.key, log=log)
        await look.start()
        await look.req("inv", {"kinds": [1059], "#p": [inv.key.pub]})
        info = None
        for _ in range(200):
            while found and info is None:
                try:
                    info = m.open_invite(inv, found.pop())
                except ValueError as e:
                    log(f"{name}: invite wrap rejected: {e}")
            if info:
                break
            await asyncio.sleep(0.1)
        await look.close()
        if not info:
            raise SystemExit(f"{name}: invite not found on {inv.hints}")
        log(f"{name}: invite from {info['profile']['name']!r}, identity {info['i'][:12]}…, offer {info['offer']}")
        a = Actor(name, log)
        inbox: list[dict] = []
        a.pool = Pool(m.relays, lambda ev, url: inbox.append(ev), auth_key=m.inbox, log=log)
        await a.pool.start()
        await a.pool.ready()
        await a.sync(m.filters())
        await publish(a.pool, m.join(profile), f"join from {name}")
        log(f"{name}: shows match code {m.match_code}")
        members[name] = (m, a, inbox)
        return m

    async def pump(seconds: float, until=None):
        """Feed relay events to the owner and members; re-subscribe when filters change."""
        end = time.time() + seconds
        while time.time() < end:
            while o_in:
                ev = o_in.pop(0)
                now_ = int(time.time())
                if ev["kind"] == 1059:
                    verdict, out = owner.on_wrap(ev, now_)
                    if not verdict.startswith("ignored"):
                        log(f"owner: wrap → {verdict}")
                    for j, p in list(owner.pending.items()):
                        typed = next((mm.match_code for mm, _, _ in members.values() if mm.M.pub == j), None)
                        if typed == p["code"]:
                            log(f"owner: wearer types {typed} = the code of the request → Allow")
                            seq = owner.seq[owner.current]
                            out += owner.approve(j, now_)
                            if owner.seq[owner.current] != seq:
                                out.append(owner.publish_membership(now_))
                    for e in out:
                        await publish(oa.pool, e, "owner reply")
                elif ev["kind"] == 31078 and ev["pubkey"] in owner.reader._index:
                    r = owner.accept(ev, now_)
                    if r.verdict != "ignored":
                        log(f"owner: stream event → {r}")
            for name, (m, a, inbox) in members.items():
                while inbox:
                    r = m.on_event(inbox.pop(0), int(time.time()))
                    if r.what not in ("duplicate", "unknown-author", "not-for-us"):
                        log(f"{name}: {r}")
                    for e in r.out:
                        await publish(a.pool, e, f"{name} request")
                for e in m.tick():
                    await publish(a.pool, e, f"{name} rekey")
                if m.status != "ended":
                    await a.sync(m.filters())
            await owner_subscribe()
            if until and until():
                return True
            await asyncio.sleep(0.3)
        return bool(until and until())

    await owner_subscribe()
    f1 = await join("F1", {"scopes": [], "back": 1}, {"name": "Reader (e2e)"})
    got = lambda m: {t for (_, t) in m.readings}  # noqa: E731
    ok_f1 = await pump(60, lambda: f1.status == "active" and got(f1) == want["F1"])
    f2 = await join("F2", {"scopes": ["write"], "back": 0}, {"name": "Writer (e2e)"})
    ok_f2 = await pump(60, lambda: f2.status == "active" and got(f2) == want["F2"])
    log(f"backfill: F1 {len(f1.readings)} readings, F2 {len(f2.readings)} readings")

    # live: the current block grows by one reading
    blk, t_live = grown(start, now, 133)
    await publish(oa.pool, owner.envelope(D, blk, at=int(time.time())), "cgm live update")
    ok_live = await pump(30, lambda: all((SESS, t_live) in m.readings for m in (f1, f2)))

    # F2 writes carbs; the owner and F1 accept it (F2 is in the membership with scope write)
    carbs = {"type": "carbs", "id": str(uuid.uuid4()), "t": int(time.time()) - 60, "g": 25, "meal": "snack",
             "rev": int(time.time() * 1000)}
    await publish(members["F2"][1].pool, f2.write(carbs), "carbs by F2")
    ok_w = await pump(30, lambda: any(k[1] == carbs["id"] for k in owner.reader.entries)
                      and any(k[1] == carbs["id"] for k in f1.entries))

    # remove F1: rotation; F2 gets the new epoch, F1 an `ended`
    old = owner.current
    for e in owner.remove(f1.M.pub, int(time.time())):
        await publish(oa.pool, e, "removal / rotation")
    log(f"owner: epoch {old} → {owner.current}")
    ok_rot = await pump(60, lambda: f1.status == "ended" and owner.current in f2.views)
    t_new = t_live - 1                    # a reading F1 never saw, published only in the new epoch
    blk = {"type": "cgm", "sess": SESS, "start": start, "len": 900, "tz": 0, "rows": [[t_new - start, 140, 40]]}
    await publish(oa.pool, owner.envelope(D, blk, at=int(time.time())), "cgm in the new epoch")
    ok_new = await pump(30, lambda: (SESS, t_new) in f2.readings)
    f1_blind = (SESS, t_new) not in f1.readings
    for _, a, _ in members.values():
        await a.pool.close()
    await oa.pool.close()

    checks = {"F1 joined and backfilled exactly its window": ok_f1,
              "F2 joined and backfilled exactly its window (nothing before today)": ok_f2, "live update reached both": ok_live,
              "writer's carbs accepted by owner and F1": ok_w, "rotation: F1 ended, F2 has the new epoch": ok_rot,
              "new epoch data reached F2": ok_new, "F1 cannot read the new epoch": f1_blind,
              "F1 stays removed (replayed joins never re-admit)": f1.status == "ended"}
    for k, v in checks.items():
        log(f"{'PASS' if v else 'FAIL'}  {k}")
    log(f"F2: {len(f2.readings)} readings, {len(f2.entries)} entries, epochs {sorted(f2.views)}; "
        f"F1: status {f1.status} ({f1.reason}), epochs {sorted(f1.views)}; today = day {day_of(now)}")
    return 0 if all(checks.values()) else 1
