package com.dfdx047.odinhub.main

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.ButtonMacros
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.HomeGestureConfig
import com.dfdx047.odinhub.models.MacroStep
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.service.GamingOverlayService
import com.dfdx047.odinhub.tools.DeviceCapabilities
import com.dfdx047.odinhub.tools.DeviceRegistry
import com.dfdx047.odinhub.tools.SettingsRepo
import com.dfdx047.odinhub.tools.ShellExecutor
import com.dfdx047.odinhub.tools.hardware.DisplayManager
import com.dfdx047.odinhub.tools.hardware.LosslessManager
import com.dfdx047.odinhub.tools.SoundManager
import com.dfdx047.odinhub.tools.hardware.VulkanNativeBridge
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import com.dfdx047.odinhub.tools.hardware.ThermalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Janela de espera antes de aplicar ao hardware um valor arrastado num slider -- ver o comentário
 * em `updateTdp`. O mesmo valor é usado no overlay (QuickAccessContent).
 */
private const val LIVE_APPLY_DEBOUNCE_MS = 70L

/** Intervalo mínimo entre avisos repetidos sobre o MESMO nó de hardware. */
private const val HARDWARE_ERROR_COOLDOWN_MS = 5 * 60 * 1000L

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceCapabilities: DeviceCapabilities,
    private val executor: ShellExecutor,
    private val settings: SettingsRepo,
    private val buttonActions: com.dfdx047.odinhub.tools.ButtonActionHandler,
    private val prefs: SharedPrefsRepo,
    private val performanceManager: PerformanceManager,
    private val thermalManager: ThermalManager,
    private val displayManager: DisplayManager,
    private val losslessManager: LosslessManager,
    private val soundManager: SoundManager,
    private val updateManager: com.dfdx047.odinhub.tools.UpdateManager,
) : ViewModel() {

    /** Estado do auto-update (o diálogo no SettingsScreen observa isto). */
    val updateState: StateFlow<com.dfdx047.odinhub.tools.UpdateState> get() = updateManager.state
    fun checkForUpdates() { viewModelScope.launch { updateManager.check(manual = true) } }
    fun installUpdate(release: com.dfdx047.odinhub.tools.AppRelease) { viewModelScope.launch { updateManager.downloadAndInstall(release) } }
    fun skipUpdate(release: com.dfdx047.odinhub.tools.AppRelease) = updateManager.skip(release)
    fun dismissUpdate() = updateManager.dismiss()

    private val _uiState = MutableStateFlow(MainUiModel())
    val uiState: StateFlow<MainUiModel> = _uiState.asStateFlow()

    // Antes, uma escrita de sysfs rejeitada pelo PServer só ficava no Log.e do PerformanceManager
    // -- o utilizador via o slider "colar" na UI sem nenhum aviso de que o valor não tinha sido
    // realmente aplicado ao hardware. Este canal expõe um evento único (não um estado, para não
    // reexibir o mesmo aviso a cada recomposição/rotação) sempre que um nó de hardware passa de
    // "ok" para "falhou", para a UI mostrar um snackbar discreto.
    private val _hardwareErrorEvents = Channel<String>(Channel.BUFFERED)
    val hardwareErrorEvents = _hardwareErrorEvents.receiveAsFlow()
    private var lastFailingHardwareLabels: Set<String> = emptySet()
    private val hardwareErrorLastShown = mutableMapOf<String, Long>()

    init {
        // Deteção sem I/O root (Build + system properties): seguro na thread principal. A versão
        // anterior fazia dois `getprop` pelo PServer aqui, bloqueando o primeiro fotograma.
        val device = DeviceRegistry.current

        _uiState.update {
            MainUiModel(
                device = device,
                deviceVersion = DeviceRegistry.firmwareVersion,
                hasBackButtons = deviceCapabilities.backButtons.value,
                showPServerNotAvailableDialog = !executor.pServerAvailable,
                homeGestures = prefs.homeGestureConfig,
                m1MacroEnabled = prefs.macroEnabled("m1"),
                m1MacroSteps = ButtonMacros.decode(prefs.macroSteps("m1")),
                m2MacroEnabled = prefs.macroEnabled("m2"),
                m2MacroSteps = ButtonMacros.decode(prefs.macroSteps("m2")),

                selectedThemeIndex = prefs.selectedThemeIndex,
                useAmoledBlack = prefs.useAmoledBlack,
                useGlass = prefs.useGlass,
                fanMode = prefs.fanMode,
                customProfiles = prefs.getAllCustomProfiles(),

                // Perfil global de performance. Faltava aqui: a UI arrancava sempre nos valores
                // por omissão do MainUiModel (15 W / 3530 / 4320 / 1100), mesmo que o utilizador
                // tivesse configurado outra coisa na sessão anterior -- e o que o hardware estava
                // realmente a aplicar (lido do prefs pelo watcher) nem sequer correspondia ao que
                // o ecrã mostrava.
                activeLimitMode = prefs.activeLimitMode,
                tdpValue = prefs.tdpValue,
                tdpProfileId = prefs.tdpProfileId,
                cpuPerfClock = prefs.cpuPerfClock,
                cpuPrimeClock = prefs.cpuPrimeClock,
                cpuEffClock = prefs.cpuEffClock,
                gpuClock = prefs.gpuClock,

                currentSaturation = prefs.saturationOverride,
                currentTemperature = prefs.temperatureOverride,

                overlayShortcutKeyCode = prefs.overlayShortcutKeyCode,

                globalLsfgEnabled = prefs.globalLsfgEnabled,
                lsfgMultiplier = prefs.lsfgMultiplier,
                lsfgFramePacing = prefs.lsfgFramePacing,
                lsfgPerformanceMode = prefs.lsfgPerformanceMode,

                globalSgsrEnabled = prefs.globalSgsrEnabled,
                sgsrMode = prefs.sgsrMode,
                sgsrSharpness = prefs.sgsrSharpness,

                reshadeProfile = prefs.reshadeProfile,
                showFpsOverlay = prefs.showFpsOverlay,
                // Só com o motor gráfico ativo (I/O de disco à toa na thread principal, senão).
                isDllImported = com.dfdx047.odinhub.models.FeatureFlags.GRAPHICS_ENGINE_AVAILABLE && losslessManager.isDllImported,

                overlayEnabled = prefs.overlayEnabled,
                overlayHandleOpacity = prefs.overlayHandleOpacity,
                overlayHandleWidth = prefs.overlayHandleWidth,

                bgmEnabled = prefs.bgmEnabled,
                bgmVolume = prefs.bgmVolume,
                sfxEnabled = prefs.sfxEnabled,
                sfxVolume = prefs.sfxVolume
            )
        }

        soundManager.startBackgroundMusicIfEnabled()

        // Auto-update: verificação discreta no arranque (no máximo a cada 6 h).
        viewModelScope.launch { runCatching { updateManager.autoCheck() } }

        // O motor gráfico (camada Vulkan real para ReShade/SGSR/LSFG) foi removido -- não estava
        // estável em hardware real (travava o próprio processo da app, ver histórico). Voltamos
        // ao `VulkanNativeBridge` original (ponte JNI que só guarda os valores, sem efeito real
        // no jogo) até termos uma implementação de referência testada para retomar isto.
        //
        // As chamadas de arranque que fazem `exec` root (`settings.applyRequiredSettings()`)
        // continuam em background por segurança -- isso já tinha causado um travamento na splash
        // screen independente do motor gráfico (ver Odin 3: "Activity pause timeout" no logcat).
        viewModelScope.launch(Dispatchers.IO) {
            settings.applyRequiredSettings()
        }

        // Motor gráfico: só quando existir (FeatureFlags) -- evita carregar a biblioteca nativa à toa.
        if (com.dfdx047.odinhub.models.FeatureFlags.GRAPHICS_ENGINE_AVAILABLE) {
            VulkanNativeBridge.applyLsfg(prefs.globalLsfgEnabled, prefs.lsfgMultiplier, prefs.lsfgFramePacing)
            VulkanNativeBridge.applySgsr(prefs.globalSgsrEnabled, prefs.sgsrMode)
            VulkanNativeBridge.applyReshade(prefs.reshadeProfile, prefs.saturationOverride, prefs.temperatureOverride)
        }

        if (prefs.overlayEnabled) {
            context.startService(Intent(context, GamingOverlayService::class.java))
        }
        // Abrir a app volta a mostrar o HUD (se tinha sido escondido com dois toques).
        com.dfdx047.odinhub.service.PerformanceHudService.hiddenFlow.value = false
        com.dfdx047.odinhub.service.PerformanceHudService.startIfEnabled(context, prefs)

        // Só emite um evento quando um nó de hardware passa a falhar (não a cada tick do daemon
        // de 1s do PerformanceManager, senão o mesmo snackbar reapareceria sem parar enquanto o
        // nó continuasse rejeitado).
        viewModelScope.launch {
            performanceManager.lastApplyStatus.collect { statuses ->
                val failingLabels = statuses.filterNot { it.ok }.map { it.label }.toSet()

                // O daemon de hardware reescreve os limites a cada segundo. Se um nó estiver
                // mesmo bloqueado, ele falha a cada tick -- e como o estado alterna entre "ok" e
                // "falhou" conforme outro daemon do sistema repõe o valor, o aviso reaparecia sem
                // parar, tapando a interface. Cada nó só pode avisar uma vez por
                // HARDWARE_ERROR_COOLDOWN_MS; o resto vai só para o log.
                val now = System.currentTimeMillis()
                val toReport = (failingLabels - lastFailingHardwareLabels).filter { label ->
                    val lastShown = hardwareErrorLastShown[label] ?: 0L
                    now - lastShown >= HARDWARE_ERROR_COOLDOWN_MS
                }

                if (toReport.isNotEmpty()) {
                    toReport.forEach { hardwareErrorLastShown[it] = now }
                    _hardwareErrorEvents.trySend(
                        (if (prefs.isEnglish) "Could not apply: " else "Não foi possível aplicar: ") + toReport.joinToString(", ")
                    )
                }
                lastFailingHardwareLabels = failingLabels
            }
        }

        // Tabelas reais de frequências (OPPs do kernel) e estado do limite térmico: copiados
        // para o uiState para os chips de clocks e o cartão térmico se desenharem a partir do
        // mesmo objeto que o overlay lê diretamente do PerformanceManager.
        viewModelScope.launch {
            performanceManager.clockTables.collect { tables ->
                _uiState.update { it.copy(clockTables = tables) }
            }
        }
        viewModelScope.launch {
            thermalManager.status.collect { status ->
                _uiState.update { it.copy(thermalStatus = status) }
            }
        }
        viewModelScope.launch {
            performanceManager.autoTdpStatus.collect { telemetry ->
                _uiState.update { it.copy(autoTdp = telemetry) }
            }
        }
        viewModelScope.launch {
            deviceCapabilities.backButtons.collect { has -> _uiState.update { it.copy(hasBackButtons = has) } }
        }
        // Presets guardados no overlay aparecem aqui sem reiniciar a app.
        viewModelScope.launch {
            prefs.customProfilesFlow.collect { list -> _uiState.update { it.copy(customProfiles = list) } }
        }

        // "AYN Odin 2 detetado": uma vez por aparelho (ou ao trocar de aparelho com backup restaurado).
        if (prefs.lastDetectedDeviceId != device.id + ":" + device.name) {
            prefs.lastDetectedDeviceId = device.id + ":" + device.name
            val message = if (device.official) {
                if (prefs.isEnglish) "${device.name} detected — frequency tables, thermal zones and TDP profiles adjusted"
                else "${device.name} detectado — tabelas de frequência, zonas térmicas e perfis de TDP ajustados"
            } else {
                if (prefs.isEnglish) "${device.name} (${device.soc.label}): no dedicated profile — using the kernel's live tables"
                else "${device.name} (${device.soc.label}): sem perfil próprio — usando as tabelas do kernel"
            }
            _hardwareErrorEvents.trySend(message)
        }
    }

    fun importLosslessDll(uri: Uri): Boolean {
        val success = losslessManager.importDll(uri)
        if (success) _uiState.update { it.copy(isDllImported = true) }
        return success
    }

    fun updateFanMode(mode: Int) { prefs.fanMode = mode; _uiState.update { it.copy(fanMode = mode) }; viewModelScope.launch(Dispatchers.IO) { performanceManager.applyFanMode(FanMode.fromSettingsValue(mode)) } }
    /**
     * CAUSA RAIZ do "mexo no Settings e nada fica": esta função só mudava o `_uiState`. O modo
     * ativo (TDP vs Clocks) é lido de `prefs.activeLimitMode` pelo ForegroundAppWatcherService e
     * pelo overlay como valor GLOBAL de recurso. Como nunca era escrito, esses dois continuavam a
     * ver o valor antigo e reaplicavam-no por cima na troca de app seguinte. Agora persiste E
     * aplica de imediato, tal como o overlay já fazia.
     */
    fun updateLimitMode(mode: String) {
        prefs.activeLimitMode = mode
        _uiState.update { it.copy(activeLimitMode = mode) }
        // Uma escrita de slider ainda pendente (janela de debounce) do OUTRO modo não pode chegar
        // ao hardware depois desta troca -- senão o aparelho ficava em TDP com a UI em Clocks.
        tdpJob?.cancel()
        clockJob?.cancel()
        clockJob = viewModelScope.launch(Dispatchers.IO) {
            val current = _uiState.value
            if (mode == "TDP") {
                // O perfil de TDP é o "mestre": Stock = sem limite, perfil fixo = os seus watts,
                // "custom" = o slider. Nunca os clocks manuais -- os dois modos não se misturam.
                performanceManager.applyTdpSelection(TdpProfiles.resolveWatts(current.tdpProfileId, current.tdpValue))
            } else {
                performanceManager.applyAbsoluteClocks(
                    (current.cpuPerfClock * 1000).toLong(),
                    (current.cpuPrimeClock * 1000).toLong(),
                    (current.gpuClock * 1_000_000).toLong(),
                    (current.cpuEffClock * 1000).toLong(),
                )
            }
        }
    }

    // CAUSA RAIZ do lag/travamento reportado ao arrastar o slider de TDP ou mudar de preset de
    // clock na aba Settings -> Performance: `applyDynamicTdp`/`applyAbsoluteClocks` fazem um
    // `exec` root SÍNCRONO, e eram chamados diretamente na callback de UI do Compose
    // (`onValueChange`, disparado dezenas de vezes por segundo durante o arrasto) -- cada chamada
    // bloqueava a thread principal até o `su` terminar. Movido para `viewModelScope` em
    // `Dispatchers.IO`, cancelando o job anterior antes de lançar o novo.
    //
    // O `delay` à frente da escrita é o que dá sentido ao cancelamento: `applyDynamicTdp` é
    // bloqueante e não tem pontos de suspensão, portanto cancelar um job que já começou a
    // escrever não o interrompe. Com a janela de espera, o job anterior morre ainda dentro do
    // `delay` -- só o último valor do arrasto chega mesmo ao root.
    private var tdpJob: Job? = null
    private var clockJob: Job? = null

    // NOTA sobre `prefs.*` abaixo: estes valores são o PERFIL GLOBAL, e é deles que o
    // ForegroundAppWatcherService e o overlay partem quando um jogo não tem regra própria. Antes,
    // estas duas funções só escreviam no `_uiState` -- o que fazia o Settings parecer funcionar
    // durante a sessão, mas: (a) ao trocar de app, o watcher reaplicava o valor antigo do prefs
    // por cima, e (b) ao reabrir a app, a UI voltava aos valores por omissão. Era este o "mexo e
    // não fica" da aba Performance.
    /**
     * Slider de TDP (1..25 W). Mover o slider aplica o valor do slider e passa a seleção para
     * "custom": nenhum chip de perfil fica destacado. Os perfis são independentes do slider --
     * ver [selectTdpProfile].
     */
    fun updateTdp(watts: Float) {
        val clamped = watts.coerceIn(TdpProfiles.TDP_MIN_WATTS, TdpProfiles.TDP_MAX_WATTS)
        prefs.tdpValue = clamped
        prefs.tdpProfileId = TdpProfiles.ID_CUSTOM
        _uiState.update { it.copy(tdpValue = clamped, tdpProfileId = TdpProfiles.ID_CUSTOM) }
        tdpJob?.cancel()
        tdpJob = viewModelScope.launch(Dispatchers.IO) {
            delay(LIVE_APPLY_DEBOUNCE_MS)
            if (prefs.activeLimitMode == "TDP") performanceManager.applyDynamicTdp(clamped)
        }
    }

    /**
     * Seleciona um perfil fixo de TDP (Power Save / Balanced / Triple A / Stock) e aplica os
     * seus watts exatos; Stock = sem limite (clocks a 100%, AutoTDP desligado). O slider é
     * movido para os watts do perfil só para refletir o que está a ser aplicado.
     */
    fun selectTdpProfile(profileId: String) {
        val profile = TdpProfiles.byId(profileId) ?: return
        prefs.tdpProfileId = profile.id
        profile.watts?.let { prefs.tdpValue = it }
        _uiState.update { it.copy(tdpProfileId = profile.id, tdpValue = profile.watts ?: it.tdpValue) }
        tdpJob?.cancel()
        tdpJob = viewModelScope.launch(Dispatchers.IO) {
            if (prefs.activeLimitMode == "TDP") performanceManager.applyTdpSelection(profile.watts)
        }
    }

    /**
     * Perfil de TDP guardado pelo utilizador (prefs.saveCustomProfile, tipo "TDP"): aplica os
     * watts guardados e marca a seleção como "custom" com esse valor -- o chip do preset
     * destaca-se por igualdade de watts.
     */
    fun selectCustomTdpProfile(watts: Float) {
        updateTdp(watts)
    }

    fun updateManualClocks(perfClock: Float, primeClock: Float, gpuClock: Float, effClock: Float = _uiState.value.cpuEffClock) {
        prefs.cpuEffClock = effClock
        _uiState.update { it.copy(cpuEffClock = effClock) }
        prefs.cpuPerfClock = perfClock
        prefs.cpuPrimeClock = primeClock
        prefs.gpuClock = gpuClock
        _uiState.update { it.copy(cpuPerfClock = perfClock, cpuPrimeClock = primeClock, gpuClock = gpuClock) }
        clockJob?.cancel()
        clockJob = viewModelScope.launch(Dispatchers.IO) {
            delay(LIVE_APPLY_DEBOUNCE_MS)
            if (prefs.activeLimitMode != "TDP") {
                performanceManager.applyAbsoluteClocks((perfClock * 1000).toLong(), (primeClock * 1000).toLong(), (gpuClock * 1000000).toLong(), (effClock * 1000).toLong())
            }
        }
    }

    fun saveCustomProfile(name: String, type: String, val1: Float, val2: Float, val3: Float, val4: Float, val5: Float = 0f) {
        // O customProfilesFlow (coletado no init) atualiza a lista.
        prefs.saveCustomProfile(name, type, val1, val2, val3, val4, val5)
    }

    // Limite térmico (root) -- ver ThermalManager. As escritas correm no scope do próprio
    // gestor (IO); aqui só encaminhamos a escolha. O estado volta pelo StateFlow coletado no init.
    fun updateThermalLimitMode(mode: String) = thermalManager.setMode(mode)
    fun updateThermalDisableZoneMode(enabled: Boolean) = thermalManager.setDisableZoneMode(enabled)
    fun refreshThermalZones() = thermalManager.refresh()

    // Música de fundo e efeitos sonoros da UI (ver SoundManager). O código que tocava estes
    // sons foi perdido numa refatoração anterior da interface; os ficheiros em res/raw
    // continuavam no projeto, só faltava quem os tocasse e os controlos persistentes.
    fun playClickSound() = soundManager.playClick()
    fun updateBgmEnabled(enabled: Boolean) { soundManager.setBgmEnabled(enabled); _uiState.update { it.copy(bgmEnabled = enabled) } }
    fun updateBgmVolume(volume: Float) { soundManager.setBgmVolume(volume); _uiState.update { it.copy(bgmVolume = volume) } }
    fun updateSfxEnabled(enabled: Boolean) { soundManager.setSfxEnabled(enabled); _uiState.update { it.copy(sfxEnabled = enabled) } }
    fun updateSfxVolume(volume: Float) { soundManager.setSfxVolume(volume); _uiState.update { it.copy(sfxVolume = volume) } }

    /** Idioma da UI, persistido e partilhado com overlay/editor por app (ver SharedPrefsRepo.isEnglish). */
    val isEnglish: StateFlow<Boolean> get() = prefs.isEnglishFlow
    fun setLanguage(english: Boolean) { prefs.isEnglish = english }

    /** Definições do fundo, persistidas (ver SharedPrefsRepo.wallpaper*). */
    data class WallpaperSettings(
        val type: String,
        val blur: Boolean,
        val blurIntensity: Float,
        val opacity: Float,
        val staticIndex: Int,
        val customUri: String?,
        val name: String?,
    )

    fun wallpaperSettings() = WallpaperSettings(
        prefs.wallpaperType, prefs.wallpaperBlur, prefs.wallpaperBlurIntensity, prefs.wallpaperOpacity,
        prefs.wallpaperStatic, prefs.wallpaperCustomUri, prefs.wallpaperName,
    )

    fun saveWallpaperSettings(w: WallpaperSettings) {
        prefs.wallpaperType = w.type
        prefs.wallpaperBlur = w.blur
        prefs.wallpaperBlurIntensity = w.blurIntensity
        prefs.wallpaperOpacity = w.opacity
        prefs.wallpaperStatic = w.staticIndex
        prefs.wallpaperCustomUri = w.customUri
        prefs.wallpaperName = w.name
    }

    fun updateThemeIndex(newIndex: Int) { prefs.selectedThemeIndex = newIndex; prefs.notifyAppearanceChanged(); _uiState.update { it.copy(selectedThemeIndex = newIndex) } }
    fun updateAmoledBlack(enabled: Boolean) { prefs.useAmoledBlack = enabled; prefs.notifyAppearanceChanged(); _uiState.update { it.copy(useAmoledBlack = enabled) } }
    fun updateGlass(enabled: Boolean) { prefs.useGlass = enabled; _uiState.update { it.copy(useGlass = enabled) } }
    fun finishWelcomeSetup() { prefs.isFirstRun = false }
    fun isFirstRun(): Boolean = prefs.isFirstRun
    fun toggleOverlay(enabled: Boolean) { prefs.overlayEnabled = enabled; _uiState.update { it.copy(overlayEnabled = enabled) }; val intent = Intent(context, GamingOverlayService::class.java); if (enabled) context.startService(intent) else context.stopService(intent) }
    // ---- HUD de desempenho ----
    val hudConfig: StateFlow<com.dfdx047.odinhub.models.HudConfig> get() = prefs.hudConfigFlow
    fun updateHudConfig(config: com.dfdx047.odinhub.models.HudConfig) {
        val wasEnabled = prefs.hudConfig.enabled
        prefs.hudConfig = config
        if (config.enabled && !wasEnabled) com.dfdx047.odinhub.service.PerformanceHudService.hiddenFlow.value = false
        if (config.enabled) com.dfdx047.odinhub.service.PerformanceHudService.startIfEnabled(context, prefs)
    }
    fun showHudAgain() {
        com.dfdx047.odinhub.service.PerformanceHudService.hiddenFlow.value = false
        com.dfdx047.odinhub.service.PerformanceHudService.startIfEnabled(context, prefs)
    }

    fun updateHandleOpacity(opacity: Float) { prefs.overlayHandleOpacity = opacity; _uiState.update { it.copy(overlayHandleOpacity = opacity) } }
    fun updateHandleWidth(width: Int) { prefs.overlayHandleWidth = width; _uiState.update { it.copy(overlayHandleWidth = width) } }
    fun toggleFpsOverlay(enabled: Boolean) { prefs.showFpsOverlay = enabled; _uiState.update { it.copy(showFpsOverlay = enabled) } }

    fun updateGlobalLsfg(enabled: Boolean) { prefs.globalLsfgEnabled = enabled; _uiState.update { it.copy(globalLsfgEnabled = enabled) }; VulkanNativeBridge.applyLsfg(enabled, prefs.lsfgMultiplier, prefs.lsfgFramePacing) }
    fun updateLsfgOptions(multiplier: String, pacing: Boolean, perfMode: Boolean) { prefs.lsfgMultiplier = multiplier; prefs.lsfgFramePacing = pacing; prefs.lsfgPerformanceMode = perfMode; _uiState.update { it.copy(lsfgMultiplier = multiplier, lsfgFramePacing = pacing, lsfgPerformanceMode = perfMode) }; VulkanNativeBridge.applyLsfg(prefs.globalLsfgEnabled, multiplier, pacing) }

    fun updateGlobalSgsr(enabled: Boolean) { prefs.globalSgsrEnabled = enabled; _uiState.update { it.copy(globalSgsrEnabled = enabled) }; VulkanNativeBridge.applySgsr(enabled, prefs.sgsrMode) }
    fun updateSgsrOptions(mode: String, sharpness: Float) { prefs.sgsrMode = mode; prefs.sgsrSharpness = sharpness; _uiState.update { it.copy(sgsrMode = mode, sgsrSharpness = sharpness) }; VulkanNativeBridge.applySgsr(prefs.globalSgsrEnabled, mode) }

    // Os sliders de saturação/temperatura são arrastados continuamente, e cada aplicação faz
    // escritas root bloqueantes (service call SurfaceFlinger). Chamadas diretamente do `onValueChange`,
    // como estavam, travavam a thread de UI a cada fotograma do arrasto -- o mesmo bug que já
    // tinha sido corrigido nos sliders de TDP/clocks, mas que aqui tinha passado despercebido.
    // Mesmo padrão: debounce com cancelamento em Dispatchers.IO.
    private var colorJob: Job? = null

    private fun applyColorLive(saturation: Float, temperature: Float) {
        colorJob?.cancel()
        colorJob = viewModelScope.launch(Dispatchers.IO) {
            delay(LIVE_APPLY_DEBOUNCE_MS)
            displayManager.applyColor(saturation, temperature)
        }
    }

    fun saveSaturation(newValue: Float) {
        prefs.saturationOverride = newValue
        _uiState.update { it.copy(currentSaturation = newValue) }
        applyColorLive(newValue, prefs.temperatureOverride)
    }

    fun saveTemperature(newValue: Float) {
        prefs.temperatureOverride = newValue
        _uiState.update { it.copy(currentTemperature = newValue) }
        applyColorLive(prefs.saturationOverride, newValue)
    }

    /**
     * Repõe a calibração de cor de fábrica.
     *
     * Repõe saturação 1.0 e matriz identidade no SurfaceFlinger (e desfaz o Night Display /
     * HWC desativado que versões antigas deixavam ligados). Ver DisplayManager.
     */
    fun resetDisplayColors() {
        prefs.saturationOverride = 1.0f
        prefs.temperatureOverride = 6500f
        _uiState.update { it.copy(currentSaturation = 1.0f, currentTemperature = 6500f) }
        colorJob?.cancel()
        colorJob = viewModelScope.launch(Dispatchers.IO) {
            displayManager.resetDisplayColor()
        }
    }
    fun pServerDialogDismissed() { _uiState.update { it.copy(showPServerNotAvailableDialog = false) } }

    fun showOverlayShortcutDialog() { _uiState.update { it.copy(showOverlayShortcutDialog = true) } }
    fun hideOverlayShortcutDialog() { _uiState.update { it.copy(showOverlayShortcutDialog = false) } }
    fun saveOverlayShortcut(keyCode: Int) { prefs.overlayShortcutKeyCode = keyCode; _uiState.update { it.copy(showOverlayShortcutDialog = false, overlayShortcutKeyCode = keyCode) } }

    // Tudo abaixo toca em `settings put/get system` através do PServer, que é bloqueante. Estas
    // funções eram chamadas diretamente de callbacks do Compose (onClick/onCheckedChange), ou
    // seja, faziam I/O root na thread de UI. Agora vão para Dispatchers.IO; o `_uiState` é
    // atualizado de imediato para a UI continuar a responder na hora.
    // ---- Gestos do botão Home ----
    fun updateHomeGestures(config: HomeGestureConfig) {
        prefs.homeGestureConfig = config
        _uiState.update { it.copy(homeGestures = config) }
    }

    // ---- Macros M1/M2 ----
    fun openMacroEditor(button: String) { _uiState.update { it.copy(macroEditorFor = button) } }
    fun closeMacroEditor() { _uiState.update { it.copy(macroEditorFor = null) } }

    /**
     * Liga/desliga a macro de um botão. Ligada: guarda o mapeamento nativo atual e aponta o botão
     * para a tecla-gatilho (ButtonMacros.TRIGGER_*). Desligada: repõe o mapeamento guardado.
     */
    fun saveMacro(button: String, enabled: Boolean, steps: List<MacroStep>) {
        val effectiveEnabled = enabled && steps.isNotEmpty()
        prefs.saveMacro(button, effectiveEnabled, ButtonMacros.encode(steps))
        _uiState.update {
            if (button == "m1") it.copy(m1MacroEnabled = effectiveEnabled, m1MacroSteps = steps, macroEditorFor = null)
            else it.copy(m2MacroEnabled = effectiveEnabled, m2MacroSteps = steps, macroEditorFor = null)
        }
        // Mesma lógica que o watcher usa ao trocar de jogo (inclui macros por jogo).
        viewModelScope.launch(Dispatchers.IO) { buttonActions.syncTriggers() }
    }

    fun remapButtonClicked(setting: String) {
        // Abre o diálogo já, e preenche o código atual quando a leitura root responder -- antes
        // esta leitura acontecia dentro do onClick e segurava a UI até o binder devolver.
        // -1 = "a ler": o diálogo não pode mostrar (nem gravar) o código do botão ANTERIOR.
        _uiState.update { it.copy(showRemapButtonDialog = true, currentButtonSetting = setting, currentButtonKeyCode = -1) }
        viewModelScope.launch(Dispatchers.IO) {
            val keyCode = executor.getIntSystemSettingOrNull(setting) ?: 0
            _uiState.update { if (it.currentButtonSetting == setting) it.copy(currentButtonKeyCode = keyCode) else it }
        }
    }

    fun remapButtonDialogDismissed() { _uiState.update { it.copy(showRemapButtonDialog = false) } }

    fun saveButtonKeyCode(setting: String, newValue: Int) {
        // `currentButtonKeyCode` passa a acompanhar o valor gravado: sem isto, reabrir o diálogo
        // mostrava o mapeamento antigo até a app ser reiniciada.
        _uiState.update { it.copy(showRemapButtonDialog = false, currentButtonKeyCode = newValue) }
        // Um mapeamento nativo novo substitui a macro desse botão (senão a tecla-gatilho perdia-se).
        val button = when (setting) {
            SettingsRepo.KEY_CUSTOM_M1_VALUE -> "m1"
            SettingsRepo.KEY_CUSTOM_M2_VALUE -> "m2"
            else -> null
        }
        if (button != null && prefs.macroEnabled(button)) {
            prefs.saveMacro(button, false, prefs.macroSteps(button).orEmpty())
            _uiState.update { if (button == "m1") it.copy(m1MacroEnabled = false) else it.copy(m2MacroEnabled = false) }
        }
        // Pelo ButtonActionHandler (sincronizado com o syncTriggers), para uma troca de app a meio
        // não repor o mapeamento antigo por cima do que acabou de ser escolhido.
        viewModelScope.launch(Dispatchers.IO) { buttonActions.setNativeMapping(setting, newValue) }
    }
}