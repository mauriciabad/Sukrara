# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Standard-library-only cryptography for the OGS reference: secp256k1 + BIP-340 Schnorr, ECDH (x only),
HKDF-SHA256 (RFC 5869), ChaCha20 (RFC 8439), NIP-44 v2 and bech32 (NIP-19 nsec/npub).

Written from the specs, not from any CGM app, so it can serve as an independent second implementation. Slow
(pure Python big integers) but fast enough for a follower: ~5 ms per scalar multiplication.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import struct

# ---- secp256k1 -------------------------------------------------------------------------------------------------

P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def _add(p1, p2):
    if p1 is None:
        return p2
    if p2 is None:
        return p1
    x1, y1 = p1
    x2, y2 = p2
    if x1 == x2 and (y1 + y2) % P == 0:
        return None
    if p1 == p2:
        lam = 3 * x1 * x1 * pow(2 * y1, P - 2, P) % P
    else:
        lam = (y2 - y1) * pow(x2 - x1, P - 2, P) % P
    x3 = (lam * lam - x1 - x2) % P
    return x3, (lam * (x1 - x3) - y1) % P


# Jacobian coordinates for the hot path (scalar multiplication).
def _jdouble(p):
    x, y, z = p
    if y == 0:
        return (0, 0, 0)
    s = 4 * x * y * y % P
    m = 3 * x * x % P
    x3 = (m * m - 2 * s) % P
    return x3, (m * (s - x3) - 8 * y ** 4) % P, 2 * y * z % P


def _jadd(p, q):
    if p[2] == 0:
        return q
    if q[2] == 0:
        return p
    x1, y1, z1 = p
    x2, y2, z2 = q
    z1z1, z2z2 = z1 * z1 % P, z2 * z2 % P
    u1, u2 = x1 * z2z2 % P, x2 * z1z1 % P
    s1, s2 = y1 * z2 * z2z2 % P, y2 * z1 * z1z1 % P
    if u1 == u2:
        return _jdouble(p) if s1 == s2 else (0, 0, 0)
    h, r = (u2 - u1) % P, (s2 - s1) % P
    hh = h * h % P
    hhh = h * hh % P
    v = u1 * hh % P
    x3 = (r * r - hhh - 2 * v) % P
    return x3, (r * (v - x3) - s1 * hhh) % P, h * z1 * z2 % P


def mul(k: int, point=G):
    """k·point (affine in, affine out; None = point at infinity)."""
    k %= N
    if k == 0 or point is None:
        return None
    acc, base = (0, 0, 0), (point[0], point[1], 1)
    while k:
        if k & 1:
            acc = _jadd(acc, base)
        base = _jdouble(base)
        k >>= 1
    if acc[2] == 0:
        return None
    zi = pow(acc[2], P - 2, P)
    return acc[0] * zi * zi % P, acc[1] * zi * zi * zi % P


