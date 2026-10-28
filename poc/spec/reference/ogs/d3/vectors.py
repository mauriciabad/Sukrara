# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""Deterministic test vectors for OGS draft 3 (test-vectors/ogs-draft3.json).

Every key and seed is sha256("OGS3 test vector …") — public throwaway values that protect nothing. NIP-44 nonces,
NIP-59 timestamps and one-time wrap keys are pinned and BIP-340 aux randomness is 32 zero bytes, so `generate()` is
byte-for-byte stable and another implementation can reproduce every event, not only verify it. Glucose is synthetic.
"""
from __future__ import annotations

import hashlib
import json
import math

from ..nostr import Keys, sign, unwrap, verify
from . import bridges as BR
from . import control as ctl
from . import data as D
from .ble import WINDOW, adv_uuid, auth, gatt_uuids, hello, info, pairing_info, split, encode, Reassembler
from .core import (DayKeys, Epoch, EpochSecret, check_record, cover, day_of, lane_of, object_name, open_envelope,
                   record_day, seal, sign_record)
from .member import Member, expiry
from .owner import Owner

NOW = 1792087200                  # 2026-10-15T18:00:00Z
TODAY = day_of(NOW)
AUX = bytes(32)
RELAYS = ["wss://relay.example.org", "wss://nos.lol"]
PROFILE = {"name": "Alex", "avatar": {"v": 1, "c": 0, "s": "cube", "a": 45, "n": 1497950029}}
SESS = "s3ns0r-0001"
TZ = 120


def H(label: str) -> bytes:
    return hashlib.sha256(f"OGS3 test vector {label}".encode()).digest()


def K(name: str) -> Keys:
    return Keys(H(f"key: {name}"))


def nonce(label: str) -> bytes:
    return H(f"nonce: {label}")


def pins(label: str, back: int = 1000) -> dict:
    return {"wrap_key": K("wrap " + label), "seal_at": NOW - back, "wrap_at": NOW - 2 * back, "rumor_at": NOW,
            "nonces": (nonce(label + " seal"), nonce(label + " wrap")), "aux": AUX}


def epoch(label: str, start: int) -> EpochSecret:
    return EpochSecret(H(f"epoch {label} id").hex()[:16], start, H(f"epoch {label} root"))


def synthetic(t: int) -> tuple[int, int]:
    """A smooth fake curve (never anyone's glucose): value mg/dL, rate ×100."""
    return round(120 + 40 * math.sin(t / 1800)), round(40 * math.cos(t / 1800) / 30 * 100)


def block(start: int, n: int, ln: int = 900, step: int = 60, flags: dict | None = None) -> dict:
    rows = []
    for i in range(n):
        v, r = synthetic(start + i * step)
        fl = (flags or {}).get(i, 0)
        v = 400 if fl & D.HI else 40 if fl & D.LO else v
        rows.append([i * step, v, r] + ([fl] if fl else []))
    return {"type": "cgm", "sess": SESS, "start": start, "len": ln, "tz": TZ, "rows": rows}


def owner_fixture() -> tuple[Owner, dict]:
    keys = {n: K(n) for n in ("identity I", "device D", "member F", "member W", "attacker X")}
    keys.update({f"inbox {n}": K(f"inbox {n}") for n in ("F", "W", "admin A", "admin B", "invite")})
    o = Owner(keys["identity I"], [keys["device D"]], relays=RELAYS, profile=PROFILE, now=NOW,
              epoch=epoch("A", TODAY - 3), inbox=keys["inbox admin A"], rng=lambda n: H(f"rng {n}")[:n])
    return o, keys


def generate() -> dict:
    o, k = owner_fixture()
    I, Dv, F, W, X = (k[n] for n in ("identity I", "device D", "member F", "member W", "attacker X"))
    A = o.epochs[o.current]
    B = epoch("B", TODAY)
    secret = H("invite secret")[:ctl.SECRET_BYTES]
    text, ev_invite = o.invite(NOW, offer={"scopes": [], "back": 1}, secret=secret, inbox=k["inbox invite"],
                               hints=["wss://nos.lol"], **pins("invite"))
    inv = ctl.parse_invite(text)
    ev: dict[str, dict] = {"invite": ev_invite}
    ev["invite-wrong-identity"] = ctl.wrap(X, inv.key.pub, json.loads(json.dumps(
        {"type": "invite", "x": NOW + 86400, "inbox": X.pub, "relays": RELAYS, "profile": PROFILE, "offer": {}})),
        now=NOW, expiration=NOW + 86400, **pins("invite X"))

    f = Member(F, k["inbox F"])
    f.open_invite(inv, ev_invite, NOW)
    ev["join-F"] = f.join({"name": "Mom", "avatar": None}, NOW, **pins("join F"))
    ev["join-X-same-token"] = ctl.wrap(X, k["inbox invite"].pub, {"type": "join", "token": inv.token,
                                                                    "inbox": X.pub, "profile": None, "caps": []},
                                       now=NOW, **pins("join X"))
    ev["join-unknown-token"] = ctl.wrap(X, k["inbox invite"].pub, {"type": "join", "token": "A" * 22,
                                                                     "inbox": X.pub, "profile": None, "caps": []},
                                        now=NOW, **pins("join unknown"))
    owner_checks = [("join-F", "accepted:join-pending"), ("join-X-same-token", "rejected:claimed"),
                    ("join-unknown-token", "rejected:unknown-token")]
    for name, _ in owner_checks:
        o.on_wrap(ev[name], NOW)
    o.pending[F.pub]["offer"] = {"scopes": [], "back": 1}
    ev["grant-F-A"] = o.approve(F.pub, NOW, **pins("grant F A"))[0]

    def env(author: Keys, body: dict, at: int, label: str, ep: EpochSecret = A, *, day: int | None = None,
            name: str | None = None, lane: str | None = None, exp: int | None = None) -> dict:
        rec = sign_record(author, body, at, AUX)
        d = record_day(body, at) if day is None else day
        dk = DayKeys(ep.id, d, ep.full().leaf(d))
        return seal(dk.author_keys(author, lane or lane_of(body["type"])), dk, rec,
                    name=name or object_name(body, author.pub), expiration=exp or expiry(body, at), created_at=at,
                    nonce=nonce(label), aux=AUX)

    blk_start = NOW - 1800
    cgm1 = block(blk_start, 10)
    ev["membership-A1"] = env(I, o.membership(), NOW - 600, "membership A1")
    ev["cgm"] = env(Dv, cgm1, NOW - 840, "cgm")
    cgm1b = block(blk_start, 15, flags={14: D.BACKFILLED})
    cgm1b["rows"][3][1] += 2                                   # a correction of one value
    ev["cgm-corrected"] = env(Dv, cgm1b, NOW - 780, "cgm corrected")
    ev["cgm-yesterday"] = env(Dv, block((TODAY - 1) * 86400 + 36000, 15), NOW - 3000, "cgm yesterday")
    ev["cgm-before-grant"] = env(Dv, block((TODAY - 2) * 86400 + 36000, 15), NOW - 3100, "cgm before grant")
    ev["cgm-hi-lo"] = env(Dv, block(NOW - 900, 3, flags={0: D.HI, 2: D.LO | D.UNRELIABLE}), NOW - 700, "cgm hilo")
    sensor = {"type": "sensor", "sess": SESS, "state": "active", "start": NOW - 3 * 86400,
              "warmup_end": NOW - 3 * 86400 + 3600, "end": NOW + 12 * 86400, "model": "synthetic", "interval": 60,
              "rev": (NOW - 690) * 1000}
    ev["sensor"] = env(Dv, sensor, NOW - 690, "sensor")
    ev["device"] = env(Dv, {"type": "device", "label": "Phone", "battery": 81, "charging": False, "link": "ok",
                            "last": NOW - 700, "rev": (NOW - 690) * 1000}, NOW - 690, "device")
    ev["settings"] = env(Dv, {"type": "settings", "unit": "mmol/L", "target": [70, 180], "zone": "Europe/Madrid",
                              "rev": (NOW - 680) * 1000}, NOW - 680, "settings")
    alert = {"type": "alert", "id": "a1b2c3d4e5f60718", "kind": "high", "prio": "time_sensitive",
             "state": "active", "start": NOW - 300, "last": NOW - 300, "n": 1, "v": 192, "r": 0.4, "th": 180,
             "rev": (NOW - 300) * 1000}
    ev["alert"] = env(Dv, alert, NOW - 300, "alert")
    ev["alert-stale"] = env(Dv, {**alert, "id": "0011223344556677", "kind": "low", "prio": "critical",
                                 "start": NOW - 1500, "last": NOW - 1500, "v": 65, "r": -1.1, "th": 70,
                                 "rev": (NOW - 1500) * 1000}, NOW - 290, "alert stale")
    carbs = {"type": "carbs", "id": "6f1c2b9a-0d3e-4c5f-8a7b-0123456789ab", "t": NOW - 600, "g": 30,
             "meal": "lunch", "tz": TZ, "rev": (NOW - 590) * 1000}
    ev["carbs-device"] = env(Dv, carbs, NOW - 590, "carbs device")
    o.members[W.pub] = {"inbox": k["inbox W"].pub, "scopes": ["write"], "from": TODAY - 1, "to": None,
                        "profile": {"name": "Sam"}, "status": "active"}
    o.seq[o.current] += 1
    ev["membership-A2"] = env(I, o.membership(), NOW - 500, "membership A2")
    ins = {"type": "insulin", "id": "0b9e3f52-7c1d-4e8a-9f60-123456789abc", "t": NOW - 400, "u": 2.5,
           "kind": "rapid", "how": "pen", "note": "lunch", "tz": TZ, "rev": (NOW - 390) * 1000}
    ev["insulin-writer"] = env(W, ins, NOW - 390, "insulin writer")
    ev["insulin-writer-edit"] = env(W, {**ins, "u": 3, "rev": (NOW - 380) * 1000}, NOW - 380, "insulin writer edit")
    ev["carbs-writer-hijack"] = env(W, {**carbs, "by": Dv.pub, "del": True, "rev": (NOW - 370) * 1000}, NOW - 370,
                                    "carbs hijack")
    ev["ack-writer"] = env(W, {"type": "ack", "alert": alert["id"], "t": NOW - 290, "note": "on it"}, NOW - 290,
                           "ack writer")
    ev["cgm-by-writer"] = env(W, block(NOW - 900, 2), NOW - 360, "cgm by writer", lane="r")
    ev["insulin-device-edit"] = env(Dv, {**ins, "by": W.pub, "u": 3.5, "rev": (NOW - 350) * 1000}, NOW - 350,
                                    "insulin device edit")
    rec_alert = sign_record(Dv, alert, NOW - 300, AUX)
    dk_today = DayKeys(A.id, TODAY, A.full().leaf(TODAY))
    ev["alert-rewrapped"] = seal(dk_today.author_keys(W, "r"), dk_today, rec_alert, name=object_name(alert, Dv.pub),
                                 expiration=NOW + 86400, created_at=NOW - 340, nonce=nonce("rewrapped"), aux=AUX)
    dk_y = DayKeys(A.id, TODAY - 1, A.full().leaf(TODAY - 1))
    wrong_key = seal(dk_today.author_keys(W, "r"), dk_y, sign_record(W, {**ins, "rev": (NOW - 330) * 1000},
                                                                     NOW - 330, AUX),
                     name=object_name(ins, W.pub), expiration=NOW + 86400, created_at=NOW - 330,
                     nonce=nonce("wrong key"), aux=AUX)
    ev["envelope-wrong-key"] = wrong_key
    bad = dict(env(Dv, {**carbs, "g": 31, "rev": (NOW - 320) * 1000}, NOW - 320, "bad sig"))
    bad["sig"] = bad["sig"][:-2] + ("00" if not bad["sig"].endswith("00") else "11")
    ev["bad-signature"] = bad
    ev["d-mismatch"] = env(Dv, {**carbs, "g": 32, "rev": (NOW - 310) * 1000}, NOW - 310, "d mismatch",
                           name="rec/" + Dv.pub + "/11111111-2222-4333-8444-555555555555")
    ev["wrong-day"] = env(Dv, {**carbs, "id": "22222222-3333-4444-8555-666666666666", "t": NOW - 86400,
                               "rev": (NOW - 300) * 1000}, NOW - 300, "wrong day", day=TODAY)
    ev["outsider"] = sign(X, 31078, "AAAA", [["d", "00" * 16]], NOW - 290, AUX)
    ev["membership-by-device"] = env(Dv, {**o.membership(), "seq": 9}, NOW - 280, "membership by device", lane="r")
    ev["expired"] = env(Dv, block(NOW - 2700, 5), NOW - 2600, "expired", exp=NOW - 1)
    ev["grant-forged"] = ctl.wrap(X, k["inbox F"].pub, {"type": "grant", "current": "00" * 8, "epochs": [],
                                                         "grant": {"scopes": [], "from": TODAY}, "membership": {}},
                                  now=NOW, **pins("grant forged"))

    # removal of W: the last membership of A without W, epoch_end, rotation to epoch B (C§11.2)
    o.members[W.pub]["status"] = "removed"
    o.seq[A.id] += 1
    ev["membership-A3"] = env(I, o.membership(), NOW - 205, "membership A3")
    ev["epoch-end-A"] = env(I, {"type": "epoch_end", "at": NOW - 200}, NOW - 200, "epoch end A")
    o.final[A.id], o.ends[A.id] = o.membership(A.id), TODAY
    # what a removed writer and a stale publisher can still sign in the old epoch: both must be unrecognisable
    ev["insulin-removed-writer"] = env(W, {**ins, "id": "33333333-4444-4555-8666-777777777777", "t": NOW - 198,
                                           "rev": (NOW - 198) * 1000}, NOW - 198, "insulin removed writer")
    ev["cgm-A-after-end"] = env(Dv, block((TODAY + 1) * 86400, 3), NOW - 195, "cgm A after end")
    o._start_epoch(B, k["inbox admin B"])
    o.sync_reader()
    ev["grant-F-B"] = o.grant(F.pub, NOW - 190, **{**pins("grant F B"), "rumor_at": NOW - 190})
    ev["cgm-epoch-B"] = env(Dv, block(NOW - 900, 12), NOW - 150, "cgm epoch B", ep=B)
    ev["cgm-B-by-removed-writer"] = env(W, block(NOW - 900, 2), NOW - 140, "cgm B by W", ep=B, lane="r")
    ev["ended-F"] = ctl.wrap(I, k["inbox F"].pub, {"type": "ended", "reason": "removed"}, now=NOW,
                             **pins("ended F"))
    ev["ended-F-stale"] = ctl.wrap(I, k["inbox F"].pub, {"type": "ended", "reason": "removed"}, now=NOW,
                                   **{**pins("ended F stale"), "rumor_at": NOW - 195})
    ev["cgm-after-ended"] = env(Dv, block(NOW - 900, 13), NOW - 60, "cgm after ended", ep=B)

    scenario = [
        ("grant-F-A", "accepted:grant"), ("membership-A1", "ignored:older"), ("cgm", "accepted:cgm"),
        ("cgm", "ignored:duplicate"), ("cgm-corrected", "accepted:cgm"), ("cgm-yesterday", "accepted:cgm"),
        ("cgm-before-grant", "ignored:unknown-author"), ("cgm-hi-lo", "accepted:cgm"), ("sensor", "accepted:sensor"),
        ("device", "accepted:device"), ("settings", "accepted:settings"), ("alert", "accepted:alert"),
        ("alert-stale", "accepted:alert-silent"), ("carbs-device", "accepted:carbs"),
        ("insulin-writer", "ignored:unknown-author"),
        ("membership-A2", "accepted:membership"), ("insulin-writer", "accepted:insulin"),
        ("insulin-writer-edit", "accepted:insulin"), ("carbs-writer-hijack", "rejected:not-creator"),
        ("ack-writer", "rejected:scope"), ("cgm-by-writer", "rejected:wrong-lane"),
        ("insulin-device-edit", "accepted:insulin"), ("alert-rewrapped", "rejected:rewrapped"),
        ("envelope-wrong-key", "rejected:decrypt"), ("bad-signature", "rejected:bad-signature"),
        ("d-mismatch", "rejected:d-mismatch"), ("wrong-day", "rejected:wrong-day"),
        ("outsider", "ignored:unknown-author"), ("membership-by-device", "rejected:wrong-lane"),
        ("expired", "ignored:expired"), ("grant-forged", "rejected:not-from-identity"),
        ("membership-A3", "accepted:membership"), ("insulin-removed-writer", "ignored:unknown-author"),
        ("epoch-end-A", "accepted:epoch-end"), ("cgm-A-after-end", "ignored:unknown-author"),
        ("cgm-epoch-B", "ignored:unknown-author"), ("grant-F-B", "accepted:grant"),
        ("ended-F-stale", "ignored:ended-before-grant"), ("cgm-epoch-B", "accepted:cgm"),
        ("cgm-B-by-removed-writer", "ignored:unknown-author"), ("ended-F", "accepted:ended"),
        ("cgm-after-ended", "ignored:ended")]

    plain = {}
    lookup = {kk.pub: kk for kk in list(k.values()) + [inv.key]}
    for name, e in ev.items():
        if e["kind"] == 1059:
            p = next(t[1] for t in e["tags"] if t[0] == "p")
            rum, sender = unwrap(lookup[p], e)
            plain[name] = {"recipient": p, "seal_signer": sender, "rumor": rum}

    tree = {"A": {"id": A.id, "start": A.start, "root": A.root.hex()},
            "B": {"id": B.id, "start": B.start, "root": B.root.hex()},
            "covers": [{"epoch": "A", "from": A.start + a, "to": A.start + b,
                        "nodes": [n.to_json() for n in cover(A.root, a, b)]}
                       for a, b in ((0, 1023), (2, 1023), (3, 3), (2, 9), (0, 0))],
            "days": {}}
    for d in (TODAY - 1, TODAY):
        dk = DayKeys(A.id, d, A.full().leaf(d))
        tree["days"][str(d)] = {
            "leaf": dk.leaf.hex(), "content_key": dk.content_key.hex(), "name_key": dk.name_key.hex(),
            "blind_key": dk.blind_key.hex(), "ble_key": dk.ble_key.hex(),
            "authors": {f"{who}/{lane}": dk.author(kk.pub, lane) for who, kk, lane in
                        (("D", Dv, "g"), ("D", Dv, "r"), ("W", W, "r"), ("I", I, "m"))},
            "blinded_secret D/g": dk.author_keys(Dv, "g").sec.hex(),
            "dtags": {nm: dk.dtag(nm) for nm in ("membership", "device", f"sensor/{SESS}",
                                                 f"cgm/{SESS}/{blk_start}/900", f"rec/{Dv.pub}/{carbs['id']}")}}
    pm = Member(F, k["inbox F"], I.pub)
    pm.views = {}
    pm._on_grant(json.loads(plain["grant-F-A"]["rumor"]["content"]), NOW)
    pm.puncture(TODAY)
    tree["puncture"] = {"epoch": "A", "cover_from": TODAY - 1, "keep_from": TODAY,
                        "nodes": [n.to_json() for n in pm.views[A.id].epoch.nodes]}

    msg = ["EVENT", ev["cgm"]]
    bn = nonce("ble hello")[:16].hex()
    bc = nonce("ble challenge")[:16].hex()
    win = NOW // WINDOW
    ble_v = {"day": TODAY, "epoch": "A", "gatt": gatt_uuids(dk_today),
             "adv": {str(w): adv_uuid(dk_today, w) for w in (win - 1, win)},
             "hello": hello(bn, NOW - 86400), "info": info(dk_today, bn, bc), "auth": auth(dk_today, bc, bn),
             "pairing_info": pairing_info(bn, text), "message": msg, "msg_id": 7,
             "frames_by_att_mtu": {str(mtu): [fr.hex() for fr in split(7, encode(msg), mtu)] for mtu in (23, 185, 517)}}

    r0 = cgm1b["rows"][0]
    t0 = cgm1b["start"] + r0[0]
    bridges = {"reading": {"sess": SESS, "t": t0, "v": r0[1], "rate": r0[2] / 100, "flags": 0, "tz": TZ},
               "nightscout_entry": BR.nightscout_entry(SESS, t0, r0[1], r0[2] / 100, 0, TZ),
               "nightscout_entry_hi": BR.nightscout_entry(SESS, NOW - 900, 400, None, D.HI, TZ),
               "fhir_observation": BR.fhir_observation(SESS, t0, r0[1], 0, TZ),
               "fhir_observation_lo": BR.fhir_observation(SESS, NOW - 780, 40, D.LO, TZ),
               "health_connect": BR.health_connect(SESS, t0, r0[1], 0, TZ, 1),
               "tidepool_cbg": BR.tidepool_cbg(SESS, t0, r0[1], r0[2] / 100, 0, TZ, 60),
               "nightscout_treatment_carbs": BR.nightscout_treatment(carbs),
               "nightscout_treatment_insulin": BR.nightscout_treatment(ins),
               "to_mmol": {str(v): D.to_mmol(v) for v in (40, 54, 70, 100, 180, 250, 400)},
               "direction": {str(r): D.direction(r) for r in (3.0, 2.5, 1.0, 0.99, -1.0, -1.5, -2.0, -3.0, None)}}

    return {
        "about": "OGS draft 3 test vectors. Throwaway keys derived from public strings; synthetic glucose. "
                 "Generated by the OGS reference implementation (uv run ogs d3 vectors generate).",
        "now": NOW, "today": TODAY,
        "keys": {n: {"sec": kk.sec.hex(), "pub": kk.pub} for n, kk in k.items()},
        "tree": tree,
        "invite": {"secret": secret.hex(), "text": text, "web": ctl.invite_text(I.pub, secret, [], "https://example.app/join"),
                   "payload": ctl.invite_payload(I.pub, secret), "fingerprint": inv.fp, "token": inv.token,
                   "hints": list(inv.hints), "invite_key_sec": inv.key.sec.hex(), "invite_key_pub": inv.key.pub,
                   "match_code(F)": ctl.match_code(F.pub, inv.token)},
        "invite_checks": [{"wrap": "invite", "expect": "ok"},
                          {"wrap": "invite-wrong-identity", "expect": "fingerprint-mismatch"},
                          {"wrap": "invite", "now": NOW + 86400, "expect": "expired"}],
        "events": ev,
        "plaintexts": plain,
        "owner_checks": [{"wrap": w, "expect": x} for w, x in owner_checks],
        "scenario": {"member": "member F", "inbox": "inbox F", "identity": I.pub, "now": NOW, "back": 1,
                     "steps": [{"event": e, "expect": x} for e, x in scenario],
                     "final": {"status": "ended", "reason": "removed", "epochs": sorted([A.id, B.id]),
                               "readings": 0, "entries": 2, "alerts": 2, "notified": 1,
                               "sensor_state": "active", "settings_unit": "mmol/L"}},
        "ble": ble_v,
        "bridges": bridges,
    }


def run_scenario(vec: dict) -> tuple[list[str], dict]:
    sc = vec["scenario"]
    keys = {n: Keys.from_hex(v["sec"]) for n, v in vec["keys"].items()}
    m = Member(keys[sc["member"]], keys[sc["inbox"]], sc["identity"], back=sc["back"])
    m.status = "joined"
    fails = []
    for i, step in enumerate(sc["steps"]):
        r = m.on_event(vec["events"][step["event"]], sc["now"])
        got = f"{r.verdict}:{r.what}"
        if got != step["expect"]:
            fails.append(f"step {i} {step['event']}: expected {step['expect']}, got {r}")
    state = {"status": m.status, "reason": m.reason, "epochs": sorted(m.views), "readings": len(m.readings),
             "entries": len(m.entries), "alerts": len(m.alerts), "notified": len(m.notified),
             "sensor_state": (m.sensors.get(SESS) or {}).get("state"),
             "settings_unit": (m.settings or {}).get("unit")}
    return fails, state


def verify_file(vec: dict, *, fill_final: bool = False) -> list[str]:
    fails: list[str] = []
    keys = {v["pub"]: Keys.from_hex(v["sec"]) for v in vec["keys"].values()}
    inv = vec["invite"]
    keys[inv["invite_key_pub"]] = Keys.from_hex(inv["invite_key_sec"])
    for name, e in vec["events"].items():
        if verify(e) != (name != "bad-signature"):
            fails.append(f"signature of {name}")
    for name, p in vec["plaintexts"].items():
        try:
            rum, sender = unwrap(keys[p["recipient"]], vec["events"][name])
            if rum != p["rumor"] or sender != p["seal_signer"]:
                fails.append(f"plaintext of {name}")
        except Exception as ex:  # noqa: BLE001
            fails.append(f"unwrap {name}: {ex}")
    # the tree, day material, authors and d tags
    t = vec["tree"]
    eps = {n: EpochSecret(t[n]["id"], t[n]["start"], bytes.fromhex(t[n]["root"])) for n in ("A", "B")}
    for cv in t["covers"]:
        ep = eps[cv["epoch"]]
        got = [n.to_json() for n in cover(ep.root, cv["from"] - ep.start, cv["to"] - ep.start)]
        if got != cv["nodes"]:
            fails.append(f"cover {cv['from']}..{cv['to']}")
        part = Epoch(ep.id, ep.start, [n for n in cover(ep.root, cv["from"] - ep.start, cv["to"] - ep.start)])
        if part.has(cv["from"] - 1) or part.has(cv["to"] + 1) or not part.has(cv["from"]):
            fails.append(f"cover bounds {cv['from']}..{cv['to']}")
    pub = {"D": vec["keys"]["device D"], "W": vec["keys"]["member W"], "I": vec["keys"]["identity I"]}
    for d, dv in t["days"].items():
        dk = DayKeys(eps["A"].id, int(d), eps["A"].full().leaf(int(d)))
        if dk.leaf.hex() != dv["leaf"] or dk.content_key.hex() != dv["content_key"] or \
                dk.name_key.hex() != dv["name_key"] or dk.blind_key.hex() != dv["blind_key"] or \
                dk.ble_key.hex() != dv["ble_key"]:
            fails.append(f"day material {d}")
        for wl, a in dv["authors"].items():
            who, lane = wl.split("/")
            if dk.author(pub[who]["pub"], lane) != a:
                fails.append(f"author {wl} day {d}")
        bs = dk.author_keys(Keys.from_hex(pub["D"]["sec"]), "g")
        if bs.sec.hex() != dv["blinded_secret D/g"] or bs.pub != dv["authors"]["D/g"]:
            fails.append(f"blinded secret day {d}")
        for nm, x in dv["dtags"].items():
            if dk.dtag(nm) != x:
                fails.append(f"dtag {nm}")
    # invite
    p = ctl.parse_invite(inv["text"])
    if (p.fp, p.token, p.key.pub, list(p.hints)) != (inv["fingerprint"], inv["token"], inv["invite_key_pub"],
                                                     inv["hints"]):
        fails.append("invite parse")
    if ctl.parse_invite(inv["web"]).secret.hex() != inv["secret"]:
        fails.append("invite web form")
    if ctl.match_code(vec["keys"]["member F"]["pub"], inv["token"]) != inv["match_code(F)"]:
        fails.append("match code")
    for chk in vec["invite_checks"]:
        m = Member(Keys.from_hex(vec["keys"]["member F"]["sec"]), Keys.from_hex(vec["keys"]["inbox F"]["sec"]))
        try:
            m.open_invite(p, vec["events"][chk["wrap"]], chk.get("now", vec["now"]))
            got = "ok"
        except ValueError as ex:
            got = "fingerprint-mismatch" if "fingerprint" in str(ex) else "expired" if "expired" in str(ex) else str(ex)
        if got != chk["expect"]:
            fails.append(f"invite check {chk}: got {got}")
    # owner side
    o, _ = owner_fixture()
    o.invite(vec["now"], offer={"scopes": [], "back": 1}, secret=bytes.fromhex(inv["secret"]),
             inbox=Keys.from_hex(vec["keys"]["inbox invite"]["sec"]), hints=["wss://nos.lol"], **pins("invite"))
    for chk in vec["owner_checks"]:
        got, _ = o.on_wrap(vec["events"][chk["wrap"]], vec["now"])
        if got != chk["expect"]:
            fails.append(f"owner check {chk['wrap']}: got {got}")
    # BLE
    b = vec["ble"]
    dk = DayKeys(eps["A"].id, b["day"], eps["A"].full().leaf(b["day"]))
    if gatt_uuids(dk) != b["gatt"] or {w: adv_uuid(dk, int(w)) for w in b["adv"]} != b["adv"] or \
            info(dk, b["hello"][1]["nonce"], b["info"][1]["challenge"]) != b["info"] or \
            auth(dk, b["info"][1]["challenge"], b["hello"][1]["nonce"]) != b["auth"]:
        fails.append("ble uuids / INFO and AUTH proofs")
    for mtu, frames in b["frames_by_att_mtu"].items():
        ra = Reassembler()
        out = [ra.push(bytes.fromhex(fr)) for fr in frames]
        if out[-1] != b["message"] or [fr.hex() for fr in split(b["msg_id"], encode(b["message"]), int(mtu))] != frames:
            fails.append(f"ble frames at MTU {mtu}")
    # records inside envelopes open with the day keys and match their d tags
    for name in ("cgm", "carbs-device", "membership-A2", "insulin-writer"):
        e = vec["events"][name]
        dkey = DayKeys(eps["A"].id, vec["today"], eps["A"].full().leaf(vec["today"]))
        body = check_record(open_envelope(e, dkey))
        rec = open_envelope(e, dkey)
        if next(tg[1] for tg in e["tags"] if tg[0] == "d") != dkey.dtag(object_name(body, rec["pubkey"])):
            fails.append(f"envelope {name}")
    sf, state = run_scenario(vec)
    fails += sf
    for kk, v in vec["scenario"]["final"].items():
        if state[kk] != v and not fill_final:
            fails.append(f"final {kk}: expected {v}, got {state[kk]}")
    if fill_final:
        vec["scenario"]["final"] = state
    return fails


def dumps(vec: dict) -> str:
    return json.dumps(vec, indent=1, ensure_ascii=False) + "\n"
