# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Deterministic test vectors for OGS draft 1 (spec/test-vectors/ogs-draft1.json).

Every key is sha256("OGS test vector key: <name>") — public throwaway values that protect nothing. Every NIP-44 nonce
and NIP-59 timestamp is pinned, BIP-340 aux randomness is 32 zero bytes, so `generate()` is byte-for-byte stable and
another implementation can reproduce the events, not only verify them.
"""
from __future__ import annotations

import hashlib
import json

from . import ble, cgms
from . import events as E
from . import model as m
from .follower import Follower
from .nostr import Keys, compact, decrypt, unwrap, verify

NOW = 1792087200                     # 2026-10-15T14:00:00Z, an hour start
SID = hashlib.sha256(b"OGS test vector sid").hexdigest()
AUX = bytes(32)
RELAYS = ["wss://relay.example.org", "wss://nos.lol"]
PROFILE = {"name": "Alex", "avatar": {"v": 1, "c": 0, "s": "cube", "a": 45, "n": 1497950029}}


def K(name: str) -> Keys:
    return Keys(hashlib.sha256(f"OGS test vector key: {name}".encode()).digest())


def nonce(label: str) -> bytes:
    return hashlib.sha256(f"OGS test vector nonce: {label}".encode()).digest()


def pins(label: str, back: int = 1000) -> dict:
    return {"wrap_key": K("wrap " + label), "seal_at": NOW - back, "wrap_at": NOW - 2 * back, "rumor_at": NOW,
            "nonces": (nonce(label + " seal"), nonce(label + " wrap")), "aux": AUX}


def synthetic(t: int) -> m.Reading:
    """A smooth fake curve: 120 ± 40 mg/dL over 3 h. Synthetic, never anyone's glucose."""
    import math
    x = 120 + 40 * math.sin(t / 1800)
    rate = 40 * math.cos(t / 1800) / 30
    return m.Reading(t, round(x), round(rate, 2), "synthetic")


