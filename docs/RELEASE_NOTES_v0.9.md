# Odin Hub v0.9 — Performance HUD, Glass & Auto-update

## ✨ New

### 📊 Performance HUD
A small MangoHud-style overlay that sits over **any app**.

- **Metrics:** FPS and 1% low (read from the game's own layer, works on Android 14 / 15), frame time (with a live graph), CPU load and temperature, clocks per cluster or for every core, GPU load / temperature / clock, RAM, power draw, battery % and temperature, TDP target and the time.
- **Fully customizable:** every metric can be switched on or off. Horizontal bar or vertical panel, five anchor positions, drag it anywhere, three text sizes, adjustable background.
- **Follows your theme**, including the new Glass mode.
- **Double-tap to hide it.** Bring it back from the side overlay, the Home gesture *Show/hide performance HUD*, or Settings.
- **Light on the battery.** Everything is read directly except FPS/frame time and CPU load, which share one call per second. Sampling stops while the HUD is hidden or the screen is off.

### 🪟 Glass appearance
A Liquid Glass–style mode that works on top of **every theme**: translucent surfaces with a light edge, tuned to keep text readable. The side overlay also blurs the game behind it when the system allows.

### 🔄 Auto-update
Odin Hub checks GitHub for a newer release and shows a pop-up with the release notes. One tap downloads the APK and installs it in place, and the app reopens by itself. If it can't install silently, it hands the APK to the Android installer.

### 🔋 New TDP profiles per chip
Power Save / Balanced / Triple A are now sized for each chip, with steps far enough apart that you actually feel the difference:

| Device | Power Save | Balanced | Triple A | Slider max |
|---|---|---|---|---|
| Odin 3 (8 Elite) | 7 W | 10 W | 15 W | 25 W |
| Odin 2 / Portal / Thor (8 Gen 2) | 5 W | 7 W | 10 W | 13 W |
| Odin 2 Mini / RP6 / RP Nova (8 Gen 2) | 4.5 W | 6.5 W | 9 W | 12 W |
| RP5 / Thor Lite (865) | 3.5 W | 4.5 W | 6 W | 8 W |
| RP Mini / Mini V2 (865) | 3 W | 4 W | 5.5 W | 7 W |
| RP4 Pro (D1100) | 3.5 W | 4.5 W | 6 W | 8 W |
| RP4 (D900) | 3 W | 4 W | 5 W | 7 W |

### ⚙️ Efficiency cluster
On three-cluster chips (Snapdragon 8 Gen 2 and 865 — Odin 2 family, Thor, Retroid Pocket 5 / 6 / Mini / Nova), the efficiency cores (3× A510 / 4× A55) now get their own clock row. It works in the main screen, per-game rules, the overlay and presets.

## 🛠️ Fixes

- **Missing frequencies:** the frequency list now merges every source the kernel exposes (including "boost" steps listed separately), and the CPU boost switch is turned on so the top steps actually apply.
- **Home button gestures** (single / double / triple tap, long press) now work. The firmware's "prevent accidental Home press" option was swallowing the first press; Odin Hub turns it off while gestures are on and restores it when you turn them off. The double-tap window is a bit longer, and the card shows the last detected gesture and warns if the accessibility service is off.
- Gestures for the side overlay or the HUD now tell you why when they can't open (overlay off, not in a game, missing permission) instead of doing nothing.
- **First-launch screens scroll**, so the Start / Continue button is reachable in landscape.
- **"Check for updates" / GitHub buttons** no longer lead to a 404 page.
- Auto-update no longer waits 6 hours after a failed check, and a check can't interrupt a download in progress.

## ⚠️ For auto-update to work
- The release must be **published** (not a draft).
- Updates must be signed with the **same key** as the installed app. See *Signing* in the README.
