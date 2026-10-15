package com.dfdx047.odinhub.tools.hardware

/**
 * Como limitar a GPU deste aparelho. Duas implementações:
 *  - [Kgsl]: Adreno (todos os Snapdragon) -- `max_gpuclk` em Hz.
 *  - [MtkGed]: Mali nos Dimensity do Retroid Pocket 4 / 4 Pro (kernel 4.19, gpufreq v1) -- o teto
 *    é um ÍNDICE de OPP em `custom_upbound_gpu_freq` (0 = o mais rápido = sem teto).
 *
 * Os nós de leitura/escrita e as tabelas são passados por quem cria, para isto ser testável sem
 * tocar no sistema de ficheiros.
 */
sealed class GpuControl {
    /** Frequências suportadas em Hz, crescentes. Vazia = GPU não controlável neste aparelho. */
    abstract val availableHz: List<Long>

    /** Escrita que põe o teto da GPU em [targetHz] (já encaixado num OPP suportado). */
    abstract fun opFor(targetHz: Long, lockMode: String?): HardwareWriteOp?

    /** Valor esperado no nó depois de [opFor] -- o que a verificação compara. */
    abstract fun expectedValue(targetHz: Long): String?

    /** Nó com a ocupação da GPU (para o AutoTDP), ou `null`. */
    abstract val busyPath: String?

    abstract fun parseBusy(raw: String?): Int?

    val maxHz: Long? get() = availableHz.maxOrNull()
    val minHz: Long? get() = availableHz.minOrNull()

    /** O OPP mais alto que não passa de [targetHz] (é um TETO); abaixo de tudo, o mínimo. */
    fun snap(targetHz: Long): Long? {
        if (availableHz.isEmpty()) return null
        return availableHz.filter { it <= targetHz + SNAP_TOLERANCE_HZ }.maxOrNull() ?: availableHz.min()
    }

    class Kgsl(override val availableHz: List<Long>, private val maxPath: String = MAX_PATH) : GpuControl() {
        override fun opFor(targetHz: Long, lockMode: String?): HardwareWriteOp? {
            val snapped = snap(targetHz) ?: return null
            return HardwareWriteOp("gpu-max-clock", maxPath, snapped.toString(), lockMode)
        }

        override fun expectedValue(targetHz: Long): String? = snap(targetHz)?.toString()

        override val busyPath: String = BUSY_PATH
        override fun parseBusy(raw: String?): Int? = AutoTdpController.parseGpuBusy(raw)

        companion object {
            const val MAX_PATH = "/sys/class/kgsl/kgsl-3d0/max_gpuclk"
            const val BUSY_PATH = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"
            val TABLE_PATHS = listOf(
                "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",
                "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",
            )
        }
    }

    /**
     * @param oppHzFastestFirst tabela tal como o driver a lista (`gpufreq_opp_dump`: índice 0 =
     *        mais rápido), em Hz.
     */
    class MtkGed(private val oppHzFastestFirst: List<Long>, private val upboundPath: String = UPBOUND_PATH) : GpuControl() {
        override val availableHz: List<Long> = oppHzFastestFirst.distinct().sorted()

        /** Índice de OPP (0 = mais rápido) para um teto em Hz. */
        fun indexFor(targetHz: Long): Int? {
            val snapped = snap(targetHz) ?: return null
            return oppHzFastestFirst.indexOf(snapped).takeIf { it >= 0 }
        }

        override fun opFor(targetHz: Long, lockMode: String?): HardwareWriteOp? {
            val index = indexFor(targetHz) ?: return null
            // O nó do GED é escrito pelo próprio driver; nunca o trancamos.
            return HardwareWriteOp("gpu-upbound-opp", upboundPath, index.toString(), lockMode = null, verify = false)
        }

        override fun expectedValue(targetHz: Long): String? = indexFor(targetHz)?.toString()

        override val busyPath: String = BUSY_PATH

        /** `gpu_utilization` do GED: "loading block idle" -- o primeiro número é a ocupação em %. */
        override fun parseBusy(raw: String?): Int? =
            raw?.let { Regex("\\d+").find(it)?.value?.toIntOrNull()?.coerceIn(0, 100) }

        companion object {
            const val UPBOUND_PATH = "/sys/kernel/ged/hal/custom_upbound_gpu_freq"
            const val OPP_DUMP_PATH = "/proc/gpufreq/gpufreq_opp_dump"
            const val BUSY_PATH = "/sys/kernel/ged/hal/gpu_utilization"

            /** `[00] freq = 836000, volt = 75000, ...` (kHz) -> Hz, pela ordem do driver. */
            fun parseOppDump(raw: String?): List<Long> =
                raw.orEmpty().lines().mapNotNull { line ->
                    Regex("freq\\s*=\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
                }.filter { it > 0 }
        }
    }

    /** Sem GPU controlável (tabela ilegível): os chips da GPU ficam com a tabela de reserva e nada é escrito. */
    object None : GpuControl() {
        override val availableHz: List<Long> = emptyList()
        override fun opFor(targetHz: Long, lockMode: String?): HardwareWriteOp? = null
        override fun expectedValue(targetHz: Long): String? = null
        override val busyPath: String? = null
        override fun parseBusy(raw: String?): Int? = null
    }

    companion object {
        /** Tolerância do encaixe: a UI mostra MHz inteiros (680 MHz para 680000000 Hz). */
        const val SNAP_TOLERANCE_HZ = 999_999L

    }
}
