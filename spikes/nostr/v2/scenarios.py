# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""End-to-end v2 scenarios over real public relays: uv run v2/scenarios.py [--every 10]

Owner, followers and an attacker are separate nodes: own keys, own SQLite, own relay connections. Nothing is shared
except the relays, so a follower can only show data that really travelled through Nostr.
1 share · 2 deny (negative) · 3 forged key delivery (negative) · 4 data from a non-roster key (negative)
5 revoke A while B keeps working · 6 phone change (restore identity, new device key) · 7 missed rotation → rekey
"""
import argparse, asyncio, shutil, sys, tempfile, time
from pathlib import Path

from cgm2 import (LIVE, RUMOR_TO_FOLLOWER, FollowerState, OwnerState, Reading, gift, gk, hour_of, keys, live_event,
                  pub, signed)
from nodes import FollowerNode, OwnerNode, synthetic

results: list[tuple[str, bool, str]] = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(f"{'✅' if ok else '❌'} {name} {detail}", flush=True)


async def wait_for(pred, timeout, step=1.0):
    end = time.time() + timeout
    while time.time() < end:
        if pred():
            return True
        await asyncio.sleep(step)
    return pred()


async def main(every: int):
    tmp = Path(tempfile.mkdtemp(prefix="cgm2-"))
    owner = OwnerNode(OwnerState.create(), tmp / "owner.json", policy=lambda name, code: not name.startswith("Eve"))
    now = int(time.time())
    for t in range(now - 12 * 3600, now, 60):                       # 12 h of history on the owner phone
        owner.add(synthetic(t), live=False)
    owner.start()
    owner.publish_chunks(owner.recent_hours(13))

    async def ticker():
        while True:
            owner.add(synthetic(int(time.time())))
            await asyncio.sleep(every)
    tick = asyncio.ensure_future(ticker())

    def follower(name):
        return FollowerNode(FollowerState(keys(), "", []), tmp / f"{name}.json", tmp / f"{name}.db", name)

    # 1 share
    A = follower("A")
    A.start()
    await A.request_access(owner.invite(), "Mom's phone")
    ok = await wait_for(lambda: A.st.status == "active", 60)
    check("1a follower A approved and got the read key", ok)
    ok = await wait_for(lambda: A.count() > 600, 90)
    check("1b A backfilled ≥ 10 h from hourly chunks", ok, f"{A.count()} readings")
    before = A.count()
    ok = await wait_for(lambda: any(e["evt"] == "reading" for e in A.events), every * 4 + 30)
    check("1c A receives live readings", ok, next((f"latency {e['latency']}s via {e['via']}" for e in A.events if e["evt"] == "reading"), ""))
    rows = A.db.execute("select t, sgv from readings").fetchall()
    mism = [t for t, sgv in rows if t in owner.readings and owner.readings[t].sgv != sgv]
    check("1d every A reading equals the owner's value", rows and not mism, f"{len(rows)} compared, {len(mism)} mismatched")
    vias = {v for (v,) in A.db.execute("select distinct via from readings")}
    check("1e every A reading records the relay it came through", all(v.startswith("relay:") for v in vias), str(sorted(vias)))

    # 2 deny (negative)
    E = follower("Eve")
    E.start()
    await E.request_access(owner.invite(), "Eve's laptop")
    await asyncio.sleep(40)
    check("2  denied requester gets no key and no data", E.st.status != "active" and E.count() == 0,
          f"status={E.st.status} readings={E.count()}")

    # 3 forged key delivery (negative): attacker sends A a 'key' sealed by its own key
    X = keys()
    fake_g = keys()
    owner.pool.publish(gift(X, pub(A.F if hasattr(A, 'F') else A.st.F), RUMOR_TO_FOLLOWER,
                            {"v": 2, "type": "key", "sid": A.st.sid, "epoch": 99, "g": fake_g.secret_key().to_bech32(),
                             "devices": [pub(X)], "relays": A.st.relays}))
    ok = await wait_for(lambda: any(e["evt"] == "reject" and "identity" in e["why"] for e in A.events), 30)
    check("3  forged key delivery rejected (not sealed by identity)", ok and gk(pub(fake_g)) not in A.st.epochs)

    # 4 data signed by a non-roster key (negative): attacker knows the current read key (e.g. a leaked one)
    forged = live_event(X, owner.st.current.pub, Reading(int(time.time()), 400, 9.9, "forged"))
    owner.pool.publish(forged)
    await asyncio.sleep(10)
    stored = lambda: A.db.execute("select count(*) from readings where sgv=400").fetchone()[0]
    check("4a non-roster reading published to relays never reaches A (subscription filters by roster)", stored() == 0)
    A.on_data(forged, "wss://misbehaving-relay")            # a relay that ignores the authors filter
    check("4b …and A's own acceptance rule rejects it if a relay delivers it anyway",
          stored() == 0 and any(e["evt"] == "reject" and e["why"] == "author not in roster" for e in A.events))

    # 5 revoke A while B keeps working
    B = follower("B")
    B.start()
    await B.request_access(owner.invite(), "Dad's tablet")
    await wait_for(lambda: B.st.status == "active", 60)
    owner.revoke("Mom's phone")
    ok = await wait_for(lambda: any(e["evt"] == "key" and e["epoch"] == 2 for e in B.events), 60)
    check("5a B received the epoch-2 key after A was revoked", ok)
    ok = await wait_for(lambda: A.st.status == "revoked", 60)
    check("5b A got the revoked notice", ok)
    a_count, t_rev = A.count(), int(time.time())
    b_live = lambda: [e for e in B.events if e["evt"] == "reading" and e["epoch"] == 2]
    ok = await wait_for(lambda: len(b_live()) >= 2, every * 6 + 30)
    check("5c B keeps receiving live readings under epoch 2", ok, f"{len(b_live())} live readings")
    check("5d A gets no new readings after revocation", A.count(t_rev + 1) == 0, f"A still has {a_count} old readings")
    check("5e A's rekey requests are refused", any(e["evt"] == "rekey_refused" for e in owner.events) or
          not any(e["evt"] == "rekeyed" and e["name"] == "Mom's phone" for e in owner.events))

    # 6 phone change: restore identity on a "new phone"; old device key stops being accepted
    tick.cancel()
    old_D = owner.st.D
    nsec = owner.st.I.secret_key().to_bech32()
    await asyncio.sleep(3)
    owner2 = await OwnerNode.restore(nsec, tmp / "owner2.json", owner.st.relays,
                                     policy=lambda name, code: True, name="owner-new-phone")
    owner2.readings = dict(owner.readings)                          # the new phone has the sensor's history
    check("6a new phone restored followers + sid from the relay backup",
          owner2.st.sid == owner.st.sid and [f["name"] for f in owner2.st.active_followers()] == ["Dad's tablet"])
    ok = await wait_for(lambda: any(e["evt"] == "key" and e["epoch"] == 3 for e in B.events) and
                        B.st.devices == [pub(owner2.st.D)], 60)
    check("6b B got epoch 3 + the new device in the roster, without re-inviting", ok)
    owner2.add(synthetic(int(time.time())))
    ok = await wait_for(lambda: any(e["evt"] == "reading" and e["epoch"] == 3 for e in B.events), 40)
    check("6c B receives readings from the new phone", ok)
    owner.pool.publish(live_event(old_D, owner2.st.current.pub, Reading(int(time.time()) + 5, 333, 0.0, "old phone")))
    ok = await wait_for(lambda: any(e["evt"] == "reject" and e["why"] == "author not in roster" and
                                    e["author"] == pub(old_D)[:12] for e in B.events), 30)
    check("6d readings signed by the old phone's key are rejected", ok)

    # 7 missed rotation: B "loses" the newest key → unknown gk → rekey → recovered
    newest = max(B.st.epochs, key=lambda k: B.st.epochs[k][0])
    del B.st.epochs[newest]
    await asyncio.sleep(2)
    owner2.add(synthetic(int(time.time())))
    ok = await wait_for(lambda: any(e["evt"] == "rekey_requested" for e in B.events) and newest in B.st.epochs, 60)
    check("7  follower that missed a rotation asks for the key and recovers", ok)

    print(f"\n{sum(ok for _, ok, _ in results)}/{len(results)} checks passed · state in {tmp}")
    return all(ok for _, ok, _ in results)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--every", type=int, default=10, help="synthetic reading interval (s)")
    sys.exit(0 if asyncio.run(main(ap.parse_args().every)) else 1)