def lift_x(x: int):
    """The point with x and an even y (BIP-340), or None."""
    if x >= P:
        return None
    y2 = (pow(x, 3, P) + 7) % P
    y = pow(y2, (P + 1) // 4, P)
    if y * y % P != y2:
        return None
    return x, y if y % 2 == 0 else P - y


def tagged_hash(tag: str, msg: bytes) -> bytes:
    t = hashlib.sha256(tag.encode()).digest()
    return hashlib.sha256(t + t + msg).digest()


def seckey_ok(sec: bytes) -> bool:
    return len(sec) == 32 and 0 < int.from_bytes(sec, "big") < N


def xonly(sec: bytes) -> bytes:
    """x-only public key (32 bytes) of a secret key."""
    if not seckey_ok(sec):
        raise ValueError("invalid secret key")
    return mul(int.from_bytes(sec, "big"))[0].to_bytes(32, "big")


def schnorr_sign(msg: bytes, sec: bytes, aux: bytes | None = None) -> bytes:
    """BIP-340 signature. `aux` = 32 bytes of auxiliary randomness (zeros gives deterministic test vectors)."""
    aux = os.urandom(32) if aux is None else aux
    d0 = int.from_bytes(sec, "big")
    if not 0 < d0 < N:
        raise ValueError("invalid secret key")
    pk = mul(d0)
    d = d0 if pk[1] % 2 == 0 else N - d0
    t = (d ^ int.from_bytes(tagged_hash("BIP0340/aux", aux), "big")).to_bytes(32, "big")
    k0 = int.from_bytes(tagged_hash("BIP0340/nonce", t + pk[0].to_bytes(32, "big") + msg), "big") % N
    if k0 == 0:
        raise ValueError("nonce is zero")
    R = mul(k0)
    k = k0 if R[1] % 2 == 0 else N - k0
    rx = R[0].to_bytes(32, "big")
    e = int.from_bytes(tagged_hash("BIP0340/challenge", rx + pk[0].to_bytes(32, "big") + msg), "big") % N
    return rx + ((k + e * d) % N).to_bytes(32, "big")


def schnorr_verify(msg: bytes, pub: bytes, sig: bytes) -> bool:
    if len(pub) != 32 or len(sig) != 64:
        return False
    pk = lift_x(int.from_bytes(pub, "big"))
    r, s = int.from_bytes(sig[:32], "big"), int.from_bytes(sig[32:], "big")
    if pk is None or r >= P or s >= N:
        return False
    e = int.from_bytes(tagged_hash("BIP0340/challenge", sig[:32] + pub + msg), "big") % N
    R = _add(mul(s), mul(N - e, pk))
    return R is not None and R[1] % 2 == 0 and R[0] == r


def ecdh_x(sec: bytes, pub: bytes) -> bytes:
    """Unhashed x coordinate of sec·lift_x(pub) — what NIP-44 calls shared_x."""
    pt = lift_x(int.from_bytes(pub, "big"))
    if pt is None or not seckey_ok(sec):
        raise ValueError("invalid key")
    return mul(int.from_bytes(sec, "big"), pt)[0].to_bytes(32, "big")


# ---- HKDF (RFC 5869) -------------------------------------------------------------------------------------------

def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    return hmac.new(salt, ikm, hashlib.sha256).digest()


def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    out, block, i = b"", b"", 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([i]), hashlib.sha256).digest()
        out += block
        i += 1
    return out[:length]


def hkdf(ikm: bytes, salt: bytes, info: bytes, length: int = 32) -> bytes:
    return hkdf_expand(hkdf_extract(salt, ikm), info, length)


# ---- ChaCha20 (RFC 8439) ---------------------------------------------------------------------------------------

def _rotl(v, c):
    return ((v << c) & 0xFFFFFFFF) | (v >> (32 - c))


def _qr(s, a, b, c, d):
    s[a] = (s[a] + s[b]) & 0xFFFFFFFF; s[d] = _rotl(s[d] ^ s[a], 16)
    s[c] = (s[c] + s[d]) & 0xFFFFFFFF; s[b] = _rotl(s[b] ^ s[c], 12)
    s[a] = (s[a] + s[b]) & 0xFFFFFFFF; s[d] = _rotl(s[d] ^ s[a], 8)
    s[c] = (s[c] + s[d]) & 0xFFFFFFFF; s[b] = _rotl(s[b] ^ s[c], 7)


