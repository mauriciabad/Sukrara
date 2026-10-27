# Sync fixtures — what a follower shows after the wearer's events

Each `*.json` here (written by the protocol chunks, Android `:core:protocol` / `:share` and Mac `SukraraShare`) is
one sequence of events the wearer publishes (protocol v2, [`../PROTOCOL-v2.md`](../PROTOCOL-v2.md)) and the state a
follower must end in. Both follower implementations replay every file and compare.

```json
{
  "v": 1,
  "name": "short id",
  "about": "what the case proves",
  "events": [ { "at": 0, "kind": "reading | treatment | treatment-delete | settings | profile | roster", "payload": {} } ],
  "expect": { "latest": { "t": 0, "sgv": 0 }, "treatments": [], "settings": {}, "linked": true }
}
```

Times are seconds relative to the first event. Payloads are the decrypted event contents exactly as PROTOCOL-v2
defines them (test keys only — `../identity/vectors.json` — never a real identity). The format may grow keys; a new
key is ignored by older readers, a changed meaning bumps `v`.
