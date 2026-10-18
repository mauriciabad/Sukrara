# 20 — Publishing Sukrara: Homebrew, notarized Mac builds, Google Play, F-Droid and the Garmin store

*2026-10-18 · research report, nothing shipped · official docs, policy pages and recent announcements, all read on
2026-10-18 (each claim links to a numbered source; the [Sources](#sources) list gives the date seen)*

## Goal

Sukrara ships today as GitHub Release files: an APK signed with the **debug keystore** (`app.mauri.sukrara.poc`) and
a Mac dmg/zip signed with a free **Apple Development** identity, so macOS says *"Apple could not verify … is free of
malware"*. Before the real app (`app.mauri.sukrara`) is built, the user wants to know how to publish it properly on
every platform it runs on:

1. **Mac**: `brew install` for developers and a plain download for everyone else; Developer ID + notarization,
   Mac App Store, in-app updates.
2. **Android**: Google Play (the user's old developer account was **closed for inactivity**: warned 2024-01-09,
   closed 2024-03-14, fee not refunded, "create a new account"), Google's 2026 **developer verification** for
   sideloaded apps, and the open alternatives (F-Droid, IzzyOnDroid, Obtainium, Accrescent).
3. **Garmin**: the Connect IQ Store for the planned watch app.
4. **Cross-cutting**: Libre/LibreView naming, privacy policy and data-safety answers, GPL obligations, costs, effort.

## Method

Web research only (no app changes): Apple, Homebrew, Sparkle, Google Play Console Help, Android Developers, F-Droid,
IzzyOnDroid, Accrescent and Garmin pages, plus news for timelines that official pages only summarise. Third-party
sources are marked as such. The app's own facts come from the repo: `spikes/showcase-android/app/build.gradle.kts`
(`targetSdk 37`, `minSdk 36`, debug signing, Juggluco's GPLv3 security core compiled from a separate checkout),
the manifest (permissions below), `spikes/menubar/bundle.sh` / `dmg.sh` (Developer ID + notarytool already wired),
the last release (APK 18.9 MB, dmg 11.0 MB) and the Play Store (`app.mauri.sukrara` and `.poc` both return 404, i.e.
unused).

## Result

| Platform | Recommended channel(s) | Blocker today | Cost |
|---|---|---|---|
| Mac, non-developers | Notarized dmg from sukrara.mauri.app / GitHub Releases + **Sparkle 2** in-app updates | Paid Apple Developer Program (Developer ID) | 99 USD/year |
| Mac, developers | **Own tap** `mauriciabad/homebrew-tap` → `brew install --cask mauriciabad/tap/sukrara`; later the official cask | Official cask needs Gatekeeper-passing (notarized) builds + notability | 0 |
| Mac App Store | **Not now** | Sandbox + no own updater + medical/third-party-service review risk | (same 99 USD) |
| Android, everyone | **Google Play** (new personal account, closed test first), without the root/LSPosed features | New account: ID verification + 12 testers × 14 days; health/medical declarations; `USE_EXACT_ALARM` justification | 25 USD once |
| Android, power users | **GitHub Releases + Obtainium**, **IzzyOnDroid**; a "full" flavor with root/LSPosed features | Release key; package registered for developer verification before it is enforced globally (2027) | 0 |
| Android, F-Droid main repo | Later, optional | Build from source incl. Juggluco core + rust-nostr; likely *NonFreeNet* anti-feature | 0 |
| Garmin | Connect IQ Store, free app | Developer account; privacy policy; no medical claims | 0 |

**Total: 99 USD per year (Apple) + 25 USD once (Google Play).** Everything else is free.

## Mac

### Homebrew

**The official cask is closed to Sukrara as it is today.** Homebrew 5.0.0 (2025-11-12) deprecated casks without code
signing and deprecated `--no-quarantine` / `--quarantine`, saying it "does not wish to easily provide circumvention
to macOS security features" [[H1]](https://brew.sh/2025/11/12/homebrew-5.0.0/). On **2026-09-01** Homebrew disabled every cask in `homebrew/cask` that fails
Gatekeeper checks [[H2]](https://github.com/Homebrew/brew/issues/20755)[[H3]](https://github.com/ivoronin/TomatoBar/issues/102); casks "must not require System Integrity Protection or Gatekeeper to be disabled or
bypassed" [[H4]](https://docs.brew.sh/Acceptable-Casks). Only the official tap is affected; maintainers point non-qualifying apps to their own taps [[H2]](https://github.com/Homebrew/brew/issues/20755).
The official cask also has a notability bar (shared GitHub-popularity metrics, with room for "strong independent
public interest") [[H4]](https://docs.brew.sh/Acceptable-Casks), which a young project may not meet yet.

**Own tap: works now, and the official cask later.** What it needs:

| Piece | What |
|---|---|
| Repo | `github.com/mauriciabad/homebrew-tap` with `Casks/sukrara.rb` |
| Cask | `version`, `sha256` of the dmg, `url` to the GitHub Release asset, `name`, `desc`, `homepage`, `app "Sukrara.app"`, `depends_on macos: ">= :sequoia"` (or whatever the widgets need), `auto_updates true` once Sparkle is in, `zap trash:` for `~/Library/Logs/Sukrara.log`, the App Group container and preferences |
| `livecheck` | `url :url` + `strategy :github_latest` (versioned asset names required) [[H5]](https://github.com/nelsonlove/homebrew-tap/pull/3) |
| Auto-bump | a release workflow in this repo (or a scheduled one in the tap) computes the sha256 and commits the new version; a scoped token with `contents: write` is enough [[H5]](https://github.com/nelsonlove/homebrew-tap/pull/3) |
| Trust | since Homebrew 6.0.0 (2026-06-11) non-official taps need explicit trust; `brew install --cask mauriciabad/tap/sukrara` (fully qualified) trusts that cask automatically, `brew tap` + short name needs `brew trust` [[H6]](https://brew.sh/7.0.0-migration-guide/)[[H7]](https://github.com/Homebrew/brew/pull/22472) |

Until the builds are notarized, a tap cask could strip quarantine in `postflight` (`xattr -dr com.apple.quarantine`),
as several taps do [[H5]](https://github.com/nelsonlove/homebrew-tap/pull/3), but that is exactly the bypass Homebrew removed upstream and it teaches users to trust an
unverified binary. **Recommendation: publish the tap only together with the first notarized build**, then submit to
`homebrew/cask` once notability is plausible; the tap cask moves over unchanged.

### Developer ID + notarization (the real fix for "could not verify")

- **Apple Developer Program: 99 USD per membership year** (local price shown at enrolment), individuals can enrol
  (2FA Apple Account, own credit card; the seller name is the person's legal name) [[A1]](https://developer.apple.com/programs/)[[A2]](https://developer.apple.com/help/account/membership/program-enrollment/). Developer ID
  certificates and notarization cost nothing extra; a free Personal Team cannot notarize [[A3]](https://developer.apple.com/programs/whats-included/).
- **Our scripts already do the rest** (`spikes/menubar/bundle.sh`, `dmg.sh`, NEXT.md §5): once, create a *Developer
  ID Application* certificate and `xcrun notarytool store-credentials sukrara …`; then
  `SIGN_ID="Developer ID Application: … (TEAMID)" NOTARY_PROFILE=sukrara ./bundle.sh && NOTARY_PROFILE=sukrara ./dmg.sh`
  signs (hardened runtime), notarizes and staples the app, then the dmg, and writes the release zip. The App Group
  is team-prefixed, so no provisioning profile is needed.
- **CI** (optional, later): the same commands in a GitHub Actions macOS job with the certificate as a base64 `.p12`
  secret and an App Store Connect API key for notarytool. Keeping it local is fine at one release a week.
- **Side effects**: the paid team may have a different Team ID than the free personal team, so the App Group
  (`<TEAMID>.app.mauri.sukrara…`) and the code signature change: existing Mac users get one more Keychain prompt,
  and the widgets read a new container (the host rewrites it on the next reading). Combine it with the
  `app.mauri.sukrara` rename so it happens once.

### In-app updates: Sparkle 2

Sparkle 2 (current 2.9 line [[S1]](https://sparkle-project.org/documentation/)): `generate_keys` puts an **EdDSA** private key in the login keychain and prints
`SUPublicEDKey`; `SUFeedURL` points at the appcast; `generate_appcast <folder>` signs the archives and writes the
appcast (+ deltas); updates must be served over HTTPS [[S1]](https://sparkle-project.org/documentation/). Developer ID signing is recommended ("if possible") and
needed for key rotation [[S1]](https://sparkle-project.org/documentation/). **Plan**: appcast at `https://sukrara.mauri.app/appcast.xml` (GitHub Pages is HTTPS),
enclosures pointing at the GitHub Release dmg; the EdDSA private key stays on the user's Mac (export a backup to the
password manager). The cask then sets `auto_updates true` so `brew upgrade` doesn't fight Sparkle.

### Mac App Store: not now

| Requirement | Impact on Sukrara |
|---|---|
| Must be sandboxed (2.4.5 i) [[A4]](https://developer.apple.com/app-store/review/guidelines/) | The menubar host is not sandboxed today. Bluetooth works with `com.apple.security.device.bluetooth` [[A5]](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.device.bluetooth), Keychain and the App Group work in the sandbox; the local Nightscout-style server needs `network.server`. Doable, but a full port + test round |
| No auto-launch without consent (2.4.5 iii) [[A4]](https://developer.apple.com/app-store/review/guidelines/) | Login item must be opt-in (SMAppService toggle) |
| Updates only via the Mac App Store (2.4.5 vii) [[A4]](https://developer.apple.com/app-store/review/guidelines/) | No Sparkle in that build: a second build variant |
| Medical apps get "greater scrutiny"; accuracy must be validated or the app is rejected; regulatory clearance link if any (1.4.1) [[A4]](https://developer.apple.com/app-store/review/guidelines/) | High rejection risk for an unofficial CGM display with alerts |
| Third-party service content needs permission "under the service's terms of use" (5.2.2); no third-party brand in name/icon (4.1c, 5.2.1) [[A4]](https://developer.apple.com/app-store/review/guidelines/) | LibreView upload uses an unofficial API: likely rejection if a reviewer asks for authorization |

**Verdict**: the effort buys little over a notarized dmg + Sparkle, and review risk is high. Revisit only if
there is ever an official Abbott/LibreView agreement or a regulatory status. **Setapp** and similar paid catalogues
don't fit a free GPL app and were not researched further.

## Android

### Google Play: a brand-new personal account

**The old account is gone.** Inactive accounts (created more than a year ago and never submitted an app, or all apps
under 1,000 lifetime installs plus no verified contacts / no Console use) are closed and the fee is not refunded
[[G1]](https://support.google.com/googleplay/android-developer/answer/11605267). Closure for inactivity is not a termination: Google's 2021 announcement said closed developers "can still open
a new account" but cannot reactivate or migrate the old one [[G2]](https://www.xda-developers.com/google-play-policy-crack-down-on-dormant-accounts/). No official page says whether the **same Google
account/e-mail** can be reused; one third-party guide suggests a fresh e-mail may be needed [[G3]](https://mrhamo.com/blog/reinstate-google-play-developer-account-closed-inactivity/). The one-account-
per-person rule is about simultaneous accounts. **Plan**: try registering with the user's current Google account;
if Play Console refuses or only shows the closed account, use a new Google account (e.g. `play@mauri.app` as a
Workspace/Gmail login). An appeal to reinstate is possible but unlikely to help for an account with no live apps [[G3]](https://mrhamo.com/blog/reinstate-google-play-developer-account-closed-inactivity/).

**Package names**: Play package names "are unique and permanent … can't be deleted or re-used" [[G4]](https://support.google.com/googleplay/android-developer/answer/9859152). Anything the old
account ever uploaded stays blocked forever (the user should check their old package names, but none can be
`app.mauri.*`: the domain and the Sukrara name are from 2026). `app.mauri.sukrara` and `app.mauri.sukrara.poc` are
unused on Play today (both 404, checked 2026-10-18). **Never upload `.poc` to Play** (even a closed test would burn
the name); the first upload should be `app.mauri.sukrara`.

**New-account requirements**

| | Personal (recommended) | Organization |
|---|---|---|
| Fee | 25 USD once [[V2]](https://developer.android.com/developer-verification/guides/faq) | 25 USD once |
| Identity | legal name + address from the Google Payments profile, **verified before publishing**; contact e-mail + phone verified by OTP; Play shows name, country and developer e-mail (full address only if monetized) [[G5]](https://support.google.com/googleplay/android-developer/answer/13628312) | D-U-N-S number required, organization name/address/phone shown publicly [[G5]](https://support.google.com/googleplay/android-developer/answer/13628312) |
| Testing before production | **closed test with ≥ 12 testers opted in continuously for 14 days**, then apply for production access (questions about the app and the test) [[G6]](https://support.google.com/googleplay/android-developer/answer/14151465); open testing unlocks only after that [[G7]](https://www.choicely.com/blog/google-play-12-tester-rule) | Not required (third-party summaries [[G7]](https://www.choicely.com/blog/google-play-12-tester-rule); the official page only speaks about personal accounts created after 2023-11-13 [[G6]](https://support.google.com/googleplay/android-developer/answer/14151465)) |
| Fits the user? | Yes | Only with a registered legal entity; not worth creating one for this |

The 12 × 14 rule applies (account created after 2023-11-13 [[G6]](https://support.google.com/googleplay/android-developer/answer/14151465)); the old account's age doesn't carry over. Count
about **3 weeks** from the first closed-test release to production [[G7]](https://www.choicely.com/blog/google-play-12-tester-rule). Testers: the user, family/followers who
already use Sukrara, the Garmin/diabetes friends — 12 Google accounts that stay opted in (use a Google Group as the
tester list so nobody drops out by accident).

**Policies that matter for this app**

| Topic | Rule | What Sukrara must do |
|---|---|---|
| Target API | New apps/updates must target **API 36+** since 2026-08-31 [[G8]](https://support.google.com/googleplay/android-developer/answer/11926878) | Already `targetSdk 37` |
| Health apps declaration | Every health/medical app fills the form; categories include *Diseases and Conditions Management* and **Medical Device Apps**, the latter including "any software interfacing with medical device hardware"; EEA apps regulated under MDR/IVDR must show device name, manufacturer, IFU, UDI-DI [[G9]](https://support.google.com/googleplay/android-developer/answer/14738291) | Declare *Diseases and Conditions Management*. Whether it must also be *Medical Device Apps* is the key risk (below) |
| Health content | Non-regulated health apps must state in the description that the app is "not a medical device and does not diagnose, treat, cure, or prevent any medical condition"; external hardware must be disclosed; a privacy policy linked in Console **and in the app**, public, not a PDF [[G10]](https://support.google.com/googleplay/android-developer/answer/16679511) | Disclaimer + "requires a FreeStyle Libre 3 sensor" in the listing; privacy page on the site; link in Settings → About |
| `USE_EXACT_ALARM` | Only for apps whose **core** function is an alarm/timer or calendar; everyone else should use `SCHEDULE_EXACT_ALARM` [[G11]](https://support.google.com/googleplay/android-developer/answer/16558241) | Glucose alarms are arguably alarm-clock-like and Juggluco ships alarms on Play, but expect a declaration and possible rejection. Safer: switch the Play build to `SCHEDULE_EXACT_ALARM` + `canScheduleExactAlarms()` prompt (the alert timing doesn't need more) |
| Foreground service | Each FGS type declared in App content with description, user impact and a **demo video** [[G12]](https://support.google.com/googleplay/android-developer/answer/13392821) | `connectedDevice` (BLE to sensor/followers): record the video (start, notification, stop) |
| Device and Network Abuse | No interfering with other apps / the system; **no self-updating outside Play**, no downloading executable code [[G13]](https://support.google.com/googleplay/android-developer/answer/16559646) | Play flavor without the LSPosed/SystemUI hook, lock-screen widget hook, root status bar, `DUMP`; and without the in-app GitHub update check (`SettingsScreens.kt` calls `api.github.com/…/releases/latest`) |
| Data safety | Required, with a privacy policy, even if nothing is collected; E2E-encrypted data unreadable by the developer is not "collected"; a user-initiated transfer to a third party is not "sharing" [[G14]](https://support.google.com/googleplay/android-developer/answer/10787469) | Nostr readings: E2E encrypted → not collected (relays see public keys, timestamps, IP). LibreView: user-initiated upload of glucose + treatments + account credentials to Abbott → disclose as health data sent at the user's request. BLE/NFC: on-device. No analytics, no ads |
| Signing | New apps are enrolled in **Play App Signing**; the developer may "Provide a copy of your app signing key" instead of a Google-generated one, before the first open/production release [[G15]](https://support.google.com/googleplay/android-developer/answer/9842756) | Upload **our own** release key so GitHub/IzzyOnDroid/F-Droid (reproducible) builds share one signature with Play |

**Is it a medical device?** This is the real risk, not a form field.
- **EU** (the user is in the EEA): software that "drives or influences the use of a device" falls in the same class as
  that device (MDR Annex VIII rule 3.3, MDCG 2019-11) [[R1]](https://health.ec.europa.eu/document/download/b45335c5-1679-4c71-a91c-fc7a4d37f12b_en?filename=mdcg_2019_11_en.pdf). Reading a Libre 3 directly, showing values and alarming
  is close to being the CGM's primary display.
- **US**: FDA's *CGM secondary display* (21 CFR 862.1350, product code PJT) is class II, 510(k)-exempt, for software
  that shows CGM data to a follower; special controls include data-tampering protection and a "no dosing decisions"
  warning [[R2]](https://www.ecfr.gov/current/title-21/chapter-I/subchapter-H/part-862/subpart-B/section-862.1350)[[R3]](https://www.accessdata.fda.gov/scripts/cdrh/cfdocs/cfpcd/classification.cfm?id=PJT). Software that reads the sensor itself is not covered by that exemption (our inference, not a
  ruling).
- **Precedent**: Juggluco (`tk.glucodata`, reads Libre 2/3, Dexcom, alarms, uploads to LibreView) is on Play today,
  100K+ installs, updated 2026-09-20 [[P1]](https://play.google.com/store/apps/details?id=tk.glucodata); a forum post says it was pulled once after an Abbott complaint and later
  returned (second-hand) [[P2]](https://forum.fudiabetes.org/t/juggluco-back-on-play-store/15038). xDrip+ is distributed outside Play (GitHub) [[P3]](https://forum.fudiabetes.org/t/upgrade-to-libre-3-from-libre-2-using-xdrip-on-android/14774).
- **Consequence**: publish as "not a medical device", no dosing language in the listing, keep the badge texts
  (*Eat carbs*, *Take insulin*) out of screenshots or soften them in the Play build, and accept that Play (or Abbott)
  can remove the app; the sideload channel is the fallback. A lawyer's opinion is the only real answer for the final
  app; not needed for an alpha.

### Android developer verification (sideloading)

| Date | What |
|---|---|
| 2026-03-30 | Verification open to all developers in Play Console and the new **Android Developer Console** (for apps distributed only outside Play) [[V1]](https://developer.android.com/developer-verification)[[V3]](https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console) |
| 2026-08 | **Limited distribution** accounts (free, no ID, **up to 20 devices**, for students/hobbyists), the developer APIs, and the **advanced flow** launch [[V1]](https://developer.android.com/developer-verification)[[V2]](https://developer.android.com/developer-verification/guides/faq) |
| 2026-09-30 | Enforced on certified devices (Android 7+) in **Brazil, Indonesia, Singapore, Thailand**, initially only for installs from the participating stores [[V1]](https://developer.android.com/developer-verification)[[V2]](https://developer.android.com/developer-verification/guides/faq)[[V4]](https://techaiwire.com/articles/android-developer-verification-enforcement-four-countries/) |
| 2027 | Global rollout for all apps on certified devices [[V1]](https://developer.android.com/developer-verification) |

How it affects GitHub-APK distribution once it reaches Spain (2027): the APK installs normally only if its package is
registered by a verified developer with the APK's signing-key fingerprint [[V2]](https://developer.android.com/developer-verification/guides/faq)[[V5]](https://developer.android.com/developer-verification/guides/open-source-app-registration). **ADB installs are exempt**
[[V2]](https://developer.android.com/developer-verification/guides/faq). Otherwise users need the **advanced flow**: developer options, a one-day wait, then biometric/PIN, "Install
Anyway", enabled for 7 days or indefinitely; with it disabled, updates to unregistered apps fail [[V2]](https://developer.android.com/developer-verification/guides/faq). If a package
name is already registered by someone else, the claim needs extra review [[V2]](https://developer.android.com/developer-verification/guides/faq). Losing the signing key means the
package can't be registered [[V2]](https://developer.android.com/developer-verification/guides/faq). Registration is done in Play Console too ("the single place … including for
apps distributed outside of Play"), so a Play account covers it; F-Droid-signed builds are handled by adding the
platform's key fingerprint next to ours [[V5]](https://developer.android.com/developer-verification/guides/open-source-app-registration).

**For Sukrara**: the full (root) flavor and the Play flavor should be **one package, one key**, registered in the
Play Console account; the limited-distribution tier (20 devices) is too small for public sideloading and isn't
needed once the Play account exists. F-Droid calls the programme an existential threat and won't register apps on
its developers' behalf [[V4]](https://techaiwire.com/articles/android-developer-verification-enforcement-four-countries/)[[F3]](https://f-droid.org/docs/Anti-Features/); that's F-Droid's problem, not ours, as long as we register our own key.

### Moving off the debug key

The debug keystore can't be used anywhere public (IzzyOnDroid and Accrescent refuse debug-signed APKs [[F4]](https://izzyondroid.org/docs/general/AppInclusionPolicy/)[[F6]](https://accrescent.app/docs/guide/appendix/requirements/)) and
Play requires an upload key. The plan:

1. Generate a **release keystore** (RSA 4096 or EC P-256) on the user's Mac; back it up (password manager + an
   offline copy). It is the identity of the app forever (developer verification + every channel).
2. Use it for the new package **`app.mauri.sukrara`**. A new package installs **side by side** with
   `app.mauri.sukrara.poc`, so there is no signature clash: nothing is overwritten, but nothing carries over either.
3. Migration for existing alpha users: in the last `.poc` release, show "Sukrara moved: install the new app, then
   uninstall this one". What must carry over: the Nostr device/read keys (already restorable from the password
   manager, investigation 06), settings (add a settings export/import), the LibreView login (re-enter), the sensor
   (re-pair over NFC, works since 0.10.5; force-stop the old app first, like Juggluco). History comes back from the
   followers' backfill and LibreView.
4. (Alternative if the package were kept: APK Signature Scheme v3 key rotation can move an install from the debug key
   to a new key in place. Not worth it given the rename.)

### Alternatives to Play

| Channel | Fit | Requirements / notes |
|---|---|---|
| **GitHub Releases + Obtainium** | Now | Obtainium tracks GitHub releases and (since 1.7.0, 2026-10-17) verifies the APK signing certificate before installing [[F1]](https://github.com/ImranR98/Obtainium/releases). Needs only versioned, release-signed APKs. Add an "Add to Obtainium" link on the site |
| **IzzyOnDroid** | Next, cheap | FOSS licence, aimed at end users, APK **< 30 MB** attached to a tagged release (Sukrara: 19 MB), tag = versionName/versionCode, Fastlane metadata (descriptions, icon, screenshots), **release-signed, not debuggable** [[F4]](https://izzyondroid.org/docs/general/AppInclusionPolicy/). Reproducible builds optional but verified when possible [[F4]](https://izzyondroid.org/docs/general/AppInclusionPolicy/). Must not download executables without consent |
| **F-Droid main repo** | Later | FLOSS only, built by F-Droid from source with a free toolchain; no Play Services/trackers [[F2]](https://f-droid.org/docs/Inclusion_Policy/) (Sukrara has none: no `gms`/Firebase in the build). Hard parts: the Juggluco security core comes from a separate checkout (make it a pinned submodule), rust-nostr (`nostr-sdk`) and zxing-cpp native libs must build from source. Likely anti-feature **NonFreeNet** (LibreView upload) [[F3]](https://f-droid.org/docs/Anti-Features/); root features have no anti-feature. Use reproducible builds so F-Droid ships **our** signature (no key split) |
| **Own F-Droid repo** | Optional | `fdroidserver` repo on GitHub Pages (`sukrara.mauri.app/fdroid/repo`); easy but adds little over Obtainium + IzzyOnDroid |
| **Accrescent** | No | Sign-up allowlisted only; the project says new submissions are unlikely to be accepted for now (funding) [[F5]](https://accrescent.app/blog/posts/the-future-of-accrescent/)[[F6]](https://accrescent.app/docs/guide/appendix/requirements/) |

### Flavors

| Flavor | Package | Channel | Contents |
|---|---|---|---|
| `play` | `app.mauri.sukrara` | Google Play | everything except LSPosed module/hooks, root status bar, `DUMP`, in-app GitHub update check; `SCHEDULE_EXACT_ALARM` instead of `USE_EXACT_ALARM` |
| `full` | `app.mauri.sukrara` (same key) | GitHub/Obtainium, IzzyOnDroid, F-Droid | everything, including root/LSPosed features and the update check |

Same package and key means a user can switch channels without reinstalling, but Play will "update" a full install
to the play build (losing root features) if Play auto-update owns it. Alternative: `app.mauri.sukrara.full` as its
own package (both installable, but two registrations and a confusing choice). **Recommendation: same package;
document that root users install from GitHub/Obtainium and turn off Play updates for Sukrara.**

## Garmin

The planned own watch app (watch face / data field / app, investigation 08) goes to the **Connect IQ Store**:

- Garmin developer account (18+, accurate registration info), **no fee** for free apps; paid apps go through the
  Merchant Service with an annual fee [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html).
- Upload the `.iq` from *Monkey C: Export Project* with every supported device in the manifest; Garmin reviews it
  before it appears in the store and notifies on approval; it can refuse or remove apps at its discretion [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html)[[C2]](https://developer.garmin.com/connect-iq/submit-an-app/).
- **Privacy policy required** if the app collects data; no keeping user data longer than needed [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html).
- **Medical**: comply with FDA and other laws, and **must not seek regulatory permissions or make determinations that
  could make Garmin's products regulated** [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html) — so the listing must be a display/convenience app with a "not a
  medical device" statement, never "alarm" or "diagnosis" claims. Precedent: CGM watch faces fed by
  xDrip/Juggluco servers are in the store (investigation 08; Kerfstok, CGM+).
- No Garmin marks in the app's marketing without consent [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html). The `Communications` permission (web requests to the
  phone's local server) is shown on the store page.
- Effort: small once the app exists (screenshots for 2–3 device sizes, description, privacy link).

## Web and update channels

- **sukrara.mauri.app as the install hub**: one *Download* page that detects the platform: Play badge, "APK /
  Obtainium / IzzyOnDroid" for power users, notarized dmg, `brew install --cask mauriciabad/tap/sukrara`, Garmin
  store link. Keep the README buttons pointing at the same assets (the `website.yml` link rewriting already exists).
- **Channels**: `stable` (Play production, Sparkle `appcast.xml`, cask) and `beta` (Play closed/open test, Sparkle
  `appcast-beta.xml` via `sparkle:channel`, GitHub pre-releases). The closed test needed for production access
  becomes the permanent beta channel.
- **Privacy page** `sukrara.mauri.app/privacy` (HTML, not PDF [[G10]](https://support.google.com/googleplay/android-developer/answer/16679511)): what stays on the device, what goes to Nostr
  relays (E2E-encrypted events, public keys, timing, IP), to LibreView (only when the user signs in; glucose,
  treatments, credentials, to Abbott), to Blossom/relays (none in 0.11), crash data (none), contact address. The same
  page serves Play, Garmin and the Mac app.

## Cross-cutting

**Names and trademarks.** Abbott claims "Libre", the butterfly logo, the sensor's look and the colour yellow; use is
allowed only to identify Abbott's products [[T1]](https://play.google.com/store/apps/details?id=com.abbott.adc.freestyle.libre.us). Apple forbids third-party brands in the app name/icon without
approval [[A4]](https://developer.apple.com/app-store/review/guidelines/); Garmin forbids its marks in marketing without consent [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html). So: the name stays **Sukrara**, the icon
never shows a sensor or yellow butterfly, listings say "works with FreeStyle Libre 3 sensors" (nominative use, like
Juggluco's listing [[P1]](https://play.google.com/store/apps/details?id=tk.glucodata)) plus "FreeStyle Libre and LibreView are trademarks of Abbott. Sukrara is not affiliated with
or endorsed by Abbott" (already in the README disclaimer). No "LibreView" in titles or screenshots' headlines.

**GPL-3.0-or-later in stores.** Distributing binaries requires the Corresponding Source. Play, the Mac dmg and the
Connect IQ store all allow a link: each release must tag the exact commit **and** the Juggluco revision compiled into
the native core (today an unpinned local checkout: pin it as a submodule or record the hash in the release notes),
and the app's About page links to the source. GPL's "no further restrictions" conflicts with store terms only for the
Mac App Store (the long-standing App Store/GPL debate), another reason to skip it. Juggluco's own GPLv3 code is
compatible.

**Costs**

| Item | Cost | When |
|---|---|---|
| Apple Developer Program (Developer ID, notarization) | 99 USD / year [[A1]](https://developer.apple.com/programs/) | before the first notarized release |
| Google Play developer account (personal) | 25 USD once [[V2]](https://developer.android.com/developer-verification/guides/faq) | before the closed test |
| Android Developer Console | 0 (not needed: Play Console registers outside-Play apps too) [[V2]](https://developer.android.com/developer-verification/guides/faq)[[V3]](https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console) | — |
| Homebrew tap, IzzyOnDroid, F-Droid, Obtainium | 0 | — |
| Garmin Connect IQ (free app) | 0 [[C1]](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html) | — |
| D-U-N-S / legal entity | not needed (personal account) | — |
| **Total** | **99 USD/year + 25 USD once** | |

**Effort and timeline**

| Step | Who | Effort | Calendar |
|---|---|---|---|
| Enrol in Apple Developer Program | user | 30 min + approval (hours–days) | week 1 |
| Developer ID cert + notarytool profile, first notarized dmg | user (cert, keychain) + agent | 1 h | week 1 |
| Homebrew tap + cask + auto-bump workflow | agent | 2 h | week 1 |
| Sparkle 2 + appcast on the site + EdDSA key | agent (user keeps the key) | 1 day | week 2 |
| Play developer account + identity verification | user | 1 h + verification (days) | week 1–2 |
| Release keystore, `play`/`full` flavors, rename to `app.mauri.sukrara`, migration screen | agent (user stores the key) | 2–3 days | week 2 |
| Privacy page, listing texts, screenshots (synthetic data), Health/FGS/exact-alarm/data-safety forms, FGS demo video | agent drafts, user submits | 1 day | week 2 |
| Closed test: 12 testers × 14 days, then production access + review | user recruits testers | — | weeks 3–5 |
| IzzyOnDroid request (Fastlane metadata, release-signed APK) | agent | 0.5 day | week 3 |
| F-Droid main repo (reproducible build, submodules) | agent | 2–4 days | later |
| Connect IQ store | user (account) + agent | 0.5 day | when the watch app exists |

## Recommendation

**Mac**: pay for the Apple Developer Program and ship **notarized** dmg/zip with **Sparkle 2** updates from the
website; serve developers with an **own Homebrew tap** from the same release; submit to `homebrew/cask` once it's
notable. Skip the Mac App Store.

**Android**: one release key, package **`app.mauri.sukrara`**, two flavors. **Google Play** (new personal account,
closed test as the permanent beta) for everyone; **GitHub + Obtainium** and **IzzyOnDroid** for the full flavor with
root features; register the package and key for developer verification in the same Play Console account before the
2027 global rollout. F-Droid main repo later, with reproducible builds.

**Garmin**: Connect IQ Store, free, display-only claims.

**Next steps, in order**

1. *(user)* Enrol in the Apple Developer Program (99 USD/year, legal name as seller).
2. *(user)* Create the Developer ID Application certificate and the `sukrara` notarytool profile (NEXT.md §5).
3. *(agent)* First notarized release with the existing scripts; Homebrew tap + cask + auto-bump; README/site
   `brew` instructions.
4. *(agent)* Sparkle 2 in the Mac app, appcast on sukrara.mauri.app. *(user)* keeps the EdDSA private key backup.
5. *(user)* Create the Google Play developer account (personal, 25 USD): try the current Google account, else a new
   one; verify identity, address, phone and e-mail. Check the old account's package names (they stay blocked).
6. *(user)* Generate and back up the release keystore (an agent can script it; the user owns the file and passwords).
7. *(agent)* Rename to `app.mauri.sukrara`, `play`/`full` flavors, `SCHEDULE_EXACT_ALARM` in `play`, migration screen
   in the last `.poc` build, settings export/import, Juggluco core pinned.
8. *(agent)* Privacy page, store listing (with the not-a-medical-device statement and Abbott disclaimer), synthetic
   screenshots, Fastlane metadata, FGS demo video script; drafts of the Health apps, data safety, FGS and exact-alarm
   answers. *(user)* submits the forms (only the account owner can).
9. *(user)* Recruit 12 testers (Google Group), run the closed test 14 days, apply for production.
10. *(user)* Register `app.mauri.sukrara` + key for developer verification in Play Console (upload our own key to Play
    App Signing so all channels share it).
11. *(agent)* IzzyOnDroid inclusion request; Obtainium link on the site.
12. Later: F-Droid main repo; Connect IQ store when the watch app exists *(user creates the Garmin account)*.

**Only the user can**: pay (Apple, Google), verify identity (Apple, Google Payments profile), own the accounts
(Apple ID with 2FA, Play Console, Garmin), create the Developer ID certificate on their Mac, hold the release
keystore and Sparkle EdDSA key, submit policy declarations, recruit testers, and decide on medical-device risk.

## Risks

- **Medical-device classification** (EU MDR rule 3.3 / Play's "software interfacing with medical device hardware")
  could force a "Medical Device Apps" declaration Sukrara can't back with a certification → Play rejection or
  removal. Mitigation: display/convenience framing, the sideload channel as fallback.
- **Abbott**: a trademark or ToS complaint can pull the Play listing (reported for Juggluco [[P2]](https://forum.fudiabetes.org/t/juggluco-back-on-play-store/15038)); the LibreView
  upload uses an unofficial API.
- **`USE_EXACT_ALARM` rejection** if kept in the Play build.
- **Closed-test drop-outs** restart the 14-day clock [[G6]](https://support.google.com/googleplay/android-developer/answer/14151465)[[G7]](https://www.choicely.com/blog/google-play-12-tester-rule).
- **Developer verification** may tighten (the advanced flow is new, and F-Droid's position is unresolved [[V4]](https://techaiwire.com/articles/android-developer-verification-enforcement-four-countries/));
  losing the release key makes the package unregistrable [[V2]](https://developer.android.com/developer-verification/guides/faq) — two backups.
- **Same package on Play and GitHub**: Play may overwrite a root install with the play flavor.
- **Team ID change** on the Mac when moving to the paid team (Keychain prompt, new App Group container).
- **Homebrew tap trust** rules have changed twice in 2026 [[H6]](https://brew.sh/7.0.0-migration-guide/); recheck the cask instructions on each Homebrew major.

## Sources

All seen **2026-10-18**. "3rd-party" = not the platform owner.

- [H1] Homebrew 5.0.0 release notes — https://brew.sh/2025/11/12/homebrew-5.0.0/
- [H2] Homebrew/brew #20755, removing `--no-quarantine` — https://github.com/Homebrew/brew/issues/20755
- [H3] TomatoBar #102, cask disabled 2026-09-02 (3rd-party) — https://github.com/ivoronin/TomatoBar/issues/102
- [H4] Homebrew, Acceptable Casks — https://docs.brew.sh/Acceptable-Casks
- [H5] Example taps: livecheck, auto-bump, postflight quarantine (3rd-party) — https://github.com/nelsonlove/homebrew-tap/pull/3, https://github.com/EricAndrechek/homebrew-tap, https://github.com/HibernalGlow/homebrew-tap
- [H6] Homebrew 7.0.0 migration guide (tap trust) — https://brew.sh/7.0.0-migration-guide/
- [H7] Homebrew/brew #22472, `brew trust` — https://github.com/Homebrew/brew/pull/22472
- [A1] Apple Developer Program — https://developer.apple.com/programs/
- [A2] Apple, Enrollment — https://developer.apple.com/help/account/membership/program-enrollment/
- [A3] Apple, Membership details (Developer ID, notarization) — https://developer.apple.com/programs/whats-included/
- [A4] App Review Guidelines (1.4.1, 2.4.5, 2.5, 4.1, 5.1.3, 5.2) — https://developer.apple.com/app-store/review/guidelines/
- [A5] Bluetooth sandbox entitlement — https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.device.bluetooth
- [S1] Sparkle documentation (2.9) — https://sparkle-project.org/documentation/
- [G1] Play Console Help, Closure of inactive developer accounts — https://support.google.com/googleplay/android-developer/answer/11605267
- [G2] XDA, inactive-account policy (2021, 3rd-party) — https://www.xda-developers.com/google-play-policy-crack-down-on-dormant-accounts/
- [G3] Reinstating / new account after inactivity closure (3rd-party) — https://mrhamo.com/blog/reinstate-google-play-developer-account-closed-inactivity/, https://magiskmodule.gitlab.io/blog/google-play-console-has-old-inactive-account-in-it-how-do-i-create-a-new-developer-account-to-publish-app/
- [G4] Play Console Help, Create and set up your app (package names) — https://support.google.com/googleplay/android-developer/answer/9859152
- [G5] Play Console Help, account types and required information — https://support.google.com/googleplay/android-developer/answer/13628312
- [G6] Play Console Help, App testing requirements for new personal developer accounts — https://support.google.com/googleplay/android-developer/answer/14151465
- [G7] 12-tester rule explained, organization exemption (3rd-party) — https://www.choicely.com/blog/google-play-12-tester-rule, https://ontest.app/blog/google-play-12-testers-14-days-requirement-explained
- [G8] Play Console Help, Target API level requirements — https://support.google.com/googleplay/android-developer/answer/11926878
- [G9] Play Console Help, Health apps declaration form — https://support.google.com/googleplay/android-developer/answer/14738291
- [G10] Play Console Help, Health Content and Services — https://support.google.com/googleplay/android-developer/answer/16679511
- [G11] Play Console Help, Permissions and APIs that access sensitive information (exact alarms) — https://support.google.com/googleplay/android-developer/answer/16558241
- [G12] Play Console Help, Foreground service and full-screen intent requirements — https://support.google.com/googleplay/android-developer/answer/13392821
- [G13] Play policy, Device and Network Abuse — https://support.google.com/googleplay/android-developer/answer/16559646
- [G14] Play Console Help, Data safety section — https://support.google.com/googleplay/android-developer/answer/10787469
- [G15] Play Console Help, Play App Signing — https://support.google.com/googleplay/android-developer/answer/9842756
- [V1] Android developer verification — https://developer.android.com/developer-verification
- [V2] Developer verification FAQ — https://developer.android.com/developer-verification/guides/faq
- [V3] Android Developers Blog, verification rolling out to all developers — https://developer.android.com/blog/posts/android-developer-verification-rolling-out-to-all-developers-on-play-console-and-android-developer-console
- [V4] Enforcement in four countries; F-Droid 2.0 (3rd-party) — https://techaiwire.com/articles/android-developer-verification-enforcement-four-countries/, https://pinggy.io/blog/f_droid_2_0_android_developer_verification/
- [V5] Register your app on open source platforms — https://developer.android.com/developer-verification/guides/open-source-app-registration
- [R1] MDCG 2019-11 (software qualification/classification, MDR Annex VIII 3.3) — https://health.ec.europa.eu/document/download/b45335c5-1679-4c71-a91c-fc7a4d37f12b_en?filename=mdcg_2019_11_en.pdf
- [R2] 21 CFR 862.1350 — https://www.ecfr.gov/current/title-21/chapter-I/subchapter-H/part-862/subpart-B/section-862.1350
- [R3] FDA product code PJT — https://www.accessdata.fda.gov/scripts/cdrh/cfdocs/cfpcd/classification.cfm?id=PJT
- [P1] Juggluco on Google Play — https://play.google.com/store/apps/details?id=tk.glucodata
- [P2] FUDiabetes forum, Juggluco back on Play (3rd-party) — https://forum.fudiabetes.org/t/juggluco-back-on-play-store/15038
- [P3] xDrip+ installed from GitHub (3rd-party) — https://forum.fudiabetes.org/t/upgrade-to-libre-3-from-libre-2-using-xdrip-on-android/14774
- [F1] Obtainium releases (1.7.0) — https://github.com/ImranR98/Obtainium/releases
- [F2] F-Droid Inclusion Policy — https://f-droid.org/docs/Inclusion_Policy/
- [F3] F-Droid Anti-Features — https://f-droid.org/docs/Anti-Features/
- [F4] IzzyOnDroid App Inclusion Policy / new app inclusions — https://izzyondroid.org/docs/general/AppInclusionPolicy/, https://izzyondroid.org/contributing/NewAppInclusions/
- [F5] Accrescent, The Future of Accrescent — https://accrescent.app/blog/posts/the-future-of-accrescent/
- [F6] Accrescent publishing requirements — https://accrescent.app/docs/guide/appendix/requirements/
- [C1] Garmin Connect IQ Developer Agreement (Exhibit A) — https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html
- [C2] Garmin, Submit an app — https://developer.garmin.com/connect-iq/submit-an-app/
- [T1] Abbott trademark notice (via its Play listings and site, 3rd-party summary) — https://play.google.com/store/apps/details?id=com.abbott.adc.freestyle.libre.us
