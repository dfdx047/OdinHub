package com.dfdx047.odinhub.tools.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixar / libertar clocks (ordem piso/teto) e classificação das frequências OC da GPU. As tabelas
 * são as do RP5 lidas no aparelho (firmware 1.0.0.189).
 */
class ClockPinAndOcTest {

    private val root = "/sys/devices/system/cpu/cpufreq"
    private val effTable = listOf(300000L, 403200L, 518400L, 614400L, 691200L, 787200L, 883200L, 979200L, 1075200L, 1171200L, 1248000L, 1344000L, 1420800L, 1516800L, 1612800L, 1708800L, 1804800L)
    private val perfTable = listOf(710400L, 825600L, 940800L, 1056000L, 1171200L, 1286400L, 1382400L, 1478400L, 1574400L, 1670400L, 1766400L, 1862400L, 1958400L, 2054400L, 2150400L, 2246400L, 2342400L, 2419200L)
    private val primeTable = listOf(844800L, 960000L, 1075200L, 1190400L, 1305600L, 1401600L, 1516800L, 1632000L, 1747200L, 1862400L, 1977600L, 2073600L, 2169600L, 2265600L, 2361600L, 2457600L, 2553600L, 2649600L, 2745600L, 2841600L)
    private val rp5 = listOf(
        CpuPolicyNode("$root/policy0", 1_804_800L, effTable, listOf(0, 1, 2, 3)),
        CpuPolicyNode("$root/policy4", 2_419_200L, perfTable, listOf(4, 5, 6)),
        CpuPolicyNode("$root/policy7", 2_841_600L, primeTable, listOf(7)),
    )

    /** Pisos que o firmware do RP5 põe no modo de alto desempenho (lidos no aparelho). */
    private val firmwareMins = mapOf(
        "$root/policy0/scaling_min_freq" to 1_420_800L,
        "$root/policy4/scaling_min_freq" to 2_150_400L,
        "$root/policy7/scaling_min_freq" to 2_841_600L,
    )

    private fun ops(pin: Int, ratio: Float = 0.5f, mins: Map<String, Long> = firmwareMins, online: Set<Int>? = null) =
        PerformanceCommandBuilder.buildCpuClockOps(rp5, ratio, ratio, PerformanceCommandBuilder.MANUAL_MIN_RATIO, ratio, pin, { mins[it] }, online)

    @Test
    fun `livre com piso do firmware acima do teto desce o piso primeiro e para o fundo da tabela`() {
        val list = ops(ClockPin.NONE)
        // Para cada política: piso (-min) ANTES do teto, senão o kernel 4.19 recusa com EINVAL.
        list.chunked(2).forEach { (first, second) ->
            assertTrue(first.label, first.label.endsWith("-min"))
            assertTrue(second.targetPath.endsWith("scaling_max_freq"))
        }
        assertEquals("300000", list.first { it.targetPath == "$root/policy0/scaling_min_freq" }.value)
        assertEquals("710400", list.first { it.targetPath == "$root/policy4/scaling_min_freq" }.value)
        // O piso descido nunca trava as permissões.
        assertTrue(list.filter { it.label.endsWith("-min") }.all { it.lockMode == null })
    }

    @Test
    fun `livre com piso ja abaixo do teto so escreve o teto`() {
        val low = firmwareMins.mapValues { 300_000L }
        val list = ops(ClockPin.NONE, ratio = 0.9f, mins = low)
        assertEquals(3, list.size)
        assertTrue(list.all { it.targetPath.endsWith("scaling_max_freq") })
    }

    @Test
    fun `fixar abaixo do piso atual escreve o piso primeiro`() {
        val list = ops(ClockPin.EFF or ClockPin.PERF or ClockPin.PRIME, ratio = 0.5f)
        list.chunked(2).forEach { (first, second) ->
            assertTrue(first.label, first.label.endsWith("-pin"))
            assertEquals(first.value, second.value) // piso = teto
            assertEquals(HardwareWriteOp.LOCKED_MODE, first.lockMode)
        }
    }

    @Test
    fun `fixar acima do piso atual escreve o teto primeiro`() {
        val low = firmwareMins.mapValues { 300_000L }
        val list = ops(ClockPin.PRIME, ratio = 1f, mins = low)
        val prime = list.filter { it.targetPath.startsWith("$root/policy7/") }
        assertEquals(listOf("scaling_max_freq", "scaling_min_freq"), prime.map { it.targetPath.substringAfterLast('/') })
        assertEquals("2841600", prime[1].value)
        // Os outros clusters ficam livres: só o teto.
        assertTrue(list.filterNot { it.targetPath.startsWith("$root/policy7/") }.all { it.targetPath.endsWith("scaling_max_freq") })
    }

    @Test
    fun `politica com todos os nucleos desligados e saltada`() {
        val list = ops(ClockPin.NONE, online = setOf(0, 1, 2, 3, 4, 5, 6))
        assertFalse(list.any { it.targetPath.startsWith("$root/policy7/") })
        assertTrue(list.any { it.targetPath.startsWith("$root/policy4/") })
    }

