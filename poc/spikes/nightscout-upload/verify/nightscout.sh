#!/bin/zsh
# CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
# Copyright (C) 2026 Maurici Abad
# SPDX-License-Identifier: GPL-3.0-or-later
#
# A throwaway local Nightscout (the official 15.0.8 image) + MongoDB 7 in Docker, bound to 127.0.0.1 only.
#   verify/nightscout.sh up      start, create the uploader role + tokens, print them
#   verify/nightscout.sh down    stop and remove both containers and the network (all data gone)
set -e
SECRET=spike-secret-0123      # a local test secret, never a real site's
B=http://localhost:1337
case $1 in
up)
  docker network create i25ns >/dev/null 2>&1 || true
  docker run -d --name i25-mongo --network i25ns --memory 512m mongo:7 --wiredTigerCacheSizeGB 0.25 >/dev/null
  docker run -d --name i25-ns --network i25ns -p 127.0.0.1:1337:1337 \
    -e MONGO_CONNECTION=mongodb://i25-mongo:27017/nightscout -e API_SECRET=$SECRET -e DISPLAY_UNITS=mg/dl \
    -e ENABLE="careportal iob cob sage upbat delta direction timeago devicestatus" \
    -e AUTH_DEFAULT_ROLES=denied -e INSECURE_USE_HTTP=true -e TIME_FORMAT=24 -e PORT=1337 \
    nightscout/cgm-remote-monitor:15.0.8 >/dev/null
  until curl -s -o /dev/null -w '%{http_code}' $B/api/v1/status.json | grep -q 401; do sleep 2; done
  H="api-secret: $(printf %s $SECRET | shasum | cut -d' ' -f1)"
  # least privilege for the phone: no admin, no profile/food/settings, no reading of other collections
  curl -s -o /dev/null -X POST $B/api/v2/authorization/roles -H "$H" -H 'Content-Type: application/json' -d '{"name":"sukrara-uploader","permissions":[
    "api:entries:create","api:entries:update","api:entries:read",
    "api:treatments:create","api:treatments:update","api:treatments:read","api:treatments:delete",
    "api:devicestatus:create","api:devicestatus:update","api:status:read"]}'
  for s in '{"name":"sukrara-phone","roles":["sukrara-uploader"]}' '{"name":"followers","roles":["readable"]}'; do
    curl -s -o /dev/null -X POST $B/api/v2/authorization/subjects -H "$H" -H 'Content-Type: application/json' -d $s
  done
  curl -s $B/api/v2/authorization/subjects -H "$H" | python3 -I -c 'import json,sys
for s in json.load(sys.stdin): print({"sukrara-phone":"NS_TOKEN","followers":"NS_READ_TOKEN"}[s["name"]] + "=" + s["accessToken"])'
  echo NS_API_SECRET=$SECRET ;;
down)
  docker rm -f i25-ns i25-mongo >/dev/null 2>&1 || true
  docker network rm i25ns >/dev/null 2>&1 || true ;;
*) echo "usage: $0 up|down"; exit 2 ;;
esac
