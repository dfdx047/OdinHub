package com.dfdx047.odinhub.models

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deteção do Odin 3 (a app é exclusiva dele) e dados do SoC (JVM puro). */
class DeviceProfilesTest {

    private fun id(model: String, soc: String = "", platform: String = "", manufacturer: String = "AYN") =
        DeviceIdentity(manufacturer = manufacturer, model = model, socModel = soc, platform = platform)

    @After
    fun resetEnvelope() {
        TdpProfiles.envelope = DeviceProfiles.POWER_SM8750
    }

    @Test
    fun `Odin 3 pelo MODEL lido no aparelho`() {
        // Build.MODEL = "Odin3", ro.soc.model = "CQ8725S", ro.board.platform = "sun" (fw 1.0.0.188).
        val p = DeviceProfiles.detect(id("Odin3", soc = "CQ8725S", platform = "sun"))
        assertEquals(DeviceProfiles.ODIN3, p)
        assertTrue(p.official)
        assertTrue(p.backButtons)
        assertEquals(SocFamily.SM8750, p.soc)
    }

    @Test
    fun `8 Elite com firmware AYN e MODEL desconhecido e um Odin 3`() {
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.detect(id("Odin3 Pro", soc = "SM8750")))
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.detect(id("XYZ", platform = "sun")))
    }

    @Test
    fun `outros aparelhos ficam genericos e nao suportados`() {
        // Odin 2 (não o Mini): SoC conhecido (8 Gen 2), mas sem perfil oficial nem guarda de pisos.
        val odin2 = DeviceProfiles.detect(id("Odin2", soc = "QCS8550", platform = "kalama"))
        assertFalse(odin2.official)
        assertEquals(SocFamily.KALAMA, odin2.soc)
        assertFalse(odin2.guardFirmwareFloors)

        val unknown = DeviceProfiles.detect(id("Toaster", soc = "XYZ1", platform = "nope", manufacturer = "Other"))
        assertFalse(unknown.official)
        assertEquals(SocFamily.UNKNOWN, unknown.soc)
        assertNull(unknown.stockGpuMaxMHz)

        val rp5 = DeviceProfiles.detect(id("Retroid Pocket 5", soc = "SM8250", platform = "kona", manufacturer = "Retroid"))
        assertFalse(rp5.official)
        assertEquals("Retroid Retroid Pocket 5", rp5.name)

        // 8 Elite de outro fabricante: SoC conhecido, mas não é um Odin 3.
        val other = DeviceProfiles.detect(id("Phone", soc = "SM8750", manufacturer = "Other"))
        assertFalse(other.official)
        assertEquals(SocFamily.SM8750, other.soc)
    }

    @Test
    fun `Odin 2 Mini pelo MODEL lido no aparelho`() {
        // Build.MODEL = "Odin2 Mini", ro.soc.model = "QCS8550", ro.board.platform = "kalama" (fw 1.0.0.117).
        val p = DeviceProfiles.detect(id("Odin2 Mini", soc = "QCS8550", platform = "kalama"))
        assertEquals(DeviceProfiles.ODIN2_MINI, p)
        assertTrue(p.official)
        assertFalse(p.backButtons)
        assertTrue(p.guardFirmwareFloors)
        assertEquals(SocFamily.KALAMA, p.soc)
        assertEquals(com.dfdx047.odinhub.ui.theme.VITA_THEME_INDEX, p.defaultThemeIndex)
        assertEquals(692, p.stockGpuMaxMHz)
        assertEquals(692, p.fallbackTables.gpuMHz.last())
        assertEquals(2016, p.fallbackTables.effMHz.last())
    }

    @Test
    fun `tabelas e GPU do Odin 3`() {
        val t = DeviceProfiles.ODIN3.fallbackTables
        assertEquals(3532, t.perfMHz.last())
        assertEquals(4320, t.primeMHz.last())
        assertEquals(1100, t.gpuMHz.last())
        assertEquals(160, t.gpuMHz.first())
        assertEquals(1100, DeviceProfiles.ODIN3.stockGpuMaxMHz)
        assertEquals(GpuBackend.KGSL, DeviceProfiles.ODIN3.gpuBackend)
        assertEquals(0, DeviceProfiles.ODIN3.thermal.minOriginalMilliC)
    }

    @Test
    fun `envelope do Odin 3 define os perfis e o teto do slider`() {
        TdpProfiles.envelope = DeviceProfiles.ODIN3.power
        assertEquals(10f, TdpProfiles.BALANCED.watts)
        assertEquals(25f, TdpProfiles.TDP_MAX_WATTS)
        assertEquals(TdpProfiles.ID_BALANCED, TdpProfiles.idForWatts(10f))
        // Um slider acima do teto é cortado ao máximo.
        assertEquals(25f, TdpProfiles.resolveWatts(TdpProfiles.ID_CUSTOM, 40f))
    }

    @Test
    fun `envelope e crescente e cabe no teto`() {
        DeviceProfiles.all.forEach { p ->
            val e = p.power
            assertTrue(p.id, e.powerSave < e.balanced && e.balanced < e.tripleA && e.tripleA < e.maxWatts)
            assertTrue(p.id, e.powerSave >= TdpProfiles.TDP_MIN_WATTS)
        }
    }
}
