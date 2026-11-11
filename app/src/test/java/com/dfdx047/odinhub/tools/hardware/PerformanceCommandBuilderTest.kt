package com.dfdx047.odinhub.tools.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes puros de JVM (sem Android, sem mocks) para o [PerformanceCommandBuilder].
 * Como o builder não toca em `File`/`IBinder`, conseguimos validar a matemática de clocks e a
 * formatação exata dos comandos que serão enviados ao PServer/root, sem precisar de um
 * dispositivo, de um emulador ou de root.
 */
class PerformanceCommandBuilderTest {

    // Topologia real "1+3+4" descoberta dinamicamente: policy0 (little/perf) e policy7 (prime).
    private val threePolicyTopology = listOf(
        CpuPolicyNode("/sys/devices/system/cpu/cpufreq/policy0", 1_800_000L),
        CpuPolicyNode("/sys/devices/system/cpu/cpufreq/policy4", 3_530_000L),
        CpuPolicyNode("/sys/devices/system/cpu/cpufreq/policy7", 4_320_000L),
    )

    @Test
    fun `buildCpuFrequencyOps trata a politica com maior clock maximo como prime`() {
        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(
            policies = threePolicyTopology,
            perfRatio = 1.0f,
            primeRatio = 1.0f,
        )

        assertEquals(3, ops.size)
        assertEquals("cpu-prime-policy", ops.last().label)
        assertEquals("/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq", ops.last().targetPath)
        assertEquals("4320000", ops.last().value)
    }

    @Test
    fun `buildCpuFrequencyOps ignora a ordem de listagem do diretorio`() {
        // dir.listFiles() do Android não garante ordem alfabética/numérica; o builder deve
        // ordenar sempre por cpuinfoMaxFreqKHz antes de decidir qual política é o "prime".
        val shuffled = listOf(threePolicyTopology[2], threePolicyTopology[0], threePolicyTopology[1])

        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(shuffled, perfRatio = 1.0f, primeRatio = 1.0f)

        assertEquals("/sys/devices/system/cpu/cpufreq/policy7/scaling_max_freq", ops.last().targetPath)
    }

    @Test
    fun `buildCpuFrequencyOps aplica ratios diferentes a perf e prime`() {
        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(
            policies = threePolicyTopology,
            perfRatio = 0.5f,
            primeRatio = 0.8f,
        )

        // policy0 (perf): 1_800_000 * 0.5 = 900_000
        assertEquals("900000", ops[0].value)
        // policy4 (perf): 3_530_000 * 0.5 = 1_765_000
        assertEquals("1765000", ops[1].value)
        // policy7 (prime): 4_320_000 * 0.8 = 3_456_000
        assertEquals("3456000", ops[2].value)
    }

    @Test
    fun `buildCpuFrequencyOps nunca ultrapassa o maximo de fabrica`() {
        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(
            policies = threePolicyTopology,
            perfRatio = 5.0f, // valor absurdo, deve ser cortado em CPU_MAX_RATIO
            primeRatio = 5.0f,
        )
        ops.forEachIndexed { index, op ->
            val max = threePolicyTopology.sortedBy { it.cpuinfoMaxFreqKHz }[index].cpuinfoMaxFreqKHz
            assertTrue(op.value.toLong() <= max)
        }
    }

    @Test
    fun `buildCpuFrequencyOps nunca fica abaixo do ratio minimo de seguranca`() {
        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(
            policies = threePolicyTopology,
            perfRatio = 0.0f, // pediria 0 Hz, o que travaria o dispositivo
            primeRatio = 0.0f,
        )
        ops.forEach { op ->
            assertTrue("valor não pode ser 0: ${op.value}", op.value.toLong() > 0)
        }
    }

    @Test
    fun `buildCpuFrequencyOps devolve lista vazia quando nenhuma politica e encontrada`() {
        // Reflete o caso real de falha de varredura de /sys/devices/system/cpu/cpufreq: deve
        // devolver uma lista vazia em vez de lançar, para que o chamador simplesmente não
        // escreva nada em vez de rebentar o daemon de hardware.
        val ops = PerformanceCommandBuilder.buildCpuFrequencyOps(emptyList(), 1.0f, 1.0f)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `HardwareWriteOp gera a cadeia unlock-write-lock correta`() {
        val op = PerformanceCommandBuilder.buildWriteOp(
            label = "gpu-max-clock",
            path = "/sys/class/kgsl/kgsl-3d0/max_gpuclk",
            value = "750000000",
        )

        assertEquals("chmod 666 /sys/class/kgsl/kgsl-3d0/max_gpuclk", op.unlockCommand)
        assertEquals("echo 750000000 > /sys/class/kgsl/kgsl-3d0/max_gpuclk", op.writeCommand)
        assertEquals("chmod 444 /sys/class/kgsl/kgsl-3d0/max_gpuclk", op.lockCommand)
        assertEquals(listOf(op.unlockCommand, op.writeCommand, op.lockCommand), op.asSequentialCommands())
    }

    @Test
    fun `wattsToRatio mapeia watts para um ratio normalizado e seguro`() {
        assertEquals(1.0f, PerformanceCommandBuilder.wattsToRatio(25f), 0.001f)
        // Antes do fix da Parte 3 (AUDIT_PARTE3.md, "5W não limita, 10W limita"), o piso aqui
        // era CPU_MIN_RATIO (0.30f) -- alto demais para um alvo baixo como 1W/5W conseguir
        // convergir de verdade. Este teste ficou desatualizado depois daquele fix (continuava a
        // exigir 0.3f) e só foi apanhado agora que a CI passou a rodar testDebugUnitTest de
        // verdade. O piso correto e atual é TDP_MIN_RATIO (0.12f).
        assertEquals(
            PerformanceCommandBuilder.TDP_MIN_RATIO,
            PerformanceCommandBuilder.wattsToRatio(1f),
            0.001f,
        ) // corta no mínimo
        assertEquals(0.6f, PerformanceCommandBuilder.wattsToRatio(15f), 0.001f)
    }

    @Test
    fun `absoluteClocksToRatios usa o segundo maior cluster como referencia de perf`() {
        val (perfRatio, primeRatio) = PerformanceCommandBuilder.absoluteClocksToRatios(
            perfClockKHz = 1_765_000L,
            primeClockKHz = 3_456_000L,
            policies = threePolicyTopology,
        )

        assertEquals(0.5f, perfRatio, 0.001f)
        assertEquals(0.8f, primeRatio, 0.001f)
    }

    @Test
    fun `absoluteClocksToRatios usa fallback quando nao ha politicas`() {
        val (perfRatio, primeRatio) = PerformanceCommandBuilder.absoluteClocksToRatios(
            perfClockKHz = 3_530_000L,
            primeClockKHz = 4_320_000L,
            policies = emptyList(),
        )

        assertEquals(1.0f, perfRatio, 0.001f)
        assertEquals(1.0f, primeRatio, 0.001f)
    }
}
