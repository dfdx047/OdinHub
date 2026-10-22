package com.dfdx047.odinhub.models

import kotlin.math.roundToInt

/** Um ponto da curva: a partir de [tempC] °C, a ventoinha vai a [percent] %. */
data class FanCurvePoint(val tempC: Int, val percent: Int)

/** Sensor que comanda a curva. MAX = o mais quente entre CPU e GPU. */
enum class FanReference(val id: String, val labelEn: String, val labelPt: String) {
    CPU("cpu", "CPU", "CPU"),
    GPU("gpu", "GPU", "GPU"),
    MAX("max", "Hottest (CPU or GPU)", "Mais quente (CPU ou GPU)");

    fun label(isEn: Boolean) = if (isEn) labelEn else labelPt

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CPU
    }
}

/**
 * Unidade do `fan_speed` do firmware. O firmware AYN usa o duty do PWM em ns: 0-50000 = 0-100 %
 * (o próprio ecrã da AYN mostra "20%(10000)"). PERCENT fica como alternativa manual para outro
 * firmware que guarde 0-100.
 */
enum class FanSpeedUnit(val id: String) {
    NANOSECONDS("ns"), PERCENT("percent");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: NANOSECONDS
    }
}

/**
 * Curva personalizada da ventoinha (ecrã "Curva da ventoinha").
 *
 * O firmware AYN não expõe a curva do modo Smart (fica nas preferências privadas da app de
 * definições da AYN). Por isso o Odin Hub põe a ventoinha no modo de velocidade manual do
 * firmware (`fan_mode` = [manualMode]) e vai ajustando `fan_speed` conforme a temperatura,
 * seguindo estes pontos.
 */
data class FanCurveConfig(
    val points: List<FanCurvePoint> = DEFAULT_POINTS,
    val reference: FanReference = FanReference.CPU,
    val useFahrenheit: Boolean = false,
    /** Valor de `fan_mode` em que o firmware usa `fan_speed` como velocidade fixa. */
    val manualMode: Int = DEFAULT_MANUAL_MODE,
    val unit: FanSpeedUnit = FanSpeedUnit.NANOSECONDS,
    /** Graus a descer antes de baixar a velocidade (evita a ventoinha a oscilar). */
    val hysteresisC: Int = 3,
) {
    /** Pontos ordenados por temperatura, sempre dentro dos limites. */
    val sortedPoints: List<FanCurvePoint>
        get() = points.map { FanCurvePoint(it.tempC.coerceIn(MIN_TEMP_C, MAX_TEMP_C), it.percent.coerceIn(0, 100)) }
            .sortedBy { it.tempC }

    /** Velocidade (%) para uma temperatura: interpolação linear entre os pontos. */
    fun percentFor(tempC: Float): Int {
        val pts = sortedPoints
        if (pts.isEmpty()) return 100
        if (tempC <= pts.first().tempC) return pts.first().percent
        if (tempC >= pts.last().tempC) return pts.last().percent
        for (i in 0 until pts.lastIndex) {
            val a = pts[i]
            val b = pts[i + 1]
            if (tempC <= b.tempC) {
                val span = (b.tempC - a.tempC).coerceAtLeast(1)
                val t = (tempC - a.tempC) / span
                return (a.percent + (b.percent - a.percent) * t).roundToInt().coerceIn(0, 100)
            }
        }
        return pts.last().percent
    }

    /**
     * Próxima velocidade, com histerese: SOBE logo que a curva pede mais; só DESCE quando a
     * temperatura cai [hysteresisC] graus abaixo da que fixou a velocidade atual. Acima de
     * [CRITICAL_C] é sempre 100 %. Devolve (percentagem, temperatura de referência a guardar).
     */
    fun nextPercent(tempC: Float, lastPercent: Int?, lastSetTempC: Float?): Pair<Int, Float> {
        if (tempC >= CRITICAL_C) return 100 to tempC
        val wanted = percentFor(tempC)
        return when {
            lastPercent == null || lastSetTempC == null -> wanted to tempC
            wanted >= lastPercent -> wanted to tempC
            tempC <= lastSetTempC - hysteresisC -> wanted to tempC
            else -> lastPercent to lastSetTempC
        }
    }

    fun encode(): String = buildString {
        append(points.joinToString(";") { "${it.tempC}:${it.percent}" })
        append("|").append(reference.id)
        append("|").append(if (useFahrenheit) "F" else "C")
        append("|").append(manualMode)
        append("|").append(unit.id)
        append("|").append(hysteresisC)
    }

    companion object {
        const val MIN_TEMP_C = 20
        const val MAX_TEMP_C = 110
        /** Acima disto a ventoinha vai SEMPRE a 100 %, seja qual for a curva. */
        const val CRITICAL_C = 90
        /** Modo "velocidade personalizada" do firmware AYN (0-6: Off/Quiet/.../Sport/personalizada). */
        const val DEFAULT_MANUAL_MODE = 6
        val MANUAL_MODE_OPTIONS = listOf(0, 2, 3, 6)

        /** O "Smart" de fábrica, para começar: 20 % até 45 °C, 100 % aos 105 °C. */
        val DEFAULT_POINTS = listOf(
            FanCurvePoint(25, 20),
            FanCurvePoint(45, 20),
            FanCurvePoint(65, 45),
            FanCurvePoint(85, 70),
            FanCurvePoint(105, 100),
        )

        fun decode(raw: String?): FanCurveConfig {
            if (raw.isNullOrBlank()) return FanCurveConfig()
            return runCatching {
                val parts = raw.split("|")
                val pts = parts[0].split(";").mapNotNull { p ->
                    val (t, v) = p.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                    FanCurvePoint(t.toInt(), v.toInt())
                }.takeIf { it.size >= 2 } ?: DEFAULT_POINTS
                FanCurveConfig(
                    points = pts,
                    reference = FanReference.fromId(parts.getOrNull(1)),
                    useFahrenheit = parts.getOrNull(2) == "F",
                    manualMode = parts.getOrNull(3)?.toIntOrNull() ?: DEFAULT_MANUAL_MODE,
                    unit = FanSpeedUnit.fromId(parts.getOrNull(4)),
                    hysteresisC = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 10) ?: 3,
                )
            }.getOrDefault(FanCurveConfig())
        }

        const val NS_FULL_SCALE = 50_000

        /** Valor a escrever em `fan_speed` para [percent] %, na unidade do firmware. */
        fun fanSpeedValue(percent: Int, unit: FanSpeedUnit): Int {
            val p = percent.coerceIn(0, 100)
            return if (unit == FanSpeedUnit.NANOSECONDS) p * NS_FULL_SCALE / 100 else p
        }

        /** Leitura de `fan_speed` -> %, na unidade configurada. */
        fun percentFromFanSpeed(value: Int, unit: FanSpeedUnit): Int =
            (if (unit == FanSpeedUnit.NANOSECONDS) value * 100 / NS_FULL_SCALE else value).coerceIn(0, 100)

        fun cToF(c: Float): Float = c * 9f / 5f + 32f
    }
}
