package com.dfdx047.odinhub.main

import com.dfdx047.odinhub.data.CustomProfile
import com.dfdx047.odinhub.models.HomeGestureConfig
import com.dfdx047.odinhub.models.MacroStep
import com.dfdx047.odinhub.models.TdpProfiles
import com.dfdx047.odinhub.models.DeviceProfile
import com.dfdx047.odinhub.tools.DeviceRegistry
import com.dfdx047.odinhub.tools.hardware.AutoTdpTelemetry
import com.dfdx047.odinhub.tools.hardware.ClockTables
import com.dfdx047.odinhub.tools.hardware.ThermalStatus

data class MainUiModel(
    /** Aparelho detetado (tabelas, zonas térmicas e envelope de TDP próprios). */
    val device: DeviceProfile = DeviceRegistry.current,
    val deviceVersion: String = "",
    /** O firmware tem botões traseiros M1/M2 remapeáveis? (confirmado em tempo de execução). */
    val hasBackButtons: Boolean = true,
    val showPServerNotAvailableDialog: Boolean = false,
    /** Estado do AutoTDP (consumo medido, pausa) para o cartão de TDP. */
    val autoTdp: AutoTdpTelemetry = AutoTdpTelemetry(),

    val currentSaturation: Float = 1.0f,
    val currentTemperature: Float = 6500f,

    val showRemapButtonDialog: Boolean = false,
    val showOverlayShortcutDialog: Boolean = false,
    val currentButtonSetting: String = "",
    /** Código atual do botão no diálogo de remapeamento; -1 = ainda a ler. */
    val currentButtonKeyCode: Int = -1,
    val overlayShortcutKeyCode: Int = 0,

    /** Gestos do botão Home (substituem o antigo "Toque único no Home", que já existe nas definições da AYN). */
    val homeGestures: HomeGestureConfig = HomeGestureConfig(),
    /** Macros dos botões traseiros: estado de cada um e qual está aberto no editor ("m1"/"m2"). */
    val m1MacroEnabled: Boolean = false,
    val m1MacroSteps: List<MacroStep> = emptyList(),
    val m2MacroEnabled: Boolean = false,
    val m2MacroSteps: List<MacroStep> = emptyList(),
    val macroEditorFor: String? = null,

    val selectedThemeIndex: Int = 1,
    val useAmoledBlack: Boolean = false,
    val useGlass: Boolean = false,

    val tdpValue: Float = TdpProfiles.envelope.tripleA,
    /** Perfil de TDP selecionado -- ver TdpProfiles ("custom" = nenhum chip destacado). */
    val tdpProfileId: String = TdpProfiles.ID_TRIPLE_A,
    /** Tabelas reais de frequências do SoC (MHz), para os chips de clocks. */
    val clockTables: ClockTables = ClockTables.EMPTY,
    /** Estado do limite térmico (via PServerBinder) -- ver ThermalManager. */
    val thermalStatus: ThermalStatus = ThermalStatus(),
    val cpuPerfClock: Float = 0f,
    val cpuPrimeClock: Float = 0f,
    /** Cluster de eficiência (MHz); 0 = SoC sem esse cluster. */
    val cpuEffClock: Float = 0f,
    val gpuClock: Float = 0f,
    val activeLimitMode: String = "TDP",
    val fanMode: Int = 0,
    val customProfiles: List<CustomProfile> = emptyList(),

    val globalLsfgEnabled: Boolean = false,
    val globalSgsrEnabled: Boolean = false,
    val lsfgMultiplier: String = "2x",
    val lsfgFramePacing: Boolean = true,
    val lsfgPerformanceMode: Boolean = false,
    val sgsrMode: String = "Quality",
    val sgsrSharpness: Float = 0.5f,
    val reshadeProfile: String = "Native",
    val showFpsOverlay: Boolean = false,
    val isDllImported: Boolean = false,

    val overlayEnabled: Boolean = false,
    val overlayHandleOpacity: Float = 0.5f,
    val overlayHandleWidth: Int = 22,

    val bgmEnabled: Boolean = true,
    val bgmVolume: Float = 0.5f,
    val sfxEnabled: Boolean = true,
    val sfxVolume: Float = 0.8f
)