package com.dfdx047.odinhub.tools.hardware

/**
 * Frequências da GPU "acima do stock".
 *
 * O Retroid Hub NUNCA inventa frequências: o slider/chips da GPU mostram só o que o kernel expõe
 * (`gpu_available_frequencies`). Se o kernel instalado tiver OPPs acima do máximo de fábrica do
 * aparelho (um kernel/DTB com overclock), esses degraus aparecem marcados como "OC".
 *
 * Máximo de fábrica (stockMaxMHz) vem do perfil do aparelho (ver DeviceProfiles): no RP5 o kernel
 * de fábrica expõe 305..670 MHz (lido no aparelho, firmware 1.0.0.189), por isso 670 é o stock.
 */
object GpuOc {

    /** Temperatura da GPU (°C) a partir da qual a UI avisa a vermelho. */
    const val HOT_WARNING_C = 80

    /** Temperatura (°C) a partir da qual a UI mostra "quente" a laranja. */
    const val WARM_WARNING_C = 70

    data class Step(val mhz: Int, val isOc: Boolean)

    /** Classifica cada frequência da tabela (MHz, qualquer ordem) em stock / OC. */
    fun classify(tableMHz: List<Int>, stockMaxMHz: Int?): List<Step> =
        tableMHz.distinct().sorted().map { Step(it, stockMaxMHz != null && stockMaxMHz > 0 && it > stockMaxMHz) }

    /** O kernel tem pelo menos um OPP acima do stock? */
    fun hasOc(tableMHz: List<Int>, stockMaxMHz: Int?): Boolean = classify(tableMHz, stockMaxMHz).any { it.isOc }

    /**
     * Teto usado como "100 %" pelo AutoTDP: o máximo de fábrica, mesmo que o kernel tenha OPPs de
     * OC. O TDP nunca empurra a GPU para overclock sozinho -- isso só com escolha explícita nos
     * clocks manuais.
     */
    fun tdpCeilingHz(availableHz: List<Long>, stockMaxMHz: Int?): Long? {
        if (availableHz.isEmpty()) return null
        val max = availableHz.max()
        if (stockMaxMHz == null || stockMaxMHz <= 0) return max
        val stockHz = stockMaxMHz * 1_000_000L + GpuControl.SNAP_TOLERANCE_HZ
        return availableHz.filter { it <= stockHz }.maxOrNull() ?: availableHz.min()
    }

    /**
     * O piso atual (`min_clock_mhz`) está acima do máximo de fábrica? Acontece com um DTB de OC: o
     * modo de desempenho do firmware fixa o piso por ÍNDICE (`min_pwrlevel`), e no RP5 com a tabela
     * de 905 MHz o índice 1 do "Alto desempenho" passa de 587 para 880 MHz (ver docs/device-rp5.md).
     */
    fun floorIsOc(floorMHz: Int?, stockMaxMHz: Int?): Boolean =
        floorMHz != null && stockMaxMHz != null && stockMaxMHz > 0 && floorMHz > stockMaxMHz

    enum class Heat { UNKNOWN, OK, WARM, HOT }

    fun heat(tempC: Float?): Heat = when {
        tempC == null -> Heat.UNKNOWN
        tempC >= HOT_WARNING_C -> Heat.HOT
        tempC >= WARM_WARNING_C -> Heat.WARM
        else -> Heat.OK
    }

    /** Margem para sair de um aviso: sobe em 70/80 °C, só desce abaixo de 67/77 °C. */
    const val HEAT_HYSTERESIS_C = 3

    /**
     * Como [heat], mas com histerese em relação ao estado anterior: a temperatura do kgsl oscila
     * 1 °C de leitura para leitura e, sem isto, o aviso aparecia e sumia a cada segundo.
     */
    fun heat(tempC: Float?, previous: Heat): Heat {
        val raw = heat(tempC)
        if (tempC == null || raw.ordinal >= previous.ordinal) return raw
        // Está a descer: só desce de nível quando passa a margem abaixo do limite desse nível.
        val keep = when (previous) {
            Heat.HOT -> tempC > HOT_WARNING_C - HEAT_HYSTERESIS_C
            Heat.WARM -> tempC > WARM_WARNING_C - HEAT_HYSTERESIS_C
            else -> false
        }
        return if (keep) previous else if (previous == Heat.HOT && tempC > WARM_WARNING_C - HEAT_HYSTERESIS_C) Heat.WARM else raw
    }

    /** `temp` do kgsl vem em m°C (30700) em uns kernels e em °C noutros. */
    fun parseKgslTemp(raw: String?): Float? {
        val v = raw?.trim()?.toFloatOrNull() ?: return null
        val c = if (v > 1000f) v / 1000f else v
        return c.takeIf { it in -20f..150f }
    }
}

/** Leitura ao vivo da GPU para o cartão (atualizada enquanto o ecrã está aberto). */
data class GpuLiveInfo(
    val curMHz: Int? = null,
    /** Teto atual (`max_gpuclk`). */
    val capMHz: Int? = null,
    /** Piso atual (`min_clock_mhz`). */
    val floorMHz: Int? = null,
    /** Maior OPP que o kernel expõe. */
    val kernelMaxMHz: Int? = null,
    val tempC: Float? = null,
)
