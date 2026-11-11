package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.hardware.PerformanceCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FPS por `dumpsys SurfaceFlinger --latency`, tabela de frequências unida e gestos do Home. */
class HudFpsAndFreqTest {

    private val frameNs = 16_666_667L

    @Test
    fun `latency ignora a linha de refresh e os frames pendentes`() {
        val out = """
            16666666
            100	1000	110
            200	2000	210
            0	0	0
            300	9223372036854775807	310
            400	4000
        """.trimIndent()
        assertEquals(listOf(1000L, 2000L), HudMath.parseLatencyPresents(out))
        assertTrue(HudMath.parseLatencyPresents(null).isEmpty())
    }

    @Test
    fun `camadas do jogo - SurfaceView BLAST primeiro, embrulho do Android 14 removido`() {
        val list = """
            com.android.systemui.ImageWallpaper#12
            RequestedLayerState{com.foo.game/com.foo.game.MainActivity#88 parentId=40 relativeParentId=-1 z=0}
            RequestedLayerState{SurfaceView[com.foo.game/com.foo.game.MainActivity](BLAST)#91 parentId=90 relativeParentId=-1 z=0}
            RequestedLayerState{Background for SurfaceView[com.foo.game/com.foo.game.MainActivity]#92 parentId=90 z=0}
            ActivityRecord{abc u0 com.foo.game/.MainActivity t12}#80
        """.trimIndent()
        val c = HudMath.layerCandidates(list, "com.foo.game")
        assertEquals("SurfaceView[com.foo.game/com.foo.game.MainActivity](BLAST)#91", c.first())
        assertEquals(listOf(
            "SurfaceView[com.foo.game/com.foo.game.MainActivity](BLAST)#91",
            "com.foo.game/com.foo.game.MainActivity#88",
        ), c)
        assertTrue(HudMath.layerCandidates(list, "").isEmpty())
    }

    @Test
    fun `FpsTracker - 60 fps estaveis`() {
        val t = FpsTracker()
        t.add((0 until 120).map { it * frameNs })
        assertEquals(60f, t.fps()!!, 0.5f)
        assertEquals(16.67f, t.frametimeMs()!!, 0.2f)
        assertEquals(60f, t.onePercentLow()!!, 0.5f)
        // Leituras repetidas não duplicam frames.
        t.add((100 until 120).map { it * frameNs })
        assertEquals(60f, t.fps()!!, 0.5f)
    }

    @Test
    fun `FpsTracker - engasgos de 100 ms aparecem no 1 por cento low`() {
        val t = FpsTracker()
        // 99 intervalos: 97 normais + 2 engasgos de 100 ms (2% > 1%).
        val gaps = List(48) { frameNs } + 100_000_000L + List(49) { frameNs } + 100_000_000L
        val frames = gaps.runningFold(0L) { acc, g -> acc + g }
        t.add(frames)
        assertEquals(10f, t.onePercentLow()!!, 0.5f)
    }

    @Test
    fun `FpsTracker - dados insuficientes`() {
        val t = FpsTracker()
        assertNull(t.fps())
        t.add(listOf(1L))
        assertNull(t.fps())
        assertNull(t.onePercentLow())
        t.clear()
        assertNull(t.newest)
    }

    @Test
    fun `tabela de frequencias junta boost, time_in_state e limites`() {
        val tis = PerformanceCommandBuilder.parseTimeInState("307200 10\n1017600 5\n2112000 0\n4320000 1\n")
        assertEquals(listOf(307200L, 1017600L, 2112000L, 4320000L), tis)
        val merged = PerformanceCommandBuilder.mergeFrequencyTables(
            available = listOf(384000L, 1017600L),
            boost = listOf(4320000L),
            timeInState = tis,
            maxKHz = 4320000L,
            minKHz = 384000L,
        )
        // 307200 está abaixo do cpuinfo_min: fica de fora; o boost de topo entra.
        assertEquals(listOf(384000L, 1017600L, 2112000L, 4320000L), merged)
        // Só o máximo conhecido -> nada para escolher.
        assertTrue(PerformanceCommandBuilder.mergeFrequencyTables(emptyList(), emptyList(), emptyList(), 2_000_000L).isEmpty())
    }

    @Test
    fun `gestos do Home - toques e janela`() {
        val cfg = HomeGestureConfig(enabled = true)
        assertEquals(3, cfg.maxTaps)
        assertEquals(ButtonAction.TOGGLE_OVERLAY, cfg.actionForTaps(2))
        assertEquals(1, cfg.copy(doubleTap = ButtonAction.NONE, tripleTap = ButtonAction.NONE).maxTaps)
        assertTrue(HomeGestureConfig.MULTI_TAP_WINDOW_MS >= 400L)
        assertTrue(HomeGestureConfig.MULTI_TAP_WINDOW_MS in HomeGestureConfig.MULTI_TAP_OPTIONS_MS)
    }

    @Test
    fun `uso da CPU pelo idle dos nucleos`() {
        // 8 núcleos, 1 s: 6 s de idle somado = 25% de uso.
        assertEquals(25, HudMath.cpuUsageFromIdle(6_000_000L, 1_000_000L, 8))
        assertEquals(0, HudMath.cpuUsageFromIdle(9_000_000L, 1_000_000L, 8)) // relógios desalinhados
        assertNull(HudMath.cpuUsageFromIdle(1L, 0L, 8))
    }

