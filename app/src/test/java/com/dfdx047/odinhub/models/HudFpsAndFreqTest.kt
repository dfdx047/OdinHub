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
}
