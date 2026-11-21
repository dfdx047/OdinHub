package com.dfdx047.odinhub.tools.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Odin 2 Mini: o pservice da AYN sobe os pisos (902/1651/1843 MHz no modo Normal, min = max no
 * Alto) sempre que `performance_mode` muda. Nos clusters livres o piso vai ao fundo da tabela e
 * fica trancado a 0444 -- testado no aparelho: com 0444 o pservice não consegue escrever.
 */
class FirmwareFloorGuardTest {

    private val root = "/sys/devices/system/cpu/cpufreq"
    private val eff = listOf(307200L, 441600L, 556800L, 672000L, 787200L, 902400L, 1017600L, 1132800L, 1248000L, 1363200L, 1478400L, 1593600L, 1708800L, 1843200L, 1958400L, 2016000L)
    private val perf = listOf(499200L, 614400L, 729600L, 844800L, 940800L, 1056000L, 1171200L, 1286400L, 1401600L, 1536000L, 1651200L, 1785600L, 1920000L, 2054400L, 2188800L, 2323200L, 2457600L, 2592000L, 2707200L, 2803200L)
    private val prime = listOf(595200L, 729600L, 864000L, 998400L, 1132800L, 1248000L, 1363200L, 1478400L, 1593600L, 1708800L, 1843200L, 1977600L, 2092800L, 2227200L, 2342400L, 2476800L, 2592000L, 2726400L, 2841600L, 2956800L, 3187200L)
    private val mini = listOf(
        CpuPolicyNode("$root/policy0", 2_016_000L, eff, listOf(0, 1, 2)),
        CpuPolicyNode("$root/policy3", 2_803_200L, perf, listOf(3, 4, 5, 6)),
        CpuPolicyNode("$root/policy7", 3_187_200L, prime, listOf(7)),
    )

    private fun ops(pin: Int, guard: Boolean) = PerformanceCommandBuilder.buildCpuClockOps(
        mini, 0.4f, 0.4f, PerformanceCommandBuilder.MANUAL_MIN_RATIO, 0.4f, pin,
        currentMinKHz = { 300_000L }, guardFloors = guard,
    )

    @Test
    fun `clusters livres ganham piso trancado no fundo da tabela antes do teto`() {
        val list = ops(ClockPin.NONE, guard = true)
        assertEquals(6, list.size)
        list.chunked(2).forEach { (floor, cap) ->
            assertTrue(floor.label, floor.label.endsWith("-guard"))
            assertTrue(floor.targetPath.endsWith("scaling_min_freq"))
            assertEquals(HardwareWriteOp.LOCKED_MODE, floor.lockMode)
            assertTrue(!floor.verify)
            assertTrue(cap.targetPath.endsWith("scaling_max_freq"))
        }
        assertEquals("307200", list.first { it.targetPath == "$root/policy0/scaling_min_freq" }.value)
        assertEquals("499200", list.first { it.targetPath == "$root/policy3/scaling_min_freq" }.value)
        assertEquals("595200", list.first { it.targetPath == "$root/policy7/scaling_min_freq" }.value)
    }

    @Test
    fun `sem guarda e com o piso ja baixo so escreve os tetos`() {
        assertTrue(ops(ClockPin.NONE, guard = false).all { it.targetPath.endsWith("scaling_max_freq") })
    }

    @Test
    fun `cluster fixado nao recebe guarda`() {
        val list = ops(ClockPin.PRIME, guard = true)
        assertTrue(list.none { it.label.endsWith("-guard") && it.targetPath.startsWith("$root/policy7") })
        assertTrue(list.any { it.label.endsWith("-guard") && it.targetPath.startsWith("$root/policy0") })
    }

    @Test
    fun `guarda da GPU tranca o devfreq no degrau mais baixo`() {
        val op = PerformanceCommandBuilder.buildGpuFloorGuardOp(124_800_000L)!!
        assertEquals(PerformanceCommandBuilder.GPU_DEVFREQ_MIN_PATH, op.targetPath)
        assertEquals("124800000", op.value)
        assertEquals(HardwareWriteOp.LOCKED_MODE, op.lockMode)
        assertNull(PerformanceCommandBuilder.buildGpuFloorGuardOp(null))
    }
}
