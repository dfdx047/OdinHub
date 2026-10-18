# Changelog

Each version is listed in English, then in Portuguese. `scripts/release.sh` publishes the section of the version being released as the GitHub release notes.

Cada versão aparece em inglês e depois em português. O `scripts/release.sh` publica a secção da versão que está a ser lançada como notas da release no GitHub.

## v0.11

> ⚠️ **Reinstall once.** Releases are now built and signed on the developer's machine, always with the same key, so from now on updates install over the previous version, straight from the app. Older releases were signed with a different key by GitHub, so uninstall your current Odin Hub once and install this APK.

### 🛠️ Fixes

- **Updates install over the old version.** Every release is signed with the same key from now on.
- **Update notification:** Odin Hub checks for new versions in the background every few hours and shows a notification. Tapping it opens the update pop-up.
- **Update pop-up:** the app checks again every time you open it (at most every 30 minutes, instead of every 6 hours).
- **HUD FPS is back,** and CPU load works too. CPU load is read from the cores' idle time (no root needed), and FPS gets its own call to the system.
- **The HUD no longer cuts off new metrics:** the window grows as you add items, and the horizontal bar wraps to a second line instead of running off the screen.
- **Screen recording from the Home button** now starts. It records the whole screen without audio and needs no app-picker dialog. Files go to `Movies/OdinHub`.
- **Double tap:** the time allowed between taps is adjustable (300 / 450 / 600 / 800 ms) in the Home Button Gestures card, and the default is a little longer than before.

### 🇧🇷 Português

> ⚠️ **Reinstale uma vez.** As releases passam a ser compiladas e assinadas no computador do desenvolvedor, sempre com a mesma chave, então a partir de agora as atualizações instalam por cima da versão anterior, direto pelo app. As versões antigas foram assinadas pelo GitHub com outra chave, por isso desinstale o Odin Hub atual uma vez e instale este APK.

- **As atualizações instalam por cima da versão antiga.** Todas as releases passam a ter a mesma assinatura.
- **Notificação de atualização:** o Odin Hub procura versões novas em segundo plano a cada poucas horas e mostra uma notificação. Tocar nela abre o pop-up de atualização.
- **Pop-up de atualização:** o app verifica de novo sempre que você o abre (no máximo a cada 30 minutos, em vez de 6 horas).
- **O FPS voltou ao HUD,** e o uso da CPU também funciona. O uso da CPU é lido pelo tempo ocioso dos núcleos (sem root), e o FPS tem a sua própria chamada ao sistema.
- **O HUD não corta mais as métricas novas:** a janela cresce quando você adiciona itens, e a barra horizontal quebra para uma segunda linha em vez de sair da tela.
- **A gravação de tela pelo botão Home** agora inicia. Grava a tela inteira, sem áudio e sem o diálogo de escolher app. Os vídeos ficam em `Movies/OdinHub`.
- **Toque duplo:** o tempo entre toques é ajustável (300 / 450 / 600 / 800 ms) no cartão Gestos do Botão Home, e o padrão ficou um pouco maior.

## v0.10

### 🛠️ Fixes

- **Glass on the tab bar:** the side tab bar (PERF / DISP / CTRL / SYS) uses the Glass material too, and the active tab gets a translucent pill.
- **Prime frequencies on the Odin 3:** the clock chips always include the three highest steps, so 4204 MHz is back between 4089 and 4320 MHz. The same applies to every cluster and device.
- **CPU load in the HUD** was showing `--%` on some setups.

### 🇧🇷 Português

- **Vidro na barra de abas:** a barra lateral (PERF / DISP / CTRL / SYS) também usa o vidro, e a aba ativa ganha uma pílula translúcida.
- **Frequências do Prime no Odin 3:** os chips de clock incluem sempre os três degraus mais altos, então o 4204 MHz voltou entre 4089 e 4320 MHz. Vale para todos os clusters e aparelhos.
- **Uso da CPU no HUD** aparecia como `--%` em alguns aparelhos.

## v0.9 — Performance HUD, Glass & Auto-update

### ✨ New

- **Performance HUD:** a MangoHud-style overlay over any app. It shows FPS and 1% low, frame time with a graph, CPU and GPU load, temperature and clocks (per cluster or per core), RAM, power draw, battery and the time. Every metric is optional, and you can pick a horizontal or vertical layout. It follows the app theme. Double-tap the HUD to hide it.
- **Glass appearance:** a Liquid Glass–style mode on top of every theme.
- **Auto-update:** a pop-up with the release notes, then a one-tap download and install.
- **TDP profiles per chip,** with steps far enough apart to feel different (Odin 3: 7 / 10 / 15 W, up to 25 W).
- **Efficiency cluster:** on the 8 Gen 2 and 865 it gets its own clock row.

### 🛠️ Fixes

- Missing top ("boost") CPU frequencies.
- Home button gestures (the firmware's "prevent accidental Home press" was swallowing the first press).
- First-launch screens scroll in landscape.
- The GitHub / "Check for updates" buttons no longer lead to a 404 page.

### 🇧🇷 Português

- **HUD de desempenho:** sobreposição estilo MangoHud por cima de qualquer app. Mostra FPS e 1% low, frame time com gráfico, uso, temperatura e clocks da CPU e da GPU (por cluster ou por núcleo), RAM, consumo, bateria e hora. Cada métrica é opcional, e o layout pode ser horizontal ou vertical. Segue o tema do app. Dois toques no HUD o escondem.
- **Aparência de vidro:** um modo estilo Liquid Glass por cima de qualquer tema.
- **Atualização automática:** pop-up com as novidades, e depois download e instalação num toque.
- **Perfis de TDP por chip,** com degraus bem separados (Odin 3: 7 / 10 / 15 W, até 25 W).
- **Cluster de eficiência:** no 8 Gen 2 e no 865 ganha a sua própria linha de clocks.
- **Correções:** as frequências de topo ("boost") que faltavam, os gestos do botão Home, a rolagem das telas iniciais em modo paisagem e os botões do GitHub sem 404.

## v0.8

- Multi-device support (Odin 2 family, Thor, Retroid Pocket 4 / 5 / 6 / Mini / Nova) with automatic detection, AutoTDP v2 and a general code review.
- 🇧🇷 Suporte a vários aparelhos (família Odin 2, Thor, Retroid Pocket 4 / 5 / 6 / Mini / Nova) com detecção automática, AutoTDP v2 e uma revisão geral do código.
