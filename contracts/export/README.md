# Export fixtures (shared by Android and Mac)

The CSV export (docs/22 "In Sukrara: CSV export", docs/FEATURES.md "Data export") as data: `libreview-csv.json` holds
`cases` that both writers must turn into the same bytes.

- Android: `GlucoseCsvTest` in `:integrations` (`cd apps/android && ./gradlew :integrations:test`)
- Mac: `CoreTests` (`cd apps/macos && swift test --filter CoreTests`)
- CI runs both (`.github/workflows/checks.yml`).

A case: `name`; `zone` (IANA id); `generated`, `from`, `to` (epoch s; rows with `from ≤ t < to`); `mmol` (optional);
`sensor` `{serial, start, end}` or null; `readings` `[{t, sgv, rate?}]`; `treatments` `[{id, k: rapid|long|carbs|note,
a, t, n?, o, del?}]`. `expect` = the file's lines without their CRLF (a line may hold a `\n` inside a quoted note).

`generate.py` is the reference writer that produced `expect`; edit the cases there and regenerate
(`python3 -I generate.py > libreview-csv.json`), then review the diff by hand.

## tidepool-check

The tool that runs Tidepool Uploader's own LibreView driver on an exported file stays in the frozen PoC
([`poc/spikes/export-fixtures/tidepool-check`](../../poc/spikes/export-fixtures/tidepool-check)).
