package com.dfdx047.odinhub.overlay

import com.dfdx047.odinhub.ui.theme.onPrimary
import com.dfdx047.odinhub.ui.theme.scrim
import com.dfdx047.odinhub.ui.theme.glass
import com.dfdx047.odinhub.ui.theme.glassEdgeTop
import com.dfdx047.odinhub.ui.theme.glassEdgeBottom
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.dfdx047.odinhub.data.AppOverrideEntity
import com.dfdx047.odinhub.data.AppOverrideRepository
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.AppOverrideLabels
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.FeatureFlags
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.ButtonActionHandler
import com.dfdx047.odinhub.tools.ForegroundAppTracker
import com.dfdx047.odinhub.models.ButtonMacros
import com.dfdx047.odinhub.models.MacroMode
import com.dfdx047.odinhub.models.MacroStep
import com.dfdx047.odinhub.ui.screens.MacroModeChips
import com.dfdx047.odinhub.ui.screens.autoTdpLine
import com.dfdx047.odinhub.ui.screens.MacroStepsEditor
import com.dfdx047.odinhub.tools.hardware.DisplayManager
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import com.dfdx047.odinhub.tools.hardware.ThermalLimitModes
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import com.dfdx047.odinhub.tools.hardware.VulkanNativeBridge
import com.dfdx047.odinhub.ui.screens.ClockChipRow
import com.dfdx047.odinhub.ui.theme.ConsoleTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Janela de espera antes de escrever um valor arrastado no slider para o hardware. Curta o
 * suficiente para continuar a parecer instantânea, longa o suficiente para que os valores
 * intermédios de um arrasto sejam descartados por cancelamento antes de chegarem ao root.
 */
private const val LIVE_APPLY_DEBOUNCE_MS = 70L

// Ids dos separadores do painel (ver `tabIds` em QuickAccessPanel).
private const val TAB_VISUALS = 0
private const val TAB_UPSCALING = 1
private const val TAB_PERFORMANCE = 2
private const val TAB_DISPLAY = 3
private const val TAB_BUTTONS = 4

@Composable
fun QuickAccessContent(
    isExpanded: Boolean,
    theme: ConsoleTheme,
    prefs: SharedPrefsRepo,
    isDllReady: Boolean,
    performanceManager: PerformanceManager,
    thermalManager: ThermalManager,
    displayManager: DisplayManager,
    buttonActions: ButtonActionHandler,
    overrideRepository: AppOverrideRepository,
    // Jogo atualmente em primeiro plano e o mapa vivo de regras por jogo, ambos observados em
    // QuickAccessOverlay a partir de StateFlows. São parâmetros (e não leituras diretas de
    // SharedPreferences, como antes) precisamente para esta composição re-chavear quando o
    // utilizador troca de jogo -- ver ForegroundAppTracker.
    currentPackage: String,
    overridesByPackage: Map<String, AppOverrideEntity>,
    onExpand: () -> Unit,
    onClose: () -> Unit
) {
    val currentWidth by animateDpAsState(
        targetValue = if (isExpanded) 390.dp else prefs.overlayHandleWidth.dp,
        animationSpec = tween(250),
        label = "widthAnim"
    )

    var panelOpacity by remember { mutableFloatStateOf(prefs.overlayPanelOpacity) }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(currentWidth)
    ) {
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Vidro: painel translúcido com contorno de luz; o jogo por trás fica desfocado
                    // pela janela (FLAG_BLUR_BEHIND, ver QuickAccessOverlay) quando o sistema deixa.
                    .background(
                        if (theme.glassMode) theme.glass().copy(alpha = (theme.glass().alpha + 0.2f) * panelOpacity) else theme.background.copy(alpha = panelOpacity),
                        RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    )
                    .then(
                        if (theme.glassMode) Modifier.border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(theme.glassEdgeTop, theme.glassEdgeBottom)), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                        else Modifier.border(1.dp, theme.primary.copy(alpha = 0.5f), RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)),
                    )
            ) {
                QuickAccessPanel(
                    theme = theme,
                    prefs = prefs,
                    isDllReady = isDllReady,
                    performanceManager = performanceManager,
                    thermalManager = thermalManager,
                    displayManager = displayManager,
                    buttonActions = buttonActions,
                    overrideRepository = overrideRepository,
                    currentPackage = currentPackage,
                    overridesByPackage = overridesByPackage,
                    panelOpacity = panelOpacity,
                    onOpacityChange = { panelOpacity = it; prefs.overlayPanelOpacity = it },
                    onClose = onClose
                )
            }
        } else {
            val handleOpacity: Float = prefs.overlayHandleOpacity.coerceIn(0.1f, 1.0f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(theme.primary.copy(alpha = handleOpacity))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .clickable { onExpand() },
                contentAlignment = Alignment.Center
            ) {
                Box(modifier = Modifier.width(2.dp).height(30.dp).background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(50)))
            }
        }
    }
}

/**
 * Painel expandido do overlay.
 *
 * Sobre o [performanceManager] recebido de fora (e não criado aqui): esta função instanciava o seu
 * PRÓPRIO `PerformanceManager(ShellExecutor())`, à parte do singleton do Hilt que o
 * MainViewModel/ForegroundAppWatcherService/OdinHubService usam. Como o PerformanceManager corre um
 * daemon que reescreve os clocks a cada segundo a partir do seu próprio estado, existiam DOIS
 * daemons a competir pelos mesmos nós de sysfs: o valor ajustado aqui "colava" por um instante e
 * era logo sobrescrito pelo outro daemon no tick seguinte — o sintoma "os valores mudam mas não
 * fixam de forma fiável".
 */
