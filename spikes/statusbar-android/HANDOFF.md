# Handoff — bring the root/LSPosed status-bar features into Sukrara

*Written 2026-10-16 by the session that did investigation 05 ([docs/05](../../docs/05-android-status-bar.md)) and
started 11. For a fresh agent.* Goal: Sukrara (`spikes/showcase-android`, `app.mauri.sukrara.poc`) already has the
rootless surfaces (Live Update chip, number icon). Missing are the two from 05 that need root:

- **3a — system icons** next to Wi-Fi/battery, set through `IStatusBarService.setIcon` by a root helper process;
- **3b — the SystemUI module** (LSPosed, libxposed API 102): coloured value before/after the clock, left of the
  system icons, on the lock screen, and the exact-slot reordering ("between Wi-Fi and battery").

All of it is proven on the user's Pixel 10 Pro / Android 17 and the working code is in `spikes/statusbar-android`
(GlucoStatus). Read docs/05 first; this file is the "how to port it safely".

## Where the code is (GlucoStatus → Sukrara)

| GlucoStatus file | What it is | Port notes |
|---|---|---|
| `SlotDaemon.kt` | `main()` run via `app_process` as root; reads `set <slot> <resId> <level> <desc>` / `remove <slot>` on stdin, calls `IStatusBarService.setIcon/removeIcon` by reflection; removes its icons on EOF; re-adds them in order after a SystemUI restart (watches its pid) | Started **by class name** from a shell → R8 must keep it: `-keep class app.mauri.sukrara.status.SlotDaemon { public static void main(java.lang.String[]); }` |
| `SlotOutput.kt` | app side: starts the daemon with `su -c "CLASSPATH=<apk> exec app_process /system/bin <class> <pkg>"`, picks the drawable per value | Uses `getIdentifier("sb_n123", "drawable", …)` → with `isShrinkResources = true` the `sb_*` drawables get stripped. Add `res/raw/keep.xml` with `tools:keep="@drawable/sb_*"` (or reference them from a generated table) |
| `tools/gen-slot-drawables.swift` + `res/drawable/sb_*.xml` (403 files) | one vector per value 40…400, LO/HI, arrow every 5° (`sb_t00`…`sb_t36`, `sb_t_up2/down2/none`), Google Sans Flex (OFL) | The Compose status bar **ignores the icon level**, so one resource per value is required. Copy the generated XMLs or re-run the generator |
| `hook/SystemUiHook.kt` | the LSPosed module (`XposedModule`, `onPackageReady` for `com.android.systemui`) | Rename package/constants; keep every hook body wrapped (see Safety) |
| `HookBridge.kt` | app → SystemUI broadcast guarded by a **signature permission**; SystemUI says HELLO on start | Change `APP`, `ACTION`, `PERMISSION` to `app.mauri.sukrara.poc…`; declare the `<permission … protectionLevel="signature">` + `uses-permission` |
| `resources/META-INF/xposed/{java_init.list,module.prop,scope.list}` | libxposed API 102 module descriptor (scope `com.android.systemui`) | Point `java_init.list` at the new class; `compileOnly("io.github.libxposed:api:102.0.0")`. R8: `-keep class app.mauri.sukrara.hook.** { *; }` and keep anything it touches by reflection |
| `DebugReceiver.kt` `--ez restartSystemUi true` | root `kill $(pidof com.android.systemui)` | Add to Sukrara's DebugReceiver (it's DUMP-protected) |
| `tools/hook-safety.sh` | `arm` / `disarm` / `kill` / `unkill` | Rename the kill-switch key (`glucostatus_hook_disabled` → e.g. `sukrara_hook_disabled`) in both the hook and the script |

Where to plug it into Sukrara:
- Switches already exist: `features/Features.kt` (`systemIcons`, `hook`, currently forced `false` at ~line 80),
  `features/DebugReceiver.kt` (~line 55 prints "not bundled"), UI toggle disabled at `ui/WearerScreens.kt:~179`.
