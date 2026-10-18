package com.dfdx047.odinhub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "appoverride")
data class AppOverrideEntity(
    @PrimaryKey
    val packageName: String,

    // Qual dos dois modos manda neste jogo: "TDP" ou "CLOCK". Mutuamente exclusivos --
    // nunca aplicar os dois ao mesmo tempo (ver ForegroundAppWatcherService/QuickAccessContent).
    val limitMode: String = "TDP",

    // Valores REAIS aplicados ao hardware. tdpProfile/clockProfile/fanProfile (abaixo)
    // continuam a existir só como o RÓTULO do preset escolhido (para mostrar na UI); antes
    // desta migração eram os ÚNICOS campos e nunca eram resolvidos para números em lado
    // nenhum -- o motor de hardware lia valores completamente diferentes do SharedPrefs.
    val tdpWatts: Float? = null,
    val perfClockKHz: Long? = null,
    val primeClockKHz: Long? = null,
    val gpuClockHz: Long? = null,
    val fanSettingsValue: Int? = null,

    // Rótulos do preset selecionado (apenas para exibição/subtítulo)
    val tdpProfile: String?,
    val clockProfile: String?,
    val fanProfile: String?,

    // Lossless Scaling (Frame Generation)
    val lsfgEnabled: Boolean = false,
    val lsfgMultiplier: Int = 2,           // 2x, 3x, 4x
    val lsfgPerformanceMode: Boolean = false,
    val lsfgFramePacing: Boolean = true,
    val lsfgQuality: Float = 1.0f,          // 0.5f a 1.0f (50% a 100%)

    // Snapdragon Super Resolution (SGSR)
    val sgsrEnabled: Boolean = false,
    val sgsrMode: String = "Quality",      // Quality (0.77x), Balanced (0.66x), Performance (0.5x)
    val sgsrSharpness: Float = 0.5f,        // 0.0f a 1.0f

    // ReShade & Pós-processamento de Cor
    val reshadeProfile: String = "Nenhum", // Vibrante, Cinema, Retrô, HDR Boost, etc.
    val saturationOverride: Float = 1.0f,  // 0.0f a 2.0f
    val temperatureOverride: Float = 6500f, // 4000K a 9000K

    // v6: calibração de cor por jogo. Só vale quando `displayOverride` = true; caso contrário o
    // jogo usa a saturação/temperatura globais (aba Display). Os dois campos acima já existiam
    // mas nunca eram aplicados ao ecrã (só iam para a ponte do motor gráfico, que está desligada).
    val displayOverride: Boolean = false,

    // v6: limite térmico por jogo (ver ThermalLimitModes). `null` = segue o global.
    val thermalMode: String? = null,

    // v7: macros de M1/M2 por jogo (ver MacroMode): null = segue o global, "" = desligada
    // (mapeamento nativo), passos codificados = macro própria deste jogo.
    val m1Macro: String? = null,
    val m2Macro: String? = null,

    /**
     * v8: teto do cluster de EFICIÊNCIA (3x A510 no 8 Gen 2, 4x A55 no 865), em kHz. `null` =
     * segue o global. Em SoCs só com dois clusters (8 Elite, MediaTek) é ignorado.
     */
    val effClockKHz: Long? = null,
)