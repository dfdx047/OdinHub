<p align="center">
  <img src="./assets/logo.png" width="300" alt="Odin Hub logo">
</p>

<h1 align="center">Odin Hub</h1>

<p align="center">
  A console-style hardware and performance control center for <b>AYN Odin</b> and <b>Retroid Pocket</b> handhelds.<br>
  Built by <b>dfdx047</b>.
</p>

<p align="center">
  <b>English</b> · <a href="./README.pt-BR.md">Português (Brasil)</a>
</p>

<p align="center">
  <a href="https://github.com/dfdx047/OdinHub/releases"><img src="https://img.shields.io/github/v/release/dfdx047/OdinHub?include_prereleases&label=download&style=for-the-badge" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/devices-Odin%202%20%7C%20Odin%203%20%7C%20Thor%20%7C%20Retroid-7c6cf0?style=for-the-badge" alt="AYN Odin / Thor / Retroid Pocket">
  <img src="https://img.shields.io/badge/root-not%20required-2ea44f?style=for-the-badge" alt="No root required">
</p>

<p align="center">
  <img src="./docs/screenshots/01-tdp-profiles.png" width="85%" alt="Odin Hub — TDP profiles">
</p>

---

## What this is

The Odin 3 ships with a Snapdragon 8 Elite (and the Odin 2, Thor and Retroid Pocket 6 with an 8 Gen 2) that is far more powerful than most Android games and emulators need. Left alone, it runs hot, drains the battery fast, and throttles — all to render a PS2 game that would have been happy with a third of that power.

Odin Hub is the layer that sits between you and that silicon. It lets you decide, **per game**, how much of the device to actually spend:

- Cap the CPU/GPU to a target power draw and hold it there.
- Or pin exact clock frequencies per CPU cluster when you want deterministic behaviour.
- Set the fan and display calibration to match.
- Save it all as a rule for that specific game — and have it applied automatically the moment the game opens, and reverted the moment you leave.

The goal is that you configure a game **once** and never think about it again.

## The core idea: per-game profiles

This is the feature everything else exists to serve.

```
You open GTA San Andreas
        │
        ▼
Odin Hub detects the foreground app
        │
        ▼
Looks up that game's rule in its database
        │
        ├── found     → applies 5 W cap, quiet fan, vibrant colors
        └── not found → applies your global profile
        │
        ▼
You leave the game → device returns to the global profile
```

Rules live in a single Room database and are edited from **two places that stay in sync**:

- **Per-App Overrides** screen — configure a game up front, from the couch.
- **In-game overlay** — a side handle you pull out mid-game, like a phone's "game mode" panel. Change the TDP while you play; the rule for that game is created or updated on the spot.

Change a game in one place and the other reflects it. There is no second source of truth.

## Screenshots

| | |
|---|---|
| ![Manual clocks](./docs/screenshots/02-manual-clocks.png) | ![Thermal limit](./docs/screenshots/03-thermal-limit.png) |
| **Real clock tables** — every chip is a frequency the SoC actually supports | **Thermal limit** — move the throttling point, globally or per game |
| ![Home gestures](./docs/screenshots/05-home-gestures.png) | ![Macro editor](./docs/screenshots/06-macro-editor.png) |
| **Home button gestures** — screenshot, full-screen recording, overlay… | **M1 / M2 macros** — combos and timed sequences, global or per game |
| ![Color calibration](./docs/screenshots/07-color-calibration.png) | ![Per-game rules](./docs/screenshots/04-per-game-rules.png) |
| **Color calibration** — saturation and white balance, live | **Per-game rules** — applied when the game opens, reverted when you leave |

## Features

