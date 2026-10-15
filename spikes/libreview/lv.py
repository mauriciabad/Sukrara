#!/usr/bin/env python3
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# SPDX-License-Identifier: GPL-3.0-or-later
"""Reference implementation of SPEC.md §3 + test harness for the LibreView experiments.

  python3 -I lv.py fixture                      regenerate fixture.json (golden payload for both apps)
  python3 -I lv.py login [--set-device]         authenticate; prints status only, never the token
  python3 -I lv.py post FILE.json [--dry-run]   upload the treatments in FILE (a JSON list, SPEC §1)
  python3 -I lv.py cleanup [--dry-run]          re-send every ledger row as deleted

Credentials: email in $LV_EMAIL (or the Mac app's lvEmail default), password in the login keychain:
  security add-generic-password -s dev.spike.libreview -a "$LV_EMAIL" -w
State (DeviceId, cached config/token, ledger) lives in ~/.config/glucobridge-spike/, never in the repo.
"""
import datetime as dt
import hashlib
import json
import os
import subprocess
import sys
import urllib.request
import uuid
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
STATE_DIR = os.path.expanduser("~/.config/glucobridge-spike")
STATE = os.path.join(STATE_DIR, "lv-state.json")
LEDGER = os.path.join(STATE_DIR, "lv-ledger.jsonl")

ASSETS = "https://fsll3.freestyleserver.com/Payloads/Mobile/FSLibre3/Android/Assets/3.3.0/DE.json"
GATEWAY = "FSLibreLink3.Android"
UA = "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 10 Pro Build/BP2A.250605.031)"
CULTURE = "en-ES"
CAPS = ["scheduledContinuousGlucose", "unscheduledContinuousGlucose", "currentGlucose", "insulin", "food"] + [
    "generic-com.abbottdiabetescare.informatics." + c for c in (
        "exercise", "ondemandalarm.low", "ondemandalarm.high", "ondemandalarm.projectedlow",
        "ondemandalarm.projectedhigh", "customnote", "sensorstart", "sensorEnd", "isfGlucoseAlarm",
        "alarmSetting", "bleDisconnect", "bleReconnect", "error")]
TYPE = {"rapid": 1, "long": 3, "carbs": 4, "note": 16}


# ---------------------------------------------------------------- payload (SPEC §3, exact bytes)

def jstr(s):
    out = ['"']
    for ch in s:
        if ch == '"': out.append('\\"')
        elif ch == '\\': out.append('\\\\')
        elif ch == '\n': out.append('\\n')
        elif ch == '\t': out.append('\\t')
        elif ord(ch) < 0x20: out.append('\\u%04x' % ord(ch))
        else: out.append(ch)
    out.append('"')
    return ''.join(out)


