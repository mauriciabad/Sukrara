# D1 changed by the owner (overrides PLAN D1, binding for every brief)

> Record of the Sukrara V1 night run (2026-10-27), copied from the run's working file `plan/D1-CHANGE.md` with paths
> made readable. `OPS/…` paths name the run's scratch files (briefs, reviews, issue lists), which are not in
> the repo. Index: [README.md](README.md).

V1 uses NEW ids, no backwards compatibility, NO data migration. The owner is the only user and re-pairs / re-sets up.
- Android applicationId AND namespace root `app.mauri.sukrara`. Mac app bundle `app.mauri.sukrara`, widget
  `app.mauri.sukrara.widget`, App Group `QWUV9H4A3G.app.mauri.sukrara` (same Team QWUV9H4A3G, same Apple identity).
- Clean names everywhere, chosen by V1 (no `.poc`, no `dev.spike.*`, no PoC keys): widget kinds
  `app.mauri.sukrara.widget.{value,graph,combined}`; notification channel ids (e.g. `glucose`, `alert.<kind>`,
  `alert_alarm.<kind>` — chunk 10 owns the registry); Keystore aliases `sukrara.<purpose>`; prefs/defaults keys = the
  keys in `contracts/settings/settings.json` (clean, V1-defined); Mac Keychain service `app.mauri.sukrara`; files are
  V1's own (`files/sensor/pairing.json`, `files/identity/*.bin`, …). Component class names are free (no FQCN
  constraints except that the JNI symbol names in our own `jni.c` must match the Kotlin class we choose).
- Mac paths: `~/Library/Application Support/app.mauri.sukrara/`, logs `~/Library/Logs/app.mauri.sukrara/`, sounds copied
  to `~/Library/Sounds/` with prefix `app.mauri.sukrara.`; URL scheme `sukrara` (see below). The V1 app is
  `~/Applications/Sukrara.app`; `tools/install.sh mac` first moves an existing PoC bundle (bundle id
  `app.mauri.sukrara.poc`) to `~/Applications/Sukrara Showcase.app` (never deletes it).
- Storage is free: Android settings in Preferences **DataStore** (keys from the schema), complex state in typed
  DataStore (kotlinx.serialization), history/treatments/outbox in **Room** (KSP), secrets via one Keystore-backed
  SecureStore. Mac: UserDefaults (schema keys), Keychain (data-protection keychain where it works without
  entitlement trouble, else login keychain), JSON/CSV files under the App Support path above.
- DROP: every migration item (contracts/migration, MigrationTest, snapshot/verify/transforms, Mac Migration, rollback
  APK, "PoC-readable invariant", versionCode > 11053, debug.keystore signing, Credential Manager binding concerns).
- ADD: **first-run setup flow** (P0). Android (chunk 24, now P0; absorbs role choice from 16): welcome → role
  (wearer / follower) → wearer identity: "Restore from recovery code" (paste ncryptsec/nsec exported from the
  showcase's Backup page — keeps existing followers) or "Start fresh" → one permissions checklist (notifications, nearby
  devices, exact alarms, full-screen intent, battery optimisation) → sensor step (hands off to the chunk-18 new-sensor
  flow: Libre 3 NFC start / take over, or Juggluco) → alert thresholds review → home. Every step skippable to a visible
  "Finish setup" home card (complete flows). Follower: join via link / QR / nearby. Mac (chunk 23, follow UI now P0):
  welcome → follow (open/paste invite link, QR image, nearby) → notifications permission → open at login → done.
- **Side by side with the PoC**: both apps install together. They can't both own the same Libre 3 sensor: the V1
  new-sensor flow says "Stop the sensor in Sukrara Showcase first (Settings › Sensor › Stop) or uninstall it", then
  takes over over NFC. If takeover of the PoC-owned sensor fails, the owner keeps the PoC for the current sensor and
  switches at the next sensor change (README + morning report say so). Links: `docs/.well-known/assetlinks.json` gets
  a second entry for `app.mauri.sukrara` (release cert + debug cert fingerprints), the poc entry stays; V1 declares
  the `https://sukrara.mauri.app/join*` App Link (autoVerify) and the `sukrara://` scheme; with both installed Android
  shows a chooser — README: uninstall the showcase after switching. Mac: both register `sukrara:`; install.sh renames
  the PoC bundle and runs `lsregister -f` on V1 so V1 is the handler.
- **Signing**: a dedicated Android release keystore, generated once by chunk 01 (`tools/release/make-keystore.sh`,
  RSA 4096, validity 30y) at `~/.android/sukrara-release.jks` (outside the repo), alias `sukrara`, password stored in
  the macOS Keychain (`security add-generic-password -s sukrara-android-release -a sukrara`), never in git; release
  build reads path from `SUKRARA_KEYSTORE` (default above) and the password from the Keychain/env. Backup procedure in
  docs/20-publishing.md (copy .jks + password into the owner's password manager; losing it = no updates).
  `tools/release/verify-apk.sh` checks the signer = that key's SHA-256 (recorded in `tools/release/android-cert.sha256`).
  Mac keeps the Apple Development identity of team QWUV9H4A3G (no notarization tonight).
- **Version**: 0.90.0, clean monotonic versionCode = major·1 000 000 + minor·1 000 + patch → 0.90.0 = 90000.
  Mac CFBundleVersion = the same integer (dev builds `90000.<yyMMddHHmm>`).
- Protocol: V1 keeps Nostr protocol v2 / identity wire formats from contracts/protocol (a restored identity keeps
  working with existing followers); only local storage formats change.
