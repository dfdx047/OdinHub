package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.hardware.ClockTables

/**
 * Odin Hub é exclusivo do **AYN Odin 3** (Snapdragon 8 Elite, SM8750 / CQ8725S, "sun").
 *
 * Valores lidos no próprio aparelho (firmware Odin3_BV1.0.0.188): policy0 = cpu0-5 (Oryon M,
 * 384..3532 MHz), policy6 = cpu6-7 (Oryon L, 1017..4320 MHz), Adreno 830 160..1100 MHz. Sem
 * `msm_performance` (o firmware não impõe pisos de CPU por módulo).
 */
enum class SocFamily(
    val label: String,
    /** Núcleos do cluster "Perf" (a política logo abaixo da mais rápida), para os títulos da UI. */
    val perfCores: String,
    /** Núcleos do cluster "Prime" (a política mais rápida). */
    val primeCores: String,
    val gpuName: String,
    /** Núcleos do cluster de eficiência (vazio = o SoC não tem um terceiro cluster). */
    val effCores: String = "",
) {
    /** Snapdragon 8 Elite (SM8750 / CQ8725S, "sun") -- Odin 3. */
    SM8750("Snapdragon 8 Elite", "6× Oryon M", "2× Oryon L", "Adreno 830"),

    /** Outro aparelho: a app corre, mas só com as tabelas vivas do kernel (não suportado). */
    UNKNOWN("SoC", "", "", "GPU"),
}

/** Como se limita a GPU: Adreno, `/sys/class/kgsl/kgsl-3d0/max_gpuclk` (Hz). */
enum class GpuBackend { KGSL }

/**
 * Watts dos perfis fixos de TDP e o teto do slider. Os watts são o consumo do APARELHO inteiro
 * medido na bateria (SoC + ecrã + resto), que é o que o AutoTDP consegue medir.
 */
data class PowerEnvelope(
    val powerSave: Float,
    val balanced: Float,
    val tripleA: Float,
    val maxWatts: Float,
)

/**
 * Que trip points passivos o limite térmico pode mexer: só trips cujo valor de fábrica é
 * >= [minOriginalMilliC]. No Odin 3 todos os trips passivos abaixo de 110 °C (testado).
 */
data class ThermalPolicy(
    val supported: Boolean,
    val minOriginalMilliC: Int,
)

/** O aparelho: tabelas, envelope de TDP e política térmica. */
data class DeviceProfile(
    val id: String,
    /** Nome mostrado na UI ("AYN Odin 3"). */
    val name: String,
    val soc: SocFamily,
    /** `true` = Odin 3; `false` = outro aparelho (não suportado, só tabelas do kernel). */
    val official: Boolean,
    val power: PowerEnvelope,
    val fallbackTables: ClockTables,
    val gpuBackend: GpuBackend,
    val thermal: ThermalPolicy,
    /**
     * Botões traseiros M1/M2 remapeáveis pelo firmware (`remap_custom_to_m1_value`). Só um
     * palpite inicial: a app confirma em tempo de execução lendo a chave (ver DeviceCapabilities).
     */
    val backButtons: Boolean,
    /**
     * Máximo de FÁBRICA da GPU (MHz). Frequências que o kernel exponha acima disto são marcadas
     * "OC" na UI e nunca são usadas pelo AutoTDP (ver GpuOc).
     */
    val stockGpuMaxMHz: Int? = null,
)

/** Identificação crua do aparelho, lida de `android.os.Build` e das system properties. */
data class DeviceIdentity(
    val manufacturer: String = "",
    val model: String = "",
    /** `Build.SOC_MODEL` / `ro.soc.model` (ex: "CQ8725S"). */
    val socModel: String = "",
    /** `ro.board.platform` (ex: "sun"). */
    val platform: String = "",
    /** `Build.HARDWARE` (ex: "qcom"). */
    val hardware: String = "",
    /** `ro.vendor.retro.name` do firmware AYN. */
    val retroName: String = "",
) {
    companion object {
        val EMPTY = DeviceIdentity()
    }
}

/**
 * Tabelas estáticas de reserva. Só são usadas quando o kernel não deixa ler as tabelas vivas
 * (`scaling_available_frequencies` / `gpu_available_frequencies`); a escrita encaixa sempre no
 * OPP real mais próximo por baixo.
 */
object SocTables {
    private fun kHz(vararg values: Long): List<Int> = values.map { (it / 1000L).toInt() }.distinct().sorted()