### Performance
- **Dynamic TDP (AutoTDP)** — a closed loop that measures real-time power draw on the battery and walks CPU/GPU clocks until the device settles at your target wattage. It filters the noisy battery reading, cuts fast when over budget and raises slowly when under (no saw-tooth stutter), and — when the GPU is the bottleneck — takes the cut from the CPU instead (and vice versa). It pauses while charging (the battery can't measure the device then) and with the screen off.
- **TDP profiles** — Power Save, Balanced and Triple A, sized for **the device it's running on** (7 / 10 / 15 W on the Odin 3, 5 / 7 / 10 W on the Odin 2, 3.5 / 4.5 / 6 W on the Retroid Pocket 5 — see the table below), plus Stock (no limit). Profiles are independent from the free slider.
- **Per-cluster clock control** — the real frequencies of each CPU cluster (Efficiency / Perf / Prime on three-cluster chips like the 8 Gen 2 and 865, including the top "boost" OPPs) and the GPU, read live from the kernel's OPP tables, with a static per-SoC table as fallback. Only one of TDP or manual clocks is active at a time.
- **Thermal limit** — optionally move the first-stage passive trip points of the CPU/GPU thermal zones (85 / 90 / 95 °C or unthrottled), globally or per game. Written through PServerBinder like everything else — no KernelSU/Magisk needed. Emergency trips and **fan trips** are never touched, and Stock restores the factory values (the backup is tied to the current boot, so it can never "restore" a modified value).
- **Fan control** — Stock, Quiet, Smart and Sport, mapped to the device's real fan modes, plus a **custom fan curve**: drag the points on a graph (or fine-tune them with the D-pad), follow the CPU, the GPU or the hottest of the two, watch the live temperature and fan speed, and run CPU / GPU stress tests. At 90 °C or more the fan always runs at 100 %.
- **Custom profiles** — save your own TDP or clock presets by name.

### Display
- Saturation (0–2×) and color temperature (4000–9000 K, warmer or cooler) applied live through SurfaceFlinger's own color transform.
- Per-game color, from the Per-App Overrides screen or the in-game overlay.

### Controls
- Remap the M1 / M2 rear buttons to any key code — or to a **macro / combo** (several buttons at once, or a timed sequence). Macros can also be set **per game**, from the Per-App Overrides screen or the in-game overlay.
- **Home button gestures** — single, double, triple tap and long press, each mapped to a system action: screenshot, full-screen recording (no app picker, saved to Movies/OdinHub), overlay toggle, recents, notifications, lock screen and more.
- Bind a physical button to open the in-game overlay.

### Performance HUD
- A small MangoHud-style overlay over **any app**: FPS, frame time (with a graph), CPU load and temperature, per-cluster or per-core clocks, GPU load / temperature / clock, RAM, power draw, battery % and temperature, TDP target and the time.
- **Everything is optional** — switch each metric on or off. Horizontal bar or vertical panel, five anchor positions, drag to move, three text sizes, adjustable background.
- Follows the app's theme, including Glass mode. **Double-tap to hide**; bring it back from the side overlay, the Home gesture "Show/hide performance HUD", or Settings.
- Cheap: everything is read straight from sysfs (CPU load comes from the cores' idle time); only FPS / frame time needs one PServerBinder call per second. Sampling stops while the HUD is hidden or the screen is off.
- The window grows as you add metrics, and the horizontal bar wraps to a second line instead of running off the screen.

### Interface
- **Glass appearance** — a Liquid Glass–style mode that works on top of every theme: translucent surfaces with a light edge; the side overlay also blurs the game behind it when the system allows.
- **Auto-update** — Odin Hub checks GitHub for a newer release when you open it and every few hours in the background (with a notification), shows a pop-up with the release notes, downloads the APK and installs it in place through PServerBinder (or hands it to the Android installer).
- Seven console-inspired themes (Odin OS, Cyberpunk, SNES, NES, PlayStation, Xbox, Steam OS) plus a Light theme, Material You dynamic colors, and an AMOLED black mode.
- D-pad navigable with haptic feedback and focus glow.
- Animated boot sequence, custom wallpapers, and an integrated BGM/SFX mixer.

## Supported devices

Odin Hub detects the device on launch ("AYN Odin 2 detected") and switches the frequency tables, thermal zones, GPU control and TDP profiles to match. Everything else — overlay, per-game rules, macros, gestures — is the same on all of them: AYN and Retroid build on the same ODM firmware (Moorechip) and ship the same `PServerBinder` helper.

| Device | SoC | GPU control | Thermal limit | M1/M2 | TDP profiles (PS / Bal / AAA / max) |
|---|---|---|---|---|---|
| AYN Odin 3 | Snapdragon 8 Elite | Adreno 830 (kgsl) | ✓ | ✓ | 7 / 10 / 15 / 25 W |
| AYN Odin 2 / Odin 2 Max / Odin 2 Portal | Snapdragon 8 Gen 2 (QCS8550) | Adreno 740, 680 MHz | ✓ | ✓ | 5 / 7 / 10 / 13 W |
| AYN Odin 2 Mini | Snapdragon 8 Gen 2 | Adreno 740 | ✓ | ✓ | 4.5 / 6.5 / 9 / 12 W |
| AYN Thor | Snapdragon 8 Gen 2 | Adreno 740 | ✓ | ✓ | 5 / 7 / 10 / 13 W |
| AYN Thor Lite | Snapdragon 865 | Adreno 650 | ✓ | ✓ | 3.5 / 4.5 / 6 / 8 W |
| Retroid Pocket 6 / Retroid Pocket Nova | Snapdragon 8 Gen 2 (QCS8550) | Adreno 740 | ✓ | if the firmware exposes them | 4.5 / 6.5 / 9 / 12 W |
| Retroid Pocket 5 | Snapdragon 865 | Adreno 650, 670 MHz | ✓ | if the firmware exposes them | 3.5 / 4.5 / 6 / 8 W |
| Retroid Pocket Mini / Mini V2 | Snapdragon 865 | Adreno 650 | ✓ | if the firmware exposes them | 3 / 4 / 5.5 / 7 W |
| Retroid Pocket 4 Pro | Dimensity 1100 | Mali-G77 (MediaTek GED) | — (MediaTek throttles in userspace) | if the firmware exposes them | 3.5 / 4.5 / 6 / 8 W |
| Retroid Pocket 4 | Dimensity 900 | Mali-G68 (MediaTek GED) | — | if the firmware exposes them | 3 / 4 / 5 / 7 W |

Notes:

- TDP figures are whole-device battery draw, sized from published per-SoC gaming measurements and each chassis' cooling. The steps are deliberately far apart so the profiles actually feel different — tune to taste with the slider.
- On the 8 Gen 2, the kernel thermal zones carry passive trips at 50–85 °C that drive the **fan**, not throttling; Odin Hub excludes them (by their cooling device and by a per-SoC minimum temperature) so a thermal limit never delays the fan.
- On the Thor and the Retroid Pocket 6 the firmware raises each cluster's minimum frequency according to its own performance mode. When a cap goes below that floor, Odin Hub lowers the floor too, so the cap actually holds.
- Some firmwares (the Odin 2 Mini is the known case) don't return command output through PServerBinder. Odin Hub detects that, switches to output-less mode and reads values straight from sysfs instead.
- Unknown devices still work in a generic mode: live kernel tables and the limits of the closest known SoC.

## How it talks to the hardware

Odin Hub does not run `su` and needs no KernelSU/Magisk. It reaches a privileged helper already present on the device — `PServerBinder`, which runs as root — through `IBinder.transact()`, a technique pioneered by **ClusterTune** and **P.U.L.S.E.** (see Credits). Through that channel it writes to `sysfs` nodes and Android system settings.

Two consequences worth knowing:

1. **Every write is verified, and only made when needed.** Before writing, the node is read directly (no root needed); if it already holds the value, nothing is sent. After writing, the value is read back and compared with what was there before — a write the kernel silently rejected is reported as a failure rather than assumed to have worked. In Stock mode the nodes are handed back to the system and left alone.
2. **Nodes are written independently.** An earlier design chained every node into one `cmd && cmd && cmd` string, so a single rejected node (a GPU clock locked by the bootloader, say) aborted every write after it. Each node now succeeds or fails on its own.

Foreground-app detection runs through an accessibility service, with a `UsageStatsManager` poll as a fallback so per-game rules and the overlay keep working even if that service is disabled.

## Requirements

- One of the devices above (or another Android handheld in generic mode).
- The `PServerBinder` helper available on the device (present on stock AYN and Retroid firmware).
- Permissions granted at first launch: **Usage Access** (foreground-app detection) and **Display over other apps** (the in-game overlay).

## Installing

1. Download the latest APK from the [Releases](https://github.com/dfdx047/OdinHub/releases) page.
2. Install it on your device.
3. Launch it and grant the two permissions it asks for.

After that, new versions show up inside the app (pop-up and notification) and install over the old one. If you installed v0.10 or older, uninstall it once before installing v0.11: those builds were signed with a different key.

## Releasing

Releases are built and published **from the developer's machine**, not by GitHub Actions (CI only runs the tests and a debug build). This keeps every release signed with the same key, which Android requires to install an update over the previous version. By default that key is the machine's Android debug key (`~/.android/debug.keystore`), so there is nothing to set up. **Back that file up**: without it, the next release can't update over the installed one.

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add a `## vX.Y` section at the top of [`CHANGELOG.md`](./CHANGELOG.md), in English and Portuguese.
3. Commit, then run:

```bash
./scripts/release.sh
```

The script runs the tests, builds the release APK, creates and pushes the `vX.Y` tag, and publishes the GitHub release with the APK (`OdinHub-vX.Y.apk`) and that version's changelog section as the release notes. It needs the [GitHub CLI](https://cli.github.com/) signed in (`gh auth login`).

Optional: to use a dedicated key instead of the debug key, put a `keystore.properties` file (git-ignored) in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Building

```bash
git clone https://github.com/dfdx047/OdinHub.git
cd OdinHub
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 35, minSdk 33). Unit tests:

```bash
./gradlew testDebugUnitTest
```

## Architecture

Some structural decisions here are not obvious from the code, and re-learning them the hard way wastes time. If you are picking this project up, read this first.

### `PerformanceCommandBuilder` — why it exists

Hardware writes are built in one place rather than assembled ad hoc at each call site. It owns:

- **Dynamic cpufreq policy discovery.** Cluster layout varies by SoC and firmware, so policies are scanned at runtime instead of hardcoding `policy0` / `policy6`. The policy with the highest max frequency is treated as Prime.
- **Independent, verified writes.** Each node is one `HardwareWriteOp` that is applied, read back, and retried through a sequential fallback if the chained form failed.
- **A single place for the clamp ratios.** Notably, the floor for dynamic TDP is *lower* than the floor for manual clocks — low targets like 5 W need room to walk the clocks down far enough to actually converge.

`PerformanceManager.lastApplyStatus` exposes the result of the last write pass so the UI can show a real failure instead of trusting a write that never landed.

### `AppOverrideRepository` — the single source of truth

Per-game rules are read and written from very different contexts: a normal ViewModel screen, and a `WindowManager` overlay running outside the activity lifecycle. Both go through this repository, backed by one Room table.

It exposes a `StateFlow<Map<String, AppOverrideEntity>>` kept warm by a Room `Flow`, so the accessibility thread can read the current rule synchronously via `.value` without ever touching disk on that thread. **If you add a new per-game setting, it goes on `AppOverrideEntity`** — not into a new `SharedPreferences` key.

### `ForegroundAppTracker` — reactive, in-memory only

Which app is in the foreground is *momentary* information, so it lives in a `StateFlow` and is never persisted. The overlay observes it, which is what lets its Compose tree re-key per game — the overlay is created once and would otherwise stay frozen on whatever game was open when the service started.

### Threading rule

Every root call is blocking. Anything triggered by user interaction — a slider drag, a toggle, an app switch — must be dispatched to `Dispatchers.IO`, and continuous controls should debounce with a cancellable job. A blocking root call on the main thread is enough to freeze the app during startup or make a slider unusable.

## Credits

Odin Hub is a heavily reworked derivative of existing open-source work, and stands on it:

- **[OdinTools](https://github.com/langerhans/OdinTools)** by Maximilian Keller (langerhans) — the original foundation this project was forked from: app-override plumbing and quick-settings infrastructure.
- **[P.U.L.S.E.](https://github.com/keiretrogaming/pulse)** by keiretrogaming — the `PServerBinder` technique and the closed-loop AutoTDP concept that make deep hardware control possible without a traditional root setup.
- **[ClusterTune](https://github.com/AurelioB/ClusterTune)** by AurelioB — the original pioneer of the PServer sysfs approach.

## License

MIT. The original copyright notice is retained in [`LICENSE`](./LICENSE) as the license requires, alongside the copyright for this project's own work.
