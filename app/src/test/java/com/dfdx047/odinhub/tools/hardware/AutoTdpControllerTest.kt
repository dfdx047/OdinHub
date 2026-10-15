package com.dfdx047.odinhub.tools.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Controlador do AutoTDP contra um modelo simples de aparelho: P = base + k * cpu^2 + g * gpu^2
 * (consumo cresce mais depressa do que a frequência). JVM puro.
 */
class AutoTdpControllerTest {

    private val min = PerformanceCommandBuilder.TDP_MIN_RATIO

    /** Modelo de um aparelho de ~25 W no máximo: 2 W de base, 13 W de CPU, 10 W de GPU. */
    private fun plant(s: AutoTdpState) = 2f + 13f * s.cpuRatio * s.cpuRatio + 10f * s.gpuRatio * s.gpuRatio

    private fun run(target: Float, ticks: Int, start: AutoTdpState = AutoTdpController.initial(target, 25f, min), gpuBusy: Int? = null): AutoTdpState {
        var s = start
        repeat(ticks) { s = AutoTdpController.step(s, plant(s), target, min, gpuBusy) }
        return s
    }

    @Test
    fun `converge para o alvo e la fica`() {
        for (target in listOf(6f, 11f, 15f)) {
            val s = run(target, 40)
            val p = plant(s)
            assertTrue("alvo $target, medido $p", abs(p - target) / target < 0.08f)
        }
    }

    @Test
    fun `nunca passa os limites de ratio`() {
        val low = run(1f, 60)
        assertEquals(min, low.cpuRatio, 1e-4f)
        assertEquals(min, low.gpuRatio, 1e-4f)
        val high = run(40f, 60)
        assertEquals(1f, high.cpuRatio, 1e-4f)
        assertEquals(1f, high.gpuRatio, 1e-4f)
    }

    @Test
    fun `corta depressa e sobe devagar`() {
        val s = AutoTdpState(0.5f, 0.5f, filteredWatts = null)
        val down = AutoTdpController.step(s, 20f, 10f, min) // muito acima
        val up = AutoTdpController.step(s, 5f, 10f, min) // muito abaixo
        assertTrue((0.5f - down.cpuRatio) > (up.cpuRatio - 0.5f))
        assertTrue((0.5f - down.cpuRatio) / 0.5f <= AutoTdpController.MAX_STEP_DOWN + 1e-4f)
        assertTrue((up.cpuRatio - 0.5f) / 0.5f <= AutoTdpController.MAX_STEP_UP + 1e-4f)
    }

    @Test
    fun `zona morta nao mexe nos ratios`() {
        val s = AutoTdpState(0.6f, 0.6f, filteredWatts = 10f)
        val next = AutoTdpController.step(s, 10.2f, 10f, min)
        assertEquals(0.6f, next.cpuRatio, 0f)
        assertEquals(0.6f, next.gpuRatio, 0f)
    }

    @Test
    fun `sem medicao o passo e ignorado`() {
        val s = AutoTdpState(0.6f, 0.6f)
        assertEquals(s, AutoTdpController.step(s, 0f, 10f, min))
    }

    @Test
    fun `jogo preso na GPU corta mais a CPU`() {
        val s = AutoTdpState(0.8f, 0.8f, filteredWatts = 20f)
        val next = AutoTdpController.step(s, 20f, 10f, min, gpuBusyPercent = 97)
        assertTrue("CPU ${next.cpuRatio} GPU ${next.gpuRatio}", next.cpuRatio < next.gpuRatio)
        val cpuBound = AutoTdpController.step(s, 20f, 10f, min, gpuBusyPercent = 30)
        assertTrue(cpuBound.gpuRatio < cpuBound.cpuRatio)
    }

    @Test
    fun `anti windup no teto`() {
        var s = AutoTdpState(1f, 1f)
        repeat(30) { s = AutoTdpController.step(s, 5f, 20f, min) } // sempre abaixo do alvo, já no máximo
        assertEquals(0f, s.integral, 1e-4f)
    }

    @Test
    fun `ponto de partida pela raiz quadrada`() {
        assertEquals(kotlin.math.sqrt(11f / 25f), AutoTdpController.feedForward(11f, 25f, min), 1e-4f)
        assertEquals(1f, AutoTdpController.feedForward(30f, 25f, min), 0f)
        assertEquals(min, AutoTdpController.feedForward(0.01f, 25f, min), 0f)
    }

    @Test
    fun `potencia da bateria em uA e em mA`() {
        assertEquals(7.6f, AutoTdpController.batteryWatts(-2_000_000L, 3_800_000L), 0.01f)
        assertEquals(7.6f, AutoTdpController.batteryWatts(2_000L, 3_800_000L), 0.01f) // firmware em mA
        assertEquals(0f, AutoTdpController.batteryWatts(1_000_000L, 0L), 0f)
    }

    @Test
    fun `ocupacao da GPU nos dois formatos do kgsl`() {
        assertEquals(37, AutoTdpController.parseGpuBusy("37 %"))
        assertEquals(25, AutoTdpController.parseGpuBusy("250 1000"))
        assertNull(AutoTdpController.parseGpuBusy(""))
        assertNull(AutoTdpController.parseGpuBusy("0 0"))
    }
}
