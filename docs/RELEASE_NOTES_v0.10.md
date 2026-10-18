# Odin Hub v0.10

## 🛠️ Fixes

- **Glass on the tab bar:** the side tab bar (PERF / DISP / CTRL / SYS) now uses the Glass material too, and the active tab gets a translucent pill.
- **Prime frequencies on the Odin 3:** the clock chips now always include the three highest steps, so 4204 MHz is back between 4089 and 4320 MHz. The same applies to every cluster and device.
- **CPU load in the HUD:** it showed `--%` on some setups. CPU usage is now read before the FPS data, so a long FPS reading can no longer cut it off. If root can't read it, Odin Hub falls back to the cores' idle time, which needs no root.
