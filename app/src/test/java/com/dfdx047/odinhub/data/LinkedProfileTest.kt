package com.dfdx047.odinhub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Jogo ligado a um perfil guardado: o perfil manda no desempenho, o resto da regra fica. */
class LinkedProfileTest {

    private val rule = AppOverrideEntity(
        packageName = "com.retro.gba", limitMode = "TDP", tdpWatts = 12f, effClockKHz = 1_200_000L,
        fanSettingsValue = 4, tdpProfile = null, clockProfile = null, fanProfile = null,
        thermalMode = "90", displayOverride = true, saturationOverride = 1.3f, profileName = "GBA",
    )

    @Test
    fun `perfil de clocks substitui modo clocks nucleos e fixacao`() {
        val gba = CustomProfile("GBA", "CLOCK", 0f, 1056f, 1248f, 295f, 787.2f, pinMask = 0, offlineMask = 0b1011_0000, fan = 1)
        val r = rule.resolvedWith(gba)
        assertEquals("CLOCK", r.limitMode)
        assertEquals(1_056_000L, r.perfClockKHz)
        assertEquals(1_248_000L, r.primeClockKHz)
        assertEquals(295_000_000L, r.gpuClockHz)
        assertEquals(787_200L, r.effClockKHz)
        assertEquals(0b1011_0000, r.offlineCpuMask)
        assertEquals(1, r.fanSettingsValue)
        // O que não é desempenho continua o do jogo.
        assertEquals("90", r.thermalMode)
        assertEquals(1.3f, r.saturationOverride)
    }

    @Test
    fun `perfil de TDP sem ventoinha mantem a do jogo`() {
        val eco = CustomProfile("GBA", "TDP", 4.5f, 0f, 0f, 0f, 0f)
        val r = rule.resolvedWith(eco)
        assertEquals("TDP", r.limitMode)
        assertEquals(4.5f, r.tdpWatts)
        assertEquals(4, r.fanSettingsValue)
        assertEquals(0, r.offlineCpuMask)
    }

    @Test
    fun `perfil apagado ou regra sem perfil fica igual`() {
        assertSame(rule, rule.resolvedWith(null))
        val own = rule.copy(profileName = null)
        assertSame(own, own.resolvedWith(CustomProfile("GBA", "TDP", 4.5f, 0f, 0f, 0f, 0f)))
    }
}
