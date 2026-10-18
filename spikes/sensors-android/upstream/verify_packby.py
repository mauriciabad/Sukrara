# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Check the Schnorr proofs in Juggluco's two embedded G7 round packets (ecJPake.cpp packby1/packby2).

Standard library only. Uses the hash Juggluco's mkhash() builds: SHA-256 over (len||G)(len||V)(len||X)(len||party),
points uncompressed (65 bytes), lengths 4-byte big-endian, reduced mod n. Accepts iff G*r + X*c == V.
Run: python3 verify_packby.py
"""
import hashlib
import struct

P = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
A = P - 3
N = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
G = (0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
     0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5)


def add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0] and (p[1] + q[1]) % P == 0:
        return None
    if p == q:
        lam = (3 * p[0] * p[0] + A) * pow(2 * p[1], P - 2, P) % P
    else:
        lam = (q[1] - p[1]) * pow(q[0] - p[0], P - 2, P) % P
    x = (lam * lam - p[0] - q[0]) % P
    return x, (lam * (p[0] - x) - p[1]) % P


def mul(k, p):
    r = None
    while k:
        if k & 1:
            r = add(r, p)
        p = add(p, p)
        k >>= 1
    return r


def enc(p):
    return b"\x04" + p[0].to_bytes(32, "big") + p[1].to_bytes(32, "big")


def challenge(g, v, x, party):
    h = hashlib.sha256()
    for item in (enc(g), enc(v), enc(x), party):
        h.update(struct.pack(">I", len(item)) + item)
    return int.from_bytes(h.digest(), "big") % N


def verify(packet_hex, party):
    b = bytes.fromhex(packet_hex)
    x = (int.from_bytes(b[0:32], "big"), int.from_bytes(b[32:64], "big"))
    v = (int.from_bytes(b[64:96], "big"), int.from_bytes(b[96:128], "big"))
    r = int.from_bytes(b[128:160], "big")
    return add(mul(r, G), mul(challenge(G, v, x, party), x)) == v


PACKBY1 = ("7ccc36e133643a357a1ffba9a2a266246ed504697f4ba03e6b2f4e7b62b4bb88b47e39052e0c11f525f344d6b3b0924f3d33cc25775b8a55"
           "cdc6117a518cff262cc2267b156f5bfc4bbbb0f93bf1f9ce09e17d621398c2b36e0acd772e713a77b14e175ae07b943411918fcfed4800"
           "66a47c06f4c25b01cb20b148c036819f4afed6f7aaf7dfcfbcf0965ae8e11900022e9298b6a546b14769cbfee1c77b9170")
PACKBY2 = ("0b7d5bc678f018f2d0d86ef4b982813e7f501c0d142975efda08e539dbf8e04d0ab6fd611dbcfe1bafd46a2fb806640c75872a2186b747"
           "a6afb8bea721e381bf823e7be9be45757c219f6a9f0f5d2d9de01cd05d3d72c911d0bae22c48ef05717ad3fc962bc47915f983285c4b78"
           "174be1d63151725dec834c4cf0769b44f8367dffb961d2a174bf3f8148707e5dae974adffb3f41c3e378a8c44d8666168ef3")

for name, party in (("b_bytes 37 56 27 67 56 27", bytes.fromhex("375627675627")), ('"server"', b"server")):
    print(f"{name:28} packby1: {verify(PACKBY1, party)!s:5}  packby2: {verify(PACKBY2, party)}")
