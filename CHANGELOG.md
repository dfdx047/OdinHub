# Changelog

Each version is listed in English, then in Portuguese. `scripts/release.sh` publishes the section of the version being released as the GitHub release notes.

Cada versão aparece em inglês e depois em português. O `scripts/release.sh` publica a seção da versão que está sendo lançada como notas da release no GitHub.

## v0.15 — Odin 2 Mini

> Odin Hub now also supports the **AYN Odin 2 Mini**. On the Odin 3 it installs over v0.14 and keeps your settings, profiles and per-game rules.

### New

- **AYN Odin 2 Mini support** (Snapdragon 8 Gen 2). Clock tables for its three CPU clusters and the Adreno 740, TDP profiles (Power Save 4.5 W, Balanced 6.5 W, Triple A 9 W, slider up to 12 W), CPU cores on/off and the thermal limit, tested on the device (firmware 1.0.0.117). On this device Odin Hub uses root through KernelSU, because SELinux blocks apps from PServerBinder there. The M1/M2 options are hidden, since the device has no rear buttons.
- **Odin 2 Mini: the firmware can no longer undo your settings.** AYN's `pservice` rewrote the CPU and GPU caps *and* floors every time the performance mode changed. Outside Stock, Odin Hub now sets its values and makes those nodes read-only; Stock hands them back to the firmware.
- **Bubble Blue theme**, inspired by the PS Vita home screen. It is the default on the Odin 2 Mini.
- **Profiles you can trust at a glance.** The profile in use is marked **✓ ACTIVE**, even when you applied the same values by hand or saved the profile before this version. Link a profile to a game, in the overlay or in per-game rules, and it is applied every time the game opens; updating the profile updates every game linked to it.
- **Selected options stand out.** The TDP / Clocks mode, the TDP profile and the clock chips are filled, outlined and marked with ✓. While Clock mode is on, the remembered TDP profile is only outlined, so it no longer looks half-selected. In the overlay, each clock row scrolls to the selected frequency.
- **HUD on one straight line.** The horizontal bar never wraps to a second line any more: the window takes the exact width of the metrics and stays on screen when you drag it to an edge. This also fixes the jumps while dragging it.
- **Active profile in the HUD:** new optional **Profile** metric (System group) shows the profile in use, the game's linked profile or "Manual".
- **A small HUD stays usable.** The minimum size is now 0.8× (it was 0.6×), and the HUD keeps a 40 dp touch area, so a tiny HUD in a corner can still be grabbed.
- **Calmer sound.** The menu music is softer and the UI sounds are subtler. On update, a music volume above 25 % and an effects volume above 35 % are lowered to those values once; a lower volume you chose is kept.

### Fixes

- The overlay now shows which global profile a game follows when the game has no rule of its own.
- Turning a CPU core back on no longer leaves its cluster capped at an old frequency.
- Saved clocks from another device or an older version snap to the nearest frequency this device supports.

### Português

> O Odin Hub agora também suporta o **AYN Odin 2 Mini**. No Odin 3 ele instala por cima da v0.14 e mantém suas configurações, perfis e regras por jogo.

