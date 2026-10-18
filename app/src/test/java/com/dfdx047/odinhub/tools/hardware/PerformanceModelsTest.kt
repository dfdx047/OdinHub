package com.dfdx047.odinhub.tools.hardware

import com.dfdx047.odinhub.models.ClusterClockPresets
import com.dfdx047.odinhub.models.TdpProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes JVM puros dos modelos da aba Performance: perfis de TDP, curadoria das tabelas de clock e
 * a regra de segurança dos trip points térmicos.
 */
class PerformanceModelsTest {

    // ---------------------------------------------------------------- TdpProfiles

    @Test
    fun `perfis fixos do 8 Elite`() {
        assertEquals(7f, TdpProfiles.POWER_SAVE.watts)
        assertEquals(10f, TdpProfiles.BALANCED.watts)
        assertEquals(15f, TdpProfiles.TRIPLE_A.watts)
        assertNull(TdpProfiles.STOCK.watts)
    }

    @Test
    fun `slider vai de 1 a 25 W`() {
        assertEquals(1f, TdpProfiles.TDP_MIN_WATTS)
        assertEquals(25f, TdpProfiles.TDP_MAX_WATTS)
    }

    @Test
    fun `perfil fixo ignora o valor do slider`() {
        assertEquals(7f, TdpProfiles.resolveWatts(TdpProfiles.ID_POWER_SAVE, 3f))
        assertEquals(10f, TdpProfiles.resolveWatts(TdpProfiles.ID_BALANCED, 20f))
        assertNull(TdpProfiles.resolveWatts(TdpProfiles.ID_STOCK, 20f))
    }

    @Test
    fun `custom usa o slider e respeita os limites`() {
        assertEquals(7f, TdpProfiles.resolveWatts(TdpProfiles.ID_CUSTOM, 7f))
        assertEquals(1f, TdpProfiles.resolveWatts(TdpProfiles.ID_CUSTOM, 0.2f))
        assertEquals(25f, TdpProfiles.resolveWatts(TdpProfiles.ID_CUSTOM, 40f))
    }

    @Test
    fun `idForWatts reconhece perfis e a sentinela de Stock`() {
        assertEquals(TdpProfiles.ID_BALANCED, TdpProfiles.idForWatts(10f))
        assertEquals(TdpProfiles.ID_CUSTOM, TdpProfiles.idForWatts(9f))
        assertEquals(TdpProfiles.ID_STOCK, TdpProfiles.idForWatts(TdpProfiles.STOCK_SENTINEL_WATTS))
    }

    @Test
    fun `formatWatts mostra casa decimal so quando existe`() {
        assertEquals("15 W", TdpProfiles.formatWatts(15f))
        assertEquals("12.5 W", TdpProfiles.formatWatts(12.5f))
    }

    // ---------------------------------------------------------------- ClockTableCuration

    @Test
    fun `curate mantem minimo e maximo e limita o tamanho`() {
        val table = ClusterClockPresets.SM8750.primeMHz
        val curated = ClockTableCuration.curate(table, targetCount = 8)
        assertTrue(curated.size <= 8)
        assertEquals(table.first(), curated.first())
        assertEquals(table.last(), curated.last())
        assertTrue(table.containsAll(curated))
    }

    @Test
    fun `curate mantem os degraus de topo todos (4204 no Prime do 8 Elite)`() {
        val curated = ClockTableCuration.curate(ClusterClockPresets.SM8750.primeMHz)
        assertEquals(listOf(4089, 4204, 4320), curated.takeLast(3))
        assertEquals(ClockTableCuration.DEFAULT_TARGET_COUNT, curated.size)
    }

    @Test
    fun `curate devolve tabelas pequenas inteiras`() {
        assertEquals(listOf(100, 200, 300), ClockTableCuration.curate(listOf(300, 100, 200)))
    }

    @Test
    fun `nearest encaixa valores antigos no chip certo`() {
        assertEquals(3532, ClockTableCuration.nearest(3530, ClusterClockPresets.SM8750.perfMHz))
    }

    @Test
    fun `tabelas de reserva do 8 Elite tem os maximos reais`() {
        assertEquals(3532, ClusterClockPresets.SM8750.perfMHz.last())
        assertEquals(4320, ClusterClockPresets.SM8750.primeMHz.last())
        assertEquals(1100, ClusterClockPresets.SM8750.gpuMHz.last())
    }

    // ---------------------------------------------------------------- limite térmico

    @Test
    fun `trips de emergencia nunca sao alterados`() {
        assertNull(ThermalManager.targetFor(originalMilliC = 135_000, chosenMilliC = 85_000))
        assertNull(ThermalManager.targetFor(originalMilliC = 110_000, chosenMilliC = ThermalLimitModes.UNTHROTTLED_MILLI_C))
    }

    @Test
    fun `trip de primeira linha recebe o valor escolhido`() {
        assertEquals(90_000, ThermalManager.targetFor(originalMilliC = 95_000, chosenMilliC = 90_000))
        assertEquals(115_000, ThermalManager.targetFor(originalMilliC = 95_000, chosenMilliC = 115_000))
    }

    @Test
    fun `sem valor de fabrica o modo unthrottled nao escreve`() {
        assertNull(ThermalManager.targetFor(originalMilliC = null, chosenMilliC = 115_000))
        assertEquals(85_000, ThermalManager.targetFor(originalMilliC = null, chosenMilliC = 85_000))
    }
}
