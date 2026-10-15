package com.dfdx047.odinhub.models

import com.dfdx047.odinhub.tools.hardware.ClockTables

/**
 * Tabelas ESTÁTICAS de reserva das frequências, usadas só quando o `PerformanceManager` não
 * consegue ler a tabela de OPPs real do kernel (`scaling_available_frequencies` /
 * `gpu_available_frequencies`). Nesse caso `ClockTables.source == "fallback"`.
 *
 * As tabelas vivem agora em [SocTables] (uma por SoC) e cada aparelho escolhe a sua em
 * [DeviceProfiles]; este objeto ficou como fachada para o código e os testes que já o usavam.
 */
object ClusterClockPresets {

    val SM8750: ClockTables get() = SocTables.SM8750

    /** 8 Gen 2 / QCS8550 (Odin 2, Thor, RP6, RP Nova). */
    val SM8550: ClockTables get() = SocTables.KALAMA

    /** 865 / SM8250 (RP5, RP Mini, Thor Lite). */
    val SM8250: ClockTables get() = SocTables.KONA

    /** Tabela de reserva pelo SoC (`Build.SOC_MODEL` / `Build.HARDWARE`). Desconhecido -> 8 Elite. */
    fun fallbackFor(socModel: String?, hardware: String?): ClockTables =
        SocTables.forSoc(
            DeviceProfiles.socFrom(DeviceIdentity(socModel = socModel.orEmpty(), hardware = hardware.orEmpty(), platform = hardware.orEmpty())),
        )

    fun evenSteps(minMHz: Int, maxMHz: Int, count: Int = 10): List<Int> = SocTables.evenSteps(minMHz, maxMHz, count)
}
