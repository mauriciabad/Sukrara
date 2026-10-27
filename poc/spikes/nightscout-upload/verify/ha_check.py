# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
"""End-to-end: stock Home Assistant + its built-in `nightscout` integration → a real Nightscout our uploader fills
(investigation 25; adapted from spikes/nostr/ha_check.py, which pointed HA at Sukrara's own read API).

  docker run -d --name i25-ha -p 127.0.0.1:8123:8123 ghcr.io/home-assistant/home-assistant:stable
  python3 -I verify/ha_check.py http://host.docker.internal:1337 <api secret>

Onboards HA via its HTTP API (throwaway user), adds the integration through the normal config flow,
then prints the glucose sensor state as Home Assistant sees it.
"""
import json, sys, time, urllib.error, urllib.request

HA = "http://127.0.0.1:8123"
NS_URL = sys.argv[1] if len(sys.argv) > 1 else "http://host.docker.internal:1337"
API_KEY = sys.argv[2] if len(sys.argv) > 2 else ""
CLIENT = HA + "/"


def call(method, path, body=None, token=None, form=False):
    data = None
    headers = {}
    if body is not None:
        if form:
            data = "&".join(f"{k}={v}" for k, v in body.items()).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(HA + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


for _ in range(90):
    try:
        steps = call("GET", "/api/onboarding")
        break
    except Exception:
        time.sleep(2)
else:
    sys.exit("HA did not come up")

if not any(s["step"] == "user" and s["done"] for s in steps):
    code = call("POST", "/api/onboarding/users", {"client_id": CLIENT, "name": "PoC", "username": "poc",
                                                    "password": "poc3-throwaway", "language": "en"})["auth_code"]
else:
    sys.exit("already onboarded — recreate the container")
token = call("POST", "/auth/token", {"grant_type": "authorization_code", "code": code, "client_id": CLIENT},
             form=True)["access_token"]
for step, body in (("core_config", {}), ("analytics", {}), ("integration", {"client_id": CLIENT, "redirect_uri": CLIENT})):
    try:
        call("POST", f"/api/onboarding/{step}", body, token)
    except urllib.error.HTTPError:
        pass
print("onboarded HA", call("GET", "/api/config", token=token)["version"])

flow = call("POST", "/api/config/config_entries/flow", {"handler": "nightscout"}, token)
print("config flow form:", [f["name"] for f in flow.get("data_schema", [])])
res = call("POST", f"/api/config/config_entries/flow/{flow['flow_id']}", {"url": NS_URL, "api_key": API_KEY}, token)
print("config flow result:", res.get("type"), res.get("title"), res.get("errors"))

for _ in range(10):
    time.sleep(6)
    states = [s for s in call("GET", "/api/states", token=token) if "nightscout" in json.dumps(s).lower()
              or s["entity_id"].startswith("sensor.") and s["attributes"].get("unit_of_measurement") in ("mg/dL", "mmol/L")]
    if states:
        break
for s in states:
    print(f"{s['entity_id']} = {s['state']} {s['attributes'].get('unit_of_measurement', '')}  "
          f"attrs={json.dumps({k: v for k, v in s['attributes'].items() if k not in ('friendly_name',)})}")
if not states:
    sys.exit("no glucose sensor entity appeared")
