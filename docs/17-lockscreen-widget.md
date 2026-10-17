# 17 — Glucose and quick treatments on the lock screen

*2026-10-17 · investigation report · Pixel 10 Pro (Android 17, KernelSU Next + LSPosed/Vector, libxposed API 102)*

## Goal

The lower part of the Pixel's lock screen is empty: below the notifications, around the fingerprint icon, above the
flashlight/camera shortcuts. The user marked it in red. Put Sukrara's **glucose value and graph** there, and let a tap
**add a treatment without unlocking**. Root is available (Sukrara already ships an LSPosed module for SystemUI). For
phones without root, find the next best thing.

## Result

**It works with root.** SystemUI itself hosts a real Sukrara app widget in that space, laid out around the
fingerprint icon. A tap opens the treatment dialog **over the lock screen; the phone stays locked**. Shipped in
Sukrara as *Settings → Lock screen → Glucose on the lock screen* (on by default; does nothing without the module).

![Lock screen widget](img/17/lockscreen-widget.png)

*The value is left of the fingerprint icon, age and rate are right of it, and the last 3 h graph (with treatments)
is below. It has no card or background, so it floats on the wallpaper like the clock.*

| What | How it was checked | Result |
|---|---|---|
| SystemUI can host our widget | `AppWidgetHost` in SystemUI's process; `bindAppWidgetIdIfAllowed` (SystemUI holds `BIND_APPWIDGET`) | `bound id 22`, `view added (id 22)`; the same id is reused after every SystemUI restart |
| Placement from SystemUI's geometry | `AuthController.mUdfpsBounds` vs `dumpsys … AuthController` | `lock icon at Rect(441, 1666 - 639, 1864)` = `udfpsBounds=Rect(441, 1666 - 639, 1864)` |
| Follows the lock screen | screenshots: lock screen / shade pulled down / bouncer / AOD | shown / hidden / dimmed with the lock screen / hidden |
| Live data | Glance widget in Sukrara's normal widget pipeline | updates with every reading while the screen is on; one catch-up render on screen on |
| Tap → treatment dialog over the lock screen | `input tap` on the value; `dumpsys activity activities` | dialog + keyboard shown; `KeyguardShowing=true … Occluded=true` (still locked); Cancel returns to the lock screen |
| Fingerprint still unlocks | the user, then `input tap` on/next to the sensor and a 700 ms press | first build: **a finger on the sensor opened the dialog** (see below); fixed build: sensor, its column and long presses never open it |
| Swipe up / long press still reach SystemUI | the widget sits *under* the Compose lock screen and only gets copies of taps | lock screen gestures unchanged |
| Accessibility | `uiautomator dump` | "154", "steady", "now", "-0,4/min", "Glucose graph, last 3 hours" readable |

![Treatment dialog over the lock screen](img/17/dialog-over-lockscreen.png)

## How it works (root)

Android 17's lock screen on Pixel is **one Compose tree** (SystemUI's "scene container", Flexiglass):
`SceneWindowRootView` → a few `ComposeView`s. There is no lock-screen layout to add a view to anymore, and the
lower region (`LockscreenLowerRegionElementProvider`: shortcuts, indication text) is a Compose element. So the module
works on the window root instead, in `hook/LockscreenWidgetHost.kt`:

1. **Host the widget in SystemUI.** `AppWidgetHost(context, 0x53554B)` in SystemUI's process, plus
   `AppWidgetManager.bindAppWidgetIdIfAllowed`. SystemUI holds `BIND_APPWIDGET` for its own glanceable hub, so no
   user prompt appears. The widget is a normal Glance widget in Sukrara (`widgets/LockscreenWidget.kt`, category
   `keyguard` only, so launchers don't offer it), and every reading updates it like the home-screen widgets.
2. **Put it under the Compose layer.** The `AppWidgetHostView` is added to `SceneWindowRootView` just below
   `shared_notification_container`. The lock icon, shortcuts and notifications draw *over* it, so it can span the
   whole free area while the fingerprint icon stays visible in its gap.
3. **Lay it out around the lock icon.** Top = top of the fingerprint icon (`AuthController.mUdfpsBounds`; without an
   under-display sensor, SystemUI's `lock_icon_margin_bottom`). Bottom = top of the shortcuts
   (`keyguard_affordance_vertical_offset + keyguard_affordance_fixed_height`) − 8 dp. Sides = 16 dp, like the
   notifications. The icon's position is sent to the widget as **extra widget options**
   (`sukrara.lockHoleLeft/Right/Bottom`, through `AppWidgetHostView.updateAppWidgetSize(extras, sizes)`), so the
   Glance layout puts the value left of it and the age/rate right of it.
4. **Follow the scene.** On every frame the window draws (`OnPreDrawListener`, so no cost while nothing moves) the
   module reads the scene interactor's transition state (`LegacySceneInteractorImpl.getTransitionState()`: `Idle` on
   `Scenes.Lockscreen` with no overlay = 1, a `Transition` from/to it = 1 − progress / progress) and sets the
   widget's alpha. The widget is hidden while the screen is off (AOD) and when the app turns the switch off.
5. **Touches.** The lock screen keeps every gesture (swipe up to unlock, pull down the shade, long press to
   customise). The hook on `SceneWindowRootView.dispatchTouchEvent` gives the widget a *copy* of a touch that starts
   on it. That copy is cancelled when the finger moves beyond the touch slop or stays down longer than the long-press
   timeout. Touches in the fingerprint icon's column (± 24 dp) never reach the widget.

### The fingerprint bug (fixed before the user's second test)

The first build opened the treatment dialog when the user put a finger on the sensor. The filter in step 5 was
there, but the window *also* delivered touches to the widget the normal way: `SceneWindowRootView` is a
`FrameLayout`, and when the Compose layer on top doesn't consume a touch (empty space, the sensor), the event falls
through to the next child, which is our view. The fix is a `GuardedView` (an `AppWidgetHostView` subclass created by
`AppWidgetHost.onCreateView`) that refuses all regular dispatch and only accepts the filtered copies. The fix also
excludes the full column above and below the sensor, not just the icon's circle, and rejects long presses (a finger
resting on the sensor).

### The dialog over the lock screen