def chacha20(key: bytes, nonce: bytes, data: bytes, counter: int = 0) -> bytes:
    const = (0x61707865, 0x3320646E, 0x79622D32, 0x6B206574)
    k = struct.unpack("<8I", key)
    n = struct.unpack("<3I", nonce)
    out = bytearray()
    for blk in range((len(data) + 63) // 64):
        init = [*const, *k, (counter + blk) & 0xFFFFFFFF, *n]
        s = list(init)
        for _ in range(10):
            _qr(s, 0, 4, 8, 12); _qr(s, 1, 5, 9, 13); _qr(s, 2, 6, 10, 14); _qr(s, 3, 7, 11, 15)
            _qr(s, 0, 5, 10, 15); _qr(s, 1, 6, 11, 12); _qr(s, 2, 7, 8, 13); _qr(s, 3, 4, 9, 14)
        ks = struct.pack("<16I", *((s[i] + init[i]) & 0xFFFFFFFF for i in range(16)))
        chunk = data[blk * 64:(blk + 1) * 64]
        out += bytes(a ^ b for a, b in zip(chunk, ks))
    return bytes(out)


# ---- NIP-44 v2 -------------------------------------------------------------------------------------------------

def nip44_conversation_key(sec: bytes, pub: bytes) -> bytes:
    return hkdf_extract(b"nip44-v2", ecdh_x(sec, pub))


def _padded_len(n: int) -> int:
    if n <= 32:
        return 32
    nxt = 1 << (n - 1).bit_length()
    chunk = 32 if nxt <= 256 else nxt // 8
    return chunk * ((n - 1) // chunk + 1)


def _message_keys(ck: bytes, nonce: bytes):
    k = hkdf_expand(ck, nonce, 76)
    return k[:32], k[32:44], k[44:]


def nip44_encrypt(plaintext: str, ck: bytes, nonce: bytes | None = None) -> str:
    nonce = os.urandom(32) if nonce is None else nonce
    raw = plaintext.encode()
    if not 1 <= len(raw) <= 65535:
        raise ValueError("plaintext length")
    padded = struct.pack(">H", len(raw)) + raw + b"\x00" * (_padded_len(len(raw)) - len(raw))
    ckey, cnonce, hkey = _message_keys(ck, nonce)
    ct = chacha20(ckey, cnonce, padded)
    mac = hmac.new(hkey, nonce + ct, hashlib.sha256).digest()
    return base64.b64encode(b"\x02" + nonce + ct + mac).decode()


def nip44_decrypt(payload: str, ck: bytes) -> str:
    if not payload or payload[0] == "#":
        raise ValueError("unknown encryption version")
    data = base64.b64decode(payload, validate=True)
    if len(data) < 99 or data[0] != 2:
        raise ValueError("unsupported NIP-44 version or length")
    nonce, ct, mac = data[1:33], data[33:-32], data[-32:]
    ckey, cnonce, hkey = _message_keys(ck, nonce)
    if not hmac.compare_digest(hmac.new(hkey, nonce + ct, hashlib.sha256).digest(), mac):
        raise ValueError("invalid MAC")
    padded = chacha20(ckey, cnonce, ct)
    n = struct.unpack(">H", padded[:2])[0]
    if n == 0 or len(padded) != 2 + _padded_len(n):
        raise ValueError("invalid padding")
    return padded[2:2 + n].decode()


# ---- bech32 (NIP-19 nsec / npub) -------------------------------------------------------------------------------

_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def _polymod(values):
    gen = (0x3B6A57B2, 0x26508E6D, 0x1EA119FA, 0x3D4233DD, 0x2A1462B3)
    chk = 1
    for v in values:
        top = chk >> 25
        chk = (chk & 0x1FFFFFF) << 5 ^ v
        for i in range(5):
            chk ^= gen[i] if (top >> i) & 1 else 0
    return chk


def _hrp_expand(hrp):
    return [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp]


def _convert(data, frm, to, pad):
    acc = bits = 0
    out = []
    for v in data:
        acc = (acc << frm) | v
        bits += frm
        while bits >= to:
            bits -= to
            out.append((acc >> bits) & ((1 << to) - 1))
    if pad and bits:
        out.append((acc << (to - bits)) & ((1 << to) - 1))
    elif not pad and (bits >= frm or (acc << (to - bits)) & ((1 << to) - 1)):
        raise ValueError("invalid padding")
    return out


def bech32_encode(hrp: str, data: bytes) -> str:
    d5 = _convert(data, 8, 5, True)
    pm = _polymod(_hrp_expand(hrp) + d5 + [0] * 6) ^ 1
    return hrp + "1" + "".join(_CHARSET[x] for x in d5 + [(pm >> 5 * (5 - i)) & 31 for i in range(6)])


def bech32_decode(s: str) -> tuple[str, bytes]:
    s = s.lower()
    pos = s.rfind("1")
    hrp, d5 = s[:pos], [_CHARSET.index(c) for c in s[pos + 1:]]
    if _polymod(_hrp_expand(hrp) + d5) != 1:
        raise ValueError("bad bech32 checksum")
    return hrp, bytes(_convert(d5[:-6], 5, 8, False))


def nsec_decode(s: str) -> bytes:
    hrp, data = bech32_decode(s)
    if hrp != "nsec" or not seckey_ok(data):
        raise ValueError("not an nsec")
    return data
