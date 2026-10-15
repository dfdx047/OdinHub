package com.dfdx047.odinhub.tools.hardware

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Estado do AutoTDP: um ratio (0..1 do máximo) para a CPU e outro para a GPU, mais a memória do
 * controlador. Imutável -- cada passo devolve um estado novo, por isso é seguro de partilhar entre
 * threads e fácil de testar.
 */
data class AutoTdpState(
    val cpuRatio: Float,
    val gpuRatio: Float,
    /** Potência medida, já filtrada (EMA), em watts. `null` até à primeira medição. */
    val filteredWatts: Float? = null,
    /** Termo integral (erro relativo acumulado), com anti-windup. */
    val integral: Float = 0f,
)

/**
 * Controlador do TDP dinâmico (AutoTDP). Mede o consumo na bateria e ajusta os tetos de CPU e GPU
 * até o aparelho gastar os watts pedidos.
 *
 * O QUE MUDOU face ao loop antigo (passo proporcional único, igual para CPU e GPU):
 *  1. ARRANQUE MAIS CERTEIRO: o consumo cresce mais depressa do que a frequência (P ~ f·V²), por
 *     isso o ponto de partida é `sqrt(watts/máximo)` em vez de `watts/máximo`. Ex. Odin 3 a 11 W:
 *     66% em vez de 44% -- o jogo já não arranca estrangulado à espera que o loop suba.
 *  2. MEDIÇÃO FILTRADA: `current_now` é instantâneo e salta muito; amostramos 4x por segundo e
 *     ainda passamos uma média exponencial, para o loop não reagir a picos de um frame.
 *  3. PI ASSIMÉTRICO: corta depressa quando passa do orçamento (calor) e sobe devagar quando
 *     sobra (evita o "serrote" de subir/descer que se sentia como stutter). O integral elimina o
 *     erro residual (o loop antigo ficava parado a ~0.3 W do alvo para sempre) e tem anti-windup
 *     quando os ratios batem no teto ou no piso.
 *  4. ZONA MORTA RELATIVA: ±4% do alvo (mín. 0.25 W) em vez de 0.3 W fixos -- 0.3 W é muito num
 *     Retroid de 6 W e pouco num Odin 3 de 15 W.
 *  5. CPU vs GPU: com a ocupação da GPU disponível, o corte vai sobretudo para quem NÃO é o
 *     gargalo. Jogo preso na GPU (>= 85%): corta-se mais a CPU e poupa-se a GPU; jogo preso na
 *     CPU (GPU <= 50%): o contrário. Mais FPS pelos mesmos watts.
 */
object AutoTdpController {

    /** Peso da medição nova na média exponencial. */
    const val EMA_ALPHA = 0.5f

    const val DEADBAND_RELATIVE = 0.04f
    const val DEADBAND_MIN_WATTS = 0.25f

    /** Ganho proporcional a cortar (acima do orçamento) e a subir (abaixo). */
    const val GAIN_DOWN = 0.35f
    const val GAIN_UP = 0.15f
    const val GAIN_INTEGRAL = 0.05f
    const val INTEGRAL_LIMIT = 2f

    /** Variação máxima por passo (relativa): no máximo -15% ou +8% por segundo. */
    const val MAX_STEP_DOWN = 0.15f
    const val MAX_STEP_UP = 0.08f

    const val GPU_BOUND_PERCENT = 85
    const val CPU_BOUND_PERCENT = 50

    /** Ratio inicial para um alvo em watts. */
    fun feedForward(targetWatts: Float, maxWatts: Float, minRatio: Float): Float {
        if (maxWatts <= 0f) return 1f
        return sqrt((targetWatts / maxWatts).coerceIn(0f, 1f)).coerceIn(minRatio, 1f)
    }

    fun initial(targetWatts: Float, maxWatts: Float, minRatio: Float): AutoTdpState {
        val r = feedForward(targetWatts, maxWatts, minRatio)
        return AutoTdpState(cpuRatio = r, gpuRatio = r)
    }

