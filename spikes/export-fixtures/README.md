# Export fixtures (shared by Android and Mac)

The CSV export (docs/22 "In Sukrara: CSV export", docs/FEATURES.md "Data export") as data: `libreview-csv.json` holds
`cases` that both writers must turn into the same bytes.

- Android: `GlucoseCsvTest.kt` (`./gradlew :app:testDebugUnitTest --tests '*GlucoseCsvTest*'` in spikes/showcase-android)
- Mac: `Sukrara -exportselftest` (`swift build && .build/debug/GlucoseBar -exportselftest` in spikes/menubar, run inside the repo)
- CI runs both (`.github/workflows/checks.yml`).

A case: `name`; `zone` (IANA id); `generated`, `from`, `to` (epoch s; rows with `from ≤ t < to`); `mmol` (optional);
`sensor` `{serial, start, end}` or null; `readings` `[{t, sgv, rate?}]`; `treatments` `[{id, k: rapid|long|carbs|note,
a, t, n?, o, del?}]`. `expect` = the file's lines without their CRLF (a line may hold a `\n` inside a quoted note).

`generate.py` is the reference writer that produced `expect`; edit the cases there and regenerate
(`python3 -I generate.py > libreview-csv.json`), then review the diff by hand.

## tidepool-check

Runs Tidepool Uploader's own LibreView driver (`third_party/uploader/lib/drivers/abbott/libreViewDriver.js`, unmodified)
on a file and prints the records it would upload (type, value, units, UTC time). Its `commonFunctions` (API, Rollbar)
and `bows` are stubbed; everything else is the uploader's code.

```sh
mkdir -p /tmp/tp && (cd /tmp/tp && npm init -y && npm i esbuild@0.25.0 babyparse@0.4.6 sundial@1.7.6 async@2.6.4 lodash@4.17.21)
DEPS=/tmp/tp node tidepool-check/build.mjs            # UPLOADER=<clone> if third_party/uploader isn't above this dir
node tidepool-check/out.cjs sukrara-glucose-….csv Europe/Madrid
```