def generate() -> dict:
    I, D, F, W, X = K("identity I"), K("device D"), K("follower F"), K("writer W"), K("attacker X")
    G1, G2 = K("read key G epoch 1"), K("read key G epoch 2")
    keys = {"I": I, "D": D, "F": F, "W": W, "X": X, "G1": G1, "G2": G2}
    secret = hashlib.sha256(b"OGS test vector invite secret").digest()[:16]
    inv_link = "https://sukrara.mauri.app/join#" + m.invite_payload(I.pub, secret)
    inv = m.parse_invite(inv_link)
    hour = NOW - 3600
    sensor = {"ss": NOW - 3 * 86400, "se": NOW - 3 * 86400 + 15 * 86400, "sw": NOW - 3 * 86400 + 3600}
    t_live = NOW - 30
    rd = synthetic(t_live)
    carbs = {"id": "6f1c2b9a-0d3e-4c5f-8a7b-0123456789ab", "k": "carbs", "a": 30, "t": NOW - 600, "n": "",
             "u": (NOW - 590) * 1000, "del": False, "o": "phone"}
    rapid = {"id": "0b9e3f52-7c1d-4e8a-9f60-123456789abc", "k": "rapid", "a": 2.5, "t": NOW - 300, "n": "lunch",
             "u": (NOW - 290) * 1000, "del": False, "o": "follower"}
    ev = {}
    g1, g2 = G1.pub, G2.pub
    n = lambda s: {"nonce": nonce(s), "aux": AUX}   # noqa: E731
    ev["invite"] = E.invite(I, secret, expiry=NOW + 86400, relays=RELAYS, profile=PROFILE, now=NOW, **pins("invite"))
    ev["invite-wrong-identity"] = E.invite(X, secret, expiry=NOW + 86400, relays=RELAYS, profile=PROFILE, now=NOW,
                                           **pins("invite X"))
    ev["access-request"] = E.access_request(F, I.pub, inv.token, {"name": "Mom", "avatar": None}, now=NOW,
                                            **pins("request"))
    ev["key-1"] = E.key_delivery(I, F.pub, sid=SID, epoch=1, g=G1, devices=[D.pub], relays=RELAYS, now=NOW,
                                 **pins("key 1"))
    ev["roster-1"] = E.roster(I, g1, SID, epoch=1, devices=[D.pub], writers=[], relays=RELAYS, profile=PROFILE,
                              at=NOW - 100, **n("roster 1"))
    rows = [synthetic(hour + 60 * i) for i in range(60)]
    ev["chunk"] = E.chunk(D, g1, SID, hour, rows, at=NOW - 60, **n("chunk"))
    ev["live"] = E.live(D, g1, t=rd.t, sgv=rd.sgv, r=rd.rate, dev="synthetic", sensor=sensor, at=NOW - 25,
                        **n("live"))
    ev["sensor-update"] = E.sensor_update(D, g1, sensor, at=NOW - 20, **n("sensor"))
    ev["alert"] = E.alert(D, g1, {"id": "a1b2c3d4e5f60718", "k": "high", "state": "fire", "at": NOW - 20,
                                  "t": rd.t, "sgv": 192, "r": -0.4, "th": 180, "n": 1, "tr": 0}, at=NOW - 20,
                          **n("alert"))
    ev["treatment-owner"] = E.treatment(D, g1, SID, carbs, at=NOW - 590, **n("treatment owner"))
    ev["roster-1-writer"] = E.roster(I, g1, SID, epoch=1, devices=[D.pub], writers=[W.pub], relays=RELAYS,
                                     profile=PROFILE, at=NOW - 50, **n("roster 1 writer"))
    ev["treatment-writer"] = E.treatment(W, g1, SID, rapid, at=NOW - 290, **n("treatment writer"))
    ev["treatment-outsider"] = E.treatment(X, g1, SID, {**rapid, "id": "11111111-2222-4333-8444-555555555555"},
                                           at=NOW - 280, **n("treatment X"))
    ev["treatment-writer-hijack"] = E.treatment(W, g1, SID, {**carbs, "del": True, "u": (NOW - 270) * 1000,
                                                             "o": "follower"}, at=NOW - 270, **n("treatment W hijack"))
    ev["treatment-writer-edit"] = E.treatment(W, g1, SID, {**rapid, "n": "lunch, late", "u": (NOW - 260) * 1000},
                                              at=NOW - 260, **n("treatment W edit"))
    ev["live-outsider"] = E.live(X, g1, t=rd.t, sgv=40, r=None, at=NOW - 15, **n("live X"))
    ev["roster-by-device"] = E.roster(D, g1, SID, epoch=1, devices=[X.pub], writers=[], relays=[], profile=None,
                                      at=NOW - 10, **n("roster D"))
    bad = dict(E.live(D, g1, t=rd.t + 60, sgv=99, r=0.0, at=NOW - 9, **n("live tampered")))
    bad["content"] = bad["content"][:-4] + ("AAAA" if not bad["content"].endswith("AAAA") else "BBBB")
    ev["live-tampered"] = bad
    wrong_d = E.chunk(D, g1, SID, hour - 3600, [synthetic(hour - 3600)], at=NOW - 8, **n("chunk wrong d"))
    from .nostr import sign
    ev["chunk-wrong-d"] = sign(D, m.KIND_DATA, wrong_d["content"],
                               [["d", m.d_chunk(SID, hour)]] + wrong_d["tags"][1:], NOW - 8, AUX)
    ev["key-forged"] = E.key_delivery(X, F.pub, sid=SID, epoch=2, g=X, devices=[X.pub], relays=[], now=NOW,
                                      **pins("key forged"))
    ev["live-epoch-2-early"] = E.live(D, g2, t=NOW - 5, sgv=118, r=-0.2, dev="synthetic", sensor=sensor, at=NOW - 5,
                                      **n("live 2 early"))
    ev["key-2"] = E.key_delivery(I, F.pub, sid=SID, epoch=2, g=G2, devices=[D.pub], relays=RELAYS, now=NOW,
                                 **pins("key 2"))
    ev["roster-2"] = E.roster(I, g2, SID, epoch=2, devices=[D.pub], writers=[W.pub], relays=RELAYS, profile=PROFILE,
                              at=NOW - 4, **n("roster 2"))
    ev["alert-stale"] = E.alert(D, g2, {"id": "0011223344556677", "k": "low", "state": "fire", "at": NOW - 900,
                                        "t": NOW - 905, "sgv": 65, "r": -1.1, "th": 70, "n": 1, "tr": 0},
                                at=NOW - 3, **n("alert stale"))
    ev["live-expired"] = E.live(D, g2, t=NOW - 7200, sgv=101, r=0.1, at=NOW - 7200, **n("live expired"))
    ev["key-other-sid"] = E.key_delivery(I, F.pub, sid=hashlib.sha256(b"other").hexdigest(), epoch=3, g=K("G3"),
                                         devices=[D.pub], relays=[], now=NOW, **pins("key other sid"))
    ev["revoked"] = E.owner_notice(I, F.pub, "revoked", now=NOW, **pins("revoked"))
    ev["live-after-revoke"] = E.live(D, g2, t=NOW, sgv=110, r=0.0, at=NOW, **n("live after revoke"))
    ev["rekey-request"] = E.rekey_request(F, I.pub, m.gk(g2), now=NOW, **pins("rekey"))
    ev["history-request"] = E.history_request(F, I.pub, hour - 3600, now=NOW, **pins("history"))

    scenario = [("key-1", "accepted:key"), ("roster-1", "accepted:roster"), ("chunk", "accepted:chunk"),
                ("live", "accepted:live"), ("live", "ignored:duplicate"), ("sensor-update", "accepted:sensor"),
                ("alert", "accepted:alert"), ("treatment-owner", "accepted:treatment"),
                ("roster-1-writer", "accepted:roster"), ("treatment-writer", "accepted:treatment"),
                ("treatment-outsider", "rejected:author-not-in-roster"),
                ("treatment-writer-hijack", "rejected:writer-not-creator"),
                ("treatment-writer-edit", "accepted:treatment"),
                ("live-outsider", "rejected:author-not-device"),
                ("roster-by-device", "rejected:roster-not-by-identity"), ("live-tampered", "rejected:bad-signature"),
                ("chunk-wrong-d", "rejected:d-mismatch"), ("key-forged", "rejected:wrap-not-from-identity"),
                ("live-epoch-2-early", "ignored:unknown-epoch"), ("key-2", "accepted:key"),
                ("roster-2", "accepted:roster"), ("live-epoch-2-early", "accepted:live"),
                ("alert-stale", "ignored:alert-stale"), ("live-expired", "ignored:expired"),
                ("key-other-sid", "rejected:sid-changed"), ("revoked", "accepted:revoked"),
                ("live-after-revoke", "ignored:revoked")]

    # plaintexts: what each event decrypts to, with the key pair to use (so implementations can check NIP-44 alone)
    plain = {}
    for name, e in ev.items():
        if e["kind"] == m.KIND_WRAP:
            rcpt = {m.invite_key(secret).pub: m.invite_key(secret), F.pub: F, I.pub: I}[[t[1] for t in e["tags"]
                                                                                         if t[0] == "p"][0]]
            rum, sender = unwrap(rcpt, e)
            plain[name] = {"recipient": rcpt.pub, "seal_signer": sender, "rumor": rum}
        elif name != "live-tampered":
            G = G2 if dict((t[0], t[1]) for t in e["tags"]).get("gk") == m.gk(g2) else G1
            plain[name] = {"read_key": G.pub, "author": e["pubkey"], "content": decrypt(G, e["pubkey"],
                                                                                         e["content"])}
    msg = ["EVENT", ev["live"]]
    ble_nonce = nonce("ble hello")[:16].hex()
    frames = {str(mtu): [f.hex() for f in ble.split(7, ble.encode(msg), mtu)] for mtu in (23, 185, 517)}
    return {
        "about": "OGS draft 1 (wire version 2) test vectors. Throwaway keys derived from public strings; synthetic "
                 "glucose. Generated by spec/reference (uv run ogs vectors generate).",
        "now": NOW,
        "keys": {k: {"sec": v.sec.hex(), "pub": v.pub} for k, v in keys.items()},
        "sid": SID,
        "derivations": {
            "H('cgm2:glucose:'+sid)": m.c_glucose(SID), "d_chunk(sid, hour)": {"hour": hour,
                                                                               "d": m.d_chunk(SID, hour)},
            "d_roster(sid)": m.d_roster(SID), "c_treatments(sid)": m.c_treatments(SID),
            "d_treatment(sid, carbs.id)": m.d_treatment(SID, carbs["id"]), "d_backup(I)": m.d_backup(I.pub),
            "gk(G1)": m.gk(g1), "gk(G2)": m.gk(g2), "nsec(G1)": G1.nsec(),
            "invite": {"secret": secret.hex(), "payload": m.invite_payload(I.pub, secret), "link": inv_link,
                       "fingerprint": inv.fp, "token": inv.token, "invite_key_sec": inv.key.sec.hex(),
                       "invite_key_pub": inv.key.pub, "match_code(F)": m.match_code(F.pub, inv.token)},
            "direction(rate)": {str(r): m.direction(r) for r in (3.0, 2.5, 1.0, 0.99, -1.0, -1.5, -2.0, -3.0, None)},
        },
        "events": ev,
        "plaintexts": plain,
        "invite_checks": [{"wrap": "invite", "expect": "ok"},
                          {"wrap": "invite-wrong-identity", "expect": "fingerprint-mismatch"},
                          {"wrap": "invite", "now": NOW + 86400, "expect": "expired"}],
        "scenario": {"follower": "F", "i": I.pub, "now": NOW,
                     "steps": [{"event": e, "expect": x} for e, x in scenario],
                     "final": {"status": "revoked", "epochs": [1, 2], "readings": 62, "treatments": 2, "deleted": 0,
                               "writers": [W.pub], "sensor": sensor, "alerts": 1}},
        "ble": {"message": msg, "msg_id": 7, "frames_by_att_mtu": frames,
                "hello": ble.hello(ble_nonce),
                "hello_id": {"sid": SID, "nonce": ble_nonce, "follower": F.pub, "dev": "mac",
                             "content": ble.id_content(SID, ble_nonce, "mac"),
                             "hello": ble.hello(ble_nonce, ble.id_proof(F, SID, ble_nonce, "mac", bytes(32)))},
                "info_m": {"sid": SID, "nonce": ble_nonce, "m": ble.info_m(SID, ble_nonce)},
                "info": ble.info(SID, ble_nonce),
                "info_pairing": ble.info(SID, ble_nonce, i_pub=I.pub, relays=RELAYS,
                                         pair={"t": inv.token, "x": NOW + 600, "profile": PROFILE})},
        "cgms": [{"ogs": {"sgv": rd.sgv, "t": rd.t, "r": rd.rate, "ss": sensor["ss"], "se": sensor["se"]},
                  "measurement": cgms.from_ogs(rd.sgv, rd.t, rd.rate, sensor["ss"], sensor["se"], NOW).hex()},
                 {"ogs": {"sgv": 192, "t": rd.t, "r": -0.4, "ss": sensor["ss"], "se": sensor["se"], "alert": "high"},
                  "measurement": cgms.from_ogs(192, rd.t, -0.4, sensor["ss"], sensor["se"], NOW, "high").hex()},
                 {"sfloat": {str(v): f"{cgms.sfloat(v):04x}" for v in (120, 54, 400, -1.25, 0.5, 2.0)}}],
    }