    /**
     * Um passo do controlador (chamado ~1x por segundo).
     *
     * @param measuredWatts média das amostras desde o último passo; <= 0 = sem medição (passo ignorado).
     * @param gpuBusyPercent ocupação da GPU 0..100, ou `null` se o nó não for legível.
     */
    fun step(
        state: AutoTdpState,
        measuredWatts: Float,
        targetWatts: Float,
        minRatio: Float,
        gpuBusyPercent: Int? = null,
    ): AutoTdpState {
        if (measuredWatts <= 0f || targetWatts <= 0f) return state

        val filtered = state.filteredWatts?.let { it + EMA_ALPHA * (measuredWatts - it) } ?: measuredWatts
        val error = (filtered - targetWatts) / targetWatts // > 0: a gastar demais
        val deadband = maxOf(DEADBAND_RELATIVE, DEADBAND_MIN_WATTS / targetWatts)

        if (abs(error) < deadband) {
            // Dentro do alvo: não mexe, e deixa o integral esvaziar devagar.
            return state.copy(filteredWatts = filtered, integral = state.integral * 0.9f)
        }

        // Anti-windup: não acumula erro na direção em que já não há margem para mexer.
        val atCeiling = state.cpuRatio >= 1f && state.gpuRatio >= 1f
        val atFloor = state.cpuRatio <= minRatio && state.gpuRatio <= minRatio
        val integral = when {
            error < 0f && atCeiling -> state.integral
            error > 0f && atFloor -> state.integral
            else -> (state.integral + error).coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT)
        }

        val gain = if (error > 0f) GAIN_DOWN else GAIN_UP
        val delta = (-(gain * error + GAIN_INTEGRAL * integral)).coerceIn(-MAX_STEP_DOWN, MAX_STEP_UP)

        val (cpuWeight, gpuWeight) = weights(delta, gpuBusyPercent)
        val cpu = (state.cpuRatio * (1f + delta * cpuWeight)).coerceIn(minRatio, 1f)
        val gpu = (state.gpuRatio * (1f + delta * gpuWeight)).coerceIn(minRatio, 1f)

        return AutoTdpState(cpuRatio = cpu, gpuRatio = gpu, filteredWatts = filtered, integral = integral)
    }

    /**
     * Quanto do passo vai para a CPU e quanto para a GPU. A cortar (`delta < 0`) pesa mais em
     * quem NÃO é gargalo; a subir, pesa mais em quem é.
     */
    fun weights(delta: Float, gpuBusyPercent: Int?): Pair<Float, Float> {
        val busy = gpuBusyPercent ?: return 1f to 1f
        val gpuBound = busy >= GPU_BOUND_PERCENT
        val cpuBound = busy <= CPU_BOUND_PERCENT
        return when {
            gpuBound && delta < 0f -> 1.4f to 0.6f
            gpuBound -> 0.6f to 1.4f
            cpuBound && delta < 0f -> 0.6f to 1.4f
            cpuBound -> 1.4f to 0.6f
            else -> 1f to 1f
        }
    }

    /**
     * Potência a partir de `current_now`/`voltage_now` da bateria. Qualcomm reporta µA/µV; há
     * firmwares MediaTek que reportam mA -- um valor absurdo de baixo (< 20 000) com o aparelho
     * ligado só pode ser mA. O sinal varia entre fabricantes, por isso usa-se o módulo; a decisão
     * "está a carregar?" vem do nó `status`, não do sinal.
     */
    fun batteryWatts(currentRaw: Long, voltageMicroV: Long): Float {
        if (voltageMicroV <= 0L) return 0f
        val current = abs(currentRaw)
        val amps = if (current in 1 until 20_000) current / 1_000f else current / 1_000_000f
        return amps * (voltageMicroV / 1_000_000f)
    }

    /** Ocupação da GPU a partir de `gpu_busy_percentage` ("37 %") ou `gpubusy` ("busy total"). */
    fun parseGpuBusy(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val numbers = Regex("\\d+").findAll(raw).map { it.value.toLongOrNull() }.filterNotNull().toList()
        return when {
            raw.contains('%') && numbers.isNotEmpty() -> numbers.first().toInt().coerceIn(0, 100)
            numbers.size >= 2 && numbers[1] > 0 -> ((numbers[0] * 100) / numbers[1]).toInt().coerceIn(0, 100)
            numbers.size == 1 -> numbers[0].toInt().coerceIn(0, 100)
            else -> null
        }
    }
}
