package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.hardware.CpuPolicyNode
import com.dfdx047.odinhub.tools.hardware.PerformanceCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HUD de desempenho, comparação de versões do auto-update e cluster de eficiência (JVM puro). */
class HudAndUpdateTest {

    @Test
    fun `page flips do SurfaceFlinger`() {
        assertEquals(0x0001a2b3L, HudMath.parsePageFlips("Result: Parcel(0001a2b3    '....')"))
        assertNull(HudMath.parsePageFlips("service: not found"))
        assertNull(HudMath.parsePageFlips(null))
    }

    @Test
    fun `fps a partir de duas contagens`() {
        assertEquals(60f, HudMath.fps(1000, 1060, 1000)!!, 0.01f)
        assertEquals(30f, HudMath.fps(1000, 1045, 1500)!!, 0.01f)
        assertNull(HudMath.fps(1000, 900, 1000)) // contador reiniciou
        assertNull(HudMath.fps(1000, 1060, 0))
    }

    @Test
    fun `uso da CPU a partir do proc stat`() {
        val a = HudMath.parseCpuStat("cpu  100 0 100 800 0 0 0 0 0 0")!!
        val b = HudMath.parseCpuStat("cpu  150 0 150 900 0 0 0 0 0 0")!!
        assertEquals(50, HudMath.cpuUsage(a, b)) // 100 ocupados em 200
        assertNull(HudMath.parseCpuStat("cpu0 1 2 3 4"))
    }

    @Test
    fun `meminfo e temperaturas`() {
        val mem = "MemTotal:       12000000 kB\nMemFree:  100 kB\nMemAvailable:    6000000 kB\n"
        assertEquals(5859 to 11718, HudMath.parseMeminfo(mem))
        assertEquals(45f, HudMath.parseTempC("45000")!!, 0.01f)
        assertEquals(31.2f, HudMath.parseTempC("312")!!, 0.01f)
        assertEquals(45f, HudMath.parseTempC("45")!!, 0.01f)
        assertNull(HudMath.parseTempC("-1"))
    }

    @Test
    fun `metricas do HUD ida e volta`() {
        val set = linkedSetOf(HudMetric.FPS, HudMetric.GPU_TEMP, HudMetric.CPU_CORES)
        assertEquals(set, HudConfig.decodeMetrics(HudConfig.encodeMetrics(set)))
        assertEquals(setOf(HudMetric.FPS), HudConfig.decodeMetrics("fps,coisa_futura"))
        assertEquals(HudMetric.DEFAULT, HudConfig.decodeMetrics(null))
        assertTrue(HudConfig(metrics = setOf(HudMetric.FPS)).needsRoot)
        assertFalse(HudConfig(metrics = setOf(HudMetric.RAM, HudMetric.GPU_TEMP)).needsRoot)
    }

    @Test
    fun `versoes do auto-update`() {
        assertTrue(Versions.isNewer("v0.9", "0.8"))
        assertTrue(Versions.isNewer("v0.10", "0.9"))
        assertTrue(Versions.isNewer("v1.0", "0.9.5"))
        assertFalse(Versions.isNewer("v0.8", "0.8"))
        assertFalse(Versions.isNewer("v0.7", "0.8"))
        assertFalse(Versions.isNewer("nightly", "0.8"))
    }

    @Test
    fun `cluster de eficiencia tem o seu proprio teto no 8 Gen 2`() {
        val policies = listOf(
            CpuPolicyNode("/sys/devices/system/cpu/cpufreq/policy0", 2_016_000L),
            CpuPolicyNode("/sys/devices/system/cpu/cpufreq/policy3", 2_803_200L),
            CpuPolicyNode("/sys/devices/system/cpu/cpufreq/policy7", 3_187_200L),
        )
        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(policies, perfRatio = 1f, primeRatio = 1f, minRatio = 0.05f, effRatio = 0.5f)
        assertEquals("cpu-eff-policy", ops[0].label)
        assertEquals("1008000", ops[0].value)
        assertEquals("2803200", ops[1].value)
        // Sem effRatio, o cluster de eficiência segue o Perf (comportamento antigo / AutoTDP).
        val same = PerformanceCommandBuilder.buildCpuFrequencyOps(policies, perfRatio = 0.5f, primeRatio = 1f, minRatio = 0.05f)
        assertEquals("1008000", same[0].value)
        assertEquals(0.5f, PerformanceCommandBuilder.effClockToRatio(1_008_000L, policies)!!, 0.001f)
        // 8 Elite (dois clusters): não há cluster de eficiência.
        assertNull(PerformanceCommandBuilder.effClockToRatio(1_000_000L, policies.drop(1)))
    }

    @Test
    fun `tabelas de eficiencia por SoC`() {
        assertEquals(2016, SocTables.KALAMA.effMHz.last())
        assertEquals(1804, SocTables.KONA.effMHz.last())
        assertTrue(SocTables.SM8750.effMHz.isEmpty())
        assertTrue(SocTables.KALAMA.hasEfficiencyCluster)
    }
}
