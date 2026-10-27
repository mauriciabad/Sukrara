#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# API v3 with the least-privilege access token, on the same site the v1 run filled: no duplicates across APIs,
# treatment edit/move/delete, and why deletes must be permanent.   usage: verify/v3-and-deletes.sh <state-dir>
cd "${0:A:h}/.."
export JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home
S=${1:?state dir}; CLI=cli/build/install/cli/bin/cli; B=http://localhost:1337
: ${NS_TOKEN:?} ${NS_READ_TOKEN:?}
count() { curl -sg "$B/api/v1/$1&token=$NS_READ_TOKEN" | python3 -I -c 'import json,sys; print(len(json.load(sys.stdin)))' }
day=$(( ($(date +%s) - 86400) * 1000 ))
echo "entries (24 h) before v3: $(count "entries/sgv.json?count=5000&find[date][\$gte]=$day")"
rm -rf $S/v3; mkdir -p $S/v3; cp $S/v1/local.json $S/v3/local.json
$CLI tick --dir $S/v3
NS_TOKEN=$NS_TOKEN $CLI sync --dir $S/v3 --url $B --api v3
echo "entries (24 h) after v3:  $(count "entries/sgv.json?count=5000&find[date][\$gte]=$day")"
id=$($CLI treat --dir $S/v3 add carbs 30 --ago 10 --note snack | awk '{print $2}')
NS_TOKEN=$NS_TOKEN $CLI sync --dir $S/v3 --url $B --api v3 | sed 's/^/  /'
$CLI treat --dir $S/v3 edit $id 35; $CLI treat --dir $S/v3 move $id 20
NS_TOKEN=$NS_TOKEN $CLI sync --dir $S/v3 --url $B --api v3 | sed 's/^/  /'
echo "docs with identifier $id after add+edit+move: $(count "treatments.json?find[identifier]=$id&count=10")"
curl -sg "$B/api/v1/treatments.json?find[identifier]=$id&token=$NS_READ_TOKEN" | python3 -I -c 'import json,sys; d=json.load(sys.stdin)[0]; print("  carbs", d["carbs"], "created_at", d["created_at"])'
# soft delete (what a plain v3 DELETE does) vs. ours (permanent)
JWT=$(curl -s $B/api/v2/authorization/request/$NS_TOKEN | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["token"])')
soft=$($CLI treat --dir $S/v3 add carbs 12 --ago 5 --note soft-delete-probe | awk '{print $2}')
NS_TOKEN=$NS_TOKEN $CLI sync --dir $S/v3 --url $B --api v3 >/dev/null
curl -s -o /dev/null -w "plain v3 DELETE → %{http_code}\n" -X DELETE "$B/api/v3/treatments/$soft" -H "Authorization: Bearer $JWT"
echo "  after a soft delete, v1 readers (LoopFollow/nightguard/web) still see it: $(count "treatments.json?find[identifier]=$soft&count=10") doc(s)"
curl -s -o /dev/null -X DELETE "$B/api/v3/treatments/$soft?permanent=true" -H "Authorization: Bearer $JWT"
$CLI treat --dir $S/v3 delete $id
NS_TOKEN=$NS_TOKEN $CLI sync --dir $S/v3 --url $B --api v3 | sed 's/^/  /'
echo "  after our delete (permanent): $(count "treatments.json?find[identifier]=$id&count=10") doc(s)"
echo "v1 delete path (API secret):"
id1=$($CLI treat --dir $S/v1 add note 0 --note "v1 delete probe" | awk '{print $2}')
NS_TOKEN= NS_API_SECRET=spike-secret-0123 $CLI sync --dir $S/v1 --url $B >/dev/null
echo "  after upload: $(count "treatments.json?find[identifier]=$id1&count=10")"
$CLI treat --dir $S/v1 move $id1 33
NS_TOKEN= NS_API_SECRET=spike-secret-0123 $CLI sync --dir $S/v1 --url $B >/dev/null
echo "  after a move: $(count "treatments.json?find[identifier]=$id1&count=10") doc(s), created_at $(curl -sg "$B/api/v1/treatments.json?find[identifier]=$id1&token=$NS_READ_TOKEN" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)[0]["created_at"])')"
$CLI treat --dir $S/v1 delete $id1
NS_TOKEN= NS_API_SECRET=spike-secret-0123 $CLI sync --dir $S/v1 --url $B | sed 's/^/  /'
echo "  after delete: $(count "treatments.json?find[identifier]=$id1&count=10")"
echo "Nightscout log, v3 identifier mismatch warnings (treatments carry our UUID, like AAPS): $(docker logs i25-ns 2>&1 | grep -c 'Identifier mismatch')"
echo "least privilege: the uploader token may not read profiles → $(curl -s -o /dev/null -w '%{http_code}' "$B/api/v1/profile.json?token=$NS_TOKEN")"
