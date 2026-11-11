package com.dfdx047.odinhub.tools.hardware

import android.util.Log
import com.dfdx047.odinhub.data.SharedPrefsRepo
import com.dfdx047.odinhub.models.FanCurveConfig
import com.dfdx047.odinhub.models.FanCurveTracker
import com.dfdx047.odinhub.models.FanMode
import com.dfdx047.odinhub.tools.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Estado partilhado da curva (sem dependências, para o PerformanceManager o poder ligar). */
object FanCurveState {
    /** `true` com o modo de ventoinha "Custom" ativo (global ou da regra do jogo). */
    val active = MutableStateFlow(false)

    /**
     * Serializa as escritas da curva com as trocas de modo (PerformanceManager.applyFanMode).
     * Sem isto, um tick a meio (chamadas root bloqueantes, que o cancelamento não interrompe)
     * podia voltar a pôr `fan_mode` no modo manual DEPOIS de o Stock/Silent/... ter reposto o
     * modo do firmware -- e a ventoinha ficava presa numa velocidade fixa sem ninguém a mexer-lhe.
     */
    val lock = Any()
}

/** O que o ecrã da curva mostra em "Estado atual". */
data class FanStatus(
    val cpuC: Float? = null,
    val gpuC: Float? = null,
    /** Velocidade pedida pela curva (só com a curva ativa). */
    val targetPercent: Int? = null,
    /** `fan_speed` atual do firmware, em bruto, e o modo. */
    val fanSpeedRaw: Int? = null,
    val firmwareMode: Int? = null,
    val curveActive: Boolean = false,
)

/**
 * Faz a ventoinha seguir a curva personalizada: põe o firmware no modo de velocidade manual
 * (`fan_mode` = FanCurveConfig.manualMode) e atualiza `fan_speed` a cada 2 s conforme a
 * temperatura de referência. Só escreve quando a velocidade muda (e com histerese, ver
 * FanCurveConfig.nextPercent), e acima de 90 °C vai sempre a 100 %.
 *
 * Arranca com o OdinHubService (serviço em primeiro plano) e com a app; fica parado enquanto
 * o modo "Custom" não estiver ativo.
 */
@Singleton
class FanCurveController @Inject constructor(
    private val executor: ShellExecutor,
    private val prefs: SharedPrefsRepo,
    private val sampler: HudSampler,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    val status = MutableStateFlow(FanStatus())

    /** Histerese e trava dos 90 °C. Só o loop da curva lhe mexe. */
    private val tracker = FanCurveTracker()
    @Volatile private var lastWrittenSpeed: Int? = null
    @Volatile private var testUntil = 0L

    /** Idempotente. O modo ativo vem de FanCurveState (posto pelo PerformanceManager.applyFanMode). */
    fun start() {
        if (job?.isActive == true) return
        // O modo guardado pode já ser Custom (reinício da app / do aparelho).
        if (prefs.fanMode == FanMode.CUSTOM_VALUE) FanCurveState.active.value = true
        job = scope.launch {
            FanCurveState.active.collectLatest { active ->
                resetState()
                if (active) runCurve()
            }
        }
    }

    private fun resetState() {
        tracker.reset()
        lastWrittenSpeed = null
    }

    private suspend fun runCurve() {
        var tick = 0
        while (currentCoroutineContext().isActive) {
            runCatching {
                val cfg = prefs.fanCurveConfig
                // Uma leitura que rebenta conta como "sem leitura" (-> 100 %), em vez de saltar o
                // tick e deixar a ventoinha na velocidade anterior.
                val cpu = runCatching { sampler.cpuTempC() }.getOrNull()
                val gpu = runCatching { sampler.gpuTempC() }.getOrNull()
                // Sem leitura / sensor mais quente >= 90 °C: 100 % (ver FanCurveTracker).
                val curvePercent = tracker.next(cfg, cpu, gpu)
                val percent = if (System.currentTimeMillis() < testUntil) 100 else curvePercent
                val value = FanCurveConfig.fanSpeedValue(percent, cfg.unit)
                synchronized(FanCurveState.lock) {
                    // A curva foi desligada entretanto (Stock/Silent/... já repôs o modo): não escreve.
                    if (!FanCurveState.active.value) return@runCatching
                    // O modo do firmware: confirmado a cada ~10 s (outra app/o firmware pode mudá-lo).
                    if (tick % 5 == 0) {
                        val mode = executor.getIntSystemSettingOrNull(FanMode.FAN_MODE)
                        if (mode != cfg.manualMode) {
                            executor.setIntSystemSetting(FanMode.FAN_MODE, cfg.manualMode)
                            lastWrittenSpeed = null // reescreve a velocidade no novo modo
                        }
                    }
                    if (value != lastWrittenSpeed) {
                        executor.setIntSystemSetting(FanMode.FAN_SPEED, value)
                        lastWrittenSpeed = value
                    }
                }
                status.value = FanStatus(cpu, gpu, percent, value, cfg.manualMode, curveActive = true)
            }.onFailure { Log.w(TAG, "tick da curva falhou", it) }
            tick++
            delay(INTERVAL_MS)
        }
    }

    /** Leitura para o ecrã (também com a curva desligada). Bloqueante. */
    fun readStatus(): FanStatus {
        val active = FanCurveState.active.value
        if (active) return status.value.copy(cpuC = sampler.cpuTempC(), gpuC = sampler.gpuTempC())
        return FanStatus(
            cpuC = sampler.cpuTempC(),
            gpuC = sampler.gpuTempC(),
            fanSpeedRaw = executor.getIntSystemSettingOrNull(FanMode.FAN_SPEED),
            firmwareMode = executor.getIntSystemSettingOrNull(FanMode.FAN_MODE),
            curveActive = false,
        )
    }

    /**
     * "Testar ventoinha": [percent] % durante [ms] com o modo manual e a unidade de [cfg] -- serve
     * para confirmar no próprio aparelho que o modo manual e a unidade estão certos (a ventoinha
     * tem de mudar de som). Com a curva desligada, repõe depois o modo e a velocidade que havia.
     */
    fun testSpeed(cfg: FanCurveConfig, percent: Int = 100, ms: Long = 5_000) {
        if (FanCurveState.active.value) {
            testUntil = System.currentTimeMillis() + ms
            lastWrittenSpeed = null
            return
        }
        scope.launch {
            val oldMode = executor.getIntSystemSettingOrNull(FanMode.FAN_MODE)
            val oldSpeed = executor.getIntSystemSettingOrNull(FanMode.FAN_SPEED)
            executor.setIntSystemSetting(FanMode.FAN_MODE, cfg.manualMode)
            executor.setIntSystemSetting(FanMode.FAN_SPEED, FanCurveConfig.fanSpeedValue(percent, cfg.unit))
            delay(ms)
            // Se entretanto a curva foi ligada, ela manda; senão volta tudo ao que estava.
            synchronized(FanCurveState.lock) {
                if (!FanCurveState.active.value) {
                    oldSpeed?.let { executor.setIntSystemSetting(FanMode.FAN_SPEED, it) }
                    oldMode?.let { executor.setIntSystemSetting(FanMode.FAN_MODE, it) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "FanCurveController"
        const val INTERVAL_MS = 2_000L
    }
}