    /** 8 Elite: policy0 (6x Oryon M, 3532 MHz) e policy6 (2x Oryon L, 4320 MHz); Adreno 830 1.1 GHz. */
    val SM8750 = ClockTables(
        perfMHz = kHz(384000, 556800, 748800, 960000, 1152000, 1363200, 1555200, 1785600, 1996800, 2227200, 2400000, 2745600, 2918400, 3072000, 3321600, 3532800),
        primeMHz = kHz(1017600, 1209600, 1401600, 1689600, 1958400, 2246400, 2438400, 2649600, 2841600, 3072000, 3283200, 3513600, 3801600, 4089600, 4204800, 4320000),
        gpuMHz = listOf(160, 222, 342, 389, 443, 525, 607, 660, 734, 832, 900, 967, 1050, 1100),
        source = ClockTables.SOURCE_FALLBACK,
    )

    fun forSoc(@Suppress("UNUSED_PARAMETER") soc: SocFamily): ClockTables = SM8750

    /** `count` valores inteiros igualmente espaçados entre `minMHz` e `maxMHz`, inclusive. */
    fun evenSteps(minMHz: Int, maxMHz: Int, count: Int = 10): List<Int> {
        if (count <= 1 || maxMHz <= minMHz) return listOf(maxMHz)
        return (0 until count).map { i ->
            minMHz + Math.round(i.toDouble() * (maxMHz - minMHz) / (count - 1)).toInt()
        }.distinct().sorted()
    }
}

/**
 * O Odin 3 e a deteção.
 *
 * ENVELOPE DE POTÊNCIA: consumo do aparelho inteiro na bateria. Poupança = emulação 2D/PS2 leve
 * com o máximo de bateria; Equilibrado = Switch/PS2 pesado; AAA = jogos Android pesados / Switch
 * exigente; o teto é o máximo que o chip + arrefecimento sustentam sem throttling imediato.
 */
object DeviceProfiles {

    val POWER_SM8750 = PowerEnvelope(7f, 10f, 15f, 25f)

    /** Odin 3: o comportamento já testado (todos os trips passivos abaixo de 110 °C). */
    private val THERMAL_SM8750 = ThermalPolicy(supported = true, minOriginalMilliC = 0)

    /** Outro aparelho: só trips a partir de 90 °C -- nunca os da ventoinha. */
    private val THERMAL_UNKNOWN = ThermalPolicy(supported = true, minOriginalMilliC = 90_000)

    /** Adreno 830 do Odin 3: 1100 MHz (tabela lida no aparelho, fw 1.0.0.188: 160..1100). */
    const val ODIN3_STOCK_GPU_MAX_MHZ = 1100

    val ODIN3 = DeviceProfile(
        id = "odin3",
        name = "AYN Odin 3",
        soc = SocFamily.SM8750,
        official = true,
        power = POWER_SM8750,
        fallbackTables = SocTables.SM8750,
        gpuBackend = GpuBackend.KGSL,
        thermal = THERMAL_SM8750,
        backButtons = true,
        stockGpuMaxMHz = ODIN3_STOCK_GPU_MAX_MHZ,
    )

    val all: List<DeviceProfile> = listOf(ODIN3)

    /** Aparelho que não é um Odin 3: corre só com as tabelas vivas do kernel, sem garantias. */
    fun generic(identity: DeviceIdentity, soc: SocFamily): DeviceProfile {
        val name = listOf(identity.manufacturer, identity.model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" }
        return ODIN3.copy(
            id = "generic",
            name = name,
            soc = soc,
            official = false,
            thermal = if (soc == SocFamily.SM8750) THERMAL_SM8750 else THERMAL_UNKNOWN,
            backButtons = false,
            stockGpuMaxMHz = if (soc == SocFamily.SM8750) ODIN3_STOCK_GPU_MAX_MHZ else null,
        )
    }

    /** SoC a partir de `ro.soc.model`, `ro.board.platform` e `Build.HARDWARE`. */
    fun socFrom(identity: DeviceIdentity): SocFamily {
        val soc = identity.socModel.uppercase()
        val platform = identity.platform.lowercase().trim()
        return when {
            soc.contains("SM8750") || soc.contains("CQ8725") || platform == "sun" -> SocFamily.SM8750
            else -> SocFamily.UNKNOWN
        }
    }

    /**
     * Odin 3 = `Build.MODEL` "Odin3" (confirmado no aparelho), ou um 8 Elite com firmware AYN e
     * um MODEL ainda desconhecido (futuras revisões). Tudo o resto é genérico (não suportado).
     */
    fun detect(identity: DeviceIdentity): DeviceProfile {
        val soc = socFrom(identity)
        val model = identity.model.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (model.contains("odin3")) return ODIN3
        if (soc == SocFamily.SM8750 && identity.manufacturer.contains("ayn", ignoreCase = true)) return ODIN3
        return generic(identity, soc)
    }
}
