#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# The whole verification against a fresh local Nightscout, writing evidence/*.txt. ~8 min (two outage waits).
# Synthetic data only; the phone's zone is pinned to America/New_York so utcOffset is visibly non-zero.
set -e
cd "${0:A:h}/.."
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home JAVA_OPTS=-Duser.timezone=America/New_York
./gradlew -q :core:test :cli:installDist
verify/nightscout.sh down; eval $(verify/nightscout.sh up | sed 's/^/export /')
S=${TMPDIR:-/tmp}/i25-state; rm -rf $S; mkdir -p $S
CLI=cli/build/install/cli/bin/cli; B=http://localhost:1337
nt() { NS_TOKEN= "$@" }   # API secret runs
{
  echo "\$ cli seed --hours 48 --gap 90..60; sync (API v1, API secret); late; sync"
  $CLI seed --dir $S/v1 --hours 48 --gap 90..60
  nt $CLI sync --dir $S/v1 --url $B --battery 81
  nt $CLI sync --dir $S/v1 --url $B
  $CLI late --dir $S/v1 --gap 90..60
  nt $CLI sync --dir $S/v1 --url $B
  curl -sg "$B/api/v1/entries/sgv.json?count=5000&find[date][\$gte]=$(( ($(date +%s) - 48*3600) * 1000 ))&token=$NS_READ_TOKEN" | python3 -I -c '
import json,sys; d=json.load(sys.stdin); ts=sorted(e["date"] for e in d); gaps=[(b-a)//60000 for a,b in zip(ts,ts[1:]) if b-a>60000]
print(len(d),"entries in 48 h; gaps > 1 min:",gaps,"; duplicates:",len(ts)-len(set(ts)))
e=d[0]; print("stored newest:", {k:e.get(k) for k in ("sgv","date","dateString","utcOffset","direction","delta","noise","device","identifier")})'
} 2>&1 | grep -v elapsed | tee evidence/upload-v1.txt
NS_TOKEN= verify/outage.sh $S/v1 2>&1 | grep -v elapsed > evidence/outage-v1.txt
verify/v3-and-deletes.sh $S 2>/dev/null | grep -v -E "elapsed|^\s*$" > evidence/v3-and-deletes.txt
nt $CLI tick --dir $S/v1 >/dev/null; nt $CLI sync --dir $S/v1 --url $B --battery 64 >/dev/null
{ echo "\$ cli check --url $B   ($(date -u +%FT%TZ))"; $CLI check --url $B } 2>&1 | tee evidence/follower-check.txt
{ echo "\$ followers/run.sh"; followers/run.sh } 2>&1 | tee evidence/follower-decoders.txt
verify/roles-and-conflict.sh $S 2>&1 | grep -v elapsed > evidence/roles-and-conflict.txt
