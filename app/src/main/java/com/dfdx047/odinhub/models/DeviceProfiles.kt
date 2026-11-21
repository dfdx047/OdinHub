package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.hardware.ClockTables

/**
 * Odin Hub corre em dois aparelhos AYN, cada um com o seu perfil:
 *
 *  - **Odin 3** (Snapdragon 8 Elite, SM8750 / CQ8725S, "sun"). Lido no aparelho (fw
 *    Odin3_BV1.0.0.188): policy0 = cpu0-5 (Oryon M, 384..3532 MHz), policy6 = cpu6-7 (Oryon L,
 *    1017..4320 MHz), Adreno 830 160..1100 MHz.
 *  - **Odin 2 Mini** (Snapdragon 8 Gen 2, QCS8550, "kalama"). Lido no aparelho (fw
 *    Odin2Mini_V1.0.0.117, kernel 5.15): policy0 = cpu0-2 (3x A510, 307..2016 MHz), policy3 =
 *    cpu3-6 (2x A715 + 2x A710, 499..2803 MHz), policy7 = cpu7 (X3, 595..3187 MHz), Adreno 740
 *    220..692 MHz (tabela IoT da AYN). O `pservice` da AYN reescreve tetos E pisos a cada troca de
 *    `performance_mode` (até o modo Normal põe pisos de 902/1651/1843 MHz e a GPU a 401 MHz) --
 *    ver [DeviceProfile.guardFirmwareFloors].
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

    /** Snapdragon 8 Gen 2 (QCS8550 / SM8550, "kalama") -- Odin 2 Mini. */
    KALAMA("Snapdragon 8 Gen 2", "2× A715 + 2× A710", "1× X3", "Adreno 740", "3× A510"),

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
    /**
     * O firmware reescreve os PISOS dos clocks por conta própria (o `pservice` da AYN no Odin 2
     * Mini: pisos de CPU e `devfreq/min_freq` da GPU a cada troca de modo de desempenho, e o
     * gameassistant por jogo). Com isto ligado, fora do Stock a app desce os pisos ao fundo da
     * tabela e TRANCA-OS (0444, o pservice não tem dac_override e não consegue escrever por cima),
     * tal como já fazia com os tetos. Sem isto um perfil "económico" ficava preso a 1.8 GHz no X3.
     */
    val guardFirmwareFloors: Boolean = false,
    /** Tema exclusivo do aparelho (índice em AvailableThemes) usado numa instalação nova. */
    val defaultThemeIndex: Int = 1,
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

    /**
     * 8 Gen 2 do Odin 2 Mini (fw 1.0.0.117): policy0 3x A510 até 2016, policy3 (2x A715 + 2x A710)
     * até 2803, policy7 X3 até 3187 MHz. Adreno 740 com a tabela IoT da AYN: 220..692 MHz (o
     * 124.8 MHz existe na tabela mas o `min_pwrlevel` do kgsl não o deixa usar).
     */
    val KALAMA = ClockTables(
        perfMHz = kHz(499200, 614400, 729600, 844800, 940800, 1056000, 1171200, 1286400, 1401600, 1536000, 1651200, 1785600, 1920000, 2054400, 2188800, 2323200, 2457600, 2592000, 2707200, 2803200),
        primeMHz = kHz(595200, 729600, 864000, 998400, 1132800, 1248000, 1363200, 1478400, 1593600, 1708800, 1843200, 1977600, 2092800, 2227200, 2342400, 2476800, 2592000, 2726400, 2841600, 2956800, 3187200),
        gpuMHz = listOf(220, 295, 348, 401, 475, 550, 615, 692),
        source = ClockTables.SOURCE_FALLBACK,
        effMHz = kHz(307200, 441600, 556800, 672000, 787200, 902400, 1017600, 1113600, 1228800, 1344000, 1459200, 1555200, 1670400, 1785600, 1900800, 2016000),
    )

    fun forSoc(soc: SocFamily): ClockTables = when (soc) {
        SocFamily.KALAMA -> KALAMA
        else -> SM8750
    }

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

    /** Odin 2 Mini: consumo do aparelho inteiro (ecrã de 6", 8 Gen 2 com ventoinha pequena). */
    val POWER_ODIN2_MINI = PowerEnvelope(4.5f, 6.5f, 9f, 12f)

    /** 8 Gen 2: só trips passivos a partir de 90 °C (os de baixo são da ventoinha / pele). */
    private val THERMAL_KALAMA = ThermalPolicy(supported = true, minOriginalMilliC = 90_000)

    /** Adreno 740 do Odin 2 Mini: 692 MHz (tabela lida no aparelho, fw 1.0.0.117). */
    const val ODIN2_MINI_STOCK_GPU_MAX_MHZ = 692

    val ODIN2_MINI = DeviceProfile(
        id = "odin2mini",
        name = "AYN Odin 2 Mini",
        soc = SocFamily.KALAMA,
        official = true,
        power = POWER_ODIN2_MINI,
        fallbackTables = SocTables.KALAMA,
        gpuBackend = GpuBackend.KGSL,
        thermal = THERMAL_KALAMA,
        // Sem M1/M2 no Odin 2 Mini (o firmware nem tem a chave remap_custom_to_m1_value).
        backButtons = false,
        stockGpuMaxMHz = ODIN2_MINI_STOCK_GPU_MAX_MHZ,
        guardFirmwareFloors = true,
        defaultThemeIndex = com.dfdx047.odinhub.ui.theme.VITA_THEME_INDEX,
    )

    val all: List<DeviceProfile> = listOf(ODIN3, ODIN2_MINI)

    /** Aparelho que não é um Odin 3: corre só com as tabelas vivas do kernel, sem garantias. */
    fun generic(identity: DeviceIdentity, soc: SocFamily): DeviceProfile {
        val name = listOf(identity.manufacturer, identity.model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" }
        val base = if (soc == SocFamily.KALAMA) ODIN2_MINI else ODIN3
        return base.copy(
            id = "generic",
            name = name,
            soc = soc,
            official = false,
            thermal = when (soc) {
                SocFamily.SM8750 -> THERMAL_SM8750
                SocFamily.KALAMA -> THERMAL_KALAMA
                else -> THERMAL_UNKNOWN
            },
            backButtons = false,
            stockGpuMaxMHz = when (soc) {
                SocFamily.SM8750 -> ODIN3_STOCK_GPU_MAX_MHZ
                SocFamily.KALAMA -> null
                else -> null
            },
            guardFirmwareFloors = false,
            defaultThemeIndex = 1,
        )
    }

    /** SoC a partir de `ro.soc.model`, `ro.board.platform` e `Build.HARDWARE`. */
    fun socFrom(identity: DeviceIdentity): SocFamily {
        val soc = identity.socModel.uppercase()
        val platform = identity.platform.lowercase().trim()
        return when {
            soc.contains("SM8750") || soc.contains("CQ8725") || platform == "sun" -> SocFamily.SM8750
            soc.contains("8550") || platform == "kalama" -> SocFamily.KALAMA
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
        // Build.MODEL "Odin2 Mini" (lido no aparelho). Só o Mini: o Odin 2 / Portal têm M1/M2 e
        // outro envelope -- ficam genéricos até terem perfil próprio.
        if (model.contains("odin2mini")) return ODIN2_MINI
        if (soc == SocFamily.SM8750 && identity.manufacturer.contains("ayn", ignoreCase = true)) return ODIN3
        return generic(identity, soc)
    }
}
