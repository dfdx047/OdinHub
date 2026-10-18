package com.dfdx047.odinhub.appsettings

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.dfdx047.odinhub.data.AppOverrideEntity
import com.dfdx047.odinhub.data.AppOverrideRepository
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.AppOverrideLabels
import com.dfdx047.odinhub.models.ButtonMacros
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.models.MacroMode
import com.dfdx047.odinhub.models.MacroStep
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.tools.hardware.ClockTables
import com.dfdx047.odinhub.tools.hardware.PerformanceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppOverrideUiState(
    val packageName: String = "",
    val appName: String = "",

    // TDP e Clock são MUTUAMENTE EXCLUSIVOS aqui, exatamente como no overlay e no ecrã de
    // Settings global (ver AppOverrideEntity.limitMode / ForegroundAppWatcherService). Antes desta
    // reformulação este ecrã tinha o seu próprio esquema de exclusividade ("Nenhum" bloqueia o
    // outro cartão), incompatível com o resto da app.
    val limitMode: String = "TDP",
    val tdpWatts: Float = TdpProfiles.envelope.tripleA,
    /** Perfil de TDP selecionado (ver TdpProfiles); "custom" = o valor livre do slider. */
    val tdpProfileId: String = TdpProfiles.ID_TRIPLE_A,
    val perfClockMHz: Float = 3530f,
    val primeClockMHz: Float = 4320f,
    /** Cluster de eficiência (MHz); 0 = SoC sem esse cluster. */
    val effClockMHz: Float = 0f,
    val gpuClockMHz: Float = 1100f,

    // -1 = FanMode.Stock (sem override -- usa o modo global). Ver FanMode.kt.
    val fanSettingsValue: Int = FanMode.Stock.settingsValue,

    val lsfgEnabled: Boolean = false,
    val lsfgMultiplier: String = "2x",
    val lsfgFramePacing: Boolean = true,
    val lsfgPerformanceMode: Boolean = false,
    val lsfgQuality: Float = 1.0f,

    val sgsrEnabled: Boolean = false,
    val sgsrMode: String = "Quality",
    val sgsrSharpness: Float = 0.5f,

    val reshadeProfile: String = "Native",
    val saturationOverride: Float = 1.0f,
    val temperatureOverride: Float = 6500f,
    /** `true` = este jogo usa a sua própria saturação/temperatura (senão segue a aba Display). */
    val displayOverride: Boolean = false,
    /** Limite térmico deste jogo (ThermalLimitModes), ou `null` = segue o global. */
    val thermalMode: String? = null,
    /** Macros de M1/M2 deste jogo (ver MacroMode) e qual está aberto no editor ("m1"/"m2"). */
    val m1MacroMode: MacroMode = MacroMode.GLOBAL,
    val m1MacroSteps: List<MacroStep> = emptyList(),
    val m2MacroMode: MacroMode = MacroMode.GLOBAL,
    val m2MacroSteps: List<MacroStep> = emptyList(),
    val macroEditorFor: String? = null,

    val isSaved: Boolean = false,
    val availableReshadeProfiles: List<String> = listOf("Native", "Vibrant", "Retro", "HDR Boost", "Game Clarity", "Cinematic"),

    // Tema ativo da app (ver ui/theme/Theme.kt) -- este ecrã usava cores fixas e destoava
    // completamente do resto da aplicação.
    val selectedThemeIndex: Int = 0,
    val useAmoledBlack: Boolean = false,
    val useGlass: Boolean = false,
)

