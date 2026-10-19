#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# usage: verify/outage.sh <state-dir>   (after verify/nightscout.sh up + cli seed; NS_READ_TOKEN from nightscout.sh)
# outage demo: Nightscout down for ~2.5 min while readings keep arriving; 20 server entries lost meanwhile
cd "${0:A:h}/.."
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home NS_API_SECRET=spike-secret-0123
S=${1:?state dir}
CLI=cli/build/install/cli/bin/cli
ts() { date -u +%H:%M:%S }
echo "$(ts) stop nightscout"; docker stop i25-ns >/dev/null
from=$(( ($(date +%s) - 5*3600) * 1000 )); to=$(( from + 20*60000 ))
docker exec i25-mongo mongosh --quiet nightscout --eval "print('deleted', db.entries.deleteMany({date:{\$gte:$from,\$lt:$to}}).deletedCount, 'server entries 5 h ago')"
for i in 1 2 3; do sleep 50; $CLI tick --dir $S; echo -n "$(ts) "; $CLI sync --dir $S --url http://localhost:1337; done
echo "$(ts) start nightscout"; docker start i25-ns >/dev/null
for i in $(seq 1 40); do curl -s -o /dev/null -w '%{http_code}' localhost:1337/api/v1/status.json | grep -q 401 && break; sleep 2; done
for i in 1 2 3 4 5 6 7 8; do $CLI tick --dir $S >/dev/null; echo -n "$(ts) "; $CLI sync --dir $S --url http://localhost:1337 && break; sleep 30; done
curl -sg "localhost:1337/api/v1/entries/sgv.json?count=5000&find[date][\$gte]=$(( ($(date +%s) - 6*3600) * 1000 ))&token=$NS_READ_TOKEN" | python3 -c 'import json,sys; d=json.load(sys.stdin); ts=sorted(e["date"] for e in d); gaps=[(b-a)//60000 for a,b in zip(ts,ts[1:]) if b-a>60000]; print(len(d),"entries in the last 6 h; gaps >1 min:",gaps,"; duplicates:",len(ts)-len(set(ts)))'
