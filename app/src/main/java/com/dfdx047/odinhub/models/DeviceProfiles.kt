package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.hardware.ClockTables

/**
 * Família de SoC. Decide as tabelas de reserva, o motor da GPU e a política térmica.
 *
 * Fontes (ver a secção "Supported devices" do README): dumps de `scaling_available_frequencies`, os `*-gpu-pwrlevels.dtsi`
 * da árvore de kernel AYN/Moorechip do LineageOS e o Geekbench de cada aparelho.
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

    /** Snapdragon 8 Gen 2 (QCS8550 / SM8550, "kalama") -- Odin 2, Thor, RP6, RP Nova. */
    KALAMA("Snapdragon 8 Gen 2", "2× A715 + 2× A710", "1× X3", "Adreno 740", "3× A510"),

    /** Snapdragon 865 (SM8250, "kona") -- RP5, RP Mini, Thor Lite. */
    KONA("Snapdragon 865", "3× A77", "1× A77", "Adreno 650", "4× A55"),

    /** Dimensity 1100 / Kompanio 1300T (MT8797 / MT6891) -- Retroid Pocket 4 Pro. */
    MTK_D1100("Dimensity 1100", "4× A55", "4× A78", "Mali-G77"),

    /** Dimensity 900 (MT6877 / MT8791) -- Retroid Pocket 4. */
    MTK_D900("Dimensity 900", "6× A55", "2× A78", "Mali-G68"),

    UNKNOWN("SoC", "", "", "GPU"),
    ;

    val isMediaTek: Boolean get() = this == MTK_D1100 || this == MTK_D900
}

/** Como se limita a GPU. */
enum class GpuBackend {
    /** Adreno: `/sys/class/kgsl/kgsl-3d0/max_gpuclk` (Hz). */
    KGSL,

    /** Mali em MediaTek (kernel 4.19): índice de OPP em `/sys/kernel/ged/hal/custom_upbound_gpu_freq`. */
    MTK_GED,
}

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
 * Que trip points passivos o limite térmico pode mexer.
 *
 * [minOriginalMilliC]: só trips cujo valor de fábrica é >= isto. No 8 Gen 2 da Moorechip há trips
 * PASSIVOS de 50-85 °C que comandam a VENTOINHA (pwm-fan) e não o throttling -- mexer neles faria a
 * ventoinha arrancar mais tarde. O throttling de verdade começa nos 95 °C (`tj_cfg` da GPU). No
 * 865 o primeiro degrau de throttling da CPU é aos 75 °C (`cpu-1-N-step`).
 */
data class ThermalPolicy(
    val supported: Boolean,
    val minOriginalMilliC: Int,
)

/**
 * Tudo o que muda de aparelho para aparelho. O resto da app (PServerBinder, overlay, regras por
 * jogo, macros, gestos) é igual em toda a família AYN/Retroid, que partilha o mesmo ODM
 * (Moorechip) e o mesmo firmware de base.
 */
data class DeviceProfile(
    val id: String,
    /** Nome mostrado na UI ("AYN Odin 2", "Retroid Pocket 5"...). */
    val name: String,
    val soc: SocFamily,
    /** `true` = aparelho com perfil próprio; `false` = genérico (só pelo SoC ou desconhecido). */
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
)

/** Identificação crua do aparelho, lida de `android.os.Build` e das system properties. */
data class DeviceIdentity(
    val manufacturer: String = "",
    val model: String = "",
    /** `Build.SOC_MODEL` / `ro.soc.model` (ex: "QCS8550", "SM8250", "MT8797"). */
    val socModel: String = "",
    /** `ro.board.platform` (ex: "kalama", "kona", "sun", "mt6893"). */
    val platform: String = "",
    /** `Build.HARDWARE` (ex: "qcom", "mt6893"). */
    val hardware: String = "",
    /** `ro.vendor.retro.name` do firmware AYN/Retroid (ex: "Q9" no Odin 2, "4.0P" no RP4 Pro). */
    val retroName: String = "",
) {
    companion object {
        val EMPTY = DeviceIdentity()
    }
}

