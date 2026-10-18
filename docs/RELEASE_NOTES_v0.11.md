# Odin Hub v0.11

> ⚠️ **One last reinstall.** From this version on, every release is signed with the same permanent key, so updates install over the previous version. Because older releases were signed with a different key, uninstall your current Odin Hub once and install this APK. After that, updates install in place, straight from the app.

## 🛠️ Fixes

- **Updates now install over the old version.** Releases are signed with one permanent key. A release build without that key now fails on purpose instead of shipping an APK that can't update.
- **Update notification:** Odin Hub checks for new versions in the background every few hours and shows a notification. Tapping it opens the update pop-up.
- **Update pop-up:** the app checks again every time you open it (at most every 30 minutes, instead of every 6 hours), so a new release shows up right away.
- **HUD FPS is back,** and CPU load now works too. CPU load is read from the cores' idle time (no root needed), and FPS gets its own root call.
- **HUD no longer cuts off new metrics:** the window grows as you add items, and the horizontal bar wraps to a second line instead of running off the screen.
- **Screen recording from the Home button** now starts. It records the whole screen without audio and needs no app-picker dialog. Files go to `Movies/OdinHub`.
- **Double tap:** the time allowed between taps is now adjustable (300 / 450 / 600 / 800 ms) in the Home Button Gestures card. The default is a little longer than before.