def run_scenario(vec: dict) -> list[str]:
    """Feeds the scenario to a fresh follower; returns the list of failures (empty = pass)."""
    keys = {k: Keys.from_hex(v["sec"]) for k, v in vec["keys"].items()}
    sc = vec["scenario"]
    f = Follower(keys[sc["follower"]], sc["i"], vec["derivations"]["invite"]["token"])
    f.status = "requested"
    fails = []
    for i, step in enumerate(sc["steps"]):
        r = f.on_event(vec["events"][step["event"]], sc["now"])
        got = f"{r.verdict}:{r.what}"
        if got != step["expect"]:
            fails.append(f"step {i} {step['event']}: expected {step['expect']}, got {r}")
    fin = sc["final"]
    state = {"status": f.status, "epochs": sorted(n for n, _ in f.epochs.values()), "readings": len(f.readings),
             "treatments": len(f.treatments), "deleted": sum(t.deleted for t in f.treatments.values()),
             "writers": f.writers,
             "sensor": f.sensor and {"ss": f.sensor.ss, "se": f.sensor.se, "sw": f.sensor.sw},
             "alerts": len(f.alerts)}
    for k, v in fin.items():
        if state[k] != v:
            fails.append(f"final {k}: expected {v}, got {state[k]}")
    return fails