- **Suporte ao AYN Odin 2 Mini** (Snapdragon 8 Gen 2). Tabelas de clock dos três clusters da CPU e da Adreno 740, perfis de TDP (Economia 4,5 W, Equilibrado 6,5 W, Triple A 9 W, slider até 12 W), núcleos da CPU liga/desliga e limite térmico, testados no aparelho (firmware 1.0.0.117). Nesse aparelho o Odin Hub usa root pelo KernelSU, porque o SELinux bloqueia o PServerBinder para os apps. As opções de M1/M2 ficam escondidas, porque o aparelho não tem botões traseiros.
- **Odin 2 Mini: o firmware não desfaz mais os seus ajustes.** O `pservice` da AYN reescrevia os limites *e* os pisos da CPU e da GPU a cada troca de modo de desempenho. Fora do Stock, o Odin Hub agora aplica os seus valores e deixa esses nós só de leitura; o Stock devolve-os ao firmware.
- **Tema Azul Bolha**, inspirado na tela inicial do PS Vita. É o padrão no Odin 2 Mini.
- **Perfis que dá para confiar só de olhar.** O perfil em uso fica marcado com **✓ ATIVO**, mesmo quando você aplicou os mesmos valores à mão ou salvou o perfil antes desta versão. Ligue um perfil a um jogo, no overlay ou nas regras por jogo, e ele é aplicado sempre que o jogo abrir; atualizar o perfil atualiza todos os jogos ligados a ele.
- **As opções escolhidas se destacam.** O modo TDP / Clocks, o perfil de TDP e os chips de clock ficam preenchidos, com borda e ✓. Com o modo Clocks ligado, o perfil de TDP guardado fica só contornado, sem parecer meio selecionado. No overlay, cada linha de clock rola até a frequência escolhida.
- **HUD numa linha reta.** A barra horizontal não quebra mais para uma segunda linha: a janela tem a largura exata das métricas e fica dentro da tela quando você a arrasta até uma borda. Isso também acaba com os saltos ao arrastar.
- **Perfil ativo no HUD:** nova métrica opcional **Perfil** (grupo Sistema), que mostra o perfil em uso, o ligado ao jogo ou "Manual".
- **HUD pequeno continua usável.** O tamanho mínimo agora é 0,8× (era 0,6×), e o HUD mantém uma área de toque de 40 dp, para um HUD pequenino num canto ainda dar para pegar.
- **Som mais calmo.** A música do menu está mais suave e os sons da interface mais discretos. Na atualização, um volume de música acima de 25 % e de efeitos acima de 35 % descem para esses valores uma vez; um volume mais baixo que você escolheu fica como está.
- **Correções:** o overlay mostra qual perfil global um jogo segue quando ele não tem regra própria; religar um núcleo da CPU não deixa mais o cluster preso numa frequência antiga; clocks salvos de outro aparelho ou de uma versão antiga vão para a frequência mais próxima que o aparelho suporta.

## v0.14 — Odin 3 edition

> **Odin 3 only.** From this version on, Odin Hub supports the AYN Odin 3 only. If you use it on an Odin 2, Thor or Retroid Pocket, stay on v0.13 and skip this update. On the Odin 3 it installs over v0.11, v0.12 or v0.13 and keeps your settings and per-game rules.

### New

