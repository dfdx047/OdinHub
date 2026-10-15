package com.dfdx047.odinhub.models

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deteção do aparelho e dados por SoC (JVM puro). Os MODEL/SOC vêm de dumps reais (Geekbench). */
class DeviceProfilesTest {

    private fun id(model: String, soc: String = "", platform: String = "", manufacturer: String = "AYN", retro: String = "") =
        DeviceIdentity(manufacturer = manufacturer, model = model, socModel = soc, platform = platform, retroName = retro)

    @After
    fun resetEnvelope() {
        TdpProfiles.envelope = PowerEnvelope(11f, 12.5f, 15f, 25f)
    }

    @Test
    fun `familia Odin detetada pelo MODEL`() {
        assertEquals(DeviceProfiles.ODIN2, DeviceProfiles.detect(id("Odin2", "QCS8550", "kalama")))
        assertEquals(DeviceProfiles.ODIN2_MINI, DeviceProfiles.detect(id("Odin2 Mini", "QCS8550", "kalama")))
        assertEquals(DeviceProfiles.ODIN2_PORTAL, DeviceProfiles.detect(id("Odin2 Portal", "QCS8550", "kalama")))
        assertEquals(DeviceProfiles.THOR, DeviceProfiles.detect(id("AYN Thor", "QCS8550", "kalama")))
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.detect(id("Odin3", "CQ8725S", "sun")))
    }

    @Test
    fun `Thor Lite separa-se pelo SoC mesmo com o mesmo MODEL`() {
        assertEquals(DeviceProfiles.THOR_LITE, DeviceProfiles.detect(id("AYN Thor", "SM8250", "kona")))
        assertEquals(SocFamily.KONA, DeviceProfiles.THOR_LITE.soc)
    }

    @Test
    fun `familia Retroid detetada pelo MODEL`() {
        fun rp(model: String, soc: String, platform: String) = DeviceProfiles.detect(id(model, soc, platform, manufacturer = "Moorechip"))
        assertEquals(DeviceProfiles.RP5, rp("Retroid Pocket 5", "SM8250", "kona"))
        assertEquals(DeviceProfiles.RP_MINI, rp("Retroid Pocket Mini", "SM8250", "kona"))
        assertEquals(DeviceProfiles.RP_MINI_V2, rp("Retroid Pocket Mini V2", "SM8250", "kona"))
        assertEquals(DeviceProfiles.RP6, rp("Retroid Pocket 6", "QCS8550", "kalama"))
        assertEquals(DeviceProfiles.RP_NOVA, rp("Retroid Pocket Nova", "QCS8550", "kalama"))
        assertEquals(DeviceProfiles.RP4_PRO, rp("Retroid Pocket 4 Pro", "MT8797", "mt6893"))
        assertEquals(DeviceProfiles.RP4, rp("Retroid Pocket 4", "MT6877", "mt6877"))
    }

    @Test
    fun `ro vendor retro name serve de reserva`() {
        assertEquals(DeviceProfiles.ODIN2, DeviceProfiles.detect(id("qualquer", retro = "Q9")))
        assertEquals(DeviceProfiles.RP4_PRO, DeviceProfiles.detect(id("qualquer", retro = "4.0P")))
        assertEquals(DeviceProfiles.RP4, DeviceProfiles.detect(id("qualquer", retro = "4.0")))
    }

    @Test
    fun `aparelho desconhecido fica generico pelo SoC`() {
        val p = DeviceProfiles.detect(id("Phone X", "SM8550", "kalama", manufacturer = "Acme"))
        assertFalse(p.official)
        assertEquals(SocFamily.KALAMA, p.soc)
        assertEquals("Acme Phone X", p.name)
        assertFalse(p.backButtons)
    }

    @Test
    fun `8 Elite AYN com MODEL novo cai no perfil do Odin 3`() {
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.detect(id("Odin3 Portal X", "CQ8725S")))
        assertEquals(DeviceProfiles.ODIN3, DeviceProfiles.detect(id("Coisa Nova", "SM8750", manufacturer = "AYN")))
    }

    @Test
    fun `tabelas por SoC batem com os maximos reais`() {
        assertEquals(3187, SocTables.KALAMA.primeMHz.last())
        assertEquals(2803, SocTables.KALAMA.perfMHz.last())
        assertEquals(680, SocTables.KALAMA.gpuMHz.last())
        assertEquals(2841, SocTables.KONA.primeMHz.last())
        assertEquals(2419, SocTables.KONA.perfMHz.last())
        assertEquals(670, SocTables.KONA.gpuMHz.last())
        assertEquals(4320, SocTables.SM8750.primeMHz.last())
        // Tabelas sempre ordenadas e sem duplicados.
        listOf(SocTables.SM8750, SocTables.KALAMA, SocTables.KONA, SocTables.MTK_D1100).forEach { t ->
            listOf(t.perfMHz, t.primeMHz, t.gpuMHz).forEach { assertEquals(it.distinct().sorted(), it) }
        }
    }

    @Test
    fun `MediaTek usa GED e nao mexe nos trips`() {
        assertEquals(GpuBackend.MTK_GED, DeviceProfiles.RP4_PRO.gpuBackend)
        assertFalse(DeviceProfiles.RP4_PRO.thermal.supported)
        assertEquals(GpuBackend.KGSL, DeviceProfiles.RP5.gpuBackend)
        assertTrue(DeviceProfiles.RP5.thermal.supported)
    }

    @Test
    fun `8 Gen 2 so mexe em trips a partir de 90 graus (os de baixo sao da ventoinha)`() {
        assertEquals(90_000, DeviceProfiles.ODIN2.thermal.minOriginalMilliC)
        assertEquals(70_000, DeviceProfiles.RP5.thermal.minOriginalMilliC)
        assertEquals(0, DeviceProfiles.ODIN3.thermal.minOriginalMilliC)
    }

    @Test
    fun `envelope do aparelho define os perfis e o teto do slider`() {
        TdpProfiles.envelope = DeviceProfiles.RP5.power
        assertEquals(DeviceProfiles.RP5.power.balanced, TdpProfiles.BALANCED.watts)
        assertEquals(DeviceProfiles.RP5.power.maxWatts, TdpProfiles.TDP_MAX_WATTS)
        assertEquals(TdpProfiles.ID_BALANCED, TdpProfiles.idForWatts(DeviceProfiles.RP5.power.balanced))
        // Um slider guardado noutro aparelho (25 W) é cortado ao máximo deste.
        assertEquals(DeviceProfiles.RP5.power.maxWatts, TdpProfiles.resolveWatts(TdpProfiles.ID_CUSTOM, 25f))
        // O id persistido segue o aparelho: "balanced" vale os watts DESTE aparelho.
        assertEquals(DeviceProfiles.RP5.power.balanced, TdpProfiles.resolveWatts(TdpProfiles.ID_BALANCED, 3f))
    }

    @Test
    fun `envelopes sao crescentes e cabem no teto`() {
        DeviceProfiles.all.forEach { p ->
            val e = p.power
            assertTrue(p.id, e.powerSave < e.balanced && e.balanced < e.tripleA && e.tripleA < e.maxWatts)
            assertTrue(p.id, e.powerSave >= TdpProfiles.TDP_MIN_WATTS)
        }
    }
}