`QuickTreatmentActivity` declares `android:showWhenLocked="true"` **in the manifest**. SystemUI checks the manifest
(`ActivityIntentHelper`) to decide whether a PendingIntent can open without unlocking, so `setShowWhenLocked()` at
runtime isn't enough. Google's lock screen widgets FAQ says the same: "users must authenticate to launch the activity"
unless the activity declares `showWhenLocked`
([android-developers.googleblog.com, 2025-03](https://android-developers.googleblog.com/2025/03/widgets-on-lock-screen-faq.html)).
"Open Sukrara" in the dialog asks to unlock first (`KeyguardManager.requestDismissKeyguard`).

Trade-off, on purpose: anyone holding the locked phone can add an entry, and with LibreView on it is uploaded. The
dialog shows nothing beyond what the lock screen already shows. The switch turns the widget off.

## Without root

| Option | Graph on the lock screen | Quick treatment without unlocking | Notes |
|---|---|---|---|
| **Lock screen widgets ("glanceable hub")** | yes, on a separate page (swipe in from the right edge) | **yes**, with `showWhenLocked` | Android 16 QPR2+ on phones; on Pixel it is tied to charging/docking ("hub mode"). This phone has the flag (`glanceable_hub_v2=true`) but the setting is off (`glanceable_hub_enabled=0`). Sukrara's widgets already declare `keyguard`, so they are listed there. Not the empty area the user marked, and not always available. |
| **Glucose notification** (Sukrara's existing custom card with the graph) | yes, in the notification list | **yes**: a tap opens the dialog over the lock screen now that the activity declares `showWhenLocked` (SystemUI skips the unlock for such activities) | Always works. The card is a silent ongoing notification, so whether it shows on the lock screen depends on the user's "silent notifications on lock screen" setting |
| **Quick Settings tile "Add treatment"** (added to Sukrara) | value as the tile subtitle | **no**: on a secure lock screen SystemUI asks for the fingerprint before any third-party tile's `onClick` | Measured on the phone (the alternate bouncer came up). Still the fastest way in once unlocked |
| **Lock screen wallpaper** (what GlucoDataHandler's "lock screen" option does) | yes, as an image | no (not interactive) | GDH was enabled on the user's phone during the test (`lockscreen_enabled=true`, `lockscreen_y_pos=85`): it rewrites the lock wallpaper (`WallpaperManager`, `FLAG_LOCK`) on each update. Static image, a disk write per update, and it replaces the user's wallpaper |
| Lock screen shortcut (the corner buttons) | — | — | Only system-defined actions (flashlight, camera, QR, wallet, DND, note-taking…); an app can't add its own. "Note-taking" opens the holder of the *Notes* role, which Sukrara would have to take from the user's notes app. Not pursued |

## In Sukrara

| Added | Where |
|---|---|
| Lock screen widget hosted by the LSPosed module, around the fingerprint icon | `hook/LockscreenWidgetHost.kt`, hooks in `hook/SystemUiHook.kt` (`hookLockscreenWidget`) |
| Keyguard-only Glance widget with the lock icon gap | `widgets/LockscreenWidget.kt`, `res/xml/widget_lockscreen.xml` |
| Switch *Settings → Lock screen → Glucose on the lock screen* (`lockWidget`, default on; DebugReceiver `--ez lockWidget`) | `features/Features.kt`, `ui/WearerScreens.kt`, sent to SystemUI with the status-bar broadcast (`status/RootStatusBar.kt`) |
| Treatment dialog over the lock screen (manifest `showWhenLocked`; "Open Sukrara" asks to unlock) | `AndroidManifest.xml`, `ui/QuickTreatmentActivity.kt` |
| Quick Settings tile "Add treatment" with the current value | `ui/TreatmentTile.kt` |

| Discarded | Why |
|---|---|
| Drawing our own view in SystemUI (like the status-bar hook) | Hosting the real widget reuses the widget pipeline, Glance layout and tap handling, and stays in sync with the home-screen widgets |
| Putting the widget *above* the Compose lock screen | It would cover the fingerprint icon and take swipe gestures |
| Delivering touches through the normal view tree | Taps on the fingerprint sensor reached the widget (the bug above) |
| Shrinking the notification stack to make room | Not needed: SystemUI already stops the stack above the fingerprint icon |
| Lock screen wallpaper, Notes-role shortcut | Not interactive / takes over the user's wallpaper or notes app (table above) |

## Code

- `spikes/showcase-android/app/src/main/kotlin/app/mauri/sukrara/hook/LockscreenWidgetHost.kt` — host, placement,
  scene alpha, filtered touches.
- `…/hook/SystemUiHook.kt` — captures `LegacySceneInteractorImpl` and `AuthController` when Dagger creates them, and
  hooks `WindowRootView.onAttachedToWindow` and `SceneWindowRootView.dispatchTouchEvent`.
- `…/widgets/LockscreenWidget.kt` — the layout (dark palette, no background, `LocalAppWidgetOptions` for the gap).

Safety: as with investigation 05, every hook body is wrapped, the kill switch `sukrara_hook_disabled` disables this
too, and SystemUI was only restarted by killing it (`hook-safety.sh arm` during the session).

## Open

- The graph uses `GraphRenderer`; investigation 16 replaces it with `GlucoseCard`. The lock screen widget moves to
  the new renderer when that lands.
- Followers: the widget shows whatever the phone's role feeds the widgets (wearer or followed person), but the switch
  is only in the wearer's settings (followers keep the default, on).
- Other OEM lock screens (Samsung One UI etc.) have different SystemUI classes: the hooks log and do nothing there.