def verify_file(vec: dict) -> list[str]:
    fails = []
    for name, e in vec["events"].items():
        if verify(e) != (name != "live-tampered"):
            fails.append(f"signature of {name}")
    keys = {v["pub"]: Keys.from_hex(v["sec"]) for v in vec["keys"].values()}
    inv = vec["derivations"]["invite"]
    keys[inv["invite_key_pub"]] = Keys.from_hex(inv["invite_key_sec"])
    for name, p in vec["plaintexts"].items():
        e = vec["events"][name]
        try:
            if "rumor" in p:
                rum, sender = unwrap(keys[p["recipient"]], e)
                ok = rum == p["rumor"] and sender == p["seal_signer"]
            else:
                ok = decrypt(keys[p["read_key"]], p["author"], e["content"]) == p["content"]
        except Exception as ex:  # noqa: BLE001
            ok = False
            fails.append(f"decrypt {name}: {ex}")
        if not ok:
            fails.append(f"plaintext of {name}")
    parsed = m.parse_invite(inv["link"])
    if (parsed.fp, parsed.token, parsed.key.pub) != (inv["fingerprint"], inv["token"], inv["invite_key_pub"]):
        fails.append("invite derivation")
    for chk in vec["invite_checks"]:
        f = Follower(Keys.from_hex(vec["keys"]["F"]["sec"]))
        try:
            f.open_invite(parsed, vec["events"][chk["wrap"]], chk.get("now", vec["now"]))
            got = "ok"
        except m.Invalid as ex:
            got = "fingerprint-mismatch" if "fingerprint" in str(ex) else "expired" if "expired" in str(ex) else str(ex)
        if got != chk["expect"]:
            fails.append(f"invite check {chk}: got {got}")
    b = vec["ble"]
    im = b["info_m"]
    if ble.info_m(im["sid"], im["nonce"]) != im["m"] or b["info"] != ble.info(im["sid"], im["nonce"]):
        fails.append("ble INFO m")
    if ble.owner_check(b["info"][1], vec["keys"]["I"]["pub"], vec["sid"], [im["nonce"]]) != "ours" or \
            ble.owner_check(b["info"][1], vec["keys"]["I"]["pub"], vec["sid"], ["00" * 16]) != "other" or \
            ble.owner_check(b["info_pairing"][1], vec["keys"]["X"]["pub"], None, []) != "other":
        fails.append("ble owner check")
    hi = b["hello_id"]
    sig = hi["hello"][1]["id"]
    others = [vec["keys"][k]["pub"] for k in ("W", "X")]
    if ble.id_content(hi["sid"], hi["nonce"], hi["dev"]) != hi["content"] or \
            ble.identify(sig, hi["sid"], hi["nonce"], others + [hi["follower"]]) != (hi["follower"], hi["dev"]) or \
            ble.identify(sig, hi["sid"], hi["nonce"], others) is not None or \
            ble.identify(sig, hi["sid"], "00" * 16, [hi["follower"]]) is not None or \
            ble.identify(sig, "0" * 32, hi["nonce"], [hi["follower"]]) is not None:
        fails.append("ble HELLO id")
    re = ble.Reassembler()
    for mtu, frames in vec["ble"]["frames_by_att_mtu"].items():
        out = [re.push(bytes.fromhex(fr)) for fr in frames]
        if out[-1] != vec["ble"]["message"] or any(out[:-1]):
            fails.append(f"ble reassembly at MTU {mtu}")
        if [f.hex() for f in ble.split(vec["ble"]["msg_id"], ble.encode(vec["ble"]["message"]), int(mtu))] != frames:
            fails.append(f"ble split at MTU {mtu}")
    return fails + run_scenario(vec)


def dumps(vec: dict) -> str:
    return json.dumps(vec, indent=1, ensure_ascii=False) + "\n"


__all__ = ["generate", "verify_file", "run_scenario", "dumps", "compact"]
