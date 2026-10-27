#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Followed readings stay on the phone; a second uploader on the site pauses ours.   usage: verify/roles-and-conflict.sh <state-dir>
cd "${0:A:h}/.."
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home NS_TOKEN= NS_API_SECRET=spike-secret-0123
S=${1:?state dir}; CLI=cli/build/install/cli/bin/cli; B=http://localhost:1337
H="api-secret: $(printf %s $NS_API_SECRET | shasum | cut -d' ' -f1)"
# alice's readings are the only ones at :30 s past the minute
alice() { curl -sg "$B/api/v1/entries/sgv.json?count=100&token=$NS_READ_TOKEN" | python3 -I -c 'import json,sys; print(sum(e["date"] % 60000 == 30000 for e in json.load(sys.stdin)))' }
echo "1. role guard"
$CLI follow --dir $S/v1 --person alice
$CLI sync --dir $S/v1 --url $B
echo "   alice's readings on the site: $(alice)"
echo "2. another uploader (an xDrip+ posting the same person) shows up"
now=$(( $(date +%s) * 1000 - 30000 ))
curl -s -o /dev/null -w "   xDrip+-style POST → %{http_code}\n" -X POST $B/api/v1/entries -H "$H" -H 'Content-Type: application/json' \
  -d "[{\"type\":\"sgv\",\"sgv\":140,\"date\":$now,\"dateString\":\"$(date -u +%FT%T.000Z)\",\"direction\":\"Flat\",\"device\":\"xDrip-LibreReceiver\"}]"
rm -rf $S/conflict; mkdir -p $S/conflict; cp $S/v1/local.json $S/conflict/local.json
$CLI tick --dir $S/conflict >/dev/null
$CLI sync --dir $S/conflict --url $B
echo "3. the user confirms (Settings → Upload anyway)"
sleep 61; $CLI tick --dir $S/conflict
$CLI sync --dir $S/conflict --url $B --accept-conflict
