package com.dfdx047.odinhub.tools.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verificação das escritas, encaixe nos OPPs e controlo da GPU (JVM puro). */
class HardwareWriteTest {

    @Test
    fun `verificacao aceita o OPP pedido ou o mais proximo por baixo`() {
        assertTrue(isWriteApplied("1555200", "3532800", "1555200"))
        assertTrue(isWriteApplied("1500000", "3532800", "1478400")) // assentou no OPP de baixo
        assertTrue(isWriteApplied("3532000", "1555200", "3532800")) // +0.02%: o mesmo OPP
    }

    @Test
    fun `subir um teto que nao pegou e falha (antes passava)`() {
        // 5 W -> Stock: o valor antigo, mais baixo, ficou lá. Não pode contar como aplicado.
        assertFalse(isWriteApplied("3532800", "1555200", "1555200"))
    }

    @Test
    fun `acima do pedido e falha`() {
        assertFalse(isWriteApplied("1555200", "3532800", "3532800"))
        assertFalse(isWriteApplied("1555200", null, null))
    }

    @Test
    fun `encaixe no OPP suportado`() {
        val table = listOf(384000L, 960000L, 1555200L, 3532800L)
        assertEquals(3532800L, snapToSupported(3532000L, table)) // chip "3532 MHz" = máximo
        assertEquals(960000L, snapToSupported(1000000L, table))
        assertEquals(384000L, snapToSupported(1000L, table)) // abaixo de tudo: o mínimo
        assertEquals(123L, snapToSupported(123L, emptyList()))
    }

    @Test
    fun `operacao recusa caminhos e valores com metacaracteres`() {
        runCatching { HardwareWriteOp("x", "/sys/a; rm -rf /", "1") }.let { assertTrue(it.isFailure) }
        runCatching { HardwareWriteOp("x", "/sys/a", "1 > /data/x") }.let { assertTrue(it.isFailure) }
        val ok = HardwareWriteOp("x", "/proc/ppm/policy/hard_userlimit_max_cpu_freq", "0 2000000")
        assertEquals("chmod 666 /proc/ppm/policy/hard_userlimit_max_cpu_freq && echo 0 2000000 > /proc/ppm/policy/hard_userlimit_max_cpu_freq && chmod 444 /proc/ppm/policy/hard_userlimit_max_cpu_freq", ok.asChainedCommand())
    }

    @Test
    fun `sem lockMode nao mexe nas permissoes (nunca deixa 0666)`() {
        val op = HardwareWriteOp("min", "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", "902400", lockMode = null)
        assertEquals(listOf("echo 902400 > /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"), op.asSequentialCommands())
        assertFalse(op.asChainedCommand().contains("chmod"))
        val restore = op.copy(lockMode = "664")
        assertTrue(restore.asChainedCommand().endsWith("chmod 664 /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq"))
    }

    @Test
    fun `kgsl encaixa no OPP e escreve Hz`() {
        val gpu = GpuControl.Kgsl(listOf(124_800_000L, 220_000_000L, 680_000_000L))
        assertEquals("680000000", gpu.opFor(680_000_000L, "444")?.value)
        assertEquals("220000000", gpu.opFor(500_000_000L, "444")?.value)
        assertEquals("124800000", gpu.opFor(1L, "444")?.value)
    }

    @Test
    fun `MediaTek GED escreve o indice do OPP (0 = mais rapido)`() {
        val dump = """
            [00] freq = 836000, volt = 75000, vsram = 75000
            [01] freq = 700000, volt = 70000, vsram = 75000
            [02] freq = 390000, volt = 60000, vsram = 75000
        """.trimIndent()
        val opps = GpuControl.MtkGed.parseOppDump(dump)
        assertEquals(listOf(836_000_000L, 700_000_000L, 390_000_000L), opps)
        val gpu = GpuControl.MtkGed(opps)
        assertEquals(0, gpu.indexFor(836_000_000L))
        assertEquals(1, gpu.indexFor(800_000_000L))
        assertEquals(2, gpu.indexFor(100_000_000L))
        val op = gpu.opFor(700_000_000L, "444")!!
        assertEquals("1", op.value)
        assertNull(op.lockMode) // o nó do GED nunca é trancado
        assertFalse(op.verify)
        assertEquals(45, gpu.parseBusy("45 3 52"))
    }

    @Test
    fun `sem GPU controlavel nada e escrito`() {
        assertNull(GpuControl.None.opFor(1_000_000_000L, "444"))
        assertNull(GpuControl.None.maxHz)
    }
}