def _offset(t):
    off = int(t.utcoffset().total_seconds()) // 60
    sign = '+' if off >= 0 else '-'
    return '%s%02d:%02d' % (sign, abs(off) // 60, abs(off) % 60)


def utc_min(epoch): return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:00Z')
def utc_ms(epoch): return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.000Z')


def local_min(epoch, tz):
    t = dt.datetime.fromtimestamp(epoch, tz)
    return t.strftime('%Y-%m-%dT%H:%M:00') + _offset(t)


def local_ms(epoch, tz):
    t = dt.datetime.fromtimestamp(epoch, tz)
    return t.strftime('%Y-%m-%dT%H:%M:%S.000') + _offset(t)


def record_number(tr, version):
    """One LibreView record per *version* of a treatment: LibreView ignores changes to an existing record number,
    so an edit is a delete of the uploaded version plus a new record (what Juggluco does)."""
    h = hashlib.sha256(("%s:%d" % (tr["id"], version)).encode()).digest()
    return (int.from_bytes(h[:5], "big") << 8) | TYPE[tr["k"]]


def food_type(grams, epoch, tz):
    if grams > 50:
        h = dt.datetime.fromtimestamp(epoch, tz).hour
        if 4 <= h < 11: return "Breakfast"
        if 11 <= h < 16: return "Lunch"
        if 16 <= h < 22: return "Dinner"
    return "Snack"


def treatment_entries(tr, tz):
    """Retract the uploaded version if it is stale (edited or deleted since), then add the current one."""
    out = []
    lv = tr.get("lv", 0)
    if lv and (tr.get("del") or lv != tr["u"]):
        out.append(treatment_entry(tr, record_number(tr, lv), True, tz))
    if not tr.get("del"):
        out.append(treatment_entry(tr, record_number(tr, tr["u"]), False, tz))
    return out


def treatment_entry(tr, rn, deleted, tz):
    dele = '"action":"deleted",' if deleted else ''
    tail = '"linkedGlucoseRecordNumber":"0"},"recordNumber":%d,"timestamp":"%s"}' % (rn, local_min(tr["t"], tz))
    fts = '"factoryTimestamp":"%s",' % utc_min(tr["t"])
    k = tr["k"]
    if k in ("rapid", "long"):
        return '{"insulinType":"%s","units":%.1f,"extendedProperties":{%s%s%s' % (
            "RapidActing" if k == "rapid" else "LongActing", tr["a"], fts, dele, tail)
    if k == "carbs":
        return '{"foodType":"%s","gramsCarbs":%d,"extendedProperties":{%s%s%s' % (
            food_type(tr["a"], tr["t"], tz), round(tr["a"]), fts, dele, tail)
    return '{"type":"com.abbottdiabetescare.informatics.customnote","extendedProperties":{%s"text":%s,%s%s' % (
        fts, jstr(tr.get("n", "")), dele, tail)


def payload(treatments, device_id, token, now, tz):
    trs = sorted(treatments, key=lambda tr: (tr["t"], tr["id"]))
    ins = [e for tr in trs if tr["k"] in ("rapid", "long") for e in treatment_entries(tr, tz)]
    food = [e for tr in trs if tr["k"] == "carbs" for e in treatment_entries(tr, tz)]
    gen = [e for tr in trs if tr["k"] == "note" for e in treatment_entries(tr, tz)]
    return (
        '{"DeviceData":{"deviceSettings":{"factoryConfig":{"UOM":"mg/dL"},"firmwareVersion":"3.3.0",'
        '"miscellaneous":{"selectedLanguage":"%s","valueGlucoseTargetRangeLowInMgPerDl":70,'
        '"valueGlucoseTargetRangeHighInMgPerDl":140,"selectedTimeFormat":"24hr","selectedCarbType":"grams of carbs"},'
        '"timestamp":"%s"},"header":{"device":{"hardwareDescriptor":"GlucoBridge-spike","hardwareName":"GlucoBridge",'
        '"modelName":"com.freestylelibre3.app.de","osType":"Android","osVersion":"36","uniqueIdentifier":"%s"}},'
        '"measurementLog":{"capabilities":[%s],"currentGlucoseEntries":[],"foodEntries":[%s],"genericEntries":[%s],'
        '"insulinEntries":[%s],"scheduledContinuousGlucoseEntries":[],"unscheduledContinuousGlucoseEntries":[]}},'
        '"UserToken":%s,"Domain":"Libreview","GatewayType":"%s"}'
    ) % (CULTURE, local_ms(now, tz), device_id, ','.join(jstr(c) for c in CAPS),
         ','.join(food), ','.join(gen), ','.join(ins), jstr(token), GATEWAY)


# ---------------------------------------------------------------- fixture

FIXTURE_INPUT = {
    "zone": "Asia/Nicosia",
    "now": 1792083600,  # 2026-10-15T17:00:00Z
    "deviceId": "00000000-0000-4000-8000-000000000001",
    "token": "TOKEN",
    "treatments": [
        {"id": "6f1c2b9a-0d3e-4c5f-8a7b-0123456789ab", "k": "rapid", "a": 2.5, "t": 1792067696, "n": "", "u": 1, "del": False, "o": "phone", "lv": 0},
        {"id": "0b0b0b0b-1111-4222-8333-00000000002a", "k": "carbs", "a": 60, "t": 1792067400, "n": "pasta", "u": 2, "del": False, "o": "mac", "lv": 0},
        {"id": "ffffffff-ffff-4fff-bfff-ffffffffffff", "k": "note", "a": 0, "t": 1792069200, "n": "spike \"test\" \\ é\nline2", "u": 3, "del": True, "o": "phone", "lv": 2},
        {"id": "12345678-9abc-4def-8123-456789abcdef", "k": "long", "a": 14, "t": 1791993600, "n": "", "u": 4, "del": False, "o": "mac", "lv": 1},
        {"id": "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "k": "carbs", "a": 20, "t": 1792083000, "n": "", "u": 5, "del": False, "o": "phone", "lv": 0},
        {"id": "dddddddd-0000-4000-8000-000000000000", "k": "rapid", "a": 1, "t": 1792083000, "n": "", "u": 6, "del": True, "o": "mac", "lv": 0},
    ],
}


def cmd_fixture():
    i = FIXTURE_INPUT
    tz = ZoneInfo(i["zone"])
    out = dict(i)
    out["recordNumbers"] = [[tr["id"], v, record_number(tr, v)] for tr in i["treatments"] for v in (1, tr["u"])]
    out["expectedPayload"] = payload(i["treatments"], i["deviceId"], i["token"], i["now"], tz)
    json.loads(out["expectedPayload"])  # must be valid JSON
    with open(os.path.join(HERE, "fixture.json"), "w") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("wrote fixture.json (%d bytes payload)" % len(out["expectedPayload"]))


# ---------------------------------------------------------------- network

def load_state():
    try:
        with open(STATE) as f: return json.load(f)
    except FileNotFoundError:
        return {}


def save_state(s):
    os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
    with open(STATE, "w") as f: json.dump(s, f, indent=1)
    os.chmod(STATE, 0o600)


def http(method, url, body=None, headers=None):
    data = body.encode() if isinstance(body, str) else body
    # Abbott's CDN rejects Python's default User-Agent; send what Android's HttpURLConnection sends.
    req = urllib.request.Request(url, data=data, method=method, headers={"User-Agent": UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")


def config(st):
    if "newYuUrl" not in st:
        code, body = http("GET", ASSETS)
        assert code == 200, "assets %d" % code
        code, body = http("GET", json.loads(body)["Configuration"])
        assert code == 200, "config %d" % code
        c = json.loads(body)
        st["newYuUrl"], st["newYuApiKey"] = c["newYuUrl"], str(c["newYuApiKey"])
    return st["newYuUrl"]


def headers(st, token=""):
    return {"Content-Type": "application/json", "Platform": "Android", "Version": "3.3.0",
            "Abbott-ADC-App-Platform": "Android/16/FSL3/3.3.0.9092", "Accept-Language": CULTURE,
            "x-api-key": st["newYuApiKey"], "x-newyu-token": token}


def credentials():
    email = os.environ.get("LV_EMAIL") or subprocess.run(
        ["defaults", "read", "dev.spike.GlucoseBar", "lvEmail"], capture_output=True, text=True).stdout.strip()
    if not email: sys.exit("set LV_EMAIL or `defaults write dev.spike.GlucoseBar lvEmail …`")
    pw = subprocess.run(["security", "find-generic-password", "-s", "dev.spike.libreview", "-a", email, "-w"],
                        capture_output=True, text=True)
    if pw.returncode: sys.exit("no keychain item dev.spike.libreview for %s" % email)
    return email, pw.stdout.rstrip("\n")


def login(st, set_device=False):
    base = config(st)
    st.setdefault("deviceId", str(uuid.uuid4()))
    email, pw = credentials()
    body = json.dumps({"Culture": CULTURE, "DeviceId": st["deviceId"], "Password": pw, "SetDevice": set_device,
                       "UserName": email, "Domain": "Libreview", "GatewayType": GATEWAY})
    code, resp = http("POST", base + "/api/nisperson/getauthentication", body, headers(st))
    o = json.loads(resp) if resp.startswith("{") else {}
    print("auth: http %d status %s reason %s" % (code, o.get("status"), o.get("reason")))
    if code == 200 and o.get("status") == 0:
        st["token"] = o["result"]["UserToken"]
        save_state(st)
        return True
    save_state(st)
    return False


def post(st, treatments, dry_run=False):
    tz = dt.datetime.now().astimezone().tzinfo
    now = int(dt.datetime.now().timestamp())
    if dry_run:
        print(payload(treatments, st.get("deviceId", "DRY-RUN"), "TOKEN", now, tz))
        return True
    if "token" not in st and not login(st):
        return False
    for attempt in range(2):
        body = payload(treatments, st["deviceId"], st["token"], now, tz)
        code, resp = http("POST", config(st) + "/api/measurements", body, headers(st, st["token"]))
        o = json.loads(resp) if resp.startswith("{") else {}
        print("measurements: http %d status %s reason %s" % (code, o.get("status"), o.get("reason")))
        if code == 200 and o.get("status") == 0:
            os.makedirs(STATE_DIR, mode=0o700, exist_ok=True)
            with open(LEDGER, "a") as f:
                for tr in treatments:
                    f.write(json.dumps({"rn": record_number(tr, tr["u"]), "u": tr["u"], "lv": tr.get("lv", 0), "id": tr["id"], "k": tr["k"], "t": tr["t"], "a": tr["a"], "n": tr.get("n", ""),
                                        "del": bool(tr.get("del")), "dev": st["deviceId"], "at": now}) + "\n")
            return True
        if attempt == 0 and o.get("status") == 20 and "wrongDeviceInToken" in str(o.get("reason")):
            if not login(st): return False
            continue
        return False
    return False


def cmd_cleanup(dry_run):
    rows = {}
    with open(LEDGER) as f:
        for line in f:
            r = json.loads(line)
            rows[r["id"]] = r
    # retract the last uploaded version of every row that is still live in LibreView
    pending = [{"id": r["id"], "k": r["k"], "a": r["a"], "t": r["t"], "n": r["n"], "del": True, "lv": r["u"], "u": r["u"] + 1}
               for r in rows.values() if not r["del"] and "u" in r]
    print("%d ledger rows, %d still live" % (len(rows), len(pending)))
    if pending:
        post(load_state(), pending, dry_run)


def main(argv):
    cmd = argv[1] if len(argv) > 1 else "help"
    dry = "--dry-run" in argv
    if cmd == "fixture": cmd_fixture()
    elif cmd == "login": login(load_state(), "--set-device" in argv)
    elif cmd == "post":
        with open(argv[2]) as f: post(load_state(), json.load(f), dry)
    elif cmd == "cleanup": cmd_cleanup(dry)
    else: print(__doc__)


if __name__ == "__main__":
    main(sys.argv)
