<p align="center">
  <img src="./assets/logo.png" width="300" alt="Logo do Odin Hub">
</p>

<h1 align="center">Odin Hub</h1>

<p align="center">
  Uma central de controle de hardware e desempenho, com cara de console, para os portáteis <b>AYN Odin</b> e <b>Retroid Pocket</b>.<br>
  Feito por <b>dfdx047</b>.
</p>

<p align="center">
  <a href="./README.md">English</a> · <b>Português (Brasil)</b>
</p>

<p align="center">
  <a href="https://github.com/dfdx047/OdinHub/releases"><img src="https://img.shields.io/github/v/release/dfdx047/OdinHub?include_prereleases&label=download&style=for-the-badge" alt="Última versão"></a>
  <img src="https://img.shields.io/badge/devices-Odin%202%20%7C%20Odin%203%20%7C%20Thor%20%7C%20Retroid-7c6cf0?style=for-the-badge" alt="AYN Odin / Thor / Retroid Pocket">
  <img src="https://img.shields.io/badge/root-n%C3%A3o%20precisa-2ea44f?style=for-the-badge" alt="Não precisa de root">
</p>

<p align="center">
  <img src="./docs/screenshots/01-tdp-profiles.png" width="85%" alt="Odin Hub — perfis de TDP">
</p>

---

## O que é

O Odin 3 vem com um Snapdragon 8 Elite (e o Odin 2, o Thor e o Retroid Pocket 6 com um 8 Gen 2), muito mais potente do que a maioria dos jogos e emuladores Android precisa. Sem controle, ele esquenta, gasta a bateria rápido e entra em throttling. Tudo isso para rodar um jogo de PS2 que ficaria feliz com um terço dessa potência.

O Odin Hub fica entre você e esse chip. Ele deixa você decidir, **jogo a jogo**, quanto do aparelho gastar de fato:

- Limitar CPU/GPU a um consumo alvo e mantê-lo ali.
- Ou fixar frequências exatas por cluster de CPU, quando você quer um comportamento previsível.
- Ajustar a ventoinha e a calibração da tela a combinar.
- Salvar tudo como uma regra daquele jogo, aplicada sozinha quando o jogo abre e desfeita quando você sai.

A ideia é configurar um jogo **uma vez** e nunca mais pensar nisso.

## A ideia central: perfis por jogo

Este é o recurso que todo o resto existe para servir.

```
Você abre o GTA San Andreas
        │
        ▼
O Odin Hub detecta o app em primeiro plano
        │
        ▼
Procura a regra desse jogo no banco de dados
        │
        ├── achou     → aplica limite de 5 W, ventoinha silenciosa, cores vivas
        └── não achou → aplica o seu perfil global
        │
        ▼
Você sai do jogo → o aparelho volta ao perfil global
```

As regras ficam num único banco de dados Room e são editadas em **dois lugares que ficam sincronizados**:

- Tela **Configurações por Aplicativo**: configure um jogo com calma, antes de jogar.
- **Overlay no jogo**: uma alça lateral que você puxa no meio do jogo, como o painel "modo jogo" dos celulares. Mude o TDP enquanto joga; a regra daquele jogo é criada ou atualizada na hora.

Mude um jogo num lugar e o outro reflete. Não existe uma segunda fonte de verdade.

## Capturas de tela

| | |
|---|---|
| ![Clocks manuais](./docs/screenshots/02-manual-clocks.png) | ![Limite térmico](./docs/screenshots/03-thermal-limit.png) |
| **Tabelas de clock reais**: cada chip é uma frequência que o SoC realmente suporta | **Limite térmico**: mude o ponto de throttling, no global ou por jogo |
| ![Gestos do Home](./docs/screenshots/05-home-gestures.png) | ![Editor de macros](./docs/screenshots/06-macro-editor.png) |
| **Gestos do botão Home**: captura de tela, gravação da tela inteira, overlay… | **Macros M1 / M2**: combos e sequências temporizadas, no global ou por jogo |
| ![Calibração de cor](./docs/screenshots/07-color-calibration.png) | ![Regras por jogo](./docs/screenshots/04-per-game-rules.png) |
| **Calibração de cor**: saturação e balanço de branco, ao vivo | **Regras por jogo**: aplicadas quando o jogo abre, desfeitas quando você sai |

