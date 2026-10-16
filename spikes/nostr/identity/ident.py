# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Investigation 14 — Nostr identity: reference implementation (see ../PROTOCOL-identity.md).

Pure functions, no I/O except the small relay/Blossom helpers at the bottom:
  - key derivation: derived accounts (main nsec → Sukrara root) and per-purpose sub-keys (profile key)
  - short invites (v3): a 32-char fragment that points at a NIP-59 gift-wrapped invite on the relays
  - kind 0 profiles + generated avatars + Blossom uploads (BUD-01/02)
"""
from __future__ import annotations

import asyncio, base64, hashlib, hmac, io, json, random, re, secrets, sys, time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "v2"))
import nostr_sdk as n                                   # noqa: E402
import cgm2                                              # noqa: E402  (gift wraps, match code)

N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141   # secp256k1 group order
APP_ID = "app.mauri.sukrara"
INVITE_BASE = "https://sukrara.mauri.app/join#"
# relay.damus.io and relay.nostr.net accept gift wraps but cannot serve them (AUTH: "relay needs serviceUrl", 2026-10-16)
DEFAULT_RELAYS = ["wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://offchain.pub"]
RUMOR_INVITE = 30502
DAY = 86400


# ---------------- key derivation ----------------

def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    """RFC 5869 HKDF-SHA256."""
    prk = hmac.new(salt, ikm, hashlib.sha256).digest()
    out, t, i = b"", b"", 1
    while len(out) < length:
        t = hmac.new(prk, t + info + bytes([i]), hashlib.sha256).digest()
        out += t
        i += 1
    return out[:length]


def _scalar(ikm: bytes, salt: bytes, info: bytes) -> bytes:
    """HKDF → valid secp256k1 secret (1 ≤ k < n). Retry with a counter suffix on the ~2^-128 out-of-range case."""
    for ctr in range(256):
        k = hkdf(ikm, salt, info + (b"" if ctr == 0 else bytes([ctr])))
        if 0 < int.from_bytes(k, "big") < N:
            return k
    raise ValueError("unreachable")


def derived_account(main_sec: bytes, app: str = APP_ID, index: int = 0) -> bytes:
    """Derived account: a new, unlinkable Nostr secret for `app`, computed from the user's main secret.

    One-way (HKDF): the derived key reveals nothing about the main key, and nothing public ever connects them —
    the main key signs nothing. The user only keeps the main nsec (already in their password manager)."""
    return _scalar(main_sec, b"nostr/derived-account/v1", f"{app}#{index}".encode())


def subkey(root_sec: bytes, purpose: str) -> bytes:
    """Per-purpose key from a Sukrara root (app-generated or derived). Nothing extra to back up."""
    return _scalar(root_sec, b"sukrara/subkey/v1", purpose.encode())


@dataclass
class Account:
    root: n.Keys        # = identity I of PROTOCOL-v2 (roster, key deliveries, owner backup). Never has a kind 0.
    profile: n.Keys     # P: publishes only kind 0 (+ Blossom auth). Its link to I is shared privately.
    origin: str         # "generated" | "derived:<npub of main, short>" | "sealed:<…>"

    @staticmethod
    def generated() -> "Account":
        return Account.from_root(sec_bytes(n.Keys.generate()), "generated")

    @staticmethod
    def from_root(root_sec: bytes, origin: str) -> "Account":
        return Account(keys_of(root_sec), keys_of(subkey(root_sec, "profile")), origin)

    @staticmethod
    def derived_from(main: n.Keys, index: int = 0) -> "Account":
        main_sec = sec_bytes(main)
        return Account.from_root(derived_account(main_sec, APP_ID, index),
                                 f"derived:{main.public_key().to_bech32()[:16]}…#{index}")


def sec_bytes(k: n.Keys) -> bytes:
    return bytes.fromhex(k.secret_key().to_hex())


def keys_of(sec: bytes) -> n.Keys:
    return n.Keys.parse(sec.hex())


def pub(k: n.Keys) -> str:
    return k.public_key().to_hex()


# ---------------- short invites (v3) ----------------

FP_LEN, SECRET_LEN = 8, 16


def b64u(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def unb64u(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def invite_key(secret: bytes) -> n.Keys:
    """Throwaway key the invite gift wrap is addressed to; anyone holding the link can derive it."""
    return keys_of(_scalar(secret, b"sukrara/invite/v3", b"wrap-recipient"))


def short_code(i_pub_hex: str, secret: bytes) -> str:
    """32 chars: 8-byte fingerprint of the identity + 16-byte invite secret (= the v2 token)."""
    return b64u(bytes.fromhex(i_pub_hex)[:FP_LEN] + secret)


def short_link(i_pub_hex: str, secret: bytes) -> str:
    return INVITE_BASE + short_code(i_pub_hex, secret)


def parse_link(link: str) -> dict:
    """`https://sukrara.mauri.app/join#<32 chars>` → {"fp", "secret"}. Anything else is not an invite."""
    link = link.strip()
    code = link[len(INVITE_BASE):] if link.startswith(INVITE_BASE) else ""
    if not re.fullmatch(r"[A-Za-z0-9_-]{32}", code):
        raise ValueError("not a Sukrara invite link")
    raw = unb64u(code)
    return {"fp": raw[:FP_LEN].hex(), "secret": raw[FP_LEN:]}


def invite_wrap(I: n.Keys, secret: bytes, expiry: int, relays: list[str], profile_pub: str | None,
                preview: dict | None = None) -> dict:
    """The invite as a standard NIP-59 gift wrap: sealed (signed) by I, addressed to invite_key(secret),
    with a NIP-40 expiration so relays drop it when the invite expires."""
    body = {"v": 3, "type": "invite", "x": expiry, "r": relays, "profile": profile_pub, **(preview or {})}
    rumor = n.EventBuilder(n.Kind(RUMOR_INVITE), json.dumps(body, separators=(",", ":"))) \
        .finalize_unsigned(I.public_key())
    k = invite_key(secret)
    # NIP-59 randomises the outer created_at up to 2 days back; the expiration tag is what relays act on.
    wrap = n.nip59_make_gift_wrap(I, k.public_key(), rumor, None, [n.Tag.expiration(n.Timestamp.from_secs(expiry))])
    return json.loads(wrap.as_json())


def open_invite(parsed: dict, wraps: list[dict], now: int | None = None) -> dict:
    """Follower side: pick the wrap that opens with the link's secret AND is sealed by a key matching the
    fingerprint. Returns {i, t, x, r} (what the access request needs) + the preview fields; raises otherwise."""
    now = now or int(time.time())
    k = invite_key(parsed["secret"])
    reasons = []
    for ev in wraps:
        u = cgm2.unwrap(k, ev)
        if not u:
            reasons.append("does not open with this link")
            continue
        sender, kind, body = u
        if kind != RUMOR_INVITE or body.get("type") != "invite":
            reasons.append("not an invite")
        elif not sender.startswith(parsed["fp"]):
            reasons.append(f"sealed by {sender[:16]}…, link expects {parsed['fp']}…")
        elif body["x"] < now:
            reasons.append("expired")
        else:
            return {"i": sender, "t": b64u(parsed["secret"]), "x": body["x"], "r": body["r"],
                    "profile": body.get("profile"), "name": body.get("name"), "picture": body.get("picture")}
    raise ValueError("no valid invite: " + ("; ".join(reasons) or "nothing found on the relays"))


# ---------------- kind 0 ----------------

def metadata_event(P: n.Keys, name: str, picture: str | None, imeta: dict | None = None) -> dict:
    """NIP-01/24 kind 0 with only what Sukrara shows. No about/website/nip05/lud16: nothing that hints at health.
    imeta (NIP-92 "Profile metadata"): {"m", "x", "dim", "fallback": [mirror urls]} describing `picture`."""
    md = {"name": name, "display_name": name}
    tags = []
    if picture:
        md["picture"] = picture
        if imeta:
            t = ["imeta", f"url {picture}"] + [f"{k} {imeta[k]}" for k in ("m", "x", "dim") if imeta.get(k)]
            t += [f"fallback {u}" for u in imeta.get("fallback", [])]
            tags.append(n.Tag.parse(t))
    ev = n.EventBuilder(n.Kind(0), json.dumps(md, ensure_ascii=False)).tags(tags).finalize(P)
    return json.loads(ev.as_json())


# ---------------- generated avatars ----------------
# Spec shared with the Android PoC (Avatar.kt): params → picture. Default = random params.

PALETTES = [("#1BB5C9", "#0B2E5E"), ("#C6F432", "#2E7D32"), ("#FF8A65", "#BF360C"), ("#BA68C8", "#4A148C"),
            ("#FFD54F", "#E65100"), ("#4FC3F7", "#01579B"), ("#F06292", "#880E4F"), ("#90A4AE", "#263238")]
SHAPES = ["cube", "drop", "blob", "rings", "initial"]


def random_avatar(rng: random.Random | None = None, initial: str = "") -> dict:
    r = rng or random.Random(secrets.randbits(64))
    return {"palette": r.randrange(len(PALETTES)), "shape": r.choice(SHAPES[:-1] if not initial else SHAPES),
            "angle": r.randrange(0, 360, 15), "seed": r.randrange(1 << 30), "initial": initial[:1].upper()}


def render_avatar(p: dict, size: int = 512) -> bytes:
    """PNG bytes. Deliberately simple shapes on a two-colour gradient."""
    from PIL import Image, ImageDraw, ImageFont
    a, b = PALETTES[p["palette"]]
    c1, c2 = [tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) for h in (a, b)]
    img = Image.new("RGB", (size, size))
    px = img.load()
    import math
    ang = math.radians(p["angle"])
    dx, dy = math.cos(ang), math.sin(ang)
    for y in range(size):
        for x in range(size):
            t = ((x / size - .5) * dx + (y / size - .5) * dy) + .5
            t = min(1, max(0, t))
            px[x, y] = tuple(int(c1[i] * (1 - t) + c2[i] * t) for i in range(3))
    d = ImageDraw.Draw(img, "RGBA")
    rng = random.Random(p["seed"])
    s, cx = size, size / 2
    if p["shape"] == "cube":
        w = s * .22
        top = [(cx, cx - w * 1.1), (cx + w, cx - w * .55), (cx, cx), (cx - w, cx - w * .55)]
        d.polygon(top, fill=(255, 255, 255, 235))
        d.polygon([(cx - w, cx - w * .55), (cx, cx), (cx, cx + w * 1.15), (cx - w, cx + w * .6)], fill=(255, 255, 255, 170))
        d.polygon([(cx + w, cx - w * .55), (cx, cx), (cx, cx + w * 1.15), (cx + w, cx + w * .6)], fill=(255, 255, 255, 110))
    elif p["shape"] == "drop":
        r = s * .2
        d.ellipse([cx - r, cx - r * .4, cx + r, cx + r * 1.6], fill=(255, 255, 255, 220))
        d.polygon([(cx - r * .93, cx + r * .3), (cx, cx - r * 1.5), (cx + r * .93, cx + r * .3)], fill=(255, 255, 255, 220))
    elif p["shape"] == "blob":
        for _ in range(5):
            r = s * rng.uniform(.08, .2)
            x, y = rng.uniform(.25, .75) * s, rng.uniform(.25, .75) * s
            d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, rng.randrange(90, 200)))
    elif p["shape"] == "rings":
        for i in range(4, 0, -1):
            r = s * .09 * i
            d.ellipse([cx - r, cx - r, cx + r, cx + r], outline=(255, 255, 255, 60 + 40 * i), width=int(s * .03))
    else:
        try:
            f = ImageFont.truetype("/System/Library/Fonts/SFNSRounded.ttf", int(s * .5))
        except OSError:
            f = ImageFont.load_default(int(s * .5))
        d.text((cx, cx), p["initial"] or "?", font=f, fill=(255, 255, 255, 240), anchor="mm")
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)
    return buf.getvalue()


# ---------------- Blossom (BUD-01/02) ----------------

def blossom_auth(k: n.Keys, verb: str, sha256_hex: str | None, server: str, minutes: int = 5) -> str:
    """BUD-11: kind 24242, `t` verb, `x` hash, NIP-40 expiration, `server` (bare domain, so a delete token can't be
    replayed elsewhere). BUD-11 says base64url without padding, but blossom.primal.net ("invalid base64 for auth event")
    and nostr.download/route96 ("Invalid auth string") only accept standard padded base64, which every server we tried
    accepts (2026-10-16) — so send that."""
    tags = [n.Tag.parse(["t", verb]), n.Tag.expiration(n.Timestamp.from_secs(int(time.time()) + minutes * 60)),
            n.Tag.parse(["server", server.split("//")[-1].strip("/")])]
    if sha256_hex:
        tags.append(n.Tag.parse(["x", sha256_hex]))
    ev = n.EventBuilder(n.Kind(24242), f"{verb.capitalize()} profile picture").tags(tags).finalize(k)
    return "Nostr " + base64.b64encode(ev.as_json().encode()).decode()


async def blossom_upload(session, server: str, k: n.Keys, data: bytes, mime: str = "image/png") -> dict:
    h = hashlib.sha256(data).hexdigest()
    async with session.put(server.rstrip("/") + "/upload", data=data,
                           headers={"Authorization": blossom_auth(k, "upload", h, server), "Content-Type": mime,
                                    "X-SHA-256": h}) as r:
        body = await r.text()
        out = {"sha256": h, "reason": r.headers.get("X-Reason", "")}
        try:
            out.update(json.loads(body))           # BUD-02 descriptor {url, sha256, size, type, uploaded}
        except Exception:
            out["body"] = body[:200]
        out["status"] = r.status                   # (some servers put their own "status" in the JSON)
        return out


async def blossom_delete(session, server: str, k: n.Keys, sha256_hex: str) -> int | str:
    try:
        async with session.delete(server.rstrip("/") + "/" + sha256_hex,
                                  headers={"Authorization": blossom_auth(k, "delete", sha256_hex, server)}) as r:
            return r.status
    except Exception as e:  # noqa: BLE001  (slow servers time out; cleanup must go on)
        return type(e).__name__


# ---------------- minimal relay I/O ----------------

async def relay_publish(url: str, ev: dict, auth_keys: n.Keys | None = None, timeout: float = 8,
                        tries: int = 3) -> tuple[bool, str]:
    """→ (accepted, message). Retries only connection failures (not relay rejections)."""
    for i in range(tries):
        res = await _publish_once(url, ev, auth_keys, timeout)
        if res[0] or not res[1].startswith(("TimeoutError", "OSError", "ConnectionClosed", "InvalidStatus", "timeout")):
            return res
        await asyncio.sleep(1 + 2 * i)
    return res


async def _publish_once(url: str, ev: dict, auth_keys: n.Keys | None, timeout: float) -> tuple[bool, str]:
    import websockets
    try:
        async with websockets.connect(url, open_timeout=timeout, max_size=2 ** 22) as ws:
            await ws.send(json.dumps(["EVENT", ev]))
            end = time.time() + timeout
            while time.time() < end:
                msg = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
                if msg[0] == "AUTH" and auth_keys:
                    await ws.send(json.dumps(["AUTH", _auth_event(auth_keys, url, msg[1])]))
                elif msg[0] == "OK" and msg[1] == ev["id"]:
                    if not msg[2] and "auth-required" in msg[3] and auth_keys:
                        await ws.send(json.dumps(["EVENT", ev]))
                        continue
                    return bool(msg[2]), msg[3]
            return False, "timeout"
    except Exception as e:  # noqa: BLE001
        return False, f"{type(e).__name__}: {e}"[:120]


async def relay_query(url: str, flt: dict, auth_keys: n.Keys | None = None, timeout: float = 8,
                      tries: int = 3) -> list[dict]:
    """REQ until EOSE. Answers NIP-42 AUTH with auth_keys and re-sends the REQ (some relays gate kind 1059)."""
    for i in range(tries):
        res = await _query_once(url, flt, auth_keys, timeout)
        if res is not None:
            return res
        await asyncio.sleep(1 + 2 * i)
    return []


async def _query_once(url: str, flt: dict, auth_keys: n.Keys | None, timeout: float) -> list[dict] | None:
    import websockets
    out, sid = [], "q" + secrets.token_hex(4)
    try:
        async with websockets.connect(url, open_timeout=timeout, max_size=2 ** 22) as ws:
            await ws.send(json.dumps(["REQ", sid, flt]))
            end, authed = time.time() + timeout, False
            while time.time() < end:
                msg = json.loads(await asyncio.wait_for(ws.recv(), end - time.time()))
                if msg[0] == "AUTH" and auth_keys and not authed:
                    authed = True
                    await ws.send(json.dumps(["AUTH", _auth_event(auth_keys, url, msg[1])]))
                    await asyncio.sleep(.5)
                    await ws.send(json.dumps(["REQ", sid, flt]))
                elif msg[0] == "EVENT" and msg[1] == sid:
                    out.append(msg[2])
                elif msg[0] == "EOSE" and msg[1] == sid:
                    return out
                elif msg[0] == "CLOSED" and msg[1] == sid:
                    if "auth-required" in msg[2] and auth_keys:
                        continue
                    return out
            return out
    except Exception:  # noqa: BLE001
        return None if not out else out
    return out


def _auth_event(k: n.Keys, url: str, challenge: str) -> dict:
    ev = n.EventBuilder(n.Kind(22242), "").tags([n.Tag.parse(["relay", url]), n.Tag.parse(["challenge", challenge])])
    return json.loads(ev.finalize(k).as_json())
