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

    /** Quantos degraus de topo entram SEMPRE na seleção (é aí que se afina o desempenho). */
    const val TOP_STEPS_KEPT = 3

    /**
     * Subconjunto da tabela para os chips por omissão: os [TOP_STEPS_KEPT] degraus de topo todos
     * (antes, no Prime do 8 Elite, o 4204 MHz desaparecia e saltava-se de 4089 para 4320) e o
     * resto espalhado de forma uniforme até ao mínimo.
     */
    fun curate(all: List<Int>, targetCount: Int = DEFAULT_TARGET_COUNT): List<Int> {
        val sorted = all.distinct().sorted()
        if (targetCount <= 0) return emptyList()
        if (sorted.size <= targetCount) return sorted
        if (targetCount == 1) return listOf(sorted.last())

        val topCount = TOP_STEPS_KEPT.coerceAtMost(targetCount - 1)
        val top = sorted.takeLast(topCount)
        val rest = sorted.dropLast(topCount)
        val restCount = targetCount - topCount
        val picked = LinkedHashSet<Int>()
        if (restCount == 1) {
            picked.add(rest.first())
        } else {
            val lastIndex = rest.lastIndex
            for (i in 0 until restCount) {
                picked.add(rest[Math.round(i.toDouble() * lastIndex / (restCount - 1)).toInt()])
            }
        }
        picked.add(rest.first())
        picked.addAll(top)
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