## Recursos

### Desempenho
- **TDP dinâmico (AutoTDP)**: um ciclo fechado que mede o consumo real na bateria e ajusta os clocks de CPU/GPU até o aparelho se estabilizar no consumo alvo. Ele filtra a leitura ruidosa da bateria, corta rápido quando passa do limite e sobe devagar quando sobra (sem engasgos em serra), e, quando a GPU é o gargalo, tira o corte da CPU (e vice-versa). Pausa durante a carga (a bateria não consegue medir o aparelho nessa hora) e com a tela desligada.
- **Perfis de TDP**: Economia, Equilibrado e Triple A, dimensionados para **o aparelho em que está rodando** (7 / 10 / 15 W no Odin 3, 5 / 7 / 10 W no Odin 2, 3,5 / 4,5 / 6 W no Retroid Pocket 5; veja a tabela abaixo), mais Stock (sem limite). Os perfis são independentes do slider livre.
- **Clock por cluster**: as frequências reais de cada cluster da CPU (Eficiência / Perf / Prime nos chips de três clusters, como o 8 Gen 2 e o 865, incluindo os degraus "boost" de topo) e da GPU, lidas ao vivo das tabelas do kernel, com uma tabela fixa por SoC como reserva. Só um dos dois fica ativo de cada vez: TDP ou clocks manuais.
- **Limite térmico**: opcionalmente, mude os primeiros pontos de throttling passivo das zonas térmicas da CPU/GPU (85 / 90 / 95 °C ou sem limite), no global ou por jogo. É escrito pelo PServerBinder como todo o resto, sem precisar de KernelSU/Magisk. Os pontos de emergência e os **da ventoinha** nunca são mexidos, e o Stock restaura os valores de fábrica (o backup é ligado ao boot atual, então nunca "restaura" um valor já modificado).
- **Controle da ventoinha**: Stock, Silencioso, Inteligente e Sport, ligados aos modos reais da ventoinha do aparelho.
- **Perfis personalizados**: salve os seus próprios presets de TDP ou de clocks com um nome.

### Tela
- Saturação (0–2×) e temperatura de cor (4000–9000 K, mais quente ou mais fria), aplicadas ao vivo pela própria transformação de cor do SurfaceFlinger.
- Cor por jogo, pela tela de Configurações por Aplicativo ou pelo overlay no jogo.

### Controles
- Remapeie os botões traseiros M1 / M2 para qualquer código de tecla, ou para uma **macro / combo** (vários botões ao mesmo tempo, ou uma sequência temporizada). As macros também podem ser **por jogo**, pela tela de Configurações por Aplicativo ou pelo overlay.
- **Gestos do botão Home**: toque simples, duplo, triplo e longo, cada um ligado a uma ação do sistema: captura de tela, gravação da tela inteira (sem escolher app, sem áudio, salva em Movies/OdinHub), abrir o overlay, recentes, notificações, bloquear a tela e mais. O tempo entre toques é ajustável.
- Ligue um botão físico para abrir o overlay no jogo.

### HUD de desempenho
- Uma pequena sobreposição estilo MangoHud por cima de **qualquer app**: FPS, frame time (com gráfico), uso e temperatura da CPU, clocks por cluster ou por núcleo, uso / temperatura / clock da GPU, RAM, consumo, bateria % e temperatura, alvo de TDP e hora.
- **Tudo é opcional**: ligue ou desligue cada métrica. Barra horizontal ou painel vertical, cinco posições, arraste para mover, três tamanhos de texto, fundo ajustável.
- Segue o tema do app, incluindo o modo vidro. **Dois toques escondem o HUD**; ele volta pelo overlay lateral, pelo gesto do Home "Mostrar/esconder HUD de desempenho" ou pelas Configurações.
- Leve: tudo é lido direto do sysfs (o uso da CPU vem do tempo ocioso dos núcleos); só o FPS / frame time precisa de uma chamada ao PServerBinder por segundo. A leitura para enquanto o HUD está escondido ou a tela está desligada.
- A janela cresce quando você adiciona métricas, e a barra horizontal quebra para uma segunda linha em vez de sair da tela.