/**
 * Tabelas estáticas de reserva por SoC. Só são usadas quando o kernel não deixa ler as tabelas
 * vivas (`scaling_available_frequencies` / `gpu_available_frequencies`); a escrita encaixa sempre
 * no OPP real mais próximo por baixo.
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
     * 8 Gen 2 (QCS8550): policy0 3x A510 até 2016, policy3 (2x A715 + 2x A710) até 2803,
     * policy7 X3 até 3187 MHz. Adreno 740 com a tabela IoT da Moorechip: máximo 680 MHz (não 719).
     */
    val KALAMA = ClockTables(
        perfMHz = kHz(499200, 614400, 729600, 844800, 940800, 1056000, 1171200, 1286400, 1401600, 1536000, 1651200, 1785600, 1920000, 2054400, 2188800, 2323200, 2457600, 2592000, 2707200, 2803200),
        primeMHz = kHz(595200, 729600, 864000, 998400, 1132800, 1248000, 1363200, 1478400, 1593600, 1708800, 1843200, 1977600, 2092800, 2227200, 2342400, 2476800, 2592000, 2726400, 2841600, 2956800, 3187200),
        gpuMHz = listOf(124, 220, 295, 348, 401, 475, 550, 615, 680),
        source = ClockTables.SOURCE_FALLBACK,
        // policy0: 3x Cortex-A510 até 2016 MHz.
        effMHz = kHz(307200, 441600, 556800, 672000, 787200, 902400, 1017600, 1113600, 1228800, 1344000, 1459200, 1555200, 1670400, 1785600, 1900800, 2016000),
    )

    /**
     * 865 (SM8250): policy0 4x A55 até 1804, policy4 3x A77 até 2419, policy7 A77 até 2841 MHz.
     * Adreno 650 até 670 MHz (bin dos Retroid). O 3091 MHz da tabela mainline é só do 870.
     */
    val KONA = ClockTables(
        perfMHz = kHz(710400, 825600, 940800, 1056000, 1171200, 1286400, 1382400, 1478400, 1574400, 1670400, 1766400, 1862400, 1958400, 2054400, 2150400, 2246400, 2342400, 2419200),
        primeMHz = kHz(844800, 960000, 1075200, 1190400, 1305600, 1401600, 1516800, 1632000, 1747200, 1862400, 1977600, 2073600, 2169600, 2265600, 2361600, 2457600, 2553600, 2649600, 2745600, 2841600),
        gpuMHz = listOf(305, 400, 441, 490, 525, 587, 670),
        source = ClockTables.SOURCE_FALLBACK,
        // policy0: 4x Cortex-A55 até 1804 MHz.
        effMHz = kHz(300000, 403200, 518400, 614400, 691200, 787200, 883200, 979200, 1075200, 1171200, 1248000, 1344000, 1420800, 1516800, 1612800, 1708800, 1804800),
    )

    /**
     * Dimensity 1100 (RP4 Pro): 4x A55 até 2000 + 4x A78 até 2600 MHz. Não há dump público dos
     * OPPs intermédios -- isto é só uma escada aproximada (a escrita encaixa na tabela real).
     * Só dois clusters: "Perf" = A55, "Prime" = A78. GPU: a tabela vem do driver em tempo real.
     */
    val MTK_D1100 = ClockTables(
        perfMHz = evenSteps(500, 2000),
        primeMHz = evenSteps(650, 2600),
        gpuMHz = emptyList(),
        source = ClockTables.SOURCE_FALLBACK,
    )

    /** Dimensity 900 (RP4): 6x A55 até 2000 + 2x A78 até 2400 MHz. */
    val MTK_D900 = ClockTables(
        perfMHz = evenSteps(500, 2000),
        primeMHz = evenSteps(650, 2400),
        gpuMHz = emptyList(),
        source = ClockTables.SOURCE_FALLBACK,
    )

    fun forSoc(soc: SocFamily): ClockTables = when (soc) {
        SocFamily.SM8750 -> SM8750
        SocFamily.KALAMA -> KALAMA
        SocFamily.KONA -> KONA
        SocFamily.MTK_D1100 -> MTK_D1100
        SocFamily.MTK_D900 -> MTK_D900
        // Desconhecido: as tabelas vivas do kernel mandam; isto só serve se nada for legível.
        SocFamily.UNKNOWN -> SM8750
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
 * Catálogo dos aparelhos suportados e a deteção.
 *
 * ENVELOPES DE POTÊNCIA (v0.9): consumo do aparelho inteiro na bateria, a partir de medições
 * publicadas de cada SoC em jogo e do tamanho/arrefecimento do chassis. Degraus bem separados
 * para que Poupança / Equilibrado / AAA se notem de facto (antes, no Odin 3, 11 vs 12.5 W quase
 * não se distinguiam). Afinar aqui depois de medir em cada aparelho.
 */
object DeviceProfiles {

    // ---- Envelopes ----
    // Poupança / Equilibrado / AAA / teto do slider. Degraus bem separados, pensados para o que
    // cada chip realmente faz: Poupança = emulação 2D/PS2 leve com o máximo de bateria;
    // Equilibrado = Switch/PS2 pesado; AAA = jogos Android pesados / Switch exigente; o teto é o
    // máximo que o chip + arrefecimento sustentam sem throttling térmico imediato.
    val POWER_SM8750 = PowerEnvelope(7f, 10f, 15f, 25f) // Odin 3
    private val POWER_KALAMA_LARGE = PowerEnvelope(5f, 7f, 10f, 13f) // Odin 2 / Portal / Thor
    private val POWER_KALAMA_SMALL = PowerEnvelope(4.5f, 6.5f, 9f, 12f) // Odin 2 Mini / RP6 / RP Nova
    private val POWER_KONA = PowerEnvelope(3.5f, 4.5f, 6f, 8f) // RP5 / Thor Lite
    private val POWER_KONA_SMALL = PowerEnvelope(3f, 4f, 5.5f, 7f) // RP Mini / Mini V2
    private val POWER_D1100 = PowerEnvelope(3.5f, 4.5f, 6f, 8f) // RP4 Pro
    private val POWER_D900 = PowerEnvelope(3f, 4f, 5f, 7f) // RP4

    // ---- Políticas térmicas ----
    /** Odin 3: o comportamento já testado (todos os trips passivos abaixo de 110 °C). */
    private val THERMAL_SM8750 = ThermalPolicy(supported = true, minOriginalMilliC = 0)
    private val THERMAL_KALAMA = ThermalPolicy(supported = true, minOriginalMilliC = 90_000)
    private val THERMAL_KONA = ThermalPolicy(supported = true, minOriginalMilliC = 70_000)

    /** MediaTek: o throttling é feito pelo `thermal_manager` em userspace, não pelos trips. */
    private val THERMAL_NONE = ThermalPolicy(supported = false, minOriginalMilliC = Int.MAX_VALUE)

    private fun profile(
        id: String,
        name: String,
        soc: SocFamily,
        power: PowerEnvelope,
        backButtons: Boolean,
        official: Boolean = true,
    ) = DeviceProfile(
        id = id,
        name = name,
        soc = soc,
        official = official,
        power = power,
        fallbackTables = SocTables.forSoc(soc),
        gpuBackend = if (soc.isMediaTek) GpuBackend.MTK_GED else GpuBackend.KGSL,
        thermal = thermalFor(soc),
        backButtons = backButtons,
    )

    private fun thermalFor(soc: SocFamily): ThermalPolicy = when (soc) {
        SocFamily.SM8750 -> THERMAL_SM8750
        SocFamily.KALAMA -> THERMAL_KALAMA
        SocFamily.KONA -> THERMAL_KONA
        SocFamily.MTK_D1100, SocFamily.MTK_D900 -> THERMAL_NONE
        // Desconhecido: só trips a partir de 90 °C -- nunca os da ventoinha.
        SocFamily.UNKNOWN -> ThermalPolicy(supported = true, minOriginalMilliC = 90_000)
    }

    private fun powerFor(soc: SocFamily): PowerEnvelope = when (soc) {
        SocFamily.SM8750, SocFamily.UNKNOWN -> POWER_SM8750
        SocFamily.KALAMA -> POWER_KALAMA_LARGE
        SocFamily.KONA -> POWER_KONA
        SocFamily.MTK_D1100 -> POWER_D1100
        SocFamily.MTK_D900 -> POWER_D900
    }

    val ODIN3 = profile("odin3", "AYN Odin 3", SocFamily.SM8750, POWER_SM8750, backButtons = true)
    val ODIN2 = profile("odin2", "AYN Odin 2", SocFamily.KALAMA, POWER_KALAMA_LARGE, backButtons = true)
    val ODIN2_MINI = profile("odin2mini", "AYN Odin 2 Mini", SocFamily.KALAMA, POWER_KALAMA_SMALL, backButtons = true)
    val ODIN2_PORTAL = profile("odin2portal", "AYN Odin 2 Portal", SocFamily.KALAMA, POWER_KALAMA_LARGE, backButtons = true)
    val THOR = profile("thor", "AYN Thor", SocFamily.KALAMA, POWER_KALAMA_LARGE, backButtons = true)
    val THOR_LITE = profile("thorlite", "AYN Thor Lite", SocFamily.KONA, POWER_KONA, backButtons = true)
    val RP6 = profile("rp6", "Retroid Pocket 6", SocFamily.KALAMA, POWER_KALAMA_SMALL, backButtons = false)
    val RP_NOVA = profile("rpnova", "Retroid Pocket Nova", SocFamily.KALAMA, POWER_KALAMA_SMALL, backButtons = false)
    val RP5 = profile("rp5", "Retroid Pocket 5", SocFamily.KONA, POWER_KONA, backButtons = false)
    val RP_MINI = profile("rpmini", "Retroid Pocket Mini", SocFamily.KONA, POWER_KONA_SMALL, backButtons = false)
    val RP_MINI_V2 = profile("rpminiv2", "Retroid Pocket Mini V2", SocFamily.KONA, POWER_KONA_SMALL, backButtons = false)
    val RP4_PRO = profile("rp4pro", "Retroid Pocket 4 Pro", SocFamily.MTK_D1100, POWER_D1100, backButtons = false)
    val RP4 = profile("rp4", "Retroid Pocket 4", SocFamily.MTK_D900, POWER_D900, backButtons = false)

    val all: List<DeviceProfile> = listOf(
        ODIN3, ODIN2, ODIN2_MINI, ODIN2_PORTAL, THOR, THOR_LITE,
        RP6, RP_NOVA, RP5, RP_MINI, RP_MINI_V2, RP4_PRO, RP4,
    )

    /** Aparelho sem perfil próprio: só o SoC é conhecido (ou nem isso). */
    fun generic(identity: DeviceIdentity, soc: SocFamily): DeviceProfile {
        val name = listOf(identity.manufacturer, identity.model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android" }
        return profile("generic", name, soc, powerFor(soc), backButtons = false, official = false)
    }

    /** SoC a partir de `ro.soc.model`, `ro.board.platform` e `Build.HARDWARE`. */
    fun socFrom(identity: DeviceIdentity): SocFamily {
        val soc = identity.socModel.uppercase()
        val platform = identity.platform.lowercase().trim()
        val hardware = identity.hardware.lowercase().trim()
        return when {
            soc.contains("SM8750") || soc.contains("CQ8725") || platform == "sun" -> SocFamily.SM8750
            soc.contains("8550") || platform == "kalama" -> SocFamily.KALAMA
            soc.contains("SM8250") || platform == "kona" -> SocFamily.KONA
            soc.contains("MT8797") || soc.contains("MT6891") || soc.contains("MT6893") ||
                platform.startsWith("mt6893") || hardware.startsWith("mt6893") -> SocFamily.MTK_D1100
            soc.contains("MT6877") || soc.contains("MT8791") ||
                platform.startsWith("mt6877") || hardware.startsWith("mt6877") -> SocFamily.MTK_D900
            else -> SocFamily.UNKNOWN
        }
    }

    /**
     * Deteção, por ordem: (1) `Build.MODEL` -- os nomes exatos estão confirmados no Geekbench;
     * `Build.DEVICE` NÃO serve (é "kalama" em todos os 8 Gen 2 de stock); (2) `ro.vendor.retro.name`
     * do firmware AYN/Retroid; (3) só o SoC, como aparelho genérico.
     */
    fun detect(identity: DeviceIdentity): DeviceProfile {
        val soc = socFrom(identity)
        val model = identity.model.lowercase().replace(Regex("[^a-z0-9]"), "")

        val byModel: DeviceProfile? = when {
            model.contains("odin3") -> ODIN3
            model.contains("odin2mini") -> ODIN2_MINI
            model.contains("odin2portal") -> ODIN2_PORTAL
            model.contains("odin2") -> ODIN2
            // O Thor Lite (865) e o Thor (8 Gen 2) podem ter o mesmo MODEL: decide o SoC.
            model.contains("thor") -> if (soc == SocFamily.KONA || model.contains("lite")) THOR_LITE else THOR
            model.contains("retroidpocketnova") -> RP_NOVA
            model.contains("retroidpocket6") -> RP6
            model.contains("retroidpocketminiv2") -> RP_MINI_V2
            model.contains("retroidpocketmini") -> RP_MINI
            model.contains("retroidpocket5") -> RP5
            model.contains("retroidpocket4pro") -> RP4_PRO
            model.contains("retroidpocket4") -> RP4
            else -> null
        }
        if (byModel != null) return byModel

        val byRetroName: DeviceProfile? = when (identity.retroName.trim().uppercase()) {
            "Q9", "ODIN2" -> ODIN2
            "4.0P" -> RP4_PRO
            "4.0" -> RP4
            else -> null
        }
        if (byRetroName != null) return byRetroName

        // 8 Elite com firmware AYN e um MODEL que ainda não conhecemos (ex: futuras revisões do
        // Odin 3): é o perfil já testado do Odin 3.
        if (soc == SocFamily.SM8750 && identity.manufacturer.contains("ayn", ignoreCase = true)) return ODIN3

        return generic(identity, soc)
    }
}
