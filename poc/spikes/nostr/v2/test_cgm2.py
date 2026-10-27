# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline unit checks of the v2 primitives: uv run v2/test_cgm2.py"""
import secrets
import time

from cgm2 import (RUMOR_TO_FOLLOWER, RUMOR_TO_OWNER, Reading, chunk_event, dec, gift, gk, keys, live_event, match_code,
                  pub, tag, unwrap, verify)

I, D, G, F, X = keys(), keys(), keys(), keys(), keys()
sid = secrets.token_hex(32)
now = int(time.time())

ev = live_event(D, pub(G), Reading(now, 123, -0.4, "test"))
assert verify(ev) and tag(ev, "gk") == gk(pub(G))
assert dec(G, pub(D), ev["content"])["sgv"] == 123

ch = chunk_event(D, pub(G), sid, now - now % 3600, [Reading(now - now % 3600 + 60 * i, 100 + i, None) for i in range(3)])
assert len(dec(G, pub(D), ch["content"])["r"]) == 3

g = gift(F, pub(I), RUMOR_TO_OWNER, {"v": 2, "type": "request", "token": "t", "name": "Mom"})
assert g["kind"] == 1059 and g["pubkey"] not in (pub(F), pub(I))            # wrapped by a one-time key
sender, kind, body = unwrap(I, g)
assert (sender, kind, body["name"]) == (pub(F), RUMOR_TO_OWNER, "Mom")       # seal signer = authenticated sender
assert unwrap(X, g) is None                                                   # not for X

d = gift(I, pub(F), RUMOR_TO_FOLLOWER, {"v": 2, "type": "key", "epoch": 1})
assert unwrap(F, d)[0] == pub(I)
forged = gift(X, pub(F), RUMOR_TO_FOLLOWER, {"v": 2, "type": "key", "epoch": 1})
assert unwrap(F, forged)[0] == pub(X) != pub(I)                              # follower can tell it's not from I

code = match_code(pub(F), "token")
assert len(code) == 4 and code.isdigit()
print("all v2 primitive checks passed; gift wrap size", len(str(g)), "B; gk", gk(pub(G)))
