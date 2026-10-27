# poc/ — the frozen PoC of Sukrara

The frozen proof of concept of Sukrara (the showcase app, 0.11.x) and the investigations behind it. **Sukrara V1
lives in [`apps/`](../apps/)**; the cross-platform contracts V1 consumes are in [`contracts/`](../contracts/).

This folder is kept for reference and emergency fixes to the 0.11.x builds people still run. **No new features
land here.**

- `spikes/` — the investigations (one report each in [`docs/NN-*.md`](../docs/)) and the showcase app they fed:
  Android `spikes/showcase-android`, Mac `spikes/menubar` + `spikes/widgets-macos`. Build instructions:
  [`spikes/README.md`](spikes/README.md).
- `spec/` — Open Glucose Stream (OGS): the open sharing standard, its test vectors and the Python reference.

The layout inside `poc/` is the repo root's before the V1 restructure, so every relative path and test fixture
walk-up inside it still resolves. The one thing outside it is `third_party/Juggluco` at the repo root (gitignored,
local only): the PoC's release build fails without it rather than ship an APK without the Libre 3 native core.
`tools/check`, `tools/install.sh` and CI still point at the PoC until V1 replaces it.
