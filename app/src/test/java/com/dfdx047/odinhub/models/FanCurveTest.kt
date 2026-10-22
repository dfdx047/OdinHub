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
}
