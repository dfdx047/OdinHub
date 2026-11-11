package com.dfdx047.odinhub.models

import org.junit.Assert.assertEquals
import org.junit.Test

class FanCurveTest {

    private val cfg = FanCurveConfig()

    @Test
    fun `interpolacao entre os pontos de fabrica`() {
        assertEquals(20, cfg.percentFor(10f))
        assertEquals(20, cfg.percentFor(45f))
        assertEquals(33, cfg.percentFor(55f)) // meio caminho entre 20 % (45 °C) e 45 % (65 °C)
        assertEquals(70, cfg.percentFor(85f))
        assertEquals(100, cfg.percentFor(120f))
    }

    @Test
    fun `histerese - sobe logo, so desce depois de arrefecer`() {
        val (p1, t1) = cfg.nextPercent(65f, null, null)
        assertEquals(45, p1)
        val (p2, _) = cfg.nextPercent(63f, p1, t1) // desceu 2 °C: mantém
        assertEquals(45, p2)
        val (p3, _) = cfg.nextPercent(61f, p1, t1) // desceu 4 °C (> 3): baixa
        assertEquals(40, p3)
        val (p4, _) = cfg.nextPercent(70f, p1, t1) // subiu: acompanha logo
        assertEquals(51, p4)
    }

    @Test
    fun `seguranca - 90 graus ou mais e sempre 100`() {
        val quiet = cfg.copy(points = listOf(FanCurvePoint(30, 10), FanCurvePoint(100, 10)))
        assertEquals(100, quiet.nextPercent(91f, 10, 80f).first)
    }

    @Test
    fun `unidades do fan_speed`() {
        assertEquals(10_000, FanCurveConfig.fanSpeedValue(20, FanSpeedUnit.NANOSECONDS))
        assertEquals(50_000, FanCurveConfig.fanSpeedValue(100, FanSpeedUnit.NANOSECONDS))
        assertEquals(20, FanCurveConfig.fanSpeedValue(20, FanSpeedUnit.PERCENT))
        assertEquals(20, FanCurveConfig.percentFromFanSpeed(10_000, FanSpeedUnit.NANOSECONDS))
    }

    @Test
    fun `codificacao ida e volta`() {
        val c = FanCurveConfig(
            points = listOf(FanCurvePoint(30, 25), FanCurvePoint(60, 50), FanCurvePoint(95, 100)),
            reference = FanReference.MAX, useFahrenheit = true, manualMode = 3, unit = FanSpeedUnit.PERCENT, hysteresisC = 5,
        )
        assertEquals(c, FanCurveConfig.decode(c.encode()))
        assertEquals(FanCurveConfig(), FanCurveConfig.decode(null))
        assertEquals(FanCurveConfig(), FanCurveConfig.decode("lixo"))
    }

    @Test
    fun `modo Custom nunca e escrito no firmware`() {
        assertEquals(FanMode.Custom, FanMode.fromSettingsValue(FanMode.CUSTOM_VALUE))
    }
    @Test
    fun `curva nova recomeca a histerese`() {
        // Bug: com a temperatura estável, guardar uma curva mais baixa deixava a ventoinha na
        // velocidade antiga (a histerese só deixa descer depois de arrefecer 3 °C).
        val loud = FanCurveConfig(points = listOf(FanCurvePoint(20, 10), FanCurvePoint(30, 30), FanCurvePoint(50, 70), FanCurvePoint(110, 100)))
        val quiet = loud.copy(points = listOf(FanCurvePoint(20, 10), FanCurvePoint(30, 20), FanCurvePoint(50, 30), FanCurvePoint(110, 100)))
        val tracker = FanCurveTracker()
        assertEquals(50, tracker.next(loud, 40f, 35f))
        assertEquals(50, tracker.next(loud, 39f, 35f)) // histerese: mantém
        assertEquals(25, tracker.next(quiet, 40f, 35f)) // curva nova: segue-a logo
        assertEquals(25, tracker.next(quiet, 39f, 35f))
    }

    @Test
    fun `sem leitura de temperatura vai a 100`() {
        val tracker = FanCurveTracker()
        assertEquals(100, tracker.next(cfg, null, null))
        // Só falta um sensor: usa o outro.
        assertEquals(20, tracker.next(cfg, null, 40f))
        assertEquals(20, FanCurveTracker().next(cfg.copy(reference = FanReference.GPU), 40f, null))
    }

    @Test
    fun `90 graus no sensor mais quente vai a 100 mesmo com a curva por outro`() {
        val quiet = cfg.copy(points = listOf(FanCurvePoint(30, 10), FanCurvePoint(100, 10)), reference = FanReference.CPU, hysteresisC = 3)
        val tracker = FanCurveTracker()
        assertEquals(10, tracker.next(quiet, 60f, 85f))
        assertEquals(100, tracker.next(quiet, 60f, 92f)) // GPU a 92 °C, curva pela CPU
        assertEquals(100, tracker.next(quiet, 60f, 89f)) // ainda não arrefeceu 3 °C
        assertEquals(10, tracker.next(quiet, 60f, 87f)) // arrefeceu: volta à curva
        // E pela própria referência, como antes.
        assertEquals(100, tracker.next(quiet, 90f, 50f))
    }

    @Test
    fun `referencia escolhe o sensor certo`() {
        assertEquals(50f, FanReference.CPU.pick(50f, 60f))
        assertEquals(60f, FanReference.GPU.pick(50f, 60f))
        assertEquals(60f, FanReference.MAX.pick(50f, 60f))
        assertEquals(60f, FanReference.CPU.pick(null, 60f))
        assertEquals(null, FanReference.MAX.pick(null, null))
    }

    @Test
    fun `fahrenheit so na apresentacao`() {
        assertEquals(32f, FanCurveConfig.cToF(0f), 0f)
        assertEquals(194f, FanCurveConfig.cToF(90f), 1e-3f)
        // A curva guarda e decide sempre em °C, mesmo com °F escolhido.
        val f = cfg.copy(useFahrenheit = true)
        assertEquals(cfg.percentFor(55f), f.percentFor(55f))
        assertEquals(f, FanCurveConfig.decode(f.encode()))
    }
}
