# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline checks for investigation 14. `uv run identity/test_identity.py` (from spikes/nostr).

Writes identity/vectors.json (cross-implementation test vectors for the Android PoC) and prints link/QR sizes."""
from __future__ import annotations

import json, secrets, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ident as I                                        # noqa: E402
import nostr_sdk as n                                    # noqa: E402
import cgm2                                              # noqa: E402

HERE = Path(__file__).resolve().parent
ok = fail = 0


def check(name, cond, detail=""):
    global ok, fail
    ok, fail = ok + bool(cond), fail + (not cond)
    print(("  ✅ " if cond else "  ❌ ") + name + (f" — {detail}" if detail else ""))


def qr_version(text: str) -> tuple[int, int]:
    import qrcode
    q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M)
    q.add_data(text)
    q.make(fit=True)
    return q.version, 17 + 4 * q.version


print("1. HKDF matches RFC 5869 test case 1")
okm = I.hkdf(bytes.fromhex("0b" * 22), bytes.fromhex("000102030405060708090a0b0c"),
             bytes.fromhex("f0f1f2f3f4f5f6f7f8f9"), 42)
check("RFC 5869 A.1 OKM", okm.hex() == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")

print("2. Derived accounts")
main = n.Keys.parse("1" * 64)                 # fixed, public test key: never a real account
a0, a0b, a1 = I.Account.derived_from(main), I.Account.derived_from(main), I.Account.derived_from(main, 1)
check("deterministic (same main → same account)", I.pub(a0.root) == I.pub(a0b.root) and I.pub(a0.profile) == I.pub(a0b.profile))
check("index 1 ≠ index 0", I.pub(a1.root) != I.pub(a0.root))
check("derived ≠ main, profile ≠ root", len({I.pub(main), I.pub(a0.root), I.pub(a0.profile)}) == 3)
other_app = I.derived_account(I.sec_bytes(main), "org.example.other")
check("other app id → other key", other_app != I.sec_bytes(a0.root))
g = I.Account.generated()
check("generated account: profile = subkey(root)", I.pub(g.profile) == I.pub(I.keys_of(I.subkey(I.sec_bytes(g.root), "profile"))))

print("3. Short invite link")
owner = I.Account.generated()
secret = secrets.token_bytes(16)
short = I.short_link(I.pub(owner.root), secret)
# the investigation-06 link (its builder is gone with the v2 CLI), only to check it's refused and what the short form saves
v2 = (f"https://sukrara.mauri.app/join#i={n.PublicKey.parse(I.pub(owner.root)).to_bech32()}&t={I.b64u(secret)}"
      f"&x={int(time.time()) + I.DAY}&r=wss%3A%2F%2Fnos.lol%2Cwss%3A%2F%2Frelay.primal.net")
p = I.parse_link(short)
check("parses", p["secret"] == secret and I.pub(owner.root).startswith(p["fp"]))
for label, bad in [("06-style link", v2), ("cgmshare: link", v2.replace("https://sukrara.mauri.app/join#", "cgmshare:join?")),
                   ("other host", short.replace("sukrara.mauri.app", "evil.example")), ("truncated", short[:-1])]:
    try:
        I.parse_link(bad)
        check(f"{label} rejected", False, "accepted!")
    except ValueError:
        check(f"{label} rejected", True)
qv2, qv3 = qr_version(v2), qr_version(short)
check(f"shorter than the 06 link: {len(v2)} → {len(short)} chars", len(short) < len(v2) / 2)
check(f"smaller QR (ECC M): version {qv2[0]} ({qv2[1]}×{qv2[1]}) → version {qv3[0]} ({qv3[1]}×{qv3[1]})", qv3[0] < qv2[0])
print(f"     06: {v2}\n     14: {short}")

print("4. Invite gift wrap")
x = int(time.time()) + I.DAY
wrap = I.invite_wrap(owner.root, secret, x, I.DEFAULT_RELAYS, I.pub(owner.profile), {"name": "Alex"})
check("outer event is a plain NIP-59 gift wrap (kind 1059, p = invite key, random signer)",
      wrap["kind"] == 1059 and cgm2.tag(wrap, "p") == I.pub(I.invite_key(secret)) and wrap["pubkey"] != I.pub(owner.root))
check("wrap carries NIP-40 expiration = invite expiry", cgm2.tag(wrap, "expiration") == str(x))
inv = I.open_invite(p, [wrap])
check("follower opens it → {i,t,x,r} + profile",
      inv["i"] == I.pub(owner.root) and inv["t"] == I.b64u(secret) and inv["profile"] == I.pub(owner.profile)
      and inv["name"] == "Alex")
check("match code = PROTOCOL-v2 formula with token = secret", cgm2.match_code("ab" * 32, inv["t"]) == cgm2.match_code("ab" * 32, I.b64u(secret)))

# negatives
mallory = I.Account.generated()
forged = I.invite_wrap(mallory.root, secret, x, ["wss://evil.example"], I.pub(mallory.profile))
try:
    got = I.open_invite(p, [forged, wrap])
    check("link-holder forging a wrap for the same secret is skipped (fingerprint), real one still found",
          got["i"] == I.pub(owner.root))
except ValueError as e:
    check("forged wrap skipped", False, str(e))
for label, wraps, parsed in [
    ("only the forged wrap → rejected", [forged], p),
    ("wrong secret → rejected", [wrap], {**p, "secret": secrets.token_bytes(16)}),
    ("expired → rejected", [I.invite_wrap(owner.root, secret, int(time.time()) - 5, [], None)], p),
]:
    try:
        I.open_invite(parsed, wraps)
        check(label, False, "accepted!")
    except ValueError as e:
        check(label, True, str(e)[:90])

print("5. kind 0")
md = I.metadata_event(owner.profile, "Alex", "https://example.invalid/" + "0" * 64 + ".png")
body = json.loads(md["content"])
check("kind 0 by the profile key, only name/display_name/picture",
      md["kind"] == 0 and md["pubkey"] == I.pub(owner.profile) and set(body) == {"name", "display_name", "picture"})
check("signature verifies", cgm2.verify(md))

print("6. Avatars")
png = I.render_avatar(I.random_avatar(), 256)
check(f"random avatar renders ({len(png)} B PNG at 256 px)", png[:8] == b"\x89PNG\r\n\x1a\n")

vectors = {
    "note": "Test vectors for investigation 14 (spikes/nostr/identity). Keys are public test values, never real accounts.",
    "main_sec": "1" * 64,
    "derived": [{"index": i, "root_sec": I.sec_bytes(I.Account.derived_from(main, i).root).hex(),
                 "profile_sec": I.sec_bytes(I.Account.derived_from(main, i).profile).hex()} for i in (0, 1)],
    "invite": {"secret": "00112233445566778899aabbccddeeff",
               "invite_key_pub": I.pub(I.invite_key(bytes.fromhex("00112233445566778899aabbccddeeff"))),
               "i_pub": I.pub(a0.root),
               "short_link": I.short_link(I.pub(a0.root), bytes.fromhex("00112233445566778899aabbccddeeff"))},
}
(HERE / "vectors.json").write_text(json.dumps(vectors, indent=2) + "\n")
print(f"\n{ok} passed, {fail} failed · vectors → identity/vectors.json")
sys.exit(1 if fail else 0)
