package com.dfdx047.odinhub.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import com.dfdx047.odinhub.data.AppOverrideEntity
import com.dfdx047.odinhub.data.AppOverrideRepository
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.FeatureFlags
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.ButtonActionHandler
import com.dfdx047.odinhub.tools.ForegroundAppTracker
import com.dfdx047.odinhub.tools.HomeGestureDetector
import com.dfdx047.odinhub.tools.hardware.DisplayManager
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import com.dfdx047.odinhub.tools.hardware.VulkanNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ForegroundAppWatcherService : AccessibilityService() {

    @Inject lateinit var prefs: SharedPrefsRepo
    @Inject lateinit var performanceManager: PerformanceManager
    @Inject lateinit var thermalManager: ThermalManager
    @Inject lateinit var displayManager: DisplayManager
    @Inject lateinit var buttonActions: ButtonActionHandler

    // Fonte única de verdade das regras por jogo (ver AppOverrideRepository). Substitui as
    // leituras diretas de SharedPrefs (`prefs.getPerApp*`) que existiam aqui antes: aquelas
    // chaves eram escritas SÓ pelo overlay, e nunca refletiam o que era configurado na aba
    // Performance -> Per-App Overrides (que só gravava na tabela Room, nunca lida aqui).
    @Inject lateinit var overrideRepository: AppOverrideRepository

    // Fonte única de verdade, reativa, de "que app está à frente" -- o overlay observa o mesmo
    // objeto, por isso a sua composição re-chaveia sozinha a cada troca de jogo. Ver
    // ForegroundAppTracker para o porquê de isto ter deixado de ser um `prefs.getString`.
    @Inject lateinit var foregroundTracker: ForegroundAppTracker

    @Volatile
    private var currentApp = ""

    /** Gestos do botão Home (toque simples/duplo/triplo/longo) -- ver HomeGestureDetector. */
    private val homeGestures by lazy {
        HomeGestureDetector(
            handler = android.os.Handler(android.os.Looper.getMainLooper()),
            config = { prefs.homeGestureConfig },
            fire = { buttonActions.perform(it) },
        )
    }

    /**
     * `onAccessibilityEvent` corre na thread principal do serviço, e aplicar um perfil faz várias
     * escritas root bloqueantes (ver ShellExecutor/PServerBinder). Fazer isso de forma síncrona
     * aqui trava a entrega de eventos de acessibilidade ao sistema -- o mesmo tipo de erro que
     * causou o congelamento na splash screen e o lag dos sliders. Cada troca de app cancela a
     * aplicação anterior: ao passar rápido por várias apps, só o perfil da app em que realmente
     * ficámos chega a ser escrito.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var applyJob: Job? = null

    /**
     * O tracker arranca a dizer "nenhum app em primeiro plano" (e portanto com o puxador do
     * overlay escondido), à espera do primeiro `TYPE_WINDOW_STATE_CHANGED`. Se o serviço for
     * ligado com um jogo já aberto — ou se for reiniciado pelo sistema durante o jogo — esse
     * evento pode demorar a chegar, e o overlay ficaria escondido dentro do jogo sem razão.
     * Aqui semeamos o estado inicial com a janela que já está ativa.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        // Enquanto este serviço estiver ligado, a deteção é por eventos e a sondagem de reserva
        // do tracker fica em repouso.
        foregroundTracker.setAccessibilityConnected(true)
        buttonActions.attach(this)

        // Nota: não usar `rootInActiveWindow` aqui. Este serviço está declarado com
        // `canRetrieveWindowContent="false"` (ver accessibility_service_config.xml), portanto essa
        // propriedade devolve sempre null e a semeadura falharia em silêncio. O tracker resolve o
        // app em primeiro plano pelo UsageStatsManager, que não precisa dessa flag.
        //
        // Janela longa (6 h) só nesta semeadura: se o processo morreu a meio de um jogo (LMK), o
        // jogo já está à frente há mais do que os 60 s da sondagem normal e, sem isto, nenhum
        // perfil era aplicado até o utilizador sair e voltar a entrar.
        applyJob?.cancel()
        applyJob = scope.launch {
            // Consulta de 6 h de eventos: fora da thread principal.
            val pkg = foregroundTracker.resolveForegroundPackage(SEED_LOOKBACK_MS)
            if (pkg == null) {
                // Não se sabe o que está à frente: aplica o perfil GLOBAL (o que o utilizador
                // escolheu por omissão) em vez de deixar o aparelho no estado de arranque.
                applyProfile(null)
                return@launch
            }
            // Um evento de acessibilidade pode ter chegado entretanto: esse manda.
            if (currentApp.isNotEmpty()) return@launch
            currentApp = pkg
            val isUserApp = foregroundTracker.onForegroundPackage(pkg)
            // Espera pela primeira leitura do Room: num processo acabado de arrancar o cache está
            // vazio e a regra do jogo era lida como "não existe".
            applyProfile(if (isUserApp) overrideRepository.getWhenLoaded(pkg) else null)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val shortcutKey = prefs.overlayShortcutKeyCode
        if (shortcutKey != 0 && event.keyCode == shortcutKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                GamingOverlayService.toggleOverlayFlow.tryEmit(Unit)
            }
            return true
        }
        // Macros de M1/M2 (a tecla-gatilho só é consumida se a macro desse botão estiver ligada).
        if (buttonActions.handleMacroKey(event)) return true
        // Gestos do Home (só com a opção ligada; desligada, o Home fica 100% nativo).
        if (homeGestures.onKeyEvent(event)) return true
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // BUG RELATADO: "clico na barrinha, o overlay abre e some, e não volta mais".
        //
        // Ao expandir, o painel do overlay larga o FLAG_NOT_FOCUSABLE para poder receber toques
        // (ver QuickAccessOverlay.applyGeometry). Uma janela focável ROUBA o foco ao jogo, e o
        // sistema emite um TYPE_WINDOW_STATE_CHANGED com o pacote... desta própria app. O tracker
        // via o nosso pacote, concluía "isto não é um jogo", punha isGameForeground a false, e o
        // overlay escondia-se a si próprio no instante em que era aberto. Depois não voltava
        // porque o jogo, ao recuperar o foco, não emite necessariamente um novo evento de
        // mudança de janela -- daí ser preciso sair e voltar a entrar no jogo.
        //
        // A janela do overlay não é "mudar de app": é a nossa própria UI por cima do jogo. Por
        // isso ignoramos o evento por completo e deixamos o estado como está. A única exceção é
        // a MainActivity: aí o utilizador saiu MESMO do jogo para dentro do Odin Hub.
        if (pkg == packageName) {
            val className = event.className?.toString().orEmpty()
            if (!className.contains("MainActivity")) return
        }

        // Cortina de notificações, heads-up, teclado, diálogos do sistema: janelas por cima do
        // jogo que NÃO significam sair dele. Ignorá-las é o que impede o overlay de desaparecer
        // ao puxar a barra de notificações (ver ForegroundAppTracker.isTransientWindow).
        if (foregroundTracker.isTransientWindow(pkg)) return

        if (pkg == currentApp) return
        currentApp = pkg

        // O tracker decide se isto é mesmo um app de utilizador (e publica o pacote + a
        // visibilidade do puxador do overlay). Antes, esta decisão era `pkg != launcherPackage`
        // mais um filtro pelo nome antigo do projeto, que nunca batia com o applicationId real
        // (`com.dfdx047.odinhub`) -- por isso o overlay aparecia por cima da própria app e de
        // ecrãs do sistema. Ver ForegroundAppTracker.isTrackableApp.
        val isUserApp = foregroundTracker.onForegroundPackage(pkg)

        applyJob?.cancel()
        applyJob = scope.launch {
            // A espera é o que torna o cancelamento acima eficaz: as funções de aplicação são
            // bloqueantes e sem pontos de suspensão, por isso cancelar um job que já começou a
            // escrever não o pára. Com esta janela, ao atravessar vários ecrãs seguidos só o
            // perfil da app onde realmente ficámos chega a ser escrito no hardware.
            delay(APPLY_DEBOUNCE_MS)

            if (isUserApp) {
                // Regra do jogo, ou os valores globais quando o jogo ainda não tem regra própria.
                applyProfile(overrideRepository.getWhenLoaded(pkg))
            } else {
                // Saímos de um jogo para a home/sistema/própria app: devolvemos o aparelho ao
                // perfil GLOBAL. Sem isto, o limite do último jogo continuava a valer para o
                // sistema inteiro -- exatamente o "ele fica controlando o sistema inteiro" que
                // foi relatado. As regras por app só valem dentro do app a que pertencem.
                applyProfile(null)
            }
        }
    }

    /**
     * Aplica um perfil ao hardware. `override == null` significa "usa os valores globais"
     * (ecrã de Settings), que é também o estado para onde voltamos ao sair de um jogo.
     */
    private fun applyProfile(override: AppOverrideEntity?) {
        // 1. TDP ou Clocks -- MUTUAMENTE EXCLUSIVOS. limitMode é por jogo (override?.limitMode);
        // sem override, usa o modo global (prefs.activeLimitMode), igual ao ecrã de Settings.
        val limitMode = override?.limitMode ?: prefs.activeLimitMode
        if (limitMode == "TDP") {
            // Regra do jogo: os watts guardados (a sentinela abaixo de 1 W significa "Stock, sem
            // limite" -- ver TdpProfiles.STOCK_SENTINEL_WATTS). Sem regra: o perfil GLOBAL, que é
            // o id do perfil selecionado (Stock = sem limite) ou o slider quando é "custom".
            val tdp: Float? = if (override?.tdpWatts != null) {
                override.tdpWatts
            } else {
                TdpProfiles.resolveWatts(prefs.tdpProfileId, prefs.tdpValue)
            }
            performanceManager.applyTdpSelection(tdp)
        } else {
            val perfKHz = override?.perfClockKHz ?: (prefs.cpuPerfClock * 1000).toLong()
            val primeKHz = override?.primeClockKHz ?: (prefs.cpuPrimeClock * 1000).toLong()
            val gpuHz = override?.gpuClockHz ?: (prefs.gpuClock * 1_000_000).toLong()
            performanceManager.applyAbsoluteClocks(perfKHz, primeKHz, gpuHz)
        }

        // 2. Ventoinha baseada no modelo FanMode
        // Numa regra, -1 ("Stock" no editor por app) quer dizer "segue o global".
        val fanModeValue = override?.fanSettingsValue?.takeIf { v -> FanMode.selectable.any { it.settingsValue == v } } ?: prefs.fanMode
        performanceManager.applyFanMode(FanMode.fromSettingsValue(fanModeValue))

        // 3. Limite térmico por jogo (null = segue o global da aba Performance).
        thermalManager.setAppOverride(override?.thermalMode)

        // 4. Calibração de cor: a do jogo quando ele tem "cor própria" ligada, senão a global.
        if (override?.displayOverride == true) {
            displayManager.applyColor(override.saturationOverride, override.temperatureOverride)
        } else {
            displayManager.applyColor(prefs.saturationOverride, prefs.temperatureOverride)
        }

        // 5. Macros de M1/M2: as do jogo (null = segue o global). Também aponta o mapeamento
        // nativo para a tecla-gatilho só quando há macro ativa.
        buttonActions.setGameMacros(override?.m1Macro, override?.m2Macro)

        // 6. Gráficos, SGSR, LSFG e ReShade -- só com o motor gráfico disponível
        // (FeatureFlags.GRAPHICS_ENGINE_AVAILABLE); sem ele não há nada a aplicar.
        if (!FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) return
        val sgsr = override?.sgsrEnabled ?: prefs.globalSgsrEnabled
        val sgsrMode = override?.sgsrMode ?: prefs.sgsrMode
        VulkanNativeBridge.applySgsr(sgsr, sgsrMode)

        val lsfg = override?.lsfgEnabled ?: prefs.globalLsfgEnabled
        val lsfgMult = override?.lsfgMultiplier?.let { "${it}x" } ?: prefs.lsfgMultiplier
        val lsfgPacing = override?.lsfgFramePacing ?: prefs.lsfgFramePacing
        VulkanNativeBridge.applyLsfg(lsfg, lsfgMult, lsfgPacing)

        val reshade = override?.reshadeProfile ?: prefs.reshadeProfile
        VulkanNativeBridge.applyReshade(reshade, prefs.saturationOverride, prefs.temperatureOverride)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        // Devolve a deteção à sondagem de reserva: se este serviço for desligado (pelo utilizador
        // ou pelo sistema), o overlay não pode simplesmente deixar de funcionar.
        foregroundTracker.setAccessibilityConnected(false)
        homeGestures.reset()
        buttonActions.detach()
        scope.cancel()
    }

    private companion object {
        /** Ver o comentário em `onAccessibilityEvent` sobre por que existe esta espera. */
        const val APPLY_DEBOUNCE_MS = 120L

        /** Janela da semeadura inicial (ver onServiceConnected). */
        const val SEED_LOOKBACK_MS = 6 * 60 * 60 * 1000L
    }
}
