package com.dfdx047.odinhub.tools.hardware

/**
 * Como limitar a GPU do Odin 3 (Adreno 830): [Kgsl] -- `max_gpuclk` em Hz.
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
