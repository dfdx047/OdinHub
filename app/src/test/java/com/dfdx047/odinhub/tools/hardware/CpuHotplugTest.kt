package com.dfdx047.odinhub.tools.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras de segurança do hotplug (JVM puro). Topologia do RP5: 0-3 Eff, 4-6 Perf, 7 Prime. */
class CpuHotplugTest {

    private val rp5 = (0..7).toList()
    private val eff = CpuCluster(ClusterKind.EFFICIENCY, listOf(0, 1, 2, 3))
    private val perf = CpuCluster(ClusterKind.PERF, listOf(4, 5, 6))
    private val prime = CpuCluster(ClusterKind.PRIME, listOf(7))
    private fun mask(vararg cpus: Int) = CpuHotplug.maskOf(cpus.toList())

    @Test
    fun `cpu0 nunca e desligado`() {
        assertEquals(0, CpuHotplug.sanitize(mask(0), rp5))
        assertEquals(mask(1, 2), CpuHotplug.sanitize(mask(0, 1, 2), rp5))
        assertEquals(0, CpuHotplug.toggleCpu(0, 0, offline = true, possible = rp5))
    }

    @Test
    fun `pedido de desligar tudo deixa pelo menos um ligado`() {
        val all = CpuHotplug.sanitize(mask(0, 1, 2, 3, 4, 5, 6, 7), rp5)
        assertFalse(CpuHotplug.isOffline(all, 0))
        assertEquals(7, Integer.bitCount(all))
        // Kernel hipotético sem cpu0 na lista: fica ligado o mais baixo que existe.
        val noBoot = CpuHotplug.sanitize(mask(4, 5), listOf(4, 5))
        assertFalse(CpuHotplug.isOffline(noBoot, 4))
    }

    @Test
    fun `nucleos que nao existem sao ignorados`() {
        assertEquals(mask(7), CpuHotplug.sanitize(mask(7, 9, 12), rp5))
        assertEquals(0, CpuHotplug.sanitize(mask(5), emptyList()))
    }

    @Test
    fun `desligar o cluster de eficiencia deixa o cpu0`() {
        val m = CpuHotplug.toggleCluster(0, eff, offline = true, possible = rp5)
        assertEquals(mask(1, 2, 3), m)
        assertTrue(CpuHotplug.isClusterOffline(m, eff))
        assertEquals(0, CpuHotplug.toggleCluster(m, eff, offline = false, possible = rp5))
    }

    @Test
    fun `clusters perf e prime ligam e desligam inteiros`() {
        var m = CpuHotplug.toggleCluster(0, prime, true, rp5)
        m = CpuHotplug.toggleCluster(m, perf, true, rp5)
        assertEquals(mask(4, 5, 6, 7), m)
        assertTrue(CpuHotplug.isClusterOffline(m, perf))
        assertFalse(CpuHotplug.isClusterOffline(m, eff))
    }

    @Test
    fun `plano liga primeiro e so depois desliga`() {
        val online = mapOf(0 to true, 1 to true, 2 to true, 3 to true, 4 to false, 5 to false, 6 to true, 7 to true)
        val plan = CpuHotplug.plan(online, mask(6, 7), rp5)
        assertEquals(listOf(4 to true, 5 to true, 6 to false, 7 to false), plan)
    }

    @Test
    fun `plano nunca toca no cpu0 e nao repete o que ja esta certo`() {
        val allOn = rp5.associateWith { true }
        assertTrue(CpuHotplug.plan(allOn, 0, rp5).isEmpty())
        assertTrue(CpuHotplug.plan(allOn, mask(0), rp5).isEmpty())
        val back = CpuHotplug.plan(allOn + (7 to false), CpuHotplug.ALL_ONLINE, rp5)
        assertEquals(listOf(7 to true), back)
    }

    @Test
    fun `Stock volta a ligar todos`() {
        val someOff = rp5.associateWith { it !in listOf(4, 5, 6, 7) }
        val plan = CpuHotplug.plan(someOff, CpuHotplug.ALL_ONLINE, rp5)
        assertEquals(listOf(4, 5, 6, 7), plan.map { it.first })
        assertTrue(plan.all { it.second })
    }

    @Test
    fun `operacoes de escrita uma por nucleo sem mexer em permissoes`() {
        val online = rp5.associateWith { true }
        val ops = PerformanceCommandBuilder.buildCpuOnlineOps(online, mask(7), rp5)
        assertEquals(1, ops.size)
        assertEquals("/sys/devices/system/cpu/cpu7/online", ops[0].targetPath)
        assertEquals("0", ops[0].value)
        assertEquals(listOf("echo 0 > /sys/devices/system/cpu/cpu7/online"), ops[0].asSequentialCommands())
    }

    @Test
    fun `lista de cpus do kernel`() {
        assertEquals(rp5, CpuHotplug.parseCpuList("0-7"))
        assertEquals(listOf(0, 1, 2, 3, 5), CpuHotplug.parseCpuList("0-3,5"))
        assertEquals(listOf(4, 5, 6), CpuHotplug.parseCpuList("4 5 6".replace(' ', ',')))
        assertTrue(CpuHotplug.parseCpuList("").isEmpty())
        assertTrue(CpuHotplug.parseCpuList("lixo").isEmpty())
    }

    @Test
    fun `clusters a partir das politicas`() {
        val clusters = CpuHotplug.clustersFrom(
            listOf(listOf(7) to 2_841_600L, listOf(0, 1, 2, 3) to 1_804_800L, listOf(4, 5, 6) to 2_419_200L),
        )
        assertEquals(listOf(eff, perf, prime), clusters)
        // Dois clusters (ex: RP4 Pro): não há eficiência.
        val two = CpuHotplug.clustersFrom(listOf(listOf(0, 1, 2, 3) to 2_000_000L, listOf(4, 5, 6, 7) to 2_600_000L))
        assertEquals(listOf(ClusterKind.PERF, ClusterKind.PRIME), two.map { it.kind })
    }
}
