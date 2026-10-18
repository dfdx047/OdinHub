package com.dfdx047.odinhub.tools.hardware

/**
 * Tabelas de frequências REAIS que o SoC suporta (OPPs do kernel), em MHz e já ordenadas de forma
 * crescente. Vêm de `scaling_available_frequencies` de cada política de cpufreq e de
 * `gpu_available_frequencies` do kgsl -- ou, se nada disso for legível, de uma tabela estática por
 * SoC (ver `ClusterClockPresets.fallbackFor`), caso em que [source] é [SOURCE_FALLBACK].
 *
 * Não depende de nada do Android para poder ser construída livremente em testes JVM puros.
 */
data class ClockTables(
    val perfMHz: List<Int>,
    val primeMHz: List<Int>,
    val gpuMHz: List<Int>,
    val source: String,
    /**
     * Cluster de EFICIÊNCIA (o de frequência mais baixa) em SoCs com três clusters: 3x A510 no
     * 8 Gen 2, 4x A55 no 865. Vazio em SoCs só com dois (8 Elite, MediaTek) -- aí não há chip.
     */
    val effMHz: List<Int> = emptyList(),
) {
    val isEmpty: Boolean get() = perfMHz.isEmpty() && primeMHz.isEmpty() && gpuMHz.isEmpty()
    val hasEfficiencyCluster: Boolean get() = effMHz.isNotEmpty()

    companion object {
        const val SOURCE_KERNEL = "kernel"
        const val SOURCE_FALLBACK = "fallback"

        val EMPTY = ClockTables(emptyList(), emptyList(), emptyList(), source = "")
    }
}

/**
 * Seleção do subconjunto de chips a mostrar quando a tabela do kernel é grande (o Oryon do
 * 8 Elite tem 20+ entradas por cluster): mantém SEMPRE o mínimo e o máximo, mais entradas
 * espaçadas de forma aproximadamente uniforme até perfazer [targetCount]. Tabelas com
 * [targetCount] entradas ou menos são devolvidas na íntegra.
 */
object ClockTableCuration {

    const val DEFAULT_TARGET_COUNT = 10

    fun curate(all: List<Int>, targetCount: Int = DEFAULT_TARGET_COUNT): List<Int> {
        val sorted = all.distinct().sorted()
        if (targetCount <= 0) return emptyList()
        if (sorted.size <= targetCount) return sorted
        if (targetCount == 1) return listOf(sorted.last())

        val lastIndex = sorted.lastIndex
        val picked = LinkedHashSet<Int>()
        for (i in 0 until targetCount) {
            val index = Math.round(i.toDouble() * lastIndex / (targetCount - 1)).toInt()
            picked.add(sorted[index])
        }
        // Garantia explícita dos extremos, mesmo que o arredondamento os tivesse perdido.
        picked.add(sorted.first())
        picked.add(sorted.last())
        return picked.sorted()
    }

    /**
     * Converte uma lista de frequências em kHz (cpufreq) para MHz inteiros, sem duplicados.
     */
    fun kHzToMHz(freqsKHz: List<Long>): List<Int> =
        freqsKHz.map { (it / 1000L).toInt() }.filter { it > 0 }.distinct().sorted()

    /** Idem para Hz (kgsl/devfreq da GPU). */
    fun hzToMHz(freqsHz: List<Long>): List<Int> =
        freqsHz.map { (it / 1_000_000L).toInt() }.filter { it > 0 }.distinct().sorted()

    /**
     * Encaixa um valor pedido (MHz) no valor mais próximo da tabela, para o chip correto ficar
     * destacado mesmo quando o valor guardado veio de uma versão antiga da app (ex: 3530 vs 3532).
     */
    fun nearest(valueMHz: Int, table: List<Int>): Int? =
        table.minByOrNull { kotlin.math.abs(it - valueMHz) }
}