### Interface
- **Aparência de vidro**: um modo estilo Liquid Glass que funciona por cima de qualquer tema, com superfícies translúcidas e borda de luz; o overlay lateral também desfoca o jogo por trás quando o sistema permite.
- **Atualização automática**: o Odin Hub procura uma versão nova no GitHub quando você o abre e a cada poucas horas em segundo plano (com notificação), mostra um pop-up com as novidades, baixa o APK e instala por cima pelo PServerBinder (ou passa para o instalador do Android).
- Sete temas inspirados em consoles (Odin OS, Cyberpunk, SNES, NES, PlayStation, Xbox, Steam OS), mais um tema claro, cores dinâmicas do Material You e um modo AMOLED preto.
- Navegável pelo D-pad, com vibração e brilho de foco.
- Animação de boot, papéis de parede personalizados e um mixer de música de fundo e efeitos sonoros.

## Aparelhos suportados

O Odin Hub detecta o aparelho ao abrir ("AYN Odin 2 detectado") e troca as tabelas de frequência, as zonas térmicas, o controle da GPU e os perfis de TDP para combinar. Todo o resto (overlay, regras por jogo, macros, gestos) é igual em todos: a AYN e a Retroid usam o mesmo firmware base do fabricante (Moorechip) e o mesmo `PServerBinder`.

| Aparelho | SoC | Controle da GPU | Limite térmico | M1/M2 | Perfis de TDP (Eco / Equil. / AAA / máx.) |
|---|---|---|---|---|---|
| AYN Odin 3 | Snapdragon 8 Elite | Adreno 830 (kgsl) | ✓ | ✓ | 7 / 10 / 15 / 25 W |
| AYN Odin 2 / Odin 2 Max / Odin 2 Portal | Snapdragon 8 Gen 2 (QCS8550) | Adreno 740, 680 MHz | ✓ | ✓ | 5 / 7 / 10 / 13 W |
| AYN Odin 2 Mini | Snapdragon 8 Gen 2 | Adreno 740 | ✓ | ✓ | 4,5 / 6,5 / 9 / 12 W |
| AYN Thor | Snapdragon 8 Gen 2 | Adreno 740 | ✓ | ✓ | 5 / 7 / 10 / 13 W |
| AYN Thor Lite | Snapdragon 865 | Adreno 650 | ✓ | ✓ | 3,5 / 4,5 / 6 / 8 W |
| Retroid Pocket 6 / Retroid Pocket Nova | Snapdragon 8 Gen 2 (QCS8550) | Adreno 740 | ✓ | se o firmware expuser | 4,5 / 6,5 / 9 / 12 W |
| Retroid Pocket 5 | Snapdragon 865 | Adreno 650, 670 MHz | ✓ | se o firmware expuser | 3,5 / 4,5 / 6 / 8 W |
| Retroid Pocket Mini / Mini V2 | Snapdragon 865 | Adreno 650 | ✓ | se o firmware expuser | 3 / 4 / 5,5 / 7 W |
| Retroid Pocket 4 Pro | Dimensity 1100 | Mali-G77 (MediaTek GED) | — (a MediaTek faz o throttling no userspace) | se o firmware expuser | 3,5 / 4,5 / 6 / 8 W |
| Retroid Pocket 4 | Dimensity 900 | Mali-G68 (MediaTek GED) | — | se o firmware expuser | 3 / 4 / 5 / 7 W |

Notas:

- Os valores de TDP são o consumo do aparelho inteiro na bateria, dimensionados a partir de medições publicadas de cada SoC em jogos e do arrefecimento de cada modelo. Os degraus são bem separados de propósito, para os perfis fazerem diferença de verdade; ajuste a gosto com o slider.
- No 8 Gen 2, as zonas térmicas do kernel têm pontos passivos de 50–85 °C que comandam a **ventoinha**, não o throttling; o Odin Hub os exclui (pelo dispositivo de arrefecimento e por uma temperatura mínima por SoC), para que um limite térmico nunca atrase a ventoinha.
- No Thor e no Retroid Pocket 6, o firmware sobe a frequência mínima de cada cluster conforme o seu próprio modo de desempenho. Quando um limite fica abaixo desse piso, o Odin Hub baixa o piso também, para o limite valer de verdade.
- Alguns firmwares (o Odin 2 Mini é o caso conhecido) não devolvem a saída dos comandos pelo PServerBinder. O Odin Hub detecta isso, passa para o modo sem saída e lê os valores direto do sysfs.
- Aparelhos desconhecidos funcionam num modo genérico: tabelas lidas do kernel e os limites do SoC conhecido mais próximo.

## Como ele fala com o hardware

O Odin Hub não roda `su` e não precisa de KernelSU/Magisk. Ele usa um auxiliar privilegiado que já existe no aparelho, o `PServerBinder`, que roda como root, através de `IBinder.transact()`, uma técnica criada pelo **ClusterTune** e pelo **P.U.L.S.E.** (veja os Créditos). Por esse canal ele escreve em nós do `sysfs` e nas configurações do sistema Android.

Duas consequências que vale a pena saber:

1. **Toda escrita é verificada, e só é feita quando precisa.** Antes de escrever, o nó é lido diretamente (sem root); se já tem o valor, nada é enviado. Depois de escrever, o valor é lido de volta e comparado com o anterior: uma escrita que o kernel rejeitou em silêncio aparece como falha, em vez de ser dada como feita. No modo Stock os nós são devolvidos ao sistema e deixados em paz.
2. **Os nós são escritos de forma independente.** Uma versão antiga encadeava todos os nós numa única string `cmd && cmd && cmd`, então um único nó rejeitado (um clock de GPU bloqueado pelo bootloader, por exemplo) cancelava todas as escritas seguintes. Agora cada nó dá certo ou falha sozinho.

A detecção do app em primeiro plano usa um serviço de acessibilidade, com uma consulta ao `UsageStatsManager` como reserva, para as regras por jogo e o overlay continuarem funcionando mesmo com esse serviço desligado.

## Requisitos

- Um dos aparelhos acima (ou outro portátil Android, no modo genérico).
- O `PServerBinder` disponível no aparelho (vem no firmware original da AYN e da Retroid).
- Permissões concedidas na primeira abertura: **Acesso ao uso** (detecção do app em primeiro plano) e **Sobrepor a outros apps** (o overlay no jogo).

## Instalação