    @Test
    fun `bits da fixacao por rotulo`() {
        assertEquals(ClockPin.PRIME, ClockPin.forCpuLabel("cpu-prime-policy"))
        assertEquals(ClockPin.EFF, ClockPin.forCpuLabel("cpu-eff-policy"))
        assertEquals(ClockPin.PERF, ClockPin.forCpuLabel("cpu-perf-policy-1"))
        assertEquals(ClockPin.GPU, ClockPin.set(0, ClockPin.GPU, true))
        assertEquals(0, ClockPin.set(ClockPin.GPU, ClockPin.GPU, false))
    }

    @Test
    fun `gpu fixa escreve min_clock_mhz no mesmo OPP com a ordem certa`() {
        val gpu = GpuControl.Kgsl(listOf(305_000_000L, 400_000_000L, 441_600_000L, 490_000_000L, 525_000_000L, 587_000_000L, 670_000_000L))
        val max = gpu.opFor(441_600_000L, HardwareWriteOp.LOCKED_MODE)
        // Piso do firmware (587) acima do alvo: piso primeiro.
        val pinned = PerformanceCommandBuilder.buildGpuClockOps(max, pinned = true, currentFloorMHz = 587)
        assertEquals(listOf("gpu-pin", max!!.label), pinned.map { it.label })
        assertEquals("441", pinned[0].value)
        // Livre: o piso desce para o fundo (305), não para o alvo.
        val free = PerformanceCommandBuilder.buildGpuClockOps(max, pinned = false, currentFloorMHz = 587, lowestMHz = 305)
        assertEquals("305", free[0].value)
        assertEquals(max.label, free[1].label)
        // Livre com o piso já baixo: só o teto.
        assertEquals(1, PerformanceCommandBuilder.buildGpuClockOps(max, pinned = false, currentFloorMHz = 305, lowestMHz = 305).size)
    }

    // ---------------------------------------------------------------- OC da GPU

    @Test
    fun `tabela de fabrica do RP5 nao tem OPPs OC`() {
        val stock = listOf(670, 587, 525, 490, 441, 400, 305)
        assertFalse(GpuOc.hasOc(stock, 670))
        assertEquals(stock.sorted(), GpuOc.classify(stock, 670).map { it.mhz })
    }

    @Test
    fun `OPPs acima do stock sao marcados OC`() {
        // Tabela de um boot com o DTB alterado pela comunidade (KonaBess): 720..905 MHz.
        val oc = listOf(305, 400, 441, 490, 525, 587, 670, 720, 810, 905)
        val steps = GpuOc.classify(oc, 670)
        assertEquals(listOf(720, 810, 905), steps.filter { it.isOc }.map { it.mhz })
        assertTrue(GpuOc.hasOc(oc, 670))
        // Sem máximo de fábrica conhecido (Mali): nada é OC.
        assertFalse(GpuOc.hasOc(oc, null))
    }

    @Test
    fun `AutoTDP nunca passa do maximo de fabrica`() {
        val hz = listOf(305_000_000L, 587_000_000L, 670_000_000L, 810_000_000L, 905_000_000L)
        assertEquals(670_000_000L, GpuOc.tdpCeilingHz(hz, 670))
        assertEquals(905_000_000L, GpuOc.tdpCeilingHz(hz, null))
        assertNull(GpuOc.tdpCeilingHz(emptyList(), 670))
        // 441.6 MHz é reportado como 441 pela UI: a tolerância apanha-o.
        assertEquals(441_600_000L, GpuOc.tdpCeilingHz(listOf(305_000_000L, 441_600_000L), 441))
    }

    @Test
    fun `temperatura da GPU e aviso`() {
        assertEquals(30.7f, GpuOc.parseKgslTemp("30700")!!, 0.01f)
        assertEquals(45f, GpuOc.parseKgslTemp("45")!!, 0.01f)
        assertNull(GpuOc.parseKgslTemp("abc"))
        assertNull(GpuOc.parseKgslTemp("999999"))
        assertEquals(GpuOc.Heat.OK, GpuOc.heat(55f))
        assertEquals(GpuOc.Heat.WARM, GpuOc.heat(72f))
        assertEquals(GpuOc.Heat.HOT, GpuOc.heat(85f))
        assertEquals(GpuOc.Heat.UNKNOWN, GpuOc.heat(null))
    }

    @Test
    fun firmwareFloorOnOcStepIsFlagged() {
        // RP5 + DTB 905 MHz, modo "Alto desempenho": min_pwrlevel=1 -> 880 MHz (stock 670).
        org.junit.Assert.assertTrue(GpuOc.floorIsOc(880, 670))
        org.junit.Assert.assertFalse(GpuOc.floorIsOc(587, 670))
        org.junit.Assert.assertFalse(GpuOc.floorIsOc(880, null))
        org.junit.Assert.assertFalse(GpuOc.floorIsOc(null, 670))
    }
}
