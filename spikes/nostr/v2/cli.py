# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Long-running v2 owner / follower for cross-testing other implementations (e.g. the Android app).

  uv run v2/cli.py owner  --dir secrets/v2-owner  [--every 60] [--deny NAME…]
        synthetic readings; writes a fresh invite link to <dir>/invite.txt; auto-approves requests (except --deny);
        type `invite`, `revoke <name>` or `followers` on stdin
  uv run v2/cli.py follow --dir secrets/v2-follower --link 'cgmshare:join?…' [--name 'Python follower']
        joins (or resumes), prints every accepted reading as JSON; SQLite in <dir>/readings.db
"""
import argparse, asyncio, sys, time
from pathlib import Path

from cgm2 import FollowerState, OwnerState, keys
from nodes import FollowerNode, OwnerNode, synthetic


async def owner(a):
    d = Path(a.dir)
    path = d / "owner.json"
    st = OwnerState.load(path) if path.exists() else OwnerState.create()
    node = OwnerNode(st, path, policy=lambda name, code: name not in (a.deny or []))
    now = int(time.time())
    for t in range(now - 12 * 3600, now, 60):
        node.add(synthetic(t), live=False)
    node.start()
    node.publish_chunks(node.recent_hours(13))
    link = node.invite()
    (d / "invite.txt").write_text(link)
    print(f"invite: {link}", flush=True)

    async def readings():
        while True:
            node.add(synthetic(int(time.time())))
            if int(time.time()) % 300 < a.every:
                node.publish_chunks()
            await asyncio.sleep(a.every)

    async def commands():
        loop = asyncio.get_running_loop()
        while True:
            line = (await loop.run_in_executor(None, sys.stdin.readline)).strip()
            if not line:
                await asyncio.sleep(1)
            elif line == "invite":
                link = node.invite()
                (d / "invite.txt").write_text(link)
                print(f"invite: {link}", flush=True)
            elif line.startswith("revoke "):
                node.revoke(line[7:])
            elif line == "followers":
                print(node.st.followers, flush=True)
    await asyncio.gather(readings(), commands())


async def follow(a):
    d = Path(a.dir)
    d.mkdir(parents=True, exist_ok=True)
    path = d / "follower.json"
    st = FollowerState.load(path) if path.exists() else FollowerState(keys(), "", [])
    node = FollowerNode(st, path, d / "readings.db", a.name)
    if st.sid or st.status != "requested" or not a.link:
        node.start()
    else:
        await node.request_access(a.link, a.name)
    while True:
        await asyncio.sleep(3600)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("role", choices=["owner", "follow"])
    ap.add_argument("--dir", required=True)
    ap.add_argument("--every", type=int, default=60)
    ap.add_argument("--deny", nargs="*")
    ap.add_argument("--link")
    ap.add_argument("--name", default="Python follower")
    a = ap.parse_args()
    asyncio.run(owner(a) if a.role == "owner" else follow(a))