1. Baixe o APK mais recente na página de [Releases](https://github.com/dfdx047/OdinHub/releases).
2. Instale no aparelho.
3. Abra e conceda as duas permissões que ele pede.

Depois disso, as versões novas aparecem dentro do app (pop-up e notificação) e instalam por cima da antiga. Se você instalou a v0.10 ou anterior, desinstale-a uma vez antes de instalar a v0.11: essas versões foram assinadas com outra chave.

## Como lançar uma versão

As releases são compiladas e publicadas **no computador do desenvolvedor**, não pelo GitHub Actions (o CI só roda os testes e um build de debug). Assim todas as releases saem assinadas com a mesma chave, que é o que o Android exige para instalar uma atualização por cima da versão anterior. Por padrão essa chave é a chave de debug do Android do computador (`~/.android/debug.keystore`), então não há nada para configurar. **Faça backup desse arquivo**: sem ele, a próxima release não instala por cima da que está instalada.

1. Suba `versionCode` e `versionName` em `app/build.gradle.kts`.
2. Adicione uma seção `## vX.Y` no topo do [`CHANGELOG.md`](./CHANGELOG.md), em inglês e português.
3. Faça o commit e rode:

```bash
./scripts/release.sh
```

O script roda os testes, compila o APK de release, cria e envia a tag `vX.Y` e publica a release no GitHub com o APK (`OdinHub-vX.Y.apk`) e a seção daquela versão do changelog como notas. Precisa do [GitHub CLI](https://cli.github.com/) com login feito (`gh auth login`).

Opcional: para usar uma chave própria em vez da chave de debug, coloque um arquivo `keystore.properties` (ignorado pelo git) na raiz do projeto, com `storeFile`, `storePassword`, `keyAlias` e `keyPassword`.

## Compilação

```bash
git clone https://github.com/dfdx047/OdinHub.git
cd OdinHub
./gradlew assembleDebug
```

Precisa do JDK 17 e do Android SDK (compileSdk 35, minSdk 33). Testes unitários:

```bash
./gradlew testDebugUnitTest
```

## Arquitetura

Algumas decisões de estrutura não são óbvias pelo código, e reaprendê-las do jeito difícil é perda de tempo. Se você vai mexer neste projeto, leia isto primeiro.

### `PerformanceCommandBuilder`: por que existe

As escritas no hardware são montadas num único lugar, em vez de improvisadas em cada chamada. Ele cuida de:

- **Descoberta dinâmica das políticas de cpufreq.** A organização dos clusters muda conforme o SoC e o firmware, então as políticas são procuradas em tempo de execução, em vez de fixar `policy0` / `policy6`. A política com a maior frequência máxima é tratada como Prime.
- **Escritas independentes e verificadas.** Cada nó é um `HardwareWriteOp` que é aplicado, lido de volta e repetido passo a passo se a forma encadeada falhou.
- **Um único lugar para os limites de proporção.** Em especial, o piso do TDP dinâmico é *menor* do que o dos clocks manuais: alvos baixos como 5 W precisam de espaço para descer os clocks o suficiente para estabilizar.

`PerformanceManager.lastApplyStatus` expõe o resultado da última passagem de escritas, para a interface mostrar uma falha real em vez de confiar numa escrita que nunca aconteceu.

### `AppOverrideRepository`: a fonte única de verdade

As regras por jogo são lidas e escritas em contextos muito diferentes: uma tela normal com ViewModel e um overlay de `WindowManager` que roda fora do ciclo de vida da activity. Os dois passam por este repositório, sobre uma única tabela Room.

Ele expõe um `StateFlow<Map<String, AppOverrideEntity>>` mantido atualizado por um `Flow` do Room, para a thread de acessibilidade ler a regra atual na hora via `.value` sem tocar no disco nessa thread. **Se você adicionar uma nova configuração por jogo, ela vai no `AppOverrideEntity`**, não numa nova chave de `SharedPreferences`.

### `ForegroundAppTracker`: reativo, só em memória

Qual app está em primeiro plano é uma informação *momentânea*, então fica num `StateFlow` e nunca é salva. O overlay observa esse estado, e é isso que deixa a árvore Compose dele se refazer para cada jogo: o overlay é criado uma vez só e, de outra forma, ficaria congelado no jogo que estava aberto quando o serviço começou.

### Regra das threads

Toda chamada root é bloqueante. Qualquer coisa disparada pelo usuário (arrastar um slider, um interruptor, uma troca de app) tem de ir para `Dispatchers.IO`, e os controles contínuos devem ter debounce com um job cancelável. Uma chamada root bloqueante na thread principal basta para travar o app na abertura ou deixar um slider inutilizável.

## Créditos

O Odin Hub é um derivado muito retrabalhado de projetos open source já existentes, e se apoia neles:

- **[OdinTools](https://github.com/langerhans/OdinTools)**, de Maximilian Keller (langerhans): a base original de onde este projeto foi bifurcado, com a estrutura das regras por app e dos atalhos rápidos.
- **[P.U.L.S.E.](https://github.com/keiretrogaming/pulse)**, de keiretrogaming: a técnica do `PServerBinder` e o conceito de AutoTDP em ciclo fechado, que tornam possível o controle profundo do hardware sem o root tradicional.
- **[ClusterTune](https://github.com/AurelioB/ClusterTune)**, de AurelioB: o pioneiro da abordagem do PServer com o sysfs.

## Licença

MIT. O aviso de copyright original é mantido em [`LICENSE`](./LICENSE), como a licença exige, junto com o copyright do trabalho deste projeto.