@HiltViewModel
class AppOverrideViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    // Fonte única de verdade partilhada com o overlay e o ForegroundAppWatcherService (ver
    // AppOverrideRepository) -- este ecrã e o overlay passam a ser "parceiros" na mesma linha do
    // Room em vez de dois caminhos de escrita desligados um do outro.
    private val overrideRepository: AppOverrideRepository,
    private val prefs: SharedPrefsRepo,
    performanceManager: PerformanceManager,
    deviceCapabilities: com.dfdx047.odinhub.tools.DeviceCapabilities,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Botões M1/M2 remapeáveis neste aparelho? (o cartão das macros só aparece se sim) */
    val hasBackButtons: StateFlow<Boolean> = deviceCapabilities.backButtons

    /** O limite térmico funciona neste aparelho? (não no MediaTek -- ver DeviceProfiles) */
    val thermalSupported: Boolean = com.dfdx047.odinhub.tools.DeviceRegistry.current.thermal.supported

    private val _uiState = MutableStateFlow(AppOverrideUiState())
    val uiState: StateFlow<AppOverrideUiState> = _uiState.asStateFlow()

    /** Tabelas reais de frequências do SoC (as mesmas do Settings e do overlay). */
    val clockTables: StateFlow<ClockTables> = performanceManager.clockTables

    /** Idioma da UI, o mesmo escolhido no ecrã principal (ver SharedPrefsRepo.isEnglish). */
    val isEnglish: StateFlow<Boolean> = prefs.isEnglishFlow

    /**
     * Passa a `true` no primeiro toque do utilizador neste ecrã. A partir daí, [observeOverride]
     * deixa de aplicar emissões do Room — caso contrário, uma escrita feita noutro sítio (por
     * exemplo o overlay, se o jogo estiver aberto por trás) apagaria por baixo as alterações que
     * o utilizador ainda não guardou.
     */
    private var userHasEdited = false

    init {
        val packageName = savedStateHandle.get<String>("packageName") ?: ""
        _uiState.update {
            it.copy(
                packageName = packageName,
                selectedThemeIndex = prefs.selectedThemeIndex,
                useAmoledBlack = prefs.useAmoledBlack,
                useGlass = prefs.useGlass,
                // Uma regra NOVA parte do perfil GLOBAL (como o overlay já fazia), não de valores
                // fixos: antes, criar uma regra só para mudar o limite térmico gravava também
                // "TDP 15 W" e passava a limitar o jogo sem o utilizador ter tocado no TDP.
                // Se o jogo já tiver regra, observeOverride() substitui isto pelos valores dela.
                limitMode = prefs.activeLimitMode,
                tdpWatts = prefs.tdpValue,
                tdpProfileId = prefs.tdpProfileId,
                perfClockMHz = prefs.cpuPerfClock,
                primeClockMHz = prefs.cpuPrimeClock,
                effClockMHz = prefs.cpuEffClock,
                gpuClockMHz = prefs.gpuClock,
                saturationOverride = prefs.saturationOverride,
                temperatureOverride = prefs.temperatureOverride,
            )
        }

        loadAppDetails(packageName)
        observeOverride(packageName)
    }

    private fun loadAppDetails(packageName: String) {
        val pm = context.packageManager
        val appName = try {
            val info = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
        _uiState.update { it.copy(appName = appName) }
    }

    /**
     * Observa a regra deste jogo no repositório -- a MESMA que o overlay escreve.
     *
     * BUG QUE ISTO CORRIGE: antes era um `overrideRepository.get(packageName)` único. Esse `get`
     * lê o cache em memória do repositório, que é um `stateIn(..., Eagerly, emptyMap())`
     * alimentado por um Flow do Room em IO. Num processo acabado de arrancar (utilizador abre a
     * app e vai direto a este ecrã), a primeira emissão do Room ainda não chegou: o `get`
     * devolvia null, o ecrã abria com os valores por omissão como se o jogo não tivesse regra
     * nenhuma, e um "Guardar" a seguir escrevia esses valores por cima da regra real criada no
     * overlay. Exatamente o oposto da parceria overlay <-> banco que se pretende.
     *
     * Agora coleta o Flow, por isso o ecrã preenche-se assim que o Room responde. Para não
     * atropelar o utilizador, as emissões deixam de ser aplicadas a partir do momento em que ele
     * mexe em alguma coisa (ver [userHasEdited]).
     */
    private fun observeOverride(packageName: String) {
        viewModelScope.launch {
            overrideRepository.overridesByPackage
                .map { it[packageName] }
                .distinctUntilChanged()
                .collect { entity ->
                    if (entity != null && !userHasEdited) applyEntity(entity)
                }
        }
    }

    private fun applyEntity(entity: AppOverrideEntity) {
        _uiState.update {
            it.copy(
                limitMode = entity.limitMode,
                // A sentinela abaixo de 1 W significa "Stock (sem limite)" -- ver TdpProfiles.
                tdpWatts = entity.tdpWatts?.takeUnless { w -> TdpProfiles.isStockWatts(w) } ?: it.tdpWatts,
                tdpProfileId = entity.tdpWatts?.let { w -> TdpProfiles.idForWatts(w) } ?: it.tdpProfileId,
                perfClockMHz = entity.perfClockKHz?.let { khz -> khz / 1000f } ?: it.perfClockMHz,
                primeClockMHz = entity.primeClockKHz?.let { khz -> khz / 1000f } ?: it.primeClockMHz,
                effClockMHz = entity.effClockKHz?.let { khz -> khz / 1000f } ?: it.effClockMHz,
                gpuClockMHz = entity.gpuClockHz?.let { hz -> hz / 1_000_000f } ?: it.gpuClockMHz,
                fanSettingsValue = entity.fanSettingsValue ?: it.fanSettingsValue,

                lsfgEnabled = entity.lsfgEnabled,
                lsfgMultiplier = "${entity.lsfgMultiplier}x",
                lsfgFramePacing = entity.lsfgFramePacing,
                lsfgPerformanceMode = entity.lsfgPerformanceMode,
                lsfgQuality = entity.lsfgQuality,

                sgsrEnabled = entity.sgsrEnabled,
                sgsrMode = entity.sgsrMode,
                sgsrSharpness = entity.sgsrSharpness,

                reshadeProfile = entity.reshadeProfile,
                saturationOverride = entity.saturationOverride,
                temperatureOverride = entity.temperatureOverride,
                displayOverride = entity.displayOverride,
                thermalMode = entity.thermalMode,
                m1MacroMode = MacroMode.of(entity.m1Macro),
                m1MacroSteps = ButtonMacros.decode(entity.m1Macro),
                m2MacroMode = MacroMode.of(entity.m2Macro),
                m2MacroSteps = ButtonMacros.decode(entity.m2Macro),

                isSaved = true
            )
        }
    }

    /** Marca que o utilizador já mexeu no ecrã -- ver [userHasEdited]/[observeOverride]. */
    private fun markEdited() {
        userHasEdited = true
    }

    fun updateLimitMode(mode: String) { markEdited(); _uiState.update { it.copy(limitMode = mode) } }
    /** Slider (1..25 W): o valor é livre e a seleção passa para "custom" (nenhum chip). */
    fun updateTdp(watts: Float) {
        markEdited()
        val clamped = watts.coerceIn(TdpProfiles.TDP_MIN_WATTS, TdpProfiles.TDP_MAX_WATTS)
        _uiState.update { it.copy(tdpWatts = clamped, tdpProfileId = TdpProfiles.ID_CUSTOM) }
    }

    /** Perfil fixo de TdpProfiles: watts exatos do perfil (Stock = sem limite). */
    fun selectTdpProfile(profileId: String) {
        val profile = TdpProfiles.byId(profileId) ?: return
        markEdited()
        _uiState.update { it.copy(tdpProfileId = profile.id, tdpWatts = profile.watts ?: it.tdpWatts) }
    }

    /** GPU é sempre passada separadamente -- ver comentário em ClockPresets.kt sobre porquê. */
    fun updateClocks(perfMHz: Float, primeMHz: Float, gpuMHz: Float, effMHz: Float = _uiState.value.effClockMHz) {
        _uiState.update { it.copy(effClockMHz = effMHz) }
        markEdited()
        _uiState.update { it.copy(perfClockMHz = perfMHz, primeClockMHz = primeMHz, gpuClockMHz = gpuMHz) }
    }

    fun updateFanMode(settingsValue: Int) { markEdited(); _uiState.update { it.copy(fanSettingsValue = settingsValue) } }

    fun updateLsfg(enabled: Boolean, multiplier: String, framePacing: Boolean, performanceMode: Boolean, quality: Float) {
        markEdited()
        _uiState.update { it.copy(lsfgEnabled = enabled, lsfgMultiplier = multiplier, lsfgFramePacing = framePacing, lsfgPerformanceMode = performanceMode, lsfgQuality = quality) }
    }

    fun updateSgsr(enabled: Boolean, mode: String, sharpness: Float) {
        markEdited()
        _uiState.update { it.copy(sgsrEnabled = enabled, sgsrMode = mode, sgsrSharpness = sharpness) }
    }

    fun updateDisplayColor(reshade: String, saturation: Float, temperature: Float) {
        markEdited()
        _uiState.update { it.copy(reshadeProfile = reshade, saturationOverride = saturation, temperatureOverride = temperature) }
    }

    fun updateDisplayOverride(enabled: Boolean) { markEdited(); _uiState.update { it.copy(displayOverride = enabled) } }
    fun updateSaturation(value: Float) { markEdited(); _uiState.update { it.copy(saturationOverride = value.coerceIn(0f, 2f)) } }
    fun updateTemperature(value: Float) { markEdited(); _uiState.update { it.copy(temperatureOverride = value.coerceIn(4000f, 9000f)) } }
    fun updateThermalMode(mode: String?) { markEdited(); _uiState.update { it.copy(thermalMode = mode) } }

    fun updateMacroMode(button: String, mode: MacroMode) {
        markEdited()
        _uiState.update { if (button == "m1") it.copy(m1MacroMode = mode) else it.copy(m2MacroMode = mode) }
        if (mode == MacroMode.CUSTOM) openMacroEditor(button)
    }
    fun openMacroEditor(button: String) { _uiState.update { it.copy(macroEditorFor = button) } }
    fun closeMacroEditor() { _uiState.update { it.copy(macroEditorFor = null) } }
    fun updateMacroSteps(button: String, steps: List<MacroStep>) {
        markEdited()
        _uiState.update {
            if (button == "m1") it.copy(m1MacroSteps = steps, macroEditorFor = null) else it.copy(m2MacroSteps = steps, macroEditorFor = null)
        }
    }

    fun saveOverride() {
        viewModelScope.launch {
            val current = _uiState.value
            val multInt = current.lsfgMultiplier.replace("x", "").toIntOrNull() ?: 2
            val isTdp = current.limitMode == "TDP"
            // Stock (sem limite) vai como a sentinela de TdpProfiles: `null` neste campo já
            // significa "usa o valor global" para o ForegroundAppWatcherService.
            val encodedTdp = TdpProfiles.encodeWatts(TdpProfiles.resolveWatts(current.tdpProfileId, current.tdpWatts))

            val entity = AppOverrideEntity(
                packageName = current.packageName,
                limitMode = current.limitMode,
                tdpWatts = encodedTdp,
                perfClockKHz = (current.perfClockMHz * 1000).toLong(),
                primeClockKHz = (current.primeClockMHz * 1000).toLong(),
                effClockKHz = (current.effClockMHz * 1000).toLong().takeIf { it > 0L },
                gpuClockHz = (current.gpuClockMHz * 1_000_000).toLong(),
                fanSettingsValue = current.fanSettingsValue,

                // Rótulos só para a subtitle da lista (AppOverrideMapper) -- só o modo ATIVO
                // ganha um rótulo descritivo, o outro fica "Stock" para não sugerir que os dois
                // modos estão em vigor ao mesmo tempo (não estão -- ver limitMode). Calculados
                // com o MESMO helper que o overlay usa (AppOverrideLabels), para que uma regra
                // criada em jogo e outra criada aqui descrevam os mesmos valores da mesma forma.
                tdpProfile = if (isTdp) AppOverrideLabels.tdpLabel(encodedTdp) else "Stock",
                clockProfile = if (!isTdp) AppOverrideLabels.clockLabel(current.perfClockMHz, current.primeClockMHz) else "Stock",
                fanProfile = AppOverrideLabels.fanLabel(current.fanSettingsValue),

                lsfgEnabled = current.lsfgEnabled,
                lsfgMultiplier = multInt,
                lsfgPerformanceMode = current.lsfgPerformanceMode,
                lsfgFramePacing = current.lsfgFramePacing,
                lsfgQuality = current.lsfgQuality,

                sgsrEnabled = current.sgsrEnabled,
                sgsrMode = current.sgsrMode,
                sgsrSharpness = current.sgsrSharpness,

                reshadeProfile = current.reshadeProfile,
                saturationOverride = current.saturationOverride,
                temperatureOverride = current.temperatureOverride,
                displayOverride = current.displayOverride,
                thermalMode = current.thermalMode,
                m1Macro = MacroMode.encode(current.m1MacroMode, current.m1MacroSteps),
                m2Macro = MacroMode.encode(current.m2MacroMode, current.m2MacroSteps),
            )
            overrideRepository.upsert(entity)
            _uiState.update { it.copy(isSaved = true) }
        }
    }

    fun deleteOverride() {
        viewModelScope.launch {
            overrideRepository.delete(_uiState.value.packageName)
            // Repõe os valores por omissão mas preserva a identidade do app e o tema -- criar um
            // AppOverrideUiState() limpo apagava também o tema escolhido e o ecrã saltava para as
            // cores por omissão no instante em que a regra era removida.
            _uiState.update {
                AppOverrideUiState(
                    packageName = it.packageName,
                    appName = it.appName,
                    selectedThemeIndex = it.selectedThemeIndex,
                    useAmoledBlack = it.useAmoledBlack,
                    useGlass = it.useGlass,
                )
            }
        }
    }
}