@Composable
private fun QuickAccessPanel(
    theme: ConsoleTheme,
    prefs: SharedPrefsRepo,
    isDllReady: Boolean,
    performanceManager: PerformanceManager,
    thermalManager: ThermalManager,
    displayManager: DisplayManager,
    buttonActions: ButtonActionHandler,
    overrideRepository: AppOverrideRepository,
    currentPackage: String,
    overridesByPackage: Map<String, AppOverrideEntity>,
    panelOpacity: Float,
    onOpacityChange: (Float) -> Unit,
    onClose: () -> Unit
) {
    // CAUSA RAIZ do bug relatado "o overlay e o banco de dados não estão interconectados":
    // isto era `val currentApp = prefs.currentForegroundApp` -- uma leitura única de
    // SharedPreferences, que o Compose não observa. Como a ComposeView do overlay é criada uma só
    // vez (GamingOverlayService.onCreate) e nada aqui dentro mudava, `currentApp` ficava
    // congelado no valor de arranque (normalmente "global"). O utilizador abria o GTA, punha 5 W,
    // e a regra era gravada no Room sob esse pacote errado -- por isso o ecrã "Per-App Overrides"
    // nunca mostrava nada para o GTA. Agora chega como parâmetro, vindo de um StateFlow observado
    // (ver ForegroundAppTracker), e todos os `remember(currentApp)` abaixo voltam a fazer sentido:
    // ao trocar de jogo, o painel recarrega-se com os valores daquele jogo.
    val currentApp = currentPackage
    val scope = rememberCoroutineScope()

    // CAUSA RAIZ do lag/travamento ao arrastar o slider de TDP ou trocar de preset de clock
    // rapidamente: `performanceManager.applyDynamicTdp`/`applyAbsoluteClocks` fazem um `exec`
    // root SÍNCRONO (bloqueante) por chamada. Um `Slider.onValueChange` dispara dezenas de vezes
    // por segundo durante o arrasto -- cada uma bloqueando a thread de UI do Compose até o `su`
    // terminar, daí o "trava mesmo". As funções abaixo movem a escrita para Dispatchers.IO.
    //
    // O `delay` antes da escrita não é cosmético, é o que torna o cancelamento eficaz: como
    // `applyDynamicTdp` é bloqueante e não tem pontos de suspensão, cancelar um job que JÁ entrou
    // na escrita não a interrompe. Com uma janela de espera à frente, o job anterior é cancelado
    // ainda dentro do `delay` e nunca chega a escrever -- só o último valor do arrasto vai ao
    // root, em vez de dezenas de escritas concorrentes a competir na mesma fila.
    //
    // ATUALIZAÇÃO: o PerformanceManager passou a ter um único daemon que escreve em sysfs; os
    // `apply*` só mudam o alvo e acordam-no (não bloqueiam). Por isso chamam-se diretamente --
    // sem coroutine do painel, que era cancelada se o painel fechasse dentro da janela de espera
    // e deixava o hardware num valor diferente do que a UI mostrava.
    fun applyTdpLive(watts: Float) = performanceManager.applyDynamicTdp(watts)
    // Cluster de eficiência (8 Gen 2 / 865): declarado aqui em cima porque o applyClocksLive o lê.
    var cpuEffClock by remember(currentPackage) { mutableFloatStateOf(overridesByPackage[currentPackage]?.effClockKHz?.let { it / 1000f } ?: prefs.cpuEffClock) }
    fun applyClocksLive(perfMHz: Float, primeMHz: Float, gpuMHz: Float) =
        performanceManager.applyAbsoluteClocks((perfMHz * 1000).toLong(), (primeMHz * 1000).toLong(), (gpuMHz * 1_000_000).toLong(), (cpuEffClock * 1000).toLong())

    // CAUSA RAIZ (auditoria): tudo aqui era lido/escrito só no SharedPrefs (`prefs.getPerApp*`
    // / `prefs.savePerAppConfig`), um armazenamento TOTALMENTE separado da tabela Room que a
    // aba Performance -> Per-App Overrides usa. Configurar um jogo ali não tinha nenhum efeito
    // no overlay (e vice-versa) porque eram dois "bancos de dados" que nunca se falavam. Agora
    // ambos leem/escrevem o mesmo AppOverrideRepository (Room). `remember(currentApp)` garante
    // que, se o overlay ficar aberto e o utilizador trocar de jogo, o estado é recarregado para
    // o jogo novo em vez de continuar a mostrar valores do jogo anterior.
    //
    // Lido do mapa VIVO do repositório (e não de um `get()` em cache no `remember`): assim, uma
    // regra criada/alterada no ecrã "Per-App Overrides" já aparece aqui da próxima vez que o
    // painel compõe para aquele jogo -- o sentido "banco -> overlay" da parceria que faltava.
    val existingOverride = overridesByPackage[currentApp]

    // Idioma partilhado com o resto da app (SharedPrefsRepo.isEnglish) -- o overlay estava todo
    // escrito em português fixo, mesmo com o inglês escolhido.
    val isEn by prefs.isEnglishFlow.collectAsState()

    // Separadores: ids estáveis (e não índices) para poder esconder os de gráficos quando o motor
    // não está disponível (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) sem baralhar os restantes.
    // O separador dos botões só existe em aparelhos com M1/M2 remapeáveis (ver DeviceCapabilities).
    val hasBackButtons by buttonActions.backButtons.collectAsState()
    val tabIds = buildList {
        add(TAB_PERFORMANCE)
        add(TAB_DISPLAY)
        if (hasBackButtons) add(TAB_BUTTONS)
        add(TAB_VISUALS)
        if (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) add(TAB_UPSCALING)
    }
    var chosenTab by remember { mutableIntStateOf(tabIds.first()) }
    // Se o separador escolhido deixar de existir (ex: botões M1/M2), mostra o primeiro.
    val selectedTab = if (chosenTab in tabIds) chosenTab else tabIds.first()

    var reshadeProfile by remember(currentApp) { mutableStateOf(existingOverride?.reshadeProfile ?: prefs.reshadeProfile) }

    var sgsrEnabled by remember(currentApp) { mutableStateOf(existingOverride?.sgsrEnabled ?: prefs.globalSgsrEnabled) }
    var sgsrMode by remember(currentApp) { mutableStateOf(existingOverride?.sgsrMode ?: prefs.sgsrMode) }
    var sgsrSharpness by remember(currentApp) { mutableFloatStateOf(existingOverride?.sgsrSharpness ?: prefs.sgsrSharpness) }

    var lsfgEnabled by remember(currentApp) { mutableStateOf(existingOverride?.lsfgEnabled ?: prefs.globalLsfgEnabled) }
    var lsfgMultiplier by remember(currentApp) { mutableStateOf(existingOverride?.lsfgMultiplier?.let { "${it}x" } ?: prefs.lsfgMultiplier) }
    var lsfgPacing by remember(currentApp) { mutableStateOf(existingOverride?.lsfgFramePacing ?: prefs.lsfgFramePacing) }
    var lsfgPerfMode by remember(currentApp) { mutableStateOf(existingOverride?.lsfgPerformanceMode ?: prefs.lsfgPerformanceMode) }

    // Antes, isto arrancava sempre fixo em "TDP", independentemente do modo realmente ativo
    // (global ou por jogo) -- por isso bastava abrir o overlay e tocar num preset/slider da
    // aba errada para reativar silenciosamente o TDP mesmo com o utilizador a usar Clocks.
    // Agora carrega o modo que está de facto persistido para este jogo (ou o global, se o
    // jogo não tiver override próprio), exatamente como o ecrã de Settings.
    var activeLimitMode by remember(currentApp) { mutableStateOf(existingOverride?.limitMode ?: prefs.activeLimitMode) }
    var fanMode by remember(currentApp) { mutableIntStateOf(existingOverride?.fanSettingsValue ?: prefs.fanMode) }

    // Perfil de TDP selecionado (ver TdpProfiles): independente do slider. Numa regra do jogo o
    // id deriva dos watts guardados (a sentinela abaixo de 1 W = Stock); sem regra, é o global.
    var tdpProfileId by remember(currentApp) {
        mutableStateOf(existingOverride?.tdpWatts?.let { TdpProfiles.idForWatts(it) } ?: prefs.tdpProfileId)
    }
    var tdpValue by remember(currentApp) {
        mutableFloatStateOf(existingOverride?.tdpWatts?.takeUnless { TdpProfiles.isStockWatts(it) } ?: prefs.tdpValue)
    }
    // Tabelas reais de frequências do SoC, lidas diretamente do PerformanceManager partilhado.
    val clockTables by performanceManager.clockTables.collectAsState()
    val autoTdp by performanceManager.autoTdpStatus.collectAsState()
    val thermalSupported = thermalManager.status.collectAsState().value.supported
    val soc = remember { com.dfdx047.odinhub.tools.DeviceRegistry.current.soc }
    // Presets reativos (antes: prefs.all copiado a cada recomposição -- cada frame de um arrasto).
    val customProfiles by prefs.customProfilesFlow.collectAsState()
    var cpuPerfClock by remember(currentApp) { mutableFloatStateOf(existingOverride?.perfClockKHz?.let { it / 1000f } ?: prefs.cpuPerfClock) }
    var cpuPrimeClock by remember(currentApp) { mutableFloatStateOf(existingOverride?.primeClockKHz?.let { it / 1000f } ?: prefs.cpuPrimeClock) }
    var gpuClock by remember(currentApp) { mutableFloatStateOf(existingOverride?.gpuClockHz?.let { it / 1_000_000f } ?: prefs.gpuClock) }

    var savedPresetName by remember { mutableStateOf("") }

    // Limite térmico deste jogo (null = segue o global) e cor própria do ecrã.
    var thermalMode by remember(currentApp) { mutableStateOf(existingOverride?.thermalMode) }
    var confirmUnthrottled by remember(currentApp) { mutableStateOf(false) }
    var displayOverride by remember(currentApp) { mutableStateOf(existingOverride?.displayOverride ?: false) }
    var saturation by remember(currentApp) { mutableFloatStateOf(existingOverride?.saturationOverride ?: prefs.saturationOverride) }
    var temperature by remember(currentApp) { mutableFloatStateOf(existingOverride?.temperatureOverride ?: prefs.temperatureOverride) }
    var colorJob by remember { mutableStateOf<Job?>(null) }

    // Macros de M1/M2 deste jogo (ver MacroMode). Gravadas no Room com o resto da regra.
    var m1Mode by remember(currentApp) { mutableStateOf(MacroMode.of(existingOverride?.m1Macro)) }
    var m2Mode by remember(currentApp) { mutableStateOf(MacroMode.of(existingOverride?.m2Macro)) }
    val m1Steps = remember(currentApp) { mutableStateListOf<MacroStep>().apply { addAll(ButtonMacros.decode(existingOverride?.m1Macro)) } }
    val m2Steps = remember(currentApp) { mutableStateListOf<MacroStep>().apply { addAll(ButtonMacros.decode(existingOverride?.m2Macro)) } }
    var macroButton by remember(currentApp) { mutableStateOf("m1") }
    fun applyMacrosLive() {
        // Cópias feitas aqui (thread de UI), antes de saltar para IO.
        val m1 = MacroMode.encode(m1Mode, m1Steps.toList())
        val m2 = MacroMode.encode(m2Mode, m2Steps.toList())
        scope.launch(Dispatchers.IO) { buttonActions.setGameMacros(m1, m2) }
    }
    fun applyColorLive() {
        val sat = if (displayOverride) saturation else prefs.saturationOverride
        val kelvin = if (displayOverride) temperature else prefs.temperatureOverride
        colorJob?.cancel()
        colorJob = scope.launch(Dispatchers.IO) {
            delay(LIVE_APPLY_DEBOUNCE_MS)
            displayManager.applyColor(sat, kelvin)
        }
    }

    // Grava o estado atual do painel como a regra deste jogo. Chamado a cada alteração
    // "definitiva" (toque num preset, soltar um slider, mudar de modo) -- não a cada tick de
    // arrasto de slider, para não martelar o Room -- e também no onDispose, como rede de
    // segurança para quando o painel fecha de forma inesperada (troca de app, serviço morto),
    // que era o único momento em que o código anterior gravava alguma coisa.
    // Só criamos/atualizamos a regra deste jogo se o utilizador mexer mesmo em alguma coisa.
    // Sem isto, bastava abrir e fechar o painel para o jogo passar a ter uma regra própria --
    // uma fotografia dos valores GLOBAIS do momento -- que a partir daí deixava de acompanhar
    // qualquer alteração global, além de encher a lista de "Regras por jogo" com entradas que o
    // utilizador nunca pediu.
    var touchedByUser by remember(currentApp) { mutableStateOf(false) }

    fun persistOverride() {
        touchedByUser = true
        // Guarda: nunca gravar uma regra para "nenhum app" (ForegroundAppTracker.GLOBAL) nem para
        // um pacote vazio. Era exatamente isto que acontecia antes -- as regras do jogo acabavam
        // numa linha "global" que nenhum ecrã mostrava. Se o painel for aberto fora de um app (o
        // que já não deve acontecer, porque a barrinha fica escondida), as alterações continuam a
        // valer para a sessão atual, mas não inventamos uma regra por app.
        if (currentApp.isBlank()) return
        if (currentApp == ForegroundAppTracker.GLOBAL) {
            // Modo degradado (sem acessibilidade nem estatísticas de uso): o painel diz "Perfil
            // global", por isso as alterações vão para o perfil GLOBAL -- antes chegavam ao
            // hardware mas não ficavam guardadas em lado nenhum, e a troca de app seguinte
            // repunha os valores antigos.
            prefs.activeLimitMode = activeLimitMode
            prefs.tdpProfileId = tdpProfileId
            prefs.tdpValue = tdpValue
            prefs.cpuPerfClock = cpuPerfClock
            prefs.cpuPrimeClock = cpuPrimeClock
            prefs.cpuEffClock = cpuEffClock
            prefs.gpuClock = gpuClock
            prefs.fanMode = fanMode
            return
        }

        val isTdpMode = activeLimitMode == "TDP"
        // Stock (sem limite) fica guardado como a sentinela de TdpProfiles, porque `null` neste
        // campo já significa "usa o valor global" para o ForegroundAppWatcherService.
        val encodedTdp = TdpProfiles.encodeWatts(TdpProfiles.resolveWatts(tdpProfileId, tdpValue))
        val entity = AppOverrideEntity(
            packageName = currentApp,
            limitMode = activeLimitMode,
            tdpWatts = encodedTdp,
            perfClockKHz = (cpuPerfClock * 1000).toLong(),
            primeClockKHz = (cpuPrimeClock * 1000).toLong(),
            effClockKHz = (cpuEffClock * 1000).toLong().takeIf { it > 0L },
            gpuClockHz = (gpuClock * 1_000_000).toLong(),
            fanSettingsValue = fanMode,
            // Rótulos calculados com o MESMO helper que o ecrã "Per-App Overrides" usa
            // (AppOverrideLabels). Antes o overlay propagava `existingOverride?.tdpProfile`, que
            // é null numa regra recém-criada em jogo -- e a lista de jogos mostrava a entrada sem
            // subtítulo nenhum, como se a regra estivesse vazia.
            tdpProfile = if (isTdpMode) AppOverrideLabels.tdpLabel(encodedTdp) else "Stock",
            clockProfile = if (!isTdpMode) AppOverrideLabels.clockLabel(cpuPerfClock, cpuPrimeClock) else "Stock",
            fanProfile = AppOverrideLabels.fanLabel(fanMode),
            lsfgEnabled = lsfgEnabled,
            lsfgMultiplier = lsfgMultiplier.replace("x", "").toIntOrNull() ?: 2,
            lsfgPerformanceMode = lsfgPerfMode,
            lsfgFramePacing = lsfgPacing,
            // O overlay não expõe um slider de qualidade LSFG (só a aba Performance ->
            // Per-App Overrides tem); preserva o que já estava gravado em vez de o resetar
            // para o valor por omissão a cada escrita feita a partir daqui.
            lsfgQuality = existingOverride?.lsfgQuality ?: 1.0f,
            sgsrEnabled = sgsrEnabled,
            sgsrMode = sgsrMode,
            sgsrSharpness = sgsrSharpness,
            reshadeProfile = reshadeProfile,
            saturationOverride = saturation,
            temperatureOverride = temperature,
            displayOverride = displayOverride,
            thermalMode = thermalMode,
            m1Macro = MacroMode.encode(m1Mode, m1Steps.toList()),
            m2Macro = MacroMode.encode(m2Mode, m2Steps.toList()),
        )
        // Escrita no scope do repositório, não no do Compose: ver AppOverrideRepository.upsertAsync
        // -- a gravação feita no `onDispose` (painel a fechar / troca de jogo) caía num scope já
        // cancelado e perdia-se.
        overrideRepository.upsertAsync(entity)
    }

    // Rede de segurança: grava o estado final quando o painel fecha ou o utilizador troca de jogo
    // (por exemplo, se o painel morrer a meio de um arrasto de slider, antes do
    // `onValueChangeFinished`). Condicional, para não inventar regras -- ver `touchedByUser`.
    DisposableEffect(currentApp) {
        onDispose { if (touchedByUser) persistOverride() }
    }

    // Nome legível do jogo em primeiro plano, para o utilizador ver claramente A QUE JOGO estas
    // configurações pertencem -- reforça que o painel é por app, e não um controlo global.
    val context = LocalContext.current
    val currentAppLabel = remember(currentApp) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(currentApp, 0)).toString()
        }.getOrDefault(currentApp)
    }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ODIN HUB", color = theme.primary, fontWeight = FontWeight.Black, fontSize = 18.sp, letterSpacing = 1.sp)
                Text(
                    text = if (currentApp == ForegroundAppTracker.GLOBAL) (if (isEn) "Global profile" else "Perfil global") else (if (isEn) "Profile: $currentAppLabel" else "Perfil de $currentAppLabel"),
                    color = theme.text.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
            }
            Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.6f)).clickable { onClose() }, contentAlignment = Alignment.Center) {
                Text("✕", color = theme.text, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.scrim).padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            tabIds.forEach { tabId ->
                val title = when (tabId) {
                    TAB_VISUALS -> if (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) "Visuals" else (if (isEn) "Overlay" else "Overlay")
                    TAB_UPSCALING -> "Upscaling"
                    TAB_DISPLAY -> if (isEn) "Display" else "Ecrã"
                    TAB_BUTTONS -> if (isEn) "Buttons" else "Botões"
                    else -> "Performance"
                }
                val isSelected = selectedTab == tabId
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSelected) theme.primary else Color.Transparent).clickable { chosenTab = tabId }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(title, color = if (isSelected) theme.onPrimary else theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (selectedTab) {
            TAB_VISUALS -> {
                Text(if (isEn) "OVERLAY APPEARANCE" else "APARÊNCIA DO OVERLAY", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(12.dp)) {
                    Text((if (isEn) "Background opacity: " else "Opacidade do fundo: ") + "${(panelOpacity * 100).toInt()}%", color = theme.text, fontSize = 11.sp)
                    Slider(value = panelOpacity, onValueChange = onOpacityChange, valueRange = 0.1f..1.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
                }

                // Atalho para o HUD de desempenho (as métricas configuram-se no Odin Hub > Sistema).
                Spacer(modifier = Modifier.height(16.dp))
                Text(if (isEn) "PERFORMANCE HUD" else "HUD DE DESEMPENHO", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val hudConfig by prefs.hudConfigFlow.collectAsState()
                val hudHidden by com.dfdx047.odinhub.service.PerformanceHudService.hiddenFlow.collectAsState()
                val hudOn = hudConfig.enabled && !hudHidden
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).clickable {
                    com.dfdx047.odinhub.service.PerformanceHudService.toggle(context, prefs)
                }.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Show FPS / temperatures HUD" else "Mostrar HUD de FPS / temperaturas", color = theme.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    Text(if (hudOn) "ON" else "OFF", color = if (hudOn) theme.primary else theme.text.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

                if (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("VULKAN POST-FX & RESHADE", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val allProfiles = listOf("Native", "Vibrant", "Retro", "HDR Boost", "Game Clarity", "Cinematic")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (rowProfiles in allProfiles.chunked(2)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (profile in rowProfiles) {
                                val isSelected = reshadeProfile == profile
                                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (isSelected) theme.primary.copy(alpha = 0.8f) else theme.surface.copy(alpha = 0.5f)).border(1.dp, if (isSelected) theme.primary else theme.text.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).clickable { reshadeProfile = profile; VulkanNativeBridge.applyReshade(profile, prefs.saturationOverride, prefs.temperatureOverride); persistOverride() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(profile, color = if (isSelected) theme.onPrimary else theme.text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                }
            }
            TAB_DISPLAY -> {
                Text(if (isEn) "DISPLAY COLOR" else "COR DO ECRÃ", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (isEn) "Custom color for this game" else "Cor própria neste jogo", color = theme.text, fontSize = 12.sp)
                            Text(if (displayOverride) (if (isEn) "Applied only while this game is open" else "Aplicada só com este jogo aberto") else (if (isEn) "Using the global color (Display tab)" else "A usar a cor global (aba Display)"), color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                        Switch(checked = displayOverride, onCheckedChange = { displayOverride = it; applyColorLive(); persistOverride() }, colors = SwitchDefaults.colors(checkedThumbColor = theme.primary, checkedTrackColor = theme.primary.copy(alpha = 0.4f)))
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text((if (isEn) "Saturation: " else "Saturação: ") + "%.2f".format(saturation), color = theme.text.copy(alpha = if (displayOverride) 1f else 0.5f), fontSize = 11.sp)
                    Slider(value = saturation, enabled = displayOverride, onValueChange = { saturation = it; applyColorLive() }, onValueChangeFinished = { persistOverride() }, valueRange = 0f..2f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
                    Text((if (isEn) "Temperature: " else "Temperatura: ") + "${temperature.toInt()}K", color = theme.text.copy(alpha = if (displayOverride) 1f else 0.5f), fontSize = 11.sp)
                    Slider(value = temperature, enabled = displayOverride, onValueChange = { temperature = it; applyColorLive() }, onValueChangeFinished = { persistOverride() }, valueRange = 4000f..9000f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isEn) "Warmer ← 6500K → Cooler" else "Quente ← 6500K → Frio", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp)
                        Text(if (isEn) "RESET" else "REDEFINIR", color = theme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = displayOverride) { saturation = 1f; temperature = 6500f; applyColorLive(); persistOverride() }.padding(4.dp))
                    }
                }
            }
            TAB_BUTTONS -> {
                Text(if (isEn) "BACK BUTTON MACROS" else "MACROS DOS BOTÕES TRASEIROS", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.scrim).padding(4.dp)) {
                    listOf("m1", "m2").forEach { b ->
                        val isSel = macroButton == b
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else Color.Transparent).clickable { macroButton = b }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(b.uppercase(), color = if (isSel) theme.onPrimary else theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                val isM1 = macroButton == "m1"
                val mode = if (isM1) m1Mode else m2Mode
                val steps = if (isM1) m1Steps else m2Steps
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(12.dp)) {
                    MacroModeChips(mode = mode, theme = theme, isEn = isEn) { newMode ->
                        if (isM1) { m1Mode = newMode } else { m2Mode = newMode }
                        // "Própria" sem passos ainda não é uma macro: grava-se quando o primeiro
                        // passo for adicionado (MacroStepsEditor.onChanged). Gravar já guardava ""
                        // e o chip voltava como "Desligada" ao reabrir o painel.
                        if (newMode != MacroMode.CUSTOM || steps.isNotEmpty()) {
                            persistOverride()
                            applyMacrosLive()
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        when (mode) {
                            MacroMode.GLOBAL -> if (isEn) "Uses the macro / remap from the Controls tab." else "Usa a macro / mapeamento da aba Controles."
                            MacroMode.OFF -> if (isEn) "Native button in this game." else "Botão nativo neste jogo."
                            MacroMode.CUSTOM -> if (isEn) "Saved for this game; applied instantly." else "Guardada para este jogo; aplicada na hora."
                        },
                        color = theme.text.copy(alpha = 0.55f), fontSize = 10.sp,
                    )
                    if (mode == MacroMode.CUSTOM) {
                        Spacer(modifier = Modifier.height(10.dp))
                        MacroStepsEditor(steps = steps, theme = theme, isEn = isEn, compact = true, onChanged = { persistOverride(); applyMacrosLive() })
                    }
                }
            }
            TAB_UPSCALING -> {
                Text("SNAPDRAGON SUPER RESOLUTION (SGSR)", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isEn) "Enable SGSR" else "Ativar SGSR", color = theme.text, fontSize = 12.sp)
                    Switch(checked = sgsrEnabled, onCheckedChange = { sgsrEnabled = it; VulkanNativeBridge.applySgsr(it, sgsrMode); persistOverride() }, colors = SwitchDefaults.colors(checkedThumbColor = theme.primary, checkedTrackColor = theme.primary.copy(alpha = 0.4f)))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(if (isEn) "SGSR mode" else "Modo SGSR", color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Quality", "Balanced", "Performance", "Ultra").forEach { mode ->
                        val isSel = sgsrMode == mode
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else theme.surface.copy(alpha = 0.5f)).clickable { sgsrMode = mode; VulkanNativeBridge.applySgsr(sgsrEnabled, mode); persistOverride() }.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Text(mode, color = if (isSel) theme.onPrimary else theme.text, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text((if (isEn) "Sharpness: " else "Nitidez: ") + "%.2f".format(sgsrSharpness), color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp)
                Slider(value = sgsrSharpness, onValueChange = { sgsrSharpness = it }, onValueChangeFinished = { persistOverride() }, valueRange = 0.0f..1.0f, colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary))

                Spacer(modifier = Modifier.height(16.dp))
                Text("LOSSLESS SCALING (LSFG)", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).alpha(if (isDllReady) 1f else 0.4f).padding(12.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isEn) "Enable LSFG" else "Ativar LSFG", color = theme.text, fontSize = 12.sp)
                            Switch(checked = lsfgEnabled && isDllReady, enabled = isDllReady, onCheckedChange = { lsfgEnabled = it; VulkanNativeBridge.applyLsfg(it, lsfgMultiplier, lsfgPacing); persistOverride() }, colors = SwitchDefaults.colors(checkedThumbColor = theme.primary, checkedTrackColor = theme.primary.copy(alpha = 0.4f)))
                        }
                        if (!isDllReady) {
                            Text(if (isEn) "Requires Lossless.dll in the Hub" else "Requer Lossless.dll no Hub", color = Color(0xFFFF5252), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isEn) "Multiplier" else "Multiplicador", color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("2x", "3x", "4x").forEach { mult ->
                                        Box(modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(if (lsfgMultiplier == mult) theme.primary else theme.surface.copy(alpha = 0.6f)).clickable { lsfgMultiplier = mult; VulkanNativeBridge.applyLsfg(lsfgEnabled, mult, lsfgPacing); persistOverride() }.padding(horizontal = 8.dp, vertical = 2.dp)) {
                                            Text(mult, color = if (lsfgMultiplier == mult) theme.onPrimary else theme.text, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isEn) "Frame pacing" else "Sincronia (Frame Pacing)", color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp)
                                Switch(checked = lsfgPacing, onCheckedChange = { lsfgPacing = it; VulkanNativeBridge.applyLsfg(lsfgEnabled, lsfgMultiplier, it); persistOverride() }, colors = SwitchDefaults.colors(checkedThumbColor = theme.primary, checkedTrackColor = theme.primary.copy(alpha = 0.4f)))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isEn) "Performance mode" else "Modo Performance", color = theme.text.copy(alpha = 0.7f), fontSize = 11.sp)
                                Switch(checked = lsfgPerfMode, onCheckedChange = { lsfgPerfMode = it; persistOverride() }, colors = SwitchDefaults.colors(checkedThumbColor = theme.primary, checkedTrackColor = theme.primary.copy(alpha = 0.4f)))
                            }
                        }
                    }
                }
            }
            else -> {
                Text(if (isEn) "FAN CONTROL" else "CONTROLE DA VENTOINHA", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    // Usa os settingsValue reais de FanMode (1/4/5) e o performanceManager
                    // partilhado, em vez de índices 0/1/2 escritos à mão com um ShellExecutor()
                    // novo a cada toque: essa combinação fazia com que o modo escolhido aqui
                    // fosse reinterpretado como outro (ou como "Stock") assim que o serviço de
                    // acessibilidade reaplicava os perfis ao trocar de app.
                    for (mode in FanMode.selectable + FanMode.Stock) {
                        val isSel = if (mode == FanMode.Stock) FanMode.selectable.none { it.settingsValue == fanMode } else fanMode == mode.settingsValue
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else Color.Transparent).clickable { fanMode = mode.settingsValue; scope.launch(Dispatchers.IO) { performanceManager.applyFanMode(mode) }; persistOverride() }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(mode.shortLabel, color = if (isSel) theme.onPrimary else theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(if (isEn) "THERMAL LIMIT" else "LIMITE TÉRMICO", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                if (!thermalSupported) {
                    Text(
                        if (isEn) "Not available on this device (MediaTek)" else "Indisponível neste aparelho (MediaTek)",
                        color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp,
                    )
                } else Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val options: List<String?> = listOf(null) + ThermalLimitModes.all
                    for (option in options) {
                        val isSel = thermalMode == option
                        val label = when (option) {
                            null -> "Global"
                            ThermalLimitModes.STOCK -> "Stock"
                            ThermalLimitModes.UNTHROTTLED -> if (isEn) "Off" else "Sem"
                            else -> "$option°"
                        }
                        Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else Color.Transparent).clickable {
                            if (option == ThermalLimitModes.UNTHROTTLED && !confirmUnthrottled) {
                                // Sem diálogos numa janela de overlay: confirmação com um segundo toque.
                                confirmUnthrottled = true
                            } else {
                                confirmUnthrottled = false
                                thermalMode = option
                                thermalManager.setAppOverride(option)
                                persistOverride()
                            }
                        }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = if (isSel) theme.onPrimary else theme.text.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
                if (confirmUnthrottled) {
                    Text(
                        if (isEn) "⚠ Tap \"Off\" again to confirm: trips go to 115 °C (emergency trips untouched). Keep the fan at max."
                        else "⚠ Toque em \"Sem\" de novo para confirmar: trips a 115 °C (os de emergência não são tocados). Ventoinha no máximo.",
                        color = Color(0xFFFF5252), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(if (isEn) "LIMIT MODE" else "MODO DE LIMITAÇÃO", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.scrim).border(1.dp, theme.text.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (activeLimitMode == "TDP") theme.primary else Color.Transparent).clickable { activeLimitMode = "TDP"; performanceManager.applyTdpSelection(TdpProfiles.resolveWatts(tdpProfileId, tdpValue)); persistOverride() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text("TDP", color = if (activeLimitMode == "TDP") theme.onPrimary else theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (activeLimitMode == "CLOCK") theme.primary else Color.Transparent).clickable { activeLimitMode = "CLOCK"; applyClocksLive(cpuPerfClock, cpuPrimeClock, gpuClock); persistOverride() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text("Clocks", color = if (activeLimitMode == "CLOCK") theme.onPrimary else theme.text.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (activeLimitMode == "TDP") {
                    Text(if (isEn) "TDP PROFILES" else "PERFIS DE TDP", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    // Perfis fixos de TdpProfiles (os MESMOS do Settings e do editor por app),
                    // independentes do slider: o chip aplica os watts exatos do perfil; o slider
                    // passa a seleção para "custom".
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (profile in TdpProfiles.fixed) {
                            val isSel = tdpProfileId == profile.id
                            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else theme.surface.copy(alpha = 0.5f)).clickable {
                                tdpProfileId = profile.id
                                profile.watts?.let { tdpValue = it }
                                performanceManager.applyTdpSelection(profile.watts)
                                persistOverride()
                            }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) { Text(profile.label(isEn), color = if (isSel) theme.onPrimary else theme.text, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    TdpProfiles.byId(tdpProfileId)?.let { selected ->
                        Text(selected.watts?.let { "${TdpProfiles.formatWatts(it)} -- ${selected.description(isEn)}" } ?: selected.description(isEn), color = theme.text.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    val userTdpProfiles = customProfiles.filter { it.type == "TDP" }
                    if (userTdpProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (prof in userTdpProfiles) {
                                val isSel = tdpProfileId == TdpProfiles.ID_CUSTOM && tdpValue == prof.v1
                                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (isSel) theme.primary else theme.surface.copy(alpha = 0.5f)).clickable { tdpProfileId = TdpProfiles.ID_CUSTOM; tdpValue = prof.v1; performanceManager.applyDynamicTdp(prof.v1); persistOverride() }.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text("${prof.name} (${TdpProfiles.formatWatts(prof.v1)})", color = if (isSel) theme.onPrimary else theme.text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(12.dp)) {
                        Text(if (tdpProfileId == TdpProfiles.ID_STOCK) (if (isEn) "TDP limit: none (Stock)" else "Limite de TDP: nenhum (Stock)") else (if (isEn) "TDP limit: " else "Limite de TDP: ") + TdpProfiles.formatWatts(tdpValue), color = theme.text, fontSize = 12.sp)
                        Slider(
                            value = tdpValue.coerceIn(TdpProfiles.TDP_MIN_WATTS, TdpProfiles.TDP_MAX_WATTS),
                            onValueChange = { tdpProfileId = TdpProfiles.ID_CUSTOM; tdpValue = it; applyTdpLive(it) },
                            onValueChangeFinished = { persistOverride() },
                            valueRange = TdpProfiles.TDP_MIN_WATTS..TdpProfiles.TDP_MAX_WATTS,
                            colors = SliderDefaults.colors(thumbColor = theme.primary, activeTrackColor = theme.primary),
                        )
                        autoTdpLine(autoTdp, isEn)?.let { line ->
                            Text(line, color = theme.primary.copy(alpha = 0.85f), fontSize = 10.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = savedPresetName, onValueChange = { savedPresetName = it }, label = { Text(if (isEn) "Preset name" else "Nome do preset", fontSize = 10.sp) }, modifier = Modifier.weight(1f).height(50.dp), textStyle = TextStyle(fontSize = 12.sp, color = theme.text))
                        Button(onClick = { if (savedPresetName.isNotBlank()) { prefs.saveCustomProfile(savedPresetName, "TDP", tdpValue, 0f, 0f, 0f); savedPresetName = "" } }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary), modifier = Modifier.height(50.dp)) { Text(if (isEn) "Save" else "Salvar", fontSize = 11.sp, color = theme.onPrimary) }
                    }
                } else {
                    Text(if (isEn) "MANUAL CLOCKS (GPU is independent)" else "CLOCKS MANUAIS (GPU é independente)", color = theme.text.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    // Sem perfis combinados de clocks: os únicos perfis que existem são os de
                    // TDP. Aqui só há os presets guardados pelo utilizador e os chips numéricos
                    // com as frequências REAIS do SoC (tabela do kernel via PerformanceManager).
                    val userClockProfiles = customProfiles.filter { it.type == "CLOCK" }
                    if (userClockProfiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (prof in userClockProfiles) {
                                Box(modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.surface.copy(alpha = 0.5f)).clickable { cpuPerfClock = prof.v2; cpuPrimeClock = prof.v3; gpuClock = prof.v4; if (prof.v5 > 0f) cpuEffClock = prof.v5; applyClocksLive(prof.v2, prof.v3, prof.v4); persistOverride() }.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(prof.name, color = theme.text, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(theme.surface.copy(alpha = 0.5f)).padding(12.dp)) {
                        // Chips numéricos com as frequências REAIS do SoC (OPPs do kernel): o
                        // 8 Elite só aceita as frequências da sua tabela de cpufreq, por isso cada
                        // chip é um valor que o hardware suporta mesmo. Mesmo componente do ecrã
                        // de Settings (ClockChipRow), com subconjunto curado + "Todos".
                        if (clockTables.hasEfficiencyCluster) {
                            ClockChipRow(
                                title = (if (isEn) "Efficiency Cluster" else "Cluster Eficiência") + soc.effCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                                currentMHz = if (cpuEffClock > 0f) cpuEffClock else (clockTables.effMHz.lastOrNull()?.toFloat() ?: 0f),
                                tableMHz = clockTables.effMHz,
                                enabled = true,
                                theme = theme,
                                isEn = isEn,
                            ) { mhz -> cpuEffClock = mhz.toFloat(); applyClocksLive(cpuPerfClock, cpuPrimeClock, gpuClock); persistOverride() }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                        ClockChipRow(
                            title = (if (isEn) "Perf Cluster" else "Cluster Perf") + soc.perfCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                            currentMHz = cpuPerfClock,
                            tableMHz = clockTables.perfMHz,
                            enabled = true,
                            theme = theme,
                            isEn = isEn,
                        ) { mhz -> cpuPerfClock = mhz.toFloat(); applyClocksLive(cpuPerfClock, cpuPrimeClock, gpuClock); persistOverride() }
                        Spacer(modifier = Modifier.height(12.dp))
                        ClockChipRow(
                            title = (if (isEn) "Prime Cluster" else "Cluster Prime") + soc.primeCores.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
                            currentMHz = cpuPrimeClock,
                            tableMHz = clockTables.primeMHz,
                            enabled = true,
                            theme = theme,
                            isEn = isEn,
                        ) { mhz -> cpuPrimeClock = mhz.toFloat(); applyClocksLive(cpuPerfClock, cpuPrimeClock, gpuClock); persistOverride() }
                        Spacer(modifier = Modifier.height(12.dp))
                        ClockChipRow(
                            title = soc.gpuName + (if (isEn) " (independent)" else " (independente)"),
                            currentMHz = gpuClock,
                            tableMHz = clockTables.gpuMHz,
                            enabled = true,
                            theme = theme,
                            isEn = isEn,
                        ) { mhz -> gpuClock = mhz.toFloat(); applyClocksLive(cpuPerfClock, cpuPrimeClock, gpuClock); persistOverride() }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(value = savedPresetName, onValueChange = { savedPresetName = it }, label = { Text(if (isEn) "Preset name" else "Nome do preset", fontSize = 10.sp) }, modifier = Modifier.weight(1f).height(50.dp), textStyle = TextStyle(fontSize = 12.sp, color = theme.text))
                        Button(onClick = { if (savedPresetName.isNotBlank()) { prefs.saveCustomProfile(savedPresetName, "CLOCK", 0f, cpuPerfClock, cpuPrimeClock, gpuClock, cpuEffClock); savedPresetName = "" } }, colors = ButtonDefaults.buttonColors(containerColor = theme.primary), modifier = Modifier.height(50.dp)) { Text(if (isEn) "Save" else "Salvar", fontSize = 11.sp, color = theme.onPrimary) }
                    }
                }
            }
        }
    }
}