- Every reading goes through `wearer/WearerExtras.kt` → `render()` (~line 144). Call `SlotOutput.show(r)` and
  `HookBridge.send(…)` there, and from `apply()` (~line 95) when the switches change. Stop the daemon / send
  `enabled=false` when switched off or the service stops.
- The hook position setting (`after_clock` / `before_clock` / `icons:0` / `slot:<name>`) needs a field in
  `Features` + DebugReceiver extra (GlucoStatus used `--es hookPosition …`). `slot:*` needs both 3a and 3b on.
- Follower role: same surfaces could show the followed person's value — decide with the user; start with wearer.

## Safety procedure for System UI (read before restarting SystemUI)

Nothing here can bootloop the phone (no `/system`, no boot image, no KernelSU module). The real risk: **SystemUI is
exempt from the crash-recovery cap — 5 SystemUI crashes in 60 s escalate to a warm reboot and then to the recovery
screen offering "Factory data reset"** (the user must pick *Try again*). So:

1. Every hook body catches `Throwable` and uses `ExceptionMode.PROTECTIVE`. Don't create Handlers/Views/Context-bound
   objects in the module's constructor or field initialisers (the module is instantiated before the main looper —
   that was the one failure in 05; LSPosed caught it, SystemUI stayed up).
2. Before loading or changing hook code: `spikes/statusbar-android/tools/hook-safety.sh arm` (raises the
   crash-recovery threshold via `device_config`, no root). Afterwards: `disarm`.
3. Reload the hook by **killing** SystemUI (root, Sukrara's DebugReceiver), never `am crash` (crashes count).
   Status bar/nav blink ~2 s, no data loss. **Tell the other agent sessions before each restart** (they may be
   mid-test on the phone).
4. If SystemUI misbehaves: `adb shell settings put global <kill-switch-key> 1` and restart SystemUI → the hook is a
   no-op. Or disable the module in LSPosed. **Do not `adb uninstall` Sukrara as a recovery step** — it holds the
   user's wearer identity (Nostr keys) and data; uninstalling GlucoStatus was fine, uninstalling Sukrara is not.
5. Last resort: KernelSU safe mode (Volume Down ×3 at the boot logo disables all modules incl. LSPosed).
6. Always pin the phone: `adb -s 62240DLCH0005W …` (emulators are attached too).

Verification used in 05: screenshot + crop the top 130 px (`adb exec-out screencap -p`), logcat
`grep 'Sukrara\|LSPosedFramework'` for "attached to MAIN/KEYGUARD" and "icon order hooks: 1". The screen locks after
30 s: ask the user to unlock for chip/status-bar shots (the lock-screen bar has no chip but shows system icons).

## What the user has to do

- **Root for Sukrara**: already granted in KernelSU (per the Sukrara session). Needed for 3a (`su` → daemon) and the
  SystemUI restart command.
- **LSPosed (Vector, API 102)**: Modules → Sukrara → enable; System UI is pre-ticked from `scope.list`. Then one
  SystemUI restart. The user prefers the modern API — don't fall back to legacy Xposed API 82.
- Nothing else (no reboot, no KernelSU module).

## Known behaviour / lessons from 05

- External icons always land **leftmost** of the system icons, newest first → add the trend before the number.
  After a SystemUI restart they come back in hash order; the daemon re-adds them (pid watch + "young SystemUI" check).
- The hook's view must mirror the clock's **alpha/visibility** every frame, or it lingers as a ghost on the lock
  screen and under heads-up notifications.
- "Right of the battery" is clipped by the rounded corner — not offered.
- The icon-order hook finds an R8-named lambda of `SystemStatusIconsViewModelImpl` **by shape** (Function0 with a
  field of that type). If a SystemUI update breaks it, it logs `icon order hooks: 0` and `slot:*` falls back to
  leftmost — harmless.
- Live Update chip showing "two numbers" (NEXT.md item 2) is because the chip uses the small icon; unrelated to
  this port, but the same code area.

## Done = …

Both switches work live from Settings and the DebugReceiver, survive a SystemUI restart and a Sukrara process
restart, turning them off leaves nothing on screen, screenshots for each position, release build (R8) tested, and
docs/10 + this file updated.
