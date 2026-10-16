# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline unit checks of the v2 primitives: uv run v2/test_cgm2.py"""
import time

from cgm2 import (RUMOR_TO_FOLLOWER, RUMOR_TO_OWNER, OwnerState, Reading, chunk_event, dec, gift, gk, invite_link,
                  keys, live_event, match_code, new_token, parse_invite, pub, tag, unwrap, verify)

o = OwnerState.create()
F, X = keys(), keys()
now = int(time.time())

ev = live_event(o.D, o.current.pub, Reading(now, 123, -0.4, "test"))
assert verify(ev) and tag(ev, "gk") == o.current.gk
assert dec(o.current.g, pub(o.D), ev["content"])["sgv"] == 123

ch = chunk_event(o.D, o.current.pub, o.sid, now - now % 3600, [Reading(now - now % 3600 + 60 * i, 100 + i, None) for i in range(3)])
assert len(dec(o.current.g, pub(o.D), ch["content"])["r"]) == 3

g = gift(F, pub(o.I), RUMOR_TO_OWNER, {"v": 2, "type": "request", "token": "t", "name": "Mom"})
assert g["kind"] == 1059 and g["pubkey"] not in (pub(F), pub(o.I))          # wrapped by a one-time key
sender, kind, body = unwrap(o.I, g)
assert (sender, kind, body["name"]) == (pub(F), RUMOR_TO_OWNER, "Mom")       # seal signer = authenticated sender
assert unwrap(X, g) is None                                                   # not for X

d = gift(o.I, pub(F), RUMOR_TO_FOLLOWER, {"v": 2, "type": "key", "epoch": 1})
assert unwrap(F, d)[0] == pub(o.I)
forged = gift(X, pub(F), RUMOR_TO_FOLLOWER, {"v": 2, "type": "key", "epoch": 1})
assert unwrap(F, forged)[0] == pub(X) != pub(o.I)                            # follower can tell it's not from I

tok = new_token()
inv = parse_invite(invite_link(pub(o.I), tok, now + 86400, ["wss://a", "wss://b"]))
assert inv["i"] == pub(o.I) and inv["t"] == tok and inv["r"] == ["wss://a", "wss://b"]
assert len(match_code(pub(F), tok)) == 4
print("all v2 primitive checks passed; gift wrap size", len(str(g)), "B; gk", gk(o.current.pub))