    @Test
    fun `zonas termicas do 8 Gen 2 e leitura por root`() {
        for (t in listOf("cpu-0-0-0", "cpu-1-7-1", "cpuss-0", "cpullc-0", "cpu0-silver-usr", "mtktscpu")) assertEquals(t, "CPU", HudMath.zoneRole(t))
        for (t in listOf("gpuss-0", "gpu-0-usr", "GPU0", "mtktsgpu")) assertEquals(t, "GPU", HudMath.zoneRole(t))
        for (t in listOf("battery", "skin-msm-therm", "aoss-0", "ddr", null)) assertNull(HudMath.zoneRole(t))
        val out = """
            /sys/class/thermal/thermal_zone12/temp:51200
            grep: /sys/class/thermal/thermal_zone13/temp: Permission denied
            /sys/class/thermal/thermal_zone14/temp:49800
        """.trimIndent()
        assertEquals(
            mapOf("/sys/class/thermal/thermal_zone12/temp" to "51200", "/sys/class/thermal/thermal_zone14/temp" to "49800"),
            HudMath.parseGrepH(out),
        )
    }

    @Test
    fun `saida de varias linhas volta inteira depois do PServer`() {
        // O script junta as linhas com 0x1E; o PServer só devolveria a 1.ª linha sem isto.
        val joined = "16666666\u001e1 100 1\u001e1 117 1\u001e\n"
        val back = com.dfdx047.odinhub.tools.RootLines.split(joined.trimEnd('\n'))
        assertEquals(listOf(100L, 117L), HudMath.parseLatencyPresents(back))
        assertTrue(com.dfdx047.odinhub.tools.RootLines.SCRIPT.contains("tr '\\n' '\\036'"))
    }

    @Test
    fun `tempos de cada frame para o grafico`() {
        val t = FpsTracker()
        t.add(listOf(0L, frameNs, 2 * frameNs, 2 * frameNs + 50_000_000L))
        val ft = t.recentFrameTimesMs(10)
        assertEquals(3, ft.size)
        assertEquals(16.67f, ft[0], 0.01f)
        assertEquals(50f, ft[2], 0.01f)
        assertEquals(2, t.recentFrameTimesMs(2).size)
    }

    @Test
    fun `tamanho do HUD fica nos limites e arredondado`() {
        assertEquals(HudConfig.MIN_SCALE, HudConfig.clampScale(0.1f), 0f)
        assertEquals(HudConfig.MAX_SCALE, HudConfig.clampScale(9f), 0f)
        assertEquals(1.25f, HudConfig.clampScale(1.2371f), 0.0001f)
    }

    @Test
    fun `consumo na tomada vem da entrada USB e na bateria da bateria`() {
        // Valores lidos no RP5: a carregar, bateria +120 mA, USB 4,93 V x 486 mA.
        // Consumo = 2,395 W do USB - 0,520 W que entram na bateria.
        val plugged = HudMath.systemPower(120_605, 4_315_174, 485_615, 4_932_870)!!
        assertEquals(1.875f, plugged.first, 0.01f)
        assertTrue(plugged.second)
        // Bypass: bateria quase parada, o consumo é o do USB (+ o pouco que sai da bateria).
        assertEquals(1.686f, HudMath.systemPower(-20_000, 4_300_000, 330_000, 4_850_000)!!.first, 0.01f)
        // 3DMark na tomada: o carregador não chega e a bateria ajuda (-1,3 A).
        assertEquals(7.46f, HudMath.systemPower(-1_296_000, 3_920_000, 485_000, 4_930_000)!!.first, 0.02f)
        // Só bateria (sem cabo: o USB lê ~0): |corrente| x tensão.
        val onBattery = HudMath.systemPower(-1_130_000, 3_900_000, 0, 300_000)!!
        assertEquals(4.407f, onBattery.first, 0.01f)
        assertTrue(!onBattery.second)
        // Kernel em mA.
        assertEquals(3.9f, HudMath.systemPower(-1_000, 3_900_000, null, null)!!.first, 0.01f)
        assertNull(HudMath.systemPower(null, null, null, null))
    }

    @Test
    fun `zona da carcaca nao conta como GPU`() {
        assertNull(HudMath.zoneRole("gpu-skin-avg-step"))
        assertEquals("GPU", HudMath.zoneRole("gpuss-0-usr"))
        assertEquals("CPU", HudMath.zoneRole("cpu-1-3-usr"))
    }
}

class HudPresetsTest {
    @org.junit.Test
    fun `alternar uma metrica mantem a ordem do HUD`() {
        val base = linkedSetOf(HudMetric.FPS, HudMetric.RAM)
        val on = HudPresets.toggle(base, HudMetric.CPU_TEMP)
        org.junit.Assert.assertEquals(listOf(HudMetric.FPS, HudMetric.CPU_TEMP, HudMetric.RAM), on.toList())
        val off = HudPresets.toggle(on, HudMetric.FPS)
        org.junit.Assert.assertEquals(listOf(HudMetric.CPU_TEMP, HudMetric.RAM), off.toList())
    }

    @org.junit.Test
    fun `grupos cobrem todas as metricas uma vez`() {
        val all = HudPresets.groups.flatMap { it.metrics }
        org.junit.Assert.assertEquals(HudMetric.entries.size, all.size)
        org.junit.Assert.assertEquals(HudMetric.entries.toSet(), all.toSet())
    }

    @org.junit.Test
    fun `predefinicoes`() {
        org.junit.Assert.assertEquals(setOf(HudMetric.FPS), HudPresets.FPS_ONLY.metrics)
        org.junit.Assert.assertEquals(HudMetric.DEFAULT, HudPresets.STANDARD.metrics)
        org.junit.Assert.assertEquals(HudMetric.entries.size, HudPresets.FULL.metrics.size)
    }
}