- **Made for the AYN Odin 3.** Clock tables, GPU control, thermal zones and TDP profiles are tuned for its Snapdragon 8 Elite (6 + 2 Oryon cores, Adreno 830, up to 1100 MHz) and were tested on the device. TDP profiles: Power Save 7 W, Balanced 10 W, Triple A 15 W, slider up to 25 W.
- **CPU cores on/off,** globally and per game (Performance > **CPU cores**, the overlay's **Cores** tab and per-game rules). The cards show which cores are really online. CPU0 never turns off, and every core comes back online in Stock, when you leave the game and at boot.
- **Clock pinning:** pin each cluster or the GPU at its cap (floor = cap) for steady frame pacing.
- **Save as global profile:** one button in the overlay and in per-game rules copies the current settings to the global profile. **Use global** drops the game's own rule.
- **HUD controls in the overlay** (new **HUD** tab): turn it on or off, presets (FPS only, Essential, Standard, Full), each metric, layout, style, position, size and background. Changes show up live.
- **Smoother HUD:** real FPS from SurfaceFlinger, frame-time graph, smooth dragging, pinch or corner resize, new styles.
- **New themes,** in groups (Odin signature, Vibrant, Retro, System) with a visual picker.
- **New icon and boot animation.**

### Fixes

- Firmware CPU floors (perf HAL, `msm_performance`) are read from the Odin 3's own path, so the app can explain why a cap won't go lower.
- Root commands run one simple command per call, and multi-step actions (such as installing an update) run as a small script. This is more reliable with PServerBinder.
- **TDP:** a small change of the target (for example 10 to 11.4 W, or a slow drag of the slider) now moves the CPU and GPU caps right away, also while charging, when AutoTDP is paused.
- **Custom fan curve:**
  - Saving a lower curve now slows the fan down right away (it used to stay at the old speed while the temperature was steady).
  - The 90 °C safety rule now looks at the hottest sensor, CPU or GPU, not only the one the curve follows.
  - A failed temperature reading now counts as "no reading" and runs the fan at 100 %, instead of keeping the previous speed.
  - Switching from Custom to another mode no longer leaves the fan stuck at a fixed speed.
  - **Stock** in the in-game overlay now follows the global fan mode, so a global custom curve is no longer switched off inside the game.
  - The curve editor no longer hides the bottom of the graph behind the gesture bar, and the line before the first point now shows the real speed (the fan stays at that point's speed, it doesn't stop).
- The HUD can read all 20 CPU temperature sensors of the Odin 3 through PServerBinder (it stopped at 16).

### Português

> **Só para o Odin 3.** A partir desta versão o Odin Hub suporta apenas o AYN Odin 3. Se você usa num Odin 2, Thor ou Retroid Pocket, continue na v0.13 e não instale esta atualização. No Odin 3 ela instala por cima da v0.11, v0.12 ou v0.13 e mantém suas configurações e regras por jogo.

- **Feito para o AYN Odin 3.** Tabelas de clock, controle da GPU, zonas térmicas e perfis de TDP ajustados ao Snapdragon 8 Elite (6 + 2 núcleos Oryon, Adreno 830, até 1100 MHz) e testados no aparelho. Perfis de TDP: Economia 7 W, Equilibrado 10 W, Triple A 15 W, slider até 25 W.
- **Ligar e desligar núcleos da CPU,** no global e por jogo (Desempenho > **Núcleos da CPU**, aba **Núcleos** do overlay e regras por jogo). Os cartões mostram quais núcleos estão realmente ligados. O CPU0 nunca desliga, e todos voltam a ligar no Stock, quando você sai do jogo e ao ligar o aparelho.
- **Fixar clocks:** fixa cada cluster ou a GPU no teto (piso = teto), para um frame pacing estável.
- **Salvar como perfil global:** um botão no overlay e nas regras por jogo copia os ajustes atuais para o perfil global. **Usar o global** apaga a regra própria do jogo.
- **Controles do HUD no overlay** (nova aba **HUD**): ligar e desligar, predefinições (Só FPS, Essencial, Padrão, Completo), cada métrica, layout, estilo, posição, tamanho e fundo, tudo em tempo real.
- **HUD mais fluido:** FPS real do SurfaceFlinger, gráfico de frame time, arrastar suave, redimensionar com pinça ou pelo canto, estilos novos.
- **Temas novos,** em grupos (Assinatura Odin, Vibrantes, Retrô, Sistema), com seletor visual.
- **Ícone e animação de abertura novos.**
- Correções: os pisos de CPU do firmware (perf HAL, `msm_performance`) são lidos no caminho do Odin 3, então o app consegue explicar por que um limite não desce mais. Os comandos root rodam um de cada vez, e as ações com vários passos (como instalar uma atualização) rodam como um pequeno script, o que é mais confiável com o PServerBinder.
- **TDP:** uma mudança pequena do alvo (por exemplo de 10 para 11,4 W, ou arrastar o slider devagar) agora move os limites de CPU e GPU na hora, também carregando, quando o AutoTDP fica em pausa.
- **Curva da ventoinha personalizada:**
  - Salvar uma curva mais baixa agora desacelera a ventoinha na hora (antes ela ficava na velocidade antiga enquanto a temperatura não mudava).
  - A regra de segurança dos 90 °C agora olha o sensor mais quente, CPU ou GPU, e não só o que a curva segue.
  - Uma leitura de temperatura que falha agora conta como "sem leitura" e põe a ventoinha a 100 %, em vez de manter a velocidade anterior.
  - Trocar do Custom para outro modo não deixa mais a ventoinha presa numa velocidade fixa.
  - O **Stock** no overlay dentro do jogo agora segue o modo global da ventoinha, então uma curva personalizada global não é mais desligada no jogo.
  - O editor da curva não esconde mais a parte de baixo do gráfico atrás da barra de gestos, e a linha antes do primeiro ponto agora mostra a velocidade real (a ventoinha fica na velocidade desse ponto, não para).
- O HUD consegue ler pelo PServerBinder os 20 sensores de temperatura da CPU do Odin 3 (parava em 16).

## v0.13 — Custom fan curve

### New

- **Custom fan curve,** in the spirit of AYN's own fan screen (Performance › Fan Control › **Custom fan curve › Edit**):
  - Drag the points on the graph, or tap one and fine-tune it with − / + (it works with the D-pad).
  - °C or °F, and the curve can follow the CPU, the GPU or whichever is hotter.
  - Current status (CPU and GPU temperature, fan speed) and an orange dot that shows where you are on the curve.
  - CPU and GPU stress tests (60 s) to watch the curve react, plus Save and Reset.
  - Adjustable smoothing, so the fan doesn't keep speeding up and slowing down.
  - **Safety:** at 90 °C or more the fan always runs at 100 %, and if no temperature can be read the fan runs at 100 %.
  - Saving turns on the new **Custom** fan mode. It's also available in the side overlay and in per-game rules.
- Odin Hub drives the fan through the firmware's manual-speed mode. If the fan doesn't respond on your device, the editor's **Advanced** section lets you pick another firmware mode or speed unit, with a **Test** button (100 % for 5 s) to check.

### Fixes

- **AYN Thor: battery, power draw and GPU load in the HUD** (they showed `--`). This firmware doesn't let apps read the battery and GPU-load nodes. Battery %, power draw and battery temperature now come from Android's own battery API, and GPU load is read through PServerBinder.
- **AutoTDP on the Thor:** it had no power reading for the same reason, so it couldn't settle on the target. It now uses the same fallback.
- **Sensor diagnostics** also lists the battery and GPU nodes (read directly, read with root, and the Android API value).

### Português

- **Curva da ventoinha personalizada,** no estilo do ecrã de ventoinha da AYN (Desempenho › Controle de Ventoinha › **Curva da ventoinha personalizada › Editar**):
  - Arraste os pontos no gráfico, ou toque num e afine com − / + (funciona com o D-pad).
  - °C ou °F, e a curva pode seguir a CPU, a GPU ou a mais quente das duas.
  - Estado atual (temperatura da CPU e da GPU, velocidade da ventoinha) e um ponto laranja que mostra onde você está na curva.
  - Testes de stress da CPU e da GPU (60 s) para ver a curva reagir, além de Guardar e Repor.
  - Suavização ajustável, para a ventoinha não ficar acelerando e desacelerando sem parar.
  - **Segurança:** a partir de 90 °C a ventoinha vai sempre a 100 %, e se não houver leitura de temperatura também vai a 100 %.
  - Guardar liga o novo modo de ventoinha **Custom**, que também aparece no overlay lateral e nas regras por jogo.
- O Odin Hub controla a ventoinha pelo modo de velocidade manual do firmware. Se a ventoinha não responder no seu aparelho, a seção **Avançado** do editor deixa escolher outro modo ou unidade de velocidade, com um botão **Testar** (100 % durante 5 s) para confirmar.
- **AYN Thor: bateria, consumo e uso da GPU no HUD** (apareciam `--`). Este firmware não deixa os apps lerem os nós da bateria e do uso da GPU. A bateria %, o consumo e a temperatura da bateria passam a vir da API de bateria do próprio Android, e o uso da GPU é lido pelo PServerBinder.
- **AutoTDP no Thor:** pelo mesmo motivo ficava sem medição de consumo e não conseguia estabilizar no alvo. Agora usa o mesmo recurso.
- **Diagnóstico dos sensores** também lista os nós da bateria e da GPU (leitura direta, leitura com root e o valor da API do Android).

## v0.12

### Fixes

- **Missing CPU / GPU temperatures in the HUD (Thor, Retroid and other 8 Gen 2 devices):** on some firmwares the system doesn't let apps read the temperature sensors directly, and Odin Hub used to skip them. Those sensors are now read through PServerBinder, all in a single call per second. Sensor names from more kernels are also recognized.
- **New: Sensor diagnostics** in the Performance HUD card. It lists every temperature sensor on the device, with its type, whether it can be read directly or only with root, and which ones the HUD uses. **Copy** and **Share** buttons make it easy to send in a bug report.

### Português

- **Temperaturas da CPU / GPU faltando no HUD (Thor, Retroid e outros 8 Gen 2):** em alguns firmwares o sistema não deixa os apps lerem os sensores de temperatura diretamente, e o Odin Hub os ignorava. Agora esses sensores são lidos pelo PServerBinder, todos numa única chamada por segundo. Também são reconhecidos os nomes de sensores de mais kernels.
- **Novo: Diagnóstico dos sensores** no cartão do HUD de Desempenho. Lista todos os sensores de temperatura do aparelho, com o tipo, se dá para ler direto ou só com root, e quais o HUD usa. Os botões **Copiar** e **Partilhar** facilitam enviar num relato de bug.

## v0.11

> **Reinstall once.** Releases are now built and signed on the developer's machine, always with the same key, so from now on updates install over the previous version, straight from the app. Older releases were signed with a different key by GitHub, so uninstall your current Odin Hub once and install this APK.

### Fixes

- **Updates install over the old version.** Every release is signed with the same key from now on.
- **Update notification:** Odin Hub checks for new versions in the background every few hours and shows a notification. Tapping it opens the update pop-up.
- **Update pop-up:** the app checks again every time you open it (at most every 30 minutes, instead of every 6 hours).
- **HUD FPS is back,** and CPU load works too. CPU load is read from the cores' idle time (no root needed), and FPS gets its own call to the system.
- **The HUD no longer cuts off new metrics:** the window grows as you add items, and the horizontal bar wraps to a second line instead of running off the screen.
- **Screen recording from the Home button** now starts. It records the whole screen without audio and needs no app-picker dialog. Files go to `Movies/OdinHub`.
- **Double tap:** the time allowed between taps is adjustable (300 / 450 / 600 / 800 ms) in the Home Button Gestures card, and the default is a little longer than before.

### Português

> **Reinstale uma vez.** As releases passam a ser compiladas e assinadas no computador do desenvolvedor, sempre com a mesma chave, então a partir de agora as atualizações instalam por cima da versão anterior, direto pelo app. As versões antigas foram assinadas pelo GitHub com outra chave, por isso desinstale o Odin Hub atual uma vez e instale este APK.

- **As atualizações instalam por cima da versão antiga.** Todas as releases passam a ter a mesma assinatura.
- **Notificação de atualização:** o Odin Hub procura versões novas em segundo plano a cada poucas horas e mostra uma notificação. Tocar nela abre o pop-up de atualização.
- **Pop-up de atualização:** o app verifica de novo sempre que você o abre (no máximo a cada 30 minutos, em vez de 6 horas).
- **O FPS voltou ao HUD,** e o uso da CPU também funciona. O uso da CPU é lido pelo tempo ocioso dos núcleos (sem root), e o FPS tem a sua própria chamada ao sistema.
- **O HUD não corta mais as métricas novas:** a janela cresce quando você adiciona itens, e a barra horizontal quebra para uma segunda linha em vez de sair da tela.
- **A gravação de tela pelo botão Home** agora inicia. Grava a tela inteira, sem áudio e sem o diálogo de escolher app. Os vídeos ficam em `Movies/OdinHub`.
- **Toque duplo:** o tempo entre toques é ajustável (300 / 450 / 600 / 800 ms) no cartão Gestos do Botão Home, e o padrão ficou um pouco maior.

## v0.10

### Fixes

- **Glass on the tab bar:** the side tab bar (PERF / DISP / CTRL / SYS) uses the Glass material too, and the active tab gets a translucent pill.
- **Prime frequencies on the Odin 3:** the clock chips always include the three highest steps, so 4204 MHz is back between 4089 and 4320 MHz. The same applies to every cluster and device.
- **CPU load in the HUD** was showing `--%` on some setups.

### Português

- **Vidro na barra de abas:** a barra lateral (PERF / DISP / CTRL / SYS) também usa o vidro, e a aba ativa ganha uma pílula translúcida.
- **Frequências do Prime no Odin 3:** os chips de clock incluem sempre os três degraus mais altos, então o 4204 MHz voltou entre 4089 e 4320 MHz. Vale para todos os clusters e aparelhos.
- **Uso da CPU no HUD** aparecia como `--%` em alguns aparelhos.

## v0.9 — Performance HUD, Glass & Auto-update

### New

- **Performance HUD:** a MangoHud-style overlay over any app. It shows FPS and 1% low, frame time with a graph, CPU and GPU load, temperature and clocks (per cluster or per core), RAM, power draw, battery and the time. Every metric is optional, and you can pick a horizontal or vertical layout. It follows the app theme. Double-tap the HUD to hide it.
- **Glass appearance:** a Liquid Glass–style mode on top of every theme.
- **Auto-update:** a pop-up with the release notes, then a one-tap download and install.
- **TDP profiles per chip,** with steps far enough apart to feel different (Odin 3: 7 / 10 / 15 W, up to 25 W).
- **Efficiency cluster:** on the 8 Gen 2 and 865 it gets its own clock row.

### Fixes

- Missing top ("boost") CPU frequencies.
- Home button gestures (the firmware's "prevent accidental Home press" was swallowing the first press).
- First-launch screens scroll in landscape.
- The GitHub / "Check for updates" buttons no longer lead to a 404 page.

### Português

- **HUD de desempenho:** sobreposição estilo MangoHud por cima de qualquer app. Mostra FPS e 1% low, frame time com gráfico, uso, temperatura e clocks da CPU e da GPU (por cluster ou por núcleo), RAM, consumo, bateria e hora. Cada métrica é opcional, e o layout pode ser horizontal ou vertical. Segue o tema do app. Dois toques no HUD o escondem.
- **Aparência de vidro:** um modo estilo Liquid Glass por cima de qualquer tema.
- **Atualização automática:** pop-up com as novidades, e depois download e instalação num toque.
- **Perfis de TDP por chip,** com degraus bem separados (Odin 3: 7 / 10 / 15 W, até 25 W).
- **Cluster de eficiência:** no 8 Gen 2 e no 865 ganha a sua própria linha de clocks.
- **Correções:** as frequências de topo ("boost") que faltavam, os gestos do botão Home, a rolagem das telas iniciais em modo paisagem e os botões do GitHub sem 404.

## v0.8

- Multi-device support (Odin 2 family, Thor, Retroid Pocket 4 / 5 / 6 / Mini / Nova) with automatic detection, AutoTDP v2 and a general code review.
- Suporte a vários aparelhos (família Odin 2, Thor, Retroid Pocket 4 / 5 / 6 / Mini / Nova) com detecção automática, AutoTDP v2 e uma revisão geral do código.
