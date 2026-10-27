# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Investigation 14 — what public relays and Blossom servers do with our identity events. Throwaway keys only.

    uv run identity/live_check.py [--keep]     (from spikes/nostr)

1. invite gift wrap: publish to the default relays, fetch it back by the link alone (#p = invite key, AUTH as it)
2. kind 0 with a URL picture vs. a data: URI picture (size limits)
3. Blossom: upload a generated avatar, GET it back, DELETE it
4. cleanup: NIP-09 deletion of the kind 0s, Blossom DELETE, NIP-62 request to vanish for every throwaway key
Writes results/14-live.txt (human) and results/14-live.jsonl (raw)."""
from __future__ import annotations

import asyncio, base64, json, secrets, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ident as I                                        # noqa: E402
import nostr_sdk as n                                    # noqa: E402

import aiohttp                                           # noqa: E402

RELAYS = I.DEFAULT_RELAYS + ["wss://relay.damus.io", "wss://relay.nostr.net", "wss://relay.snort.social"]
BLOSSOM = ["https://blossom.primal.net", "https://nostr.download", "https://blossom.band", "https://cdn.nostrcheck.me",
           "https://blossom.nostr.build", "https://files.sovbit.host", "https://blossom.yakihonne.com",
           "https://24242.io", "https://cdn.satellite.earth", "https://blossom.f7z.io"]
RES = Path(__file__).resolve().parent.parent / "results"
lines, raw = [], []


def out(s=""):
    print(s)
    lines.append(s)


async def pub_all(ev, auth=None):
    res = await asyncio.gather(*[I.relay_publish(u, ev, auth) for u in RELAYS])
    return dict(zip(RELAYS, res))


async def main(keep: bool):
    t0 = time.time()
    owner, follower = I.Account.generated(), I.Account.generated()
    throwaway = [owner.root, owner.profile, follower.root, follower.profile]
    out(f"# Investigation 14 live check · {time.strftime('%Y-%m-%d %H:%M:%S %Z')} · throwaway keys only")
    out("\n## 0. Relay limits (NIP-11)")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as http:
        for u in RELAYS:
            try:
                async with http.get(u.replace("wss://", "https://"), headers={"Accept": "application/nostr+json"}) as r:
                    lim = (await r.json(content_type=None)).get("limitation", {})
            except Exception as e:  # noqa: BLE001
                lim = {"error": type(e).__name__}
            keep_ = {k_: lim[k_] for k_ in ("max_message_length", "max_content_length", "auth_required",
                                            "payment_required", "restricted_writes", "error") if k_ in lim}
            out(f"- {u:28} {keep_ or 'no limitation published'}")
            raw.append({"test": "nip11", "relay": u, "limitation": lim})

    # 1. invite gift wrap
    secret = secrets.token_bytes(16)
    link = I.short_link(I.pub(owner.root), secret)
    x = int(time.time()) + I.DAY
    wrap = I.invite_wrap(owner.root, secret, x, I.DEFAULT_RELAYS, I.pub(owner.profile), {"name": "Test wearer"})
    throwaway.append(I.invite_key(secret))
    out(f"\n## 1. Invite gift wrap ({len(json.dumps(wrap))} B event) · link {len(link)} chars")
    pubres = await pub_all(wrap)
    await asyncio.sleep(2)
    k = I.invite_key(secret)
    parsed = I.parse_link(link)
    found_any = []
    for u in RELAYS:
        t = time.time()
        noauth = await I.relay_query(u, {"kinds": [1059], "#p": [I.pub(k)]})
        got = noauth or await I.relay_query(u, {"kinds": [1059], "#p": [I.pub(k)]}, auth_keys=k)
        dt = time.time() - t
        try:
            inv = I.open_invite(parsed, got)
            opened = inv["i"] == I.pub(owner.root)
        except ValueError:
            opened = False
        found_any += got
        out(f"- {u:28} publish: {'ok' if pubres[u][0] else 'NO  ' + pubres[u][1][:60]:<20} "
            f"fetch-by-link: {'opened' if opened else 'not found'}{' (needed AUTH as invite key)' if got and not noauth else ''}"
            f" · {dt:.1f}s")
        raw.append({"test": "invite", "relay": u, "publish": pubres[u], "found": len(got), "noauth": len(noauth),
                    "opened": opened})
    try:
        I.open_invite(parsed, found_any)
        out("→ follower resolves the short link from the relays: **yes**")
    except ValueError as e:
        out(f"→ follower resolves the short link: **NO** ({e})")

    # 2. kind 0: URL picture vs data: URI
    out("\n## 2. kind 0 (profile key)")
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=25)) as http:
        # 3. Blossom first, so the kind 0 can point at a real upload
        av = I.random_avatar()
        png = I.render_avatar(av, 512)
        out(f"\n## 3. Blossom upload of a generated avatar ({len(png)} B PNG, signed by the profile key)")
        uploaded = []
        for s in BLOSSOM:
            try:
                r = await I.blossom_upload(http, s, owner.profile, png)
            except Exception as e:  # noqa: BLE001
                r = {"status": 0, "reason": f"{type(e).__name__}: {e}"[:80]}
            got, ctype = None, ""
            if r.get("status") in (200, 201) and r.get("url"):
                try:
                    async with http.get(r["url"]) as g:
                        body = await g.read()
                        got, ctype = (I.hashlib.sha256(body).hexdigest() == r["sha256"]), g.headers.get("Content-Type", "")
                except Exception:  # noqa: BLE001
                    got = False
                uploaded.append((s, r))
            out(f"- {s:32} {r.get('status')} {('url ' + r['url']) if r.get('url') else (r.get('reason') or r.get('body', ''))[:90]}"
                + (f" · GET {'same bytes' if got else 'MISMATCH'} ({ctype})" if got is not None else ""))
            raw.append({"test": "blossom", "server": s, **{k_: v for k_, v in r.items() if k_ != "body"}, "get_ok": got})

        pic = uploaded[0][1]["url"] if uploaded else None
        md = I.metadata_event(owner.profile, "Test wearer", pic, {
            "m": "image/png", "x": I.hashlib.sha256(png).hexdigest(), "dim": "512x512",
            "fallback": [r["url"] for _, r in uploaded[1:3]]})
        res = await pub_all(md)
        out(f"\nkind 0 with picture URL ({len(json.dumps(md))} B): accepted by "
            f"{sum(v[0] for v in res.values())}/{len(RELAYS)} — " + ", ".join(f"{u.split('//')[1]}" for u, v in res.items() if v[0]))
        for u, v in res.items():
            if not v[0]:
                out(f"  - {u}: {v[1][:90]}")
        raw.append({"test": "kind0-url", "res": res})

        for size in (256, 512):
            png_s = I.render_avatar(av, size)
            data_uri = "data:image/png;base64," + base64.b64encode(png_s).decode()
            md_d = I.metadata_event(follower.profile, "Test follower", data_uri)
            res = await pub_all(md_d)
            out(f"kind 0 with data: URI picture, {size}px ({len(json.dumps(md_d)) // 1024} KB event): accepted by "
                f"{sum(v[0] for v in res.values())}/{len(RELAYS)}")
            for u, v in res.items():
                if not v[0]:
                    out(f"  - {u}: {v[1][:90]}")
            raw.append({"test": f"kind0-data-{size}", "bytes": len(json.dumps(md_d)), "res": res})

        # read back owner's kind 0 from every relay
        await asyncio.sleep(1)
        back = await asyncio.gather(*[I.relay_query(u, {"kinds": [0], "authors": [I.pub(owner.profile)]}) for u in RELAYS])
        out(f"kind 0 read back (URL version): {sum(1 for b in back if b)}/{len(RELAYS)} relays")

        # 4. cleanup
        out("\n## 4. Cleanup")
        if keep:
            out("--keep: left in place")
        else:
            for s, r in uploaded:
                st = await I.blossom_delete(http, s, owner.profile, r["sha256"])
                await asyncio.sleep(10)
                try:
                    async with http.get(r["url"] + f"?nocache={secrets.token_hex(4)}",
                                        headers={"Cache-Control": "no-cache"}) as g:
                        after = g.status
                except Exception:  # noqa: BLE001
                    after = "error"
                out(f"- Blossom DELETE {s:32} → {st}, then GET → {after}")
                raw.append({"test": "blossom-delete", "server": s, "delete": st, "get_after": after})
            # a. overwrite: kind 0 is replaceable, so a newer empty profile replaces the old one where relays comply
            blank = I.metadata_event(owner.profile, "", None)
            await pub_all(blank)
            await asyncio.sleep(2)
            back = await asyncio.gather(*[I.relay_query(u, {"kinds": [0], "authors": [I.pub(owner.profile)]}) for u in RELAYS])
            old_left = sum(1 for b in back if any(json.loads(e["content"]).get("name") for e in b))
            out(f"- overwrite with an empty kind 0: old name still served by {old_left}/{len(RELAYS)} relays")
            raw.append({"test": "overwrite", "old_left": old_left})
            # b. NIP-09 deletion of the follower's data-URI kind 0s (by `a` coordinate + `k`)
            dele = n.EventBuilder(n.Kind(5), "test cleanup").tags([
                n.Tag.parse(["a", f"0:{I.pub(follower.profile)}:"]), n.Tag.parse(["k", "0"])]).finalize(follower.profile)
            await pub_all(json.loads(dele.as_json()))
            await asyncio.sleep(2)
            back = await asyncio.gather(*[I.relay_query(u, {"kinds": [0], "authors": [I.pub(follower.profile)]}) for u in RELAYS])
            out(f"- NIP-09 deletion (a = 0:<pubkey>:): kind 0 still served by {sum(1 for b in back if b)}/{len(RELAYS)} relays")
            raw.append({"test": "nip09", "left": [len(b) for b in back]})
            for kk in throwaway:
                # NIP-62 request to vanish, scoped to every relay we used (relay tags), then NIP-09 for kind 0
                ev = n.EventBuilder(n.Kind(62), "throwaway test key (Sukrara investigation 14)") \
                    .tags([n.Tag.parse(["relay", u]) for u in RELAYS]).finalize(kk)
                vres = await pub_all(json.loads(ev.as_json()), kk)
                raw.append({"test": "vanish", "key": I.pub(kk)[:16], "res": vres})
            out("- NIP-62 request to vanish for all 5 throwaway keys: accepted by " +
                ", ".join(f"{u.split('//')[1]} {sum(1 for r in raw if r['test'] == 'vanish' and r['res'][u][0])}/5"
                          for u in RELAYS))
            await asyncio.sleep(3)
            left = await asyncio.gather(*[I.relay_query(u, {"kinds": [0], "authors": [I.pub(owner.profile), I.pub(follower.profile)]})
                                          for u in RELAYS])
            leftw = await asyncio.gather(*[I.relay_query(u, {"kinds": [1059], "#p": [I.pub(k)]}, auth_keys=k) for u in RELAYS])
            for u, a, b in zip(RELAYS, left, leftw):
                out(f"- after vanish {u:28} kind 0 left: {len(a)} · invite wrap left: {len(b)}")
                raw.append({"test": "after-vanish", "relay": u, "kind0": len(a), "wraps": len(b)})
    out(f"\n({time.time() - t0:.0f} s)")


def save():
    RES.mkdir(exist_ok=True)
    (RES / "14-live.txt").write_text("\n".join(lines) + "\n")
    (RES / "14-live.jsonl").write_text("\n".join(json.dumps(r) for r in raw) + "\n")


if __name__ == "__main__":
    try:
        asyncio.run(main("--keep" in sys.argv))
    finally:
        save()                     # partial results are evidence too